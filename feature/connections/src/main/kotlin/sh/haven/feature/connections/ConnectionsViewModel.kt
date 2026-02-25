package sh.haven.feature.connections

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.haven.core.data.db.entities.ConnectionProfile
import sh.haven.core.data.db.entities.SshKey
import sh.haven.core.data.preferences.UserPreferencesRepository
import sh.haven.core.data.repository.ConnectionRepository
import sh.haven.core.data.repository.SshKeyRepository
import sh.haven.core.ssh.ConnectionConfig
import sh.haven.core.ssh.SshClient
import sh.haven.core.ssh.SshConnectionService
import sh.haven.core.ssh.SessionManager
import sh.haven.core.ssh.SshSessionManager
import java.util.Base64
import javax.inject.Inject

@HiltViewModel
class ConnectionsViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val repository: ConnectionRepository,
    private val sessionManager: SshSessionManager,
    private val sshKeyRepository: SshKeyRepository,
    private val preferencesRepository: UserPreferencesRepository,
) : ViewModel() {

    val connections: StateFlow<List<ConnectionProfile>> = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val sshKeys: StateFlow<List<SshKey>> = sshKeyRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val sessions: StateFlow<Map<String, SshSessionManager.SessionState>> = sessionManager.sessions

    private val _connectingId = MutableStateFlow<String?>(null)
    val connectingId: StateFlow<String?> = _connectingId.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Emitted once after a successful connect to trigger navigation to terminal. */
    private val _navigateToTerminal = MutableStateFlow<String?>(null)
    val navigateToTerminal: StateFlow<String?> = _navigateToTerminal.asStateFlow()

    fun onNavigated() {
        _navigateToTerminal.value = null
    }

    fun saveConnection(profile: ConnectionProfile) {
        viewModelScope.launch {
            repository.save(profile)
        }
    }

    fun deleteConnection(id: String) {
        viewModelScope.launch {
            sessionManager.removeSession(id)
            repository.delete(id)
        }
    }

    fun connect(profile: ConnectionProfile, password: String) {
        viewModelScope.launch {
            _connectingId.value = profile.id
            _error.value = null

            val client = SshClient()
            sessionManager.registerSession(profile.id, profile.label, client)

            try {
                withContext(Dispatchers.IO) {
                    val authMethod = resolveAuthMethod(profile, password)
                    val config = ConnectionConfig(
                        host = profile.host,
                        port = profile.port,
                        username = profile.username,
                        authMethod = authMethod,
                    )
                    client.connect(config)
                    val prefSessionMgr = preferencesRepository.sessionManager.first()
                    val sshSessionMgr = prefSessionMgr.toSshSessionManager()
                    sessionManager.storeConnectionConfig(profile.id, config, sshSessionMgr)
                    sessionManager.openShellForSession(profile.id)
                }
                sessionManager.updateStatus(profile.id, SshSessionManager.SessionState.Status.CONNECTED)
                repository.markConnected(profile.id)
                startForegroundServiceIfNeeded()
                _navigateToTerminal.value = profile.id
            } catch (e: Exception) {
                sessionManager.updateStatus(profile.id, SshSessionManager.SessionState.Status.ERROR)
                _error.value = e.message ?: "Connection failed"
                sessionManager.removeSession(profile.id)
            } finally {
                _connectingId.value = null
            }
        }
    }

    /**
     * Resolve the auth method for a connection profile.
     * If the profile has an assigned key, use it.
     * Otherwise if keys exist and password is empty, try the first available key.
     * Falls back to password auth.
     */
    private suspend fun resolveAuthMethod(
        profile: ConnectionProfile,
        password: String,
    ): ConnectionConfig.AuthMethod {
        // Profile has an explicit key assigned
        val keyId = profile.keyId
        if (keyId != null) {
            val key = sshKeyRepository.getById(keyId)
            if (key != null) {
                return ConnectionConfig.AuthMethod.PrivateKey(
                    keyBytes = rawKeyToPem(key.privateKeyBytes, key.keyType),
                    passphrase = password,
                )
            }
        }

        // No explicit key but keys are available — try first key when password is empty
        if (password.isEmpty()) {
            val keys = sshKeyRepository.getAll()
            if (keys.isNotEmpty()) {
                val key = keys.first()
                return ConnectionConfig.AuthMethod.PrivateKey(
                    keyBytes = rawKeyToPem(key.privateKeyBytes, key.keyType),
                    passphrase = "",
                )
            }
        }

        return ConnectionConfig.AuthMethod.Password(password)
    }

    /**
     * Convert raw private key bytes to PEM format that JSch can parse.
     * Ed25519 keys are raw 32-byte seeds that need a PKCS#8 DER envelope.
     * RSA/ECDSA keys from JCA are already PKCS#8 DER encoded.
     */
    private fun rawKeyToPem(rawBytes: ByteArray, keyType: String): ByteArray {
        val pkcs8Der = if (keyType.contains("Ed25519", ignoreCase = true)) {
            // PKCS#8 DER prefix for Ed25519: SEQUENCE { INTEGER 0, SEQUENCE { OID 1.3.101.112 }, OCTET STRING { OCTET STRING { <32 bytes> } } }
            val prefix = byteArrayOf(
                0x30, 0x2e, 0x02, 0x01, 0x00, 0x30, 0x05, 0x06,
                0x03, 0x2b, 0x65, 0x70, 0x04, 0x22, 0x04, 0x20,
            )
            prefix + rawBytes
        } else {
            // RSA/ECDSA: keyPair.private.encoded already returns PKCS#8 DER
            rawBytes
        }
        val b64 = Base64.getEncoder().encodeToString(pkcs8Der)
        val pem = buildString {
            append("-----BEGIN PRIVATE KEY-----\n")
            for (i in b64.indices step 64) {
                append(b64, i, minOf(i + 64, b64.length))
                append('\n')
            }
            append("-----END PRIVATE KEY-----\n")
        }
        return pem.toByteArray()
    }

    fun disconnect(profileId: String) {
        sessionManager.removeSession(profileId)
    }

    fun dismissError() {
        _error.value = null
    }

    private val _deploySuccess = MutableStateFlow(false)
    val deploySuccess: StateFlow<Boolean> = _deploySuccess.asStateFlow()

    fun dismissDeploySuccess() {
        _deploySuccess.value = false
    }

    fun deployKey(profile: ConnectionProfile, keyId: String, password: String) {
        viewModelScope.launch {
            _error.value = null
            val key = sshKeyRepository.getById(keyId)
            if (key == null) {
                _error.value = "SSH key not found"
                return@launch
            }

            val client = SshClient()
            try {
                val config = ConnectionConfig(
                    host = profile.host,
                    port = profile.port,
                    username = profile.username,
                    authMethod = ConnectionConfig.AuthMethod.Password(password),
                )
                client.connect(config)

                val pubKey = key.publicKeyOpenSsh.trim()
                val command = "mkdir -p ~/.ssh && chmod 700 ~/.ssh && " +
                    "echo '${pubKey}' >> ~/.ssh/authorized_keys && " +
                    "chmod 600 ~/.ssh/authorized_keys"

                val result = client.execCommand(command)
                if (result.exitStatus != 0) {
                    _error.value = "Deploy failed: ${result.stderr.ifBlank { "exit ${result.exitStatus}" }}"
                } else {
                    _deploySuccess.value = true
                }
            } catch (e: Exception) {
                _error.value = "Deploy failed: ${e.message ?: "unknown error"}"
            } finally {
                client.disconnect()
            }
        }
    }

    private fun startForegroundServiceIfNeeded() {
        val intent = Intent(appContext, SshConnectionService::class.java)
        appContext.startForegroundService(intent)
    }

    fun parseQuickConnect(input: String): ConnectionProfile? {
        val config = ConnectionConfig.parseQuickConnect(input) ?: return null
        return ConnectionProfile(
            label = "${config.username}@${config.host}",
            host = config.host,
            port = config.port,
            username = config.username,
        )
    }

    private fun UserPreferencesRepository.SessionManager.toSshSessionManager(): SessionManager =
        when (this) {
            UserPreferencesRepository.SessionManager.NONE -> SessionManager.NONE
            UserPreferencesRepository.SessionManager.TMUX -> SessionManager.TMUX
            UserPreferencesRepository.SessionManager.ZELLIJ -> SessionManager.ZELLIJ
            UserPreferencesRepository.SessionManager.SCREEN -> SessionManager.SCREEN
        }
}
