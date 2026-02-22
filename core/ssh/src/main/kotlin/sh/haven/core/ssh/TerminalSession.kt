package sh.haven.core.ssh

import com.jcraft.jsch.ChannelShell
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import kotlin.concurrent.thread

/**
 * Bridges a JSch [ChannelShell] to a terminal emulator.
 *
 * Reads SSH output on a background thread and delivers it via [onDataReceived].
 * Call [sendToSsh] to forward keyboard input to the remote shell.
 */
class TerminalSession(
    val profileId: String,
    val label: String,
    private val channel: ChannelShell,
    private val client: SshClient,
    private val onDataReceived: (ByteArray, Int, Int) -> Unit,
) : Closeable {

    private val sshInput: InputStream = channel.inputStream
    private val sshOutput: OutputStream = channel.outputStream

    @Volatile
    private var closed = false

    private var readerThread: Thread? = null

    /**
     * Start the reader thread that delivers SSH output to [onDataReceived].
     * Call this after all wiring (e.g., emulator setup) is complete.
     */
    fun start() {
        readerThread = thread(
            name = "ssh-reader-$profileId",
            isDaemon = true,
        ) {
            readLoop()
        }
    }

    private fun readLoop() {
        val buffer = ByteArray(8192)
        try {
            while (!closed && channel.isConnected) {
                val bytesRead = sshInput.read(buffer)
                if (bytesRead == -1) break
                if (bytesRead > 0) {
                    onDataReceived(buffer, 0, bytesRead)
                }
            }
        } catch (_: Exception) {
            // Channel closed or IO error
        }
    }

    fun sendToSsh(data: ByteArray) {
        if (closed || !channel.isConnected) return
        try {
            sshOutput.write(data)
            sshOutput.flush()
        } catch (_: Exception) {
            // Channel closed
        }
    }

    fun resize(cols: Int, rows: Int) {
        client.resizeShell(channel, cols, rows)
    }

    override fun close() {
        if (closed) return
        closed = true
        try { channel.disconnect() } catch (_: Exception) {}
        readerThread?.interrupt()
    }
}
