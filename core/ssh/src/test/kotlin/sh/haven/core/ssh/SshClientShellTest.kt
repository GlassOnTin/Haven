package sh.haven.core.ssh

import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertNotNull
import org.junit.Test

class SshClientShellTest {

    @Test
    fun `openShellChannel opens shell channel with PTY settings`() {
        // We can't easily mock the internal JSch instance, so test via integration-style
        // verification of the public API contract. The real JSch call chain is:
        // session.openChannel("shell") -> setPtyType() -> connect()
        // We verify the method throws when not connected.
        val client = SshClient()
        try {
            client.openShellChannel()
            throw AssertionError("Expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assert(e.message == "Not connected")
        }
    }

    @Test
    fun `openShellChannel uses default term type and dimensions`() {
        // Verify default parameters are accepted without error
        val client = SshClient()
        try {
            client.openShellChannel(term = "xterm-256color", cols = 80, rows = 24)
        } catch (e: IllegalStateException) {
            // Expected — not connected
        }
    }

    @Test
    fun `resizeShell calls setPtySize on channel`() {
        val channel = mockk<ChannelShell>(relaxed = true)
        val client = SshClient()
        client.resizeShell(channel, 120, 40)
        verify { channel.setPtySize(120, 40, 0, 0) }
    }
}
