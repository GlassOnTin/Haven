package sh.haven.core.ssh

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SshSessionManagerTest {

    private lateinit var manager: SshSessionManager

    @Before
    fun setUp() {
        manager = SshSessionManager()
    }

    @Test
    fun `initially has no sessions`() {
        assertTrue(manager.sessions.value.isEmpty())
        assertFalse(manager.hasActiveSessions)
    }

    @Test
    fun `registerSession adds session with CONNECTING status`() {
        val client = mockk<SshClient>(relaxed = true)
        manager.registerSession("id1", "My Server", client)

        val session = manager.getSession("id1")
        assertNotNull(session)
        assertEquals("My Server", session!!.label)
        assertEquals(SshSessionManager.SessionState.Status.CONNECTING, session.status)
    }

    @Test
    fun `updateStatus changes session status`() {
        val client = mockk<SshClient>(relaxed = true)
        manager.registerSession("id1", "Server", client)
        manager.updateStatus("id1", SshSessionManager.SessionState.Status.CONNECTED)

        val session = manager.getSession("id1")
        assertEquals(SshSessionManager.SessionState.Status.CONNECTED, session!!.status)
    }

    @Test
    fun `updateStatus for non-existent session is no-op`() {
        manager.updateStatus("nonexistent", SshSessionManager.SessionState.Status.CONNECTED)
        assertNull(manager.getSession("nonexistent"))
    }

    @Test
    fun `activeSessions returns only CONNECTING and CONNECTED`() {
        val c1 = mockk<SshClient>(relaxed = true)
        val c2 = mockk<SshClient>(relaxed = true)
        val c3 = mockk<SshClient>(relaxed = true)

        manager.registerSession("id1", "S1", c1)
        manager.registerSession("id2", "S2", c2)
        manager.registerSession("id3", "S3", c3)

        manager.updateStatus("id1", SshSessionManager.SessionState.Status.CONNECTED)
        manager.updateStatus("id2", SshSessionManager.SessionState.Status.DISCONNECTED)
        // id3 remains CONNECTING

        val active = manager.activeSessions
        assertEquals(2, active.size)
        assertTrue(active.any { it.profileId == "id1" })
        assertTrue(active.any { it.profileId == "id3" })
    }

    @Test
    fun `removeSession disconnects client and removes from map`() {
        val client = mockk<SshClient>(relaxed = true)
        manager.registerSession("id1", "Server", client)
        manager.removeSession("id1")

        // State is cleared synchronously
        assertNull(manager.getSession("id1"))
        assertFalse(manager.hasActiveSessions)

        // Teardown dispatches to background executor
        Thread.sleep(200)
        verify { client.disconnect() }
    }

    @Test
    fun `removeSession for non-existent session is safe`() {
        manager.removeSession("nonexistent")
        // No exception
    }

    @Test
    fun `disconnectAll clears all sessions`() {
        val c1 = mockk<SshClient>(relaxed = true)
        val c2 = mockk<SshClient>(relaxed = true)
        manager.registerSession("id1", "S1", c1)
        manager.registerSession("id2", "S2", c2)

        manager.disconnectAll()

        // State is cleared synchronously
        assertTrue(manager.sessions.value.isEmpty())

        // Teardown dispatches to background executor
        Thread.sleep(200)
        verify { c1.disconnect() }
        verify { c2.disconnect() }
    }

    @Test
    fun `hasActiveSessions returns true when connected sessions exist`() {
        val client = mockk<SshClient>(relaxed = true)
        manager.registerSession("id1", "Server", client)
        manager.updateStatus("id1", SshSessionManager.SessionState.Status.CONNECTED)

        assertTrue(manager.hasActiveSessions)
    }

    @Test
    fun `hasActiveSessions returns false when all disconnected`() {
        val client = mockk<SshClient>(relaxed = true)
        manager.registerSession("id1", "Server", client)
        manager.updateStatus("id1", SshSessionManager.SessionState.Status.DISCONNECTED)

        assertFalse(manager.hasActiveSessions)
    }

    @Test
    fun `attachShellChannel stores channel in session state`() {
        val client = mockk<SshClient>(relaxed = true)
        val channel = mockk<com.jcraft.jsch.ChannelShell>(relaxed = true)
        manager.registerSession("id1", "Server", client)
        manager.attachShellChannel("id1", channel)

        val session = manager.getSession("id1")
        assertNotNull(session?.shellChannel)
        assertEquals(channel, session?.shellChannel)
    }

    @Test
    fun `attachTerminalSession stores terminal session in state`() {
        val client = mockk<SshClient>(relaxed = true)
        val terminalSession = mockk<TerminalSession>(relaxed = true)
        manager.registerSession("id1", "Server", client)
        manager.attachTerminalSession("id1", terminalSession)

        val session = manager.getSession("id1")
        assertNotNull(session?.terminalSession)
    }

    @Test
    fun `removeSession closes terminal session`() {
        val client = mockk<SshClient>(relaxed = true)
        val terminalSession = mockk<TerminalSession>(relaxed = true)
        manager.registerSession("id1", "Server", client)
        manager.attachTerminalSession("id1", terminalSession)
        manager.removeSession("id1")

        // Teardown dispatches to background executor
        Thread.sleep(200)
        verify { terminalSession.close() }
        verify { client.disconnect() }
    }

    @Test
    fun `disconnectAll closes all terminal sessions`() {
        val c1 = mockk<SshClient>(relaxed = true)
        val c2 = mockk<SshClient>(relaxed = true)
        val t1 = mockk<TerminalSession>(relaxed = true)
        manager.registerSession("id1", "S1", c1)
        manager.registerSession("id2", "S2", c2)
        manager.attachTerminalSession("id1", t1)

        manager.disconnectAll()

        // State is cleared synchronously
        assertTrue(manager.sessions.value.isEmpty())

        // Teardown dispatches to background executor
        Thread.sleep(200)
        verify { t1.close() }
        verify { c1.disconnect() }
        verify { c2.disconnect() }
    }
}
