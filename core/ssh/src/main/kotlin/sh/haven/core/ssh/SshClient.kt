package sh.haven.core.ssh

import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable

/**
 * Wrapper around JSch providing coroutine-based SSH connectivity.
 */
class SshClient : Closeable {
    private val jsch = JSch()
    private var session: Session? = null

    val isConnected: Boolean
        get() = session?.isConnected == true

    /**
     * Connect to an SSH server using the given config.
     * This suspends on Dispatchers.IO.
     */
    suspend fun connect(
        config: ConnectionConfig,
        connectTimeoutMs: Int = 10_000,
    ) = withContext(Dispatchers.IO) {
        disconnect()

        val sess = jsch.getSession(config.username, config.host, config.port)
        sess.setConfig("StrictHostKeyChecking", "ask")
        sess.serverAliveInterval = 15_000
        sess.serverAliveCountMax = 3

        when (val auth = config.authMethod) {
            is ConnectionConfig.AuthMethod.Password -> {
                sess.setPassword(auth.password)
            }
            is ConnectionConfig.AuthMethod.PrivateKey -> {
                jsch.addIdentity(
                    "haven-key",
                    auth.keyBytes,
                    null,
                    auth.passphrase.toByteArray(),
                )
            }
        }

        sess.connect(connectTimeoutMs)
        session = sess
    }

    /**
     * Disconnect the current session if connected.
     */
    fun disconnect() {
        session?.disconnect()
        session = null
    }

    override fun close() = disconnect()
}
