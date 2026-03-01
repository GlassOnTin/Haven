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
     * Initialise RNS, connecting to either Sideband's shared instance or a direct gateway.
     *
     * @param configDir writable directory for RNS config/identity storage
     * @param host shared instance or gateway host (default 127.0.0.1)
     * @param port shared instance or gateway TCP port (default 37428)
     * @return Haven's RNS identity hash
     */
    fun initReticulum(
        configDir: String,
        host: String = "127.0.0.1",
        port: Int = 37428,
    ): String

    /**
     * Request a path to a destination. Non-blocking — returns true if path
     * is already known, false if a request was sent (result arrives asynchronously).
     */
    fun requestPath(destinationHashHex: String): Boolean

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

    /** Return the RNS init mode: "sideband", "gateway", or null if not initialised. */
    fun getInitMode(): String?

    /** Return discovered rnsh destinations as a JSON array string. */
    fun getDiscoveredDestinations(): String

    /**
     * Probe for Sideband's shared instance and speculatively init RNS if found.
     * Safe to call multiple times. Returns true if connected to Sideband.
     */
    fun probeSideband(configDir: String): Boolean
}
