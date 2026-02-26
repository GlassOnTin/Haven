package sh.haven.core.reticulum

/**
 * Bridge interface to the embedded Python Reticulum stack.
 *
 * The implementation lives in the app module (using Chaquopy).
 * All methods must be called from a background thread.
 */
interface ReticulumBridge {

    fun isInitialised(): Boolean

    /**
     * Initialise RNS, connecting to Sideband's shared instance.
     *
     * @param configDir writable directory for RNS config/identity storage
     * @param rpcKey hex-encoded shared instance RPC key from Sideband
     * @param host shared instance host (default 127.0.0.1)
     * @param port shared instance TCP port (default 37428)
     * @return Haven's RNS identity hash
     */
    fun initReticulum(
        configDir: String,
        rpcKey: String? = null,
        host: String = "127.0.0.1",
        port: Int = 37428,
    ): String

    /**
     * Resolve a destination hash via the Reticulum network.
     * Blocks up to 15 seconds.
     */
    fun resolveDestination(destinationHashHex: String): Boolean

    /**
     * Create and connect an rnsh session.
     * Blocks until the Reticulum Link is established (up to 30s).
     */
    fun createSession(destinationHashHex: String, sessionId: String): String

    /**
     * Read output from a session. Blocks up to [timeoutMs] milliseconds.
     *
     * @return output bytes, empty bytes on timeout, or null if disconnected.
     */
    fun readOutput(sessionId: String, timeoutMs: Int = 1000): ByteArray?

    /** Send keyboard input to a session. */
    fun sendInput(sessionId: String, data: ByteArray): Boolean

    /** Send a window resize to a session. */
    fun resizeSession(sessionId: String, cols: Int, rows: Int)

    /** Check if a session is still connected. */
    fun isConnected(sessionId: String): Boolean

    /** Close a specific session. */
    fun closeSession(sessionId: String)

    /** Close all sessions and shut down Reticulum. */
    fun closeAll()
}
