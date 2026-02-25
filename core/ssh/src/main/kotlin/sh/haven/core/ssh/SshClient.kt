package sh.haven.core.ssh

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable

data class ExecResult(
    val exitStatus: Int,
    val stdout: String,
    val stderr: String,
)

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
        // TODO(M5): implement proper known hosts verification with KnownHost entity
        sess.setConfig("StrictHostKeyChecking", "no")
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
                    auth.passphrase.ifEmpty { null }?.toByteArray(),
                )
            }
        }

        sess.connect(connectTimeoutMs)
        session = sess
    }

    /**
     * Open an interactive shell channel on the current SSH session.
     * Must be called after [connect].
     */
    fun openShellChannel(
        term: String = "xterm-256color",
        cols: Int = 80,
        rows: Int = 24,
    ): ChannelShell {
        val sess = session ?: throw IllegalStateException("Not connected")
        val channel = sess.openChannel("shell") as ChannelShell
        channel.setPtyType(term, cols, rows, 0, 0)
        channel.connect()
        return channel
    }

    /**
     * Resize the PTY of an open shell channel.
     */
    fun resizeShell(channel: ChannelShell, cols: Int, rows: Int) {
        channel.setPtySize(cols, rows, 0, 0)
    }

    /**
     * Open an SFTP channel on the current SSH session.
     * Must be called after [connect].
     */
    fun openSftpChannel(): ChannelSftp {
        val sess = session ?: throw IllegalStateException("Not connected")
        val channel = sess.openChannel("sftp") as ChannelSftp
        channel.connect()
        return channel
    }

    /**
     * Execute a command on the remote host and return stdout, stderr, and exit status.
     * Must be called after [connect].
     */
    suspend fun execCommand(command: String): ExecResult = withContext(Dispatchers.IO) {
        val sess = session ?: throw IllegalStateException("Not connected")
        val channel = sess.openChannel("exec") as ChannelExec
        channel.setCommand(command)
        channel.inputStream = null

        val stdout = channel.inputStream
        val stderr = channel.errStream

        channel.connect()

        val outBytes = stdout.readBytes()
        val errBytes = stderr.readBytes()

        // Wait for channel to close so exitStatus is available
        while (!channel.isClosed) {
            Thread.sleep(50)
        }

        val result = ExecResult(
            exitStatus = channel.exitStatus,
            stdout = outBytes.decodeToString(),
            stderr = errBytes.decodeToString(),
        )
        channel.disconnect()
        result
    }

    /**
     * Connect synchronously (for use on background threads like reconnect).
     * Same as [connect] but without the coroutine wrapper.
     */
    fun connectBlocking(config: ConnectionConfig, connectTimeoutMs: Int = 10_000) {
        disconnect()

        val sess = jsch.getSession(config.username, config.host, config.port)
        sess.setConfig("StrictHostKeyChecking", "no")
        sess.serverAliveInterval = 15_000
        sess.serverAliveCountMax = 3

        when (val auth = config.authMethod) {
            is ConnectionConfig.AuthMethod.Password -> {
                sess.setPassword(auth.password)
            }
            is ConnectionConfig.AuthMethod.PrivateKey -> {
                jsch.addIdentity(
                    "haven-key-${System.nanoTime()}",
                    auth.keyBytes,
                    null,
                    auth.passphrase.ifEmpty { null }?.toByteArray(),
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
