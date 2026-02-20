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

        verify { client.disconnect() }
        assertNull(manager.getSession("id1"))
        assertFalse(manager.hasActiveSessions)
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

        verify { c1.disconnect() }
        verify { c2.disconnect() }
        assertTrue(manager.sessions.value.isEmpty())
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
}
