package sh.haven.core.reticulum

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ReticulumSessionManager"

/**
 * Manages active Reticulum/rnsh sessions across the app.
 * Parallel to [sh.haven.core.ssh.SshSessionManager] but without
 * reconnect, SFTP, or session manager (tmux/zellij) logic.
 */
@Singleton
class ReticulumSessionManager @Inject constructor(
    private val bridge: ReticulumBridge,
) {

    data class SessionState(
        val sessionId: String,
        val profileId: String,
        val label: String,
        val status: Status,
        val destinationHash: String,
        val reticulumSession: ReticulumSession? = null,
    ) {
        enum class Status { CONNECTING, CONNECTED, DISCONNECTED, ERROR }
    }

    private val _sessions = MutableStateFlow<Map<String, SessionState>>(emptyMap())
    val sessions: StateFlow<Map<String, SessionState>> = _sessions.asStateFlow()

    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "rns-session-io").apply { isDaemon = true }
    }

    val activeSessions: List<SessionState>
        get() = _sessions.value.values.filter {
            it.status == SessionState.Status.CONNECTED ||
                it.status == SessionState.Status.CONNECTING
        }

    /**
     * Register a new session. Returns the generated sessionId.
     */
    fun registerSession(profileId: String, label: String, destinationHash: String): String {
        val sessionId = UUID.randomUUID().toString()
        _sessions.update { map ->
            map + (sessionId to SessionState(
                sessionId = sessionId,
                profileId = profileId,
                label = label,
                status = SessionState.Status.CONNECTING,
                destinationHash = destinationHash,
            ))
        }
        return sessionId
    }

    /**
     * Connect a registered session. Must be called on a background thread.
     * Initialises Reticulum if needed, resolves the destination, creates a Python session.
     */
    /**
     * Connect a registered session. Must be called on a background thread.
     * Initialises Reticulum if needed, resolves the destination, creates a Python session.
     * Throws on failure so the caller can handle the error.
     */
    fun connectSession(
        sessionId: String,
        configDir: String,
        rpcKey: String?,
        host: String,
        port: Int,
    ) {
        val session = _sessions.value[sessionId]
            ?: throw IllegalStateException("Session $sessionId not found")

        if (!bridge.isInitialised()) {
            Log.d(TAG, "Initialising Reticulum...")
            bridge.initReticulum(configDir, rpcKey, host, port)
        }

        Log.d(TAG, "Resolving destination ${session.destinationHash}...")
        val resolved = bridge.resolveDestination(session.destinationHash)
        if (!resolved) {
            Log.e(TAG, "Failed to resolve destination ${session.destinationHash}")
            updateStatus(sessionId, SessionState.Status.ERROR)
            throw RuntimeException(
                "Could not resolve destination ${session.destinationHash}. " +
                    "Check that Sideband is running and the destination is reachable."
            )
        }

        Log.d(TAG, "Creating rnsh session to ${session.destinationHash}...")
        bridge.createSession(session.destinationHash, sessionId)

        updateStatus(sessionId, SessionState.Status.CONNECTED)
        Log.d(TAG, "Session $sessionId connected")
    }

    /**
     * Create a [ReticulumSession] for a connected session.
     * Returns the session, or null if not ready.
     */
    fun createTerminalSession(
        sessionId: String,
        onDataReceived: (ByteArray, Int, Int) -> Unit,
    ): ReticulumSession? {
        val session = _sessions.value[sessionId] ?: return null
        if (session.status != SessionState.Status.CONNECTED) return null
        if (session.reticulumSession != null) return null

        val reticulumSession = ReticulumSession(
            sessionId = sessionId,
            profileId = session.profileId,
            label = session.label,
            bridge = bridge,
            onDataReceived = onDataReceived,
            onDisconnected = { _ ->
                Log.d(TAG, "Session $sessionId disconnected")
                updateStatus(sessionId, SessionState.Status.DISCONNECTED)
            },
        )

        _sessions.update { map ->
            val existing = map[sessionId] ?: return@update map
            map + (sessionId to existing.copy(reticulumSession = reticulumSession))
        }

        return reticulumSession
    }

    fun isReadyForTerminal(sessionId: String): Boolean {
        val session = _sessions.value[sessionId] ?: return false
        return session.status == SessionState.Status.CONNECTED &&
            session.reticulumSession == null
    }

    fun updateStatus(sessionId: String, status: SessionState.Status) {
        _sessions.update { map ->
            val existing = map[sessionId] ?: return@update map
            map + (sessionId to existing.copy(status = status))
        }
    }

    fun removeSession(sessionId: String) {
        val session = _sessions.value[sessionId] ?: return
        _sessions.update { it - sessionId }
        ioExecutor.execute {
            try {
                session.reticulumSession?.close()
            } catch (e: Exception) {
                Log.e(TAG, "tearDown failed for $sessionId", e)
            }
        }
    }

    fun removeAllSessionsForProfile(profileId: String) {
        val toRemove = _sessions.value.values.filter { it.profileId == profileId }
        _sessions.update { map -> map.filterValues { it.profileId != profileId } }
        ioExecutor.execute {
            toRemove.forEach { session ->
                try {
                    session.reticulumSession?.close()
                } catch (e: Exception) {
                    Log.e(TAG, "tearDown failed for ${session.sessionId}", e)
                }
            }
        }
    }

    fun disconnectAll() {
        val snapshot = _sessions.value.values.toList()
        _sessions.update { emptyMap() }
        ioExecutor.execute {
            snapshot.forEach { session ->
                try {
                    session.reticulumSession?.close()
                } catch (e: Exception) {
                    Log.e(TAG, "tearDown failed for ${session.sessionId}", e)
                }
            }
        }
    }

    fun getSessionsForProfile(profileId: String): List<SessionState> =
        _sessions.value.values.filter { it.profileId == profileId }

    fun isProfileConnected(profileId: String): Boolean =
        _sessions.value.values.any {
            it.profileId == profileId && it.status == SessionState.Status.CONNECTED
        }

    fun getProfileStatus(profileId: String): SessionState.Status? {
        val statuses = _sessions.value.values
            .filter { it.profileId == profileId }
            .map { it.status }
        if (statuses.isEmpty()) return null
        return when {
            SessionState.Status.CONNECTED in statuses -> SessionState.Status.CONNECTED
            SessionState.Status.CONNECTING in statuses -> SessionState.Status.CONNECTING
            SessionState.Status.ERROR in statuses -> SessionState.Status.ERROR
            else -> SessionState.Status.DISCONNECTED
        }
    }
}
