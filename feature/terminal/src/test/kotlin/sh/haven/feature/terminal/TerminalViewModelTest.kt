package sh.haven.feature.terminal

import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import sh.haven.core.ssh.SshClient
import sh.haven.core.ssh.SshSessionManager

class TerminalViewModelTest {

    private lateinit var sessionManager: SshSessionManager
    private lateinit var viewModel: TerminalViewModel

    @Before
    fun setUp() {
        sessionManager = SshSessionManager()
        viewModel = TerminalViewModel(sessionManager)
    }

    @Test
    fun `initially has no tabs`() {
        assertEquals(0, viewModel.tabs.value.size)
        assertEquals(0, viewModel.activeTabIndex.value)
    }

    @Test
    fun `syncSessions with no sessions produces no tabs`() {
        viewModel.syncSessions()
        assertEquals(0, viewModel.tabs.value.size)
    }

    @Test
    fun `syncSessions skips CONNECTING sessions`() {
        val client = mockk<SshClient>(relaxed = true)
        sessionManager.registerSession("id1", "Server", client)
        // Status is CONNECTING, no shell channel

        viewModel.syncSessions()
        assertEquals(0, viewModel.tabs.value.size)
    }

    @Test
    fun `syncSessions skips CONNECTED sessions without shell channel`() {
        val client = mockk<SshClient>(relaxed = true)
        sessionManager.registerSession("id1", "Server", client)
        sessionManager.updateStatus("id1", SshSessionManager.SessionState.Status.CONNECTED)
        // No shell channel attached

        viewModel.syncSessions()
        assertEquals(0, viewModel.tabs.value.size)
    }

    @Test
    fun `selectTab with no tabs is no-op`() {
        viewModel.selectTab(2)
        assertEquals(0, viewModel.activeTabIndex.value)
    }

    @Test
    fun `closeSession removes from session manager`() {
        val client = mockk<SshClient>(relaxed = true)
        sessionManager.registerSession("id1", "Server", client)
        viewModel.closeSession("id1")

        assertEquals(null, sessionManager.getSession("id1"))
    }

    @Test
    fun `selectTabByProfileId with no matching tab is no-op`() {
        viewModel.selectTabByProfileId("nonexistent")
        assertEquals(0, viewModel.activeTabIndex.value)
    }
}
