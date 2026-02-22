package sh.haven.core.ssh

import com.jcraft.jsch.ChannelShell
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
        val shellChannel: ChannelShell? = null,
        val terminalSession: TerminalSession? = null,
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

    /**
     * Open a shell channel on the SSH session and store it in the session state.
     * Must be called after the SSH session is connected.
     */
    fun openShellForSession(profileId: String) {
        val session = _sessions.value[profileId] ?: return
        val channel = session.client.openShellChannel()
        attachShellChannel(profileId, channel)
    }

    fun attachShellChannel(profileId: String, channel: ChannelShell) {
        _sessions.update { map ->
            val existing = map[profileId] ?: return@update map
            map + (profileId to existing.copy(shellChannel = channel))
        }
    }

    /**
     * Create a [TerminalSession] for a connected session that has a shell channel.
     * Returns the session, or null if the session/channel doesn't exist.
     * The [onDataReceived] callback delivers SSH output bytes.
     * Call [TerminalSession.start] after wiring up the emulator.
     */
    fun createTerminalSession(
        profileId: String,
        onDataReceived: (ByteArray, Int, Int) -> Unit,
    ): TerminalSession? {
        val session = _sessions.value[profileId] ?: return null
        val channel = session.shellChannel ?: return null
        val termSession = TerminalSession(
            profileId = profileId,
            label = session.label,
            channel = channel,
            client = session.client,
            onDataReceived = onDataReceived,
        )
        attachTerminalSession(profileId, termSession)
        return termSession
    }

    /**
     * Whether a session has a shell channel ready but no terminal session yet.
     */
    fun isReadyForTerminal(profileId: String): Boolean {
        val session = _sessions.value[profileId] ?: return false
        return session.status == SessionState.Status.CONNECTED &&
            session.shellChannel != null &&
            session.terminalSession == null
    }

    fun attachTerminalSession(profileId: String, terminalSession: TerminalSession) {
        _sessions.update { map ->
            val existing = map[profileId] ?: return@update map
            map + (profileId to existing.copy(terminalSession = terminalSession))
        }
    }

    fun removeSession(profileId: String) {
        val session = _sessions.value[profileId]
        session?.terminalSession?.close()
        if (session?.shellChannel?.isConnected == true) {
            session.shellChannel.disconnect()
        }
        session?.client?.disconnect()
        _sessions.update { it - profileId }
    }

    fun getSession(profileId: String): SessionState? = _sessions.value[profileId]

    fun disconnectAll() {
        _sessions.value.values.forEach {
            it.terminalSession?.close()
            if (it.shellChannel?.isConnected == true) {
                it.shellChannel.disconnect()
            }
            it.client.disconnect()
        }
        _sessions.update { emptyMap() }
    }
}
