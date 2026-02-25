package sh.haven.core.ssh

import android.util.Log
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.ChannelShell
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SshSessionManager"

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
        val sftpChannel: ChannelSftp? = null,
    ) {
        enum class Status { CONNECTING, CONNECTED, DISCONNECTED, ERROR }
    }

    private val _sessions = MutableStateFlow<Map<String, SessionState>>(emptyMap())
    val sessions: StateFlow<Map<String, SessionState>> = _sessions.asStateFlow()

    /** Background executor for disconnect I/O so callers on main thread don't block. */
    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ssh-session-io").apply { isDaemon = true }
    }

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
     *
     * If [useTmux] is true, checks for tmux on the remote host and attaches to
     * (or creates) a persistent session named "haven-{profileId-prefix}".
     * This survives SSH disconnections — reconnecting reattaches to the same session.
     */
    fun openShellForSession(profileId: String, useTmux: Boolean = false) {
        val session = _sessions.value[profileId] ?: return
        val channel = session.client.openShellChannel()
        attachShellChannel(profileId, channel)

        if (useTmux) {
            try {
                val sessionName = "haven-${profileId.take(8)}"
                // Send tmux attach-or-create command to the shell.
                // -A: attach to existing session if it exists, otherwise create.
                // Small delay to let the shell initialize before sending.
                Thread.sleep(200)
                val cmd = "tmux new-session -A -s $sessionName\n"
                channel.outputStream.write(cmd.toByteArray())
                channel.outputStream.flush()
                Log.d(TAG, "Sent tmux attach for session $sessionName")
            } catch (e: Exception) {
                Log.w(TAG, "tmux attach failed, continuing without tmux", e)
            }
        }
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
            onDisconnected = {
                Log.d(TAG, "Session $profileId disconnected unexpectedly")
                updateStatus(profileId, SessionState.Status.DISCONNECTED)
            },
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

    /**
     * Open an SFTP channel on the SSH session and store it in the session state.
     * Returns the channel, or null if the session isn't connected.
     */
    fun openSftpForSession(profileId: String): ChannelSftp? {
        val session = _sessions.value[profileId] ?: return null
        if (session.status != SessionState.Status.CONNECTED) return null
        // Reuse existing channel if still connected
        session.sftpChannel?.let { if (it.isConnected) return it }
        val channel = session.client.openSftpChannel()
        _sessions.update { map ->
            val existing = map[profileId] ?: return@update map
            map + (profileId to existing.copy(sftpChannel = channel))
        }
        return channel
    }

    fun attachTerminalSession(profileId: String, terminalSession: TerminalSession) {
        _sessions.update { map ->
            val existing = map[profileId] ?: return@update map
            map + (profileId to existing.copy(terminalSession = terminalSession))
        }
    }

    fun removeSession(profileId: String) {
        val session = _sessions.value[profileId] ?: return
        _sessions.update { it - profileId }
        ioExecutor.execute { tearDown(session) }
    }

    fun getSession(profileId: String): SessionState? = _sessions.value[profileId]

    fun disconnectAll() {
        val snapshot = _sessions.value.values.toList()
        _sessions.update { emptyMap() }
        ioExecutor.execute {
            snapshot.forEach { tearDown(it) }
        }
    }

    private fun tearDown(session: SessionState) {
        try { session.terminalSession?.close() } catch (e: Exception) {
            Log.e(TAG, "tearDown: terminalSession.close() failed", e)
        }
        try {
            if (session.sftpChannel?.isConnected == true) {
                session.sftpChannel.disconnect()
            }
        } catch (e: Exception) {
            Log.e(TAG, "tearDown: sftpChannel.disconnect() failed", e)
        }
        try {
            if (session.shellChannel?.isConnected == true) {
                session.shellChannel.disconnect()
            }
        } catch (e: Exception) {
            Log.e(TAG, "tearDown: shellChannel.disconnect() failed", e)
        }
        try { session.client.disconnect() } catch (e: Exception) {
            Log.e(TAG, "tearDown: client.disconnect() failed", e)
        }
    }
}
