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
    private val sshSessionManager: SshSessionManager,
    private val sshKeyRepository: SshKeyRepository,
    private val preferencesRepository: UserPreferencesRepository,
) : ViewModel() {

    val connections: StateFlow<List<ConnectionProfile>> = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val sshKeys: StateFlow<List<SshKey>> = sshKeyRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val sessions: StateFlow<Map<String, SshSessionManager.SessionState>> = sshSessionManager.sessions

    private val _connectingId = MutableStateFlow<String?>(null)
    val connectingId: StateFlow<String?> = _connectingId.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Emitted once after a successful connect to trigger navigation to terminal. */
    private val _navigateToTerminal = MutableStateFlow<String?>(null)
    val navigateToTerminal: StateFlow<String?> = _navigateToTerminal.asStateFlow()

    /** When non-null, the UI should show a session picker dialog. */
    data class SessionSelection(
        val profileId: String,
        val managerLabel: String,
        val sessionNames: List<String>,
    )

    private val _sessionSelection = MutableStateFlow<SessionSelection?>(null)
    val sessionSelection: StateFlow<SessionSelection?> = _sessionSelection.asStateFlow()

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
            sshSessionManager.removeSession(id)
            repository.delete(id)
        }
    }

    fun connect(profile: ConnectionProfile, password: String) {
        viewModelScope.launch {
            _connectingId.value = profile.id
            _error.value = null

            val client = SshClient()
            sshSessionManager.registerSession(profile.id, profile.label, client)

            try {
                val sshSessionMgr = withContext(Dispatchers.IO) {
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
                    sshSessionManager.storeConnectionConfig(profile.id, config, sshSessionMgr)
                    sshSessionMgr
                }

                // If session manager supports listing, check for existing sessions
                val listCmd = sshSessionMgr.listCommand
                if (listCmd != null) {
                    val existingSessions = withContext(Dispatchers.IO) {
                        try {
                            val result = client.execCommand(listCmd)
                            if (result.exitStatus == 0) {
                                SessionManager.parseSessionList(sshSessionMgr, result.stdout)
                            } else emptyList()
                        } catch (_: Exception) {
                            emptyList()
                        }
                    }
                    if (existingSessions.isNotEmpty()) {
                        _sessionSelection.value = SessionSelection(
                            profileId = profile.id,
                            managerLabel = sshSessionMgr.label,
                            sessionNames = existingSessions,
                        )
                        _connectingId.value = null
                        return@launch // UI will call onSessionSelected() to continue
                    }
                }

                // No existing sessions or no session manager — proceed directly
                finishConnect(profile.id)
            } catch (e: Exception) {
                sshSessionManager.updateStatus(profile.id, SshSessionManager.SessionState.Status.ERROR)
                _error.value = e.message ?: "Connection failed"
                sshSessionManager.removeSession(profile.id)
            } finally {
                _connectingId.value = null
            }
        }
    }

    /**
     * Called from the session picker dialog when user selects a session.
     * @param sessionName The name to attach to, or null to create a new session.
     */
    fun onSessionSelected(profileId: String, sessionName: String?) {
        _sessionSelection.value = null
        viewModelScope.launch {
            _connectingId.value = profileId
            try {
                if (sessionName != null) {
                    sshSessionManager.setChosenSessionName(profileId, sessionName)
                }
                finishConnect(profileId)
            } catch (e: Exception) {
                sshSessionManager.updateStatus(profileId, SshSessionManager.SessionState.Status.ERROR)
                _error.value = e.message ?: "Connection failed"
                sshSessionManager.removeSession(profileId)
            } finally {
                _connectingId.value = null
            }
        }
    }

    fun dismissSessionPicker() {
        val sel = _sessionSelection.value ?: return
        _sessionSelection.value = null
        sshSessionManager.removeSession(sel.profileId)
    }

    private suspend fun finishConnect(profileId: String) {
        withContext(Dispatchers.IO) {
            sshSessionManager.openShellForSession(profileId)
        }
        sshSessionManager.updateStatus(profileId, SshSessionManager.SessionState.Status.CONNECTED)
        repository.markConnected(profileId)
        startForegroundServiceIfNeeded()
        _navigateToTerminal.value = profileId
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
        sshSessionManager.removeSession(profileId)
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
            UserPreferencesRepository.SessionManager.BYOBU -> SessionManager.BYOBU
        }
}
