package sh.haven.core.ssh

import com.jcraft.jsch.ChannelShell
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream

class TerminalSessionTest {

    @Test
    fun `sendToSsh writes bytes to channel output stream`() {
        val outputStream = ByteArrayOutputStream()
        val channel = mockk<ChannelShell>(relaxed = true) {
            every { inputStream } returns ByteArrayInputStream(ByteArray(0))
            every { getOutputStream() } returns outputStream
            every { isConnected } returns true
        }
        val client = mockk<SshClient>(relaxed = true)

        val session = TerminalSession(
            profileId = "test",
            label = "test@host",
            channel = channel,
            client = client,
            onDataReceived = { _, _, _ -> },
        )

        val testData = "ls -la\n".toByteArray()
        session.sendToSsh(testData)

        // sendToSsh dispatches to a background executor
        Thread.sleep(200)

        assertArrayEquals(testData, outputStream.toByteArray())

        session.close()
    }

    @Test
    fun `sendToSsh is no-op when channel disconnected`() {
        val outputStream = ByteArrayOutputStream()
        val channel = mockk<ChannelShell>(relaxed = true) {
            every { inputStream } returns ByteArrayInputStream(ByteArray(0))
            every { getOutputStream() } returns outputStream
            every { isConnected } returns false
        }
        val client = mockk<SshClient>(relaxed = true)

        val session = TerminalSession(
            profileId = "test",
            label = "test@host",
            channel = channel,
            client = client,
            onDataReceived = { _, _, _ -> },
        )

        session.sendToSsh("data".toByteArray())

        // sendToSsh guards on !channel.isConnected before submitting to executor
        Thread.sleep(200)

        assertEquals(0, outputStream.size())

        session.close()
    }

    @Test
    fun `reader thread delivers SSH data to onDataReceived callback`() {
        val received = mutableListOf<ByteArray>()
        val pipeOut = PipedOutputStream()
        val pipeIn = PipedInputStream(pipeOut)

        val channel = mockk<ChannelShell>(relaxed = true) {
            every { inputStream } returns pipeIn
            every { getOutputStream() } returns ByteArrayOutputStream()
            every { isConnected } returnsMany listOf(true, true, true, false)
        }
        val client = mockk<SshClient>(relaxed = true)

        val session = TerminalSession(
            profileId = "test",
            label = "test@host",
            channel = channel,
            client = client,
            onDataReceived = { data, offset, length ->
                received.add(data.copyOfRange(offset, offset + length))
            },
        )
        session.start()

        // Write test data to the pipe (simulating SSH output)
        val testData = "hello from ssh\n".toByteArray()
        pipeOut.write(testData)
        pipeOut.flush()
        pipeOut.close()

        // Give reader thread time to process
        Thread.sleep(200)

        assertEquals(1, received.size)
        assertArrayEquals(testData, received[0])

        session.close()
    }

    @Test
    fun `resize calls client resizeShell`() {
        val channel = mockk<ChannelShell>(relaxed = true) {
            every { inputStream } returns ByteArrayInputStream(ByteArray(0))
            every { getOutputStream() } returns ByteArrayOutputStream()
            every { isConnected } returns false
        }
        val client = mockk<SshClient>(relaxed = true)

        val session = TerminalSession(
            profileId = "test",
            label = "test@host",
            channel = channel,
            client = client,
            onDataReceived = { _, _, _ -> },
        )

        session.resize(120, 40)

        // resize dispatches to a background executor
        Thread.sleep(200)

        verify { client.resizeShell(channel, 120, 40) }

        session.close()
    }

    @Test
    fun `close disconnects channel`() {
        val channel = mockk<ChannelShell>(relaxed = true) {
            every { inputStream } returns ByteArrayInputStream(ByteArray(0))
            every { getOutputStream() } returns ByteArrayOutputStream()
            every { isConnected } returns false
        }
        val client = mockk<SshClient>(relaxed = true)

        val session = TerminalSession(
            profileId = "test",
            label = "test@host",
            channel = channel,
            client = client,
            onDataReceived = { _, _, _ -> },
        )

        session.close()
        verify { channel.disconnect() }
    }

    @Test
    fun `sendToSsh deduplicates identical back-to-back sends`() {
        val outputStream = ByteArrayOutputStream()
        val channel = mockk<ChannelShell>(relaxed = true) {
            every { inputStream } returns ByteArrayInputStream(ByteArray(0))
            every { getOutputStream() } returns outputStream
            every { isConnected } returns true
        }
        val client = mockk<SshClient>(relaxed = true)

        val session = TerminalSession(
            profileId = "test",
            label = "test@host",
            channel = channel,
            client = client,
            onDataReceived = { _, _, _ -> },
        )

        val testData = "a".toByteArray()
        session.sendToSsh(testData)
        session.sendToSsh(testData) // duplicate — should be dropped

        Thread.sleep(200)

        // Only one 'a' should be written
        assertArrayEquals(testData, outputStream.toByteArray())

        session.close()
    }

    @Test
    fun `sendToSsh allows same data after dedup window`() {
        val outputStream = ByteArrayOutputStream()
        val channel = mockk<ChannelShell>(relaxed = true) {
            every { inputStream } returns ByteArrayInputStream(ByteArray(0))
            every { getOutputStream() } returns outputStream
            every { isConnected } returns true
        }
        val client = mockk<SshClient>(relaxed = true)

        val session = TerminalSession(
            profileId = "test",
            label = "test@host",
            channel = channel,
            client = client,
            onDataReceived = { _, _, _ -> },
        )

        val testData = "a".toByteArray()
        session.sendToSsh(testData)
        Thread.sleep(60) // exceed 50ms dedup window
        session.sendToSsh(testData) // should NOT be dropped

        Thread.sleep(200)

        // Both 'a's should be written
        assertArrayEquals("aa".toByteArray(), outputStream.toByteArray())

        session.close()
    }

    @Test
    fun `close is idempotent`() {
        val channel = mockk<ChannelShell>(relaxed = true) {
            every { inputStream } returns ByteArrayInputStream(ByteArray(0))
            every { getOutputStream() } returns ByteArrayOutputStream()
            every { isConnected } returns false
        }
        val client = mockk<SshClient>(relaxed = true)

        val session = TerminalSession(
            profileId = "test",
            label = "test@host",
            channel = channel,
            client = client,
            onDataReceived = { _, _, _ -> },
        )

        session.close()
        session.close() // Should not throw
        // channel.disconnect() called once due to relaxed mock
    }
}
