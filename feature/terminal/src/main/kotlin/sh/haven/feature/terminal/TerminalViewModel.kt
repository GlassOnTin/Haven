package sh.haven.feature.terminal

import android.util.Log
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.connectbot.terminal.TerminalEmulator
import org.connectbot.terminal.TerminalEmulatorFactory
import sh.haven.core.ssh.ConnectionConfig
import sh.haven.core.ssh.SessionManager
import sh.haven.core.ssh.SshClient
import sh.haven.core.ssh.SshSessionManager
import sh.haven.core.ssh.SshSessionManager.SessionState
import sh.haven.core.ssh.TerminalSession
import javax.inject.Inject

private const val TAG = "TerminalViewModel"

data class TerminalTab(
    val sessionId: String,
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

    /** Emitted once when a session closes and no tabs remain. */
    private val _navigateToConnections = MutableStateFlow(false)
    val navigateToConnections: StateFlow<Boolean> = _navigateToConnections.asStateFlow()

    fun onNavigatedToConnections() {
        _navigateToConnections.value = false
    }

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

    private val trackedSessionIds = mutableSetOf<String>()

    /**
     * Called by the screen on each composition to sync tabs with session state.
     * Creates emulator + TerminalSession for new CONNECTED sessions that have
     * a shell channel but no terminal session yet.
     */
    fun syncSessions() {
        val sessions = sessionManager.sessions.value

        // Find sessions that are connected or reconnecting (keep tabs alive during reconnect)
        val activeSessionIds = sessions.values
            .filter {
                it.status == SessionState.Status.CONNECTED ||
                it.status == SessionState.Status.RECONNECTING
            }
            .map { it.sessionId }
            .toSet()

        val currentTabs = _tabs.value.toMutableList()

        // Remove tabs for disconnected sessions or sessions that were replaced
        // (reconnected with a fresh SSH connection — terminalSession is null)
        val hadTabs = currentTabs.isNotEmpty()
        val removed = currentTabs.removeAll { tab ->
            tab.sessionId !in activeSessionIds ||
                sessions[tab.sessionId]?.terminalSession == null
        }
        if (removed) {
            trackedSessionIds.retainAll(currentTabs.map { it.sessionId }.toSet())
            if (hadTabs && currentTabs.isEmpty()) {
                _navigateToConnections.value = true
            }
        }

        // Create tabs for new sessions ready for terminal
        for (sessionId in activeSessionIds) {
            if (sessionId in trackedSessionIds) continue
            if (!sessionManager.isReadyForTerminal(sessionId)) continue

            val session = sessions[sessionId] ?: continue

            // Generate a tab label — disambiguate when multiple tabs share same profile
            val tabLabel = generateTabLabel(session.label, session.profileId, currentTabs)

            // Create emulator, then wire TerminalSession to feed it
            lateinit var emulator: TerminalEmulator
            val termSession = sessionManager.createTerminalSession(
                sessionId = sessionId,
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
                    sessionId = session.sessionId,
                    profileId = session.profileId,
                    label = tabLabel,
                    emulator = emulator,
                    terminalSession = termSession,
                )
            )
            trackedSessionIds.add(session.sessionId)
        }

        _tabs.value = currentTabs

        // Clamp active index
        if (_activeTabIndex.value >= currentTabs.size && currentTabs.isNotEmpty()) {
            _activeTabIndex.value = currentTabs.size - 1
        }
    }

    /**
     * Generate a tab label, appending a number suffix when multiple tabs
     * share the same profile (e.g., "myserver", "myserver (2)").
     */
    private fun generateTabLabel(
        baseLabel: String,
        profileId: String,
        existingTabs: List<TerminalTab>,
    ): String {
        val sameProfileCount = existingTabs.count { it.profileId == profileId }
        return if (sameProfileCount == 0) baseLabel
        else "$baseLabel (${sameProfileCount + 1})"
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

    fun selectTabBySessionId(sessionId: String) {
        val index = _tabs.value.indexOfFirst { it.sessionId == sessionId }
        if (index >= 0) {
            _activeTabIndex.value = index
        }
    }

    fun closeTab(sessionId: String) {
        sessionManager.removeSession(sessionId)
        trackedSessionIds.remove(sessionId)
        syncSessions()
    }

    /** Close all sessions for a profile (called from connections disconnect). */
    fun closeSession(profileId: String) {
        sessionManager.removeAllSessionsForProfile(profileId)
        trackedSessionIds.removeAll(
            _tabs.value.filter { it.profileId == profileId }.map { it.sessionId }.toSet()
        )
        syncSessions()
    }

    /** When non-null, the UI should show a session picker for a new tab. */
    data class NewTabSessionSelection(
        val profileId: String,
        val managerLabel: String,
        val sessionNames: List<String>,
        val sessionId: String,
    )

    private val _newTabSessionPicker = MutableStateFlow<NewTabSessionSelection?>(null)
    val newTabSessionPicker: StateFlow<NewTabSessionSelection?> = _newTabSessionPicker.asStateFlow()

    private val _newTabLoading = MutableStateFlow(false)
    val newTabLoading: StateFlow<Boolean> = _newTabLoading.asStateFlow()

    /**
     * Add a new tab by creating a fresh SSH connection to the same server as the current tab.
     */
    fun addTab() {
        val activeTab = _tabs.value.getOrNull(_activeTabIndex.value) ?: return
        val profileId = activeTab.profileId
        val configPair = sessionManager.getConnectionConfigForProfile(profileId) ?: return
        val (config, sshSessionMgr) = configPair

        // Get label from existing session
        val label = activeTab.label.replace(Regex(" \\(\\d+\\)$"), "")

        viewModelScope.launch {
            _newTabLoading.value = true
            try {
                val client = SshClient()
                val sessionId = sessionManager.registerSession(profileId, label, client)

                withContext(Dispatchers.IO) {
                    client.connect(config)
                }
                sessionManager.storeConnectionConfig(sessionId, config, sshSessionMgr)

                // Check for session manager sessions
                val listCmd = sshSessionMgr.listCommand
                if (listCmd != null) {
                    val existingSessions = withContext(Dispatchers.IO) {
                        try {
                            val result = client.execCommand(listCmd)
                            if (result.exitStatus == 0) {
                                SessionManager.parseSessionList(sshSessionMgr, result.stdout)
                            } else emptyList()
                        } catch (_: Exception) {
                            emptyList()
                        }
                    }
                    if (existingSessions.isNotEmpty()) {
                        _newTabSessionPicker.value = NewTabSessionSelection(
                            profileId = profileId,
                            managerLabel = sshSessionMgr.label,
                            sessionNames = existingSessions,
                            sessionId = sessionId,
                        )
                        _newTabLoading.value = false
                        return@launch
                    }
                }

                // No session manager or no existing sessions — proceed directly
                finishNewTab(sessionId)
            } catch (e: Exception) {
                Log.e(TAG, "addTab failed", e)
            } finally {
                _newTabLoading.value = false
            }
        }
    }

    fun onNewTabSessionSelected(sessionId: String, sessionName: String?) {
        _newTabSessionPicker.value = null
        viewModelScope.launch {
            _newTabLoading.value = true
            try {
                if (sessionName != null) {
                    sessionManager.setChosenSessionName(sessionId, sessionName)
                }
                finishNewTab(sessionId)
            } catch (e: Exception) {
                Log.e(TAG, "onNewTabSessionSelected failed", e)
                sessionManager.removeSession(sessionId)
            } finally {
                _newTabLoading.value = false
            }
        }
    }

    fun dismissNewTabSessionPicker() {
        val sel = _newTabSessionPicker.value ?: return
        _newTabSessionPicker.value = null
        sessionManager.removeSession(sel.sessionId)
    }

    private suspend fun finishNewTab(sessionId: String) {
        withContext(Dispatchers.IO) {
            sessionManager.openShellForSession(sessionId)
        }
        sessionManager.updateStatus(sessionId, SessionState.Status.CONNECTED)
        syncSessions()
        // Select the new tab
        selectTabBySessionId(sessionId)
    }
}
