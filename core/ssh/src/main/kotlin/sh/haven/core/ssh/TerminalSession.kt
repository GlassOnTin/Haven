package sh.haven.core.ssh

import android.util.Log
import com.jcraft.jsch.ChannelShell
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Executors
import kotlin.concurrent.thread

private const val TAG = "TerminalSession"

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

    /** Single-thread executor for serialising writes off the main thread. */
    private val writeExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ssh-writer-$profileId").apply { isDaemon = true }
    }

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

    /**
     * Forward keyboard input to the remote shell.
     * Safe to call from any thread — writes are dispatched to a background thread
     * to avoid NetworkOnMainThreadException.
     */
    fun sendToSsh(data: ByteArray) {
        if (closed || !channel.isConnected) {
            Log.d(TAG, "sendToSsh: dropping ${data.size} bytes (closed=$closed connected=${channel.isConnected})")
            return
        }
        writeExecutor.execute {
            if (closed || !channel.isConnected) return@execute
            try {
                sshOutput.write(data)
                sshOutput.flush()
            } catch (e: Exception) {
                Log.e(TAG, "sendToSsh: write failed", e)
            }
        }
    }

    fun resize(cols: Int, rows: Int) {
        client.resizeShell(channel, cols, rows)
    }

    override fun close() {
        if (closed) return
        closed = true
        writeExecutor.shutdown()
        try { channel.disconnect() } catch (_: Exception) {}
        readerThread?.interrupt()
    }
}
