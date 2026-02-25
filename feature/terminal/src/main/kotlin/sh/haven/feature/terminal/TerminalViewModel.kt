package sh.haven.feature.terminal

import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.connectbot.terminal.TerminalEmulator
import org.connectbot.terminal.TerminalEmulatorFactory
import sh.haven.core.ssh.SshSessionManager
import sh.haven.core.ssh.SshSessionManager.SessionState
import sh.haven.core.ssh.TerminalSession
import javax.inject.Inject

data class TerminalTab(
    val profileId: String,
    val label: String,
    val emulator: TerminalEmulator,
    val terminalSession: TerminalSession,
)

@HiltViewModel
class TerminalViewModel @Inject constructor(
    private val sessionManager: SshSessionManager,
) : ViewModel() {

    private val _tabs = MutableStateFlow<List<TerminalTab>>(emptyList())
    val tabs: StateFlow<List<TerminalTab>> = _tabs.asStateFlow()

    private val _activeTabIndex = MutableStateFlow(0)
    val activeTabIndex: StateFlow<Int> = _activeTabIndex.asStateFlow()

    // Modifier key state — read by onKeyboardInput callback, toggled by toolbar
    private val _ctrlActive = MutableStateFlow(false)
    val ctrlActive: StateFlow<Boolean> = _ctrlActive.asStateFlow()

    private val _altActive = MutableStateFlow(false)
    val altActive: StateFlow<Boolean> = _altActive.asStateFlow()

    fun toggleCtrl() { _ctrlActive.value = !_ctrlActive.value }
    fun toggleAlt() { _altActive.value = !_altActive.value }

    /**
     * Apply Ctrl/Alt modifiers to keyboard input, then reset them (one-shot).
     * Ctrl+letter → char AND 0x1F (e.g. Ctrl+C = 0x03).
     * Alt+char → ESC prefix.
     */
    private fun applyModifiers(data: ByteArray): ByteArray {
        val ctrl = _ctrlActive.value
        val alt = _altActive.value
        if (!ctrl && !alt) return data

        _ctrlActive.value = false
        _altActive.value = false

        var result = data
        if (ctrl && result.size == 1) {
            val b = result[0].toInt() and 0xFF
            if (b in 0x40..0x7F) {
                result = byteArrayOf((b and 0x1F).toByte())
            }
        }
        if (alt) {
            result = byteArrayOf(0x1b) + result
        }
        return result
    }

    private val trackedProfileIds = mutableSetOf<String>()

    /**
     * Called by the screen on each composition to sync tabs with session state.
     * Creates emulator + TerminalSession for new CONNECTED sessions that have
     * a shell channel but no terminal session yet.
     */
    fun syncSessions() {
        val sessions = sessionManager.sessions.value

        // Find sessions that are connected or reconnecting (keep tabs alive during reconnect)
        val connectedIds = sessions.values
            .filter {
                it.status == SessionState.Status.CONNECTED ||
                it.status == SessionState.Status.RECONNECTING
            }
            .map { it.profileId }
            .toSet()

        val currentTabs = _tabs.value.toMutableList()

        // Remove tabs for disconnected sessions
        val removed = currentTabs.removeAll { it.profileId !in connectedIds }
        if (removed) {
            trackedProfileIds.retainAll(connectedIds)
        }

        // Create tabs for new sessions ready for terminal
        for (profileId in connectedIds) {
            if (profileId in trackedProfileIds) continue
            if (!sessionManager.isReadyForTerminal(profileId)) continue

            val session = sessions[profileId] ?: continue

            // Create emulator, then wire TerminalSession to feed it
            lateinit var emulator: TerminalEmulator
            val termSession = sessionManager.createTerminalSession(
                profileId = profileId,
                onDataReceived = { data, offset, length ->
                    emulator.writeInput(data, offset, length)
                },
            ) ?: continue

            emulator = TerminalEmulatorFactory.create(
                initialRows = 24,
                initialCols = 80,
                defaultForeground = Color.White,
                defaultBackground = Color(0xFF1A1A2E),
                onKeyboardInput = { data -> termSession.sendToSsh(applyModifiers(data)) },
                onResize = { dims -> termSession.resize(dims.columns, dims.rows) },
            )

            // Start reader thread now that emulator is wired
            termSession.start()

            currentTabs.add(
                TerminalTab(
                    profileId = session.profileId,
                    label = session.label,
                    emulator = emulator,
                    terminalSession = termSession,
                )
            )
            trackedProfileIds.add(session.profileId)
        }

        _tabs.value = currentTabs

        // Clamp active index
        if (_activeTabIndex.value >= currentTabs.size && currentTabs.isNotEmpty()) {
            _activeTabIndex.value = currentTabs.size - 1
        }
    }

    fun selectTab(index: Int) {
        if (index in _tabs.value.indices) {
            _activeTabIndex.value = index
        }
    }

    fun selectTabByProfileId(profileId: String) {
        val index = _tabs.value.indexOfFirst { it.profileId == profileId }
        if (index >= 0) {
            _activeTabIndex.value = index
        }
    }

    fun closeSession(profileId: String) {
        sessionManager.removeSession(profileId)
        trackedProfileIds.remove(profileId)
        syncSessions()
    }
}
