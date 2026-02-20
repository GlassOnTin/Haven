package sh.haven.core.ssh

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages active SSH sessions across the app.
 * Sessions are identified by connection profile ID.
 */
@Singleton
class SshSessionManager @Inject constructor() {

    data class SessionState(
        val profileId: String,
        val label: String,
        val status: Status,
        val client: SshClient,
    ) {
        enum class Status { CONNECTING, CONNECTED, DISCONNECTED, ERROR }
    }

    private val _sessions = MutableStateFlow<Map<String, SessionState>>(emptyMap())
    val sessions: StateFlow<Map<String, SessionState>> = _sessions.asStateFlow()

    val activeSessions: List<SessionState>
        get() = _sessions.value.values.filter {
            it.status == SessionState.Status.CONNECTED ||
            it.status == SessionState.Status.CONNECTING
        }

    val hasActiveSessions: Boolean
        get() = activeSessions.isNotEmpty()

    fun registerSession(profileId: String, label: String, client: SshClient) {
        _sessions.update { map ->
            map + (profileId to SessionState(
                profileId = profileId,
                label = label,
                status = SessionState.Status.CONNECTING,
                client = client,
            ))
        }
    }

    fun updateStatus(profileId: String, status: SessionState.Status) {
        _sessions.update { map ->
            val existing = map[profileId] ?: return@update map
            map + (profileId to existing.copy(status = status))
        }
    }

    fun removeSession(profileId: String) {
        val session = _sessions.value[profileId]
        session?.client?.disconnect()
        _sessions.update { it - profileId }
    }

    fun getSession(profileId: String): SessionState? = _sessions.value[profileId]

    fun disconnectAll() {
        _sessions.value.values.forEach { it.client.disconnect() }
        _sessions.update { emptyMap() }
    }
}
