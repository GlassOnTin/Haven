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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.haven.core.data.db.entities.ConnectionProfile
import sh.haven.core.data.repository.ConnectionRepository
import sh.haven.core.ssh.ConnectionConfig
import sh.haven.core.ssh.SshClient
import sh.haven.core.ssh.SshConnectionService
import sh.haven.core.ssh.SshSessionManager
import javax.inject.Inject

@HiltViewModel
class ConnectionsViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val repository: ConnectionRepository,
    private val sessionManager: SshSessionManager,
) : ViewModel() {

    val connections: StateFlow<List<ConnectionProfile>> = repository.observeAll()
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
                    val config = ConnectionConfig(
                        host = profile.host,
                        port = profile.port,
                        username = profile.username,
                        authMethod = ConnectionConfig.AuthMethod.Password(password),
                    )
                    client.connect(config)
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

    fun disconnect(profileId: String) {
        sessionManager.removeSession(profileId)
    }

    fun dismissError() {
        _error.value = null
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
}
