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
import sh.haven.core.reticulum.ReticulumSessionManager
import javax.inject.Inject

private const val TAG = "TerminalViewModel"

/**
 * Coalesces rapid single-byte inputs into a batch, then deduplicates only
 * the exact IME double-fire pattern (buffer == [X, X]).
 *
 * Android IMEs often fire both commitText and sendKeyEvent for the same
 * keystroke, causing onKeyboardInput to be called twice with the same byte.
 * Both calls happen within a single Handler message, so a posted Runnable
 * flushes after all input from the current message is processed.
 *
 * The IME double-fire signature: exactly 2 identical bytes in the buffer.
 * Paste "aa" also matches this, but that's a rare edge case compared to
 * the constant double-fire on every keystroke. Longer paste sequences
 * (e.g., "aab", "43339") are preserved correctly.
 *
 * Multi-byte inputs (toolbar, escape sequences) bypass coalescing.
 */
private class InputCoalescer(private val sink: (ByteArray) -> Unit) {
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val buffer = mutableListOf<Byte>()

    private val flushRunnable = Runnable { flush() }

    fun send(data: ByteArray) {
        if (data.size != 1) {
            // Multi-byte input (toolbar key combos, etc.) — flush pending then send directly
            flush()
            sink(data)
            return
        }

        synchronized(buffer) {
            buffer.add(data[0])
        }

        // Post flush to run after the current message completes.
        // Both IME double-fire calls and paste iteration happen within
        // one message, so the flush sees the complete batch.
        handler.removeCallbacks(flushRunnable)
        handler.post(flushRunnable)
    }

    private fun flush() {
        handler.removeCallbacks(flushRunnable)
        val bytes: ByteArray
        synchronized(buffer) {
            if (buffer.isEmpty()) return
            // IME double-fire signature: exactly 2 identical bytes
            if (buffer.size == 2 && buffer[0] == buffer[1]) {
                bytes = byteArrayOf(buffer[0])
            } else {
                bytes = buffer.toByteArray()
            }
            buffer.clear()
        }
        sink(bytes)
    }
}

data class TerminalTab(
    val sessionId: String,
    val profileId: String,
    val label: String,
    val transportType: String,
    val emulator: TerminalEmulator,
    val sendInput: (ByteArray) -> Unit,
    val resize: (Int, Int) -> Unit,
    val close: () -> Unit,
)

@HiltViewModel
class TerminalViewModel @Inject constructor(
    private val sessionManager: SshSessionManager,
    private val reticulumSessionManager: ReticulumSessionManager,
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
     * Ctrl+letter -> char AND 0x1F (e.g. Ctrl+C = 0x03).
     * Alt+char -> ESC prefix.
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
     * Creates emulator + terminal session for new CONNECTED sessions.
     */
    fun syncSessions() {
        val sshSessions = sessionManager.sessions.value
        val rnsSessions = reticulumSessionManager.sessions.value

        // Find SSH sessions that are connected or reconnecting
        val activeSshIds = sshSessions.values
            .filter {
                it.status == SessionState.Status.CONNECTED ||
                    it.status == SessionState.Status.RECONNECTING
            }
            .map { it.sessionId }
            .toSet()

        // Find Reticulum sessions that are connected
        val activeRnsIds = rnsSessions.values
            .filter {
                it.status == ReticulumSessionManager.SessionState.Status.CONNECTED
            }
            .map { it.sessionId }
            .toSet()

        val allActiveIds = activeSshIds + activeRnsIds

        val currentTabs = _tabs.value.toMutableList()

        // Remove tabs for disconnected sessions
        val hadTabs = currentTabs.isNotEmpty()
        val removed = currentTabs.removeAll { tab ->
            when (tab.transportType) {
                "SSH" -> tab.sessionId !in activeSshIds ||
                    sshSessions[tab.sessionId]?.terminalSession == null
                "RETICULUM" -> tab.sessionId !in activeRnsIds ||
                    rnsSessions[tab.sessionId]?.reticulumSession == null
                else -> true
            }
        }
        if (removed) {
            trackedSessionIds.retainAll(currentTabs.map { it.sessionId }.toSet())
            if (hadTabs && currentTabs.isEmpty()) {
                _navigateToConnections.value = true
            }
        }

        // Create tabs for new SSH sessions
        for (sessionId in activeSshIds) {
            if (sessionId in trackedSessionIds) continue
            if (!sessionManager.isReadyForTerminal(sessionId)) continue

            val session = sshSessions[sessionId] ?: continue
            val tabLabel = generateTabLabel(session.label, session.profileId, currentTabs)

            lateinit var emulator: TerminalEmulator
            val termSession = sessionManager.createTerminalSession(
                sessionId = sessionId,
                onDataReceived = { data, offset, length ->
                    emulator.writeInput(data, offset, length)
                },
            ) ?: continue

            val coalescer = InputCoalescer { data -> termSession.sendToSsh(data) }
            emulator = TerminalEmulatorFactory.create(
                initialRows = 24,
                initialCols = 80,
                defaultForeground = Color.White,
                defaultBackground = Color(0xFF1A1A2E),
                onKeyboardInput = { data -> coalescer.send(applyModifiers(data)) },
                onResize = { dims ->
                    Log.d(TAG, "SSH onResize: ${dims.columns}x${dims.rows}")
                    termSession.resize(dims.columns, dims.rows)
                },
            )

            termSession.start()

            currentTabs.add(
                TerminalTab(
                    sessionId = session.sessionId,
                    profileId = session.profileId,
                    label = tabLabel,
                    transportType = "SSH",
                    emulator = emulator,
                    sendInput = { data -> termSession.sendToSsh(data) },
                    resize = { cols, rows -> termSession.resize(cols, rows) },
                    close = { termSession.close() },
                )
            )
            trackedSessionIds.add(session.sessionId)
        }

        // Create tabs for new Reticulum sessions
        for (sessionId in activeRnsIds) {
            if (sessionId in trackedSessionIds) continue
            if (!reticulumSessionManager.isReadyForTerminal(sessionId)) continue

            val session = rnsSessions[sessionId] ?: continue
            val tabLabel = generateTabLabel(session.label, session.profileId, currentTabs)

            lateinit var emulator: TerminalEmulator
            val rnsSession = reticulumSessionManager.createTerminalSession(
                sessionId = sessionId,
                onDataReceived = { data, offset, length ->
                    emulator.writeInput(data, offset, length)
                },
            ) ?: continue

            val rnsCoalescer = InputCoalescer { data -> rnsSession.sendInput(data) }
            emulator = TerminalEmulatorFactory.create(
                initialRows = 24,
                initialCols = 80,
                defaultForeground = Color.White,
                defaultBackground = Color(0xFF1A1A2E),
                onKeyboardInput = { data -> rnsCoalescer.send(applyModifiers(data)) },
                onResize = { dims ->
                    Log.d(TAG, "RNS onResize: ${dims.columns}x${dims.rows}")
                    rnsSession.resize(dims.columns, dims.rows)
                },
            )

            rnsSession.start()

            currentTabs.add(
                TerminalTab(
                    sessionId = session.sessionId,
                    profileId = session.profileId,
                    label = tabLabel,
                    transportType = "RETICULUM",
                    emulator = emulator,
                    sendInput = { data -> rnsSession.sendInput(data) },
                    resize = { cols, rows -> rnsSession.resize(cols, rows) },
                    close = { rnsSession.close() },
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

    fun renameTab(sessionId: String, newLabel: String) {
        _tabs.value = _tabs.value.map { tab ->
            if (tab.sessionId == sessionId) tab.copy(label = newLabel) else tab
        }
    }

    fun closeTab(sessionId: String) {
        // Check both managers
        if (sessionManager.sessions.value.containsKey(sessionId)) {
            sessionManager.removeSession(sessionId)
        } else {
            reticulumSessionManager.removeSession(sessionId)
        }
        trackedSessionIds.remove(sessionId)
        syncSessions()
    }

    /** Close all sessions for a profile (called from connections disconnect). */
    fun closeSession(profileId: String) {
        sessionManager.removeAllSessionsForProfile(profileId)
        reticulumSessionManager.removeAllSessionsForProfile(profileId)
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
        val manager: SessionManager = SessionManager.NONE,
    )

    private val _newTabSessionPicker = MutableStateFlow<NewTabSessionSelection?>(null)
    val newTabSessionPicker: StateFlow<NewTabSessionSelection?> = _newTabSessionPicker.asStateFlow()

    private val _newTabLoading = MutableStateFlow(false)
    val newTabLoading: StateFlow<Boolean> = _newTabLoading.asStateFlow()

    /**
     * Add a new tab by creating a fresh connection to the same server as the current tab.
     */
    fun addTab() {
        val activeTab = _tabs.value.getOrNull(_activeTabIndex.value) ?: return

        if (activeTab.transportType == "RETICULUM") {
            addReticulumTab(activeTab)
            return
        }

        // SSH tab — existing logic
        val profileId = activeTab.profileId
        val configPair = sessionManager.getConnectionConfigForProfile(profileId) ?: return
        val (config, sshSessionMgr) = configPair

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
                            manager = sshSessionMgr,
                        )
                        _newTabLoading.value = false
                        return@launch
                    }
                }

                finishNewSshTab(sessionId)
            } catch (e: Exception) {
                Log.e(TAG, "addTab failed", e)
            } finally {
                _newTabLoading.value = false
            }
        }
    }

    /**
     * Add a new Reticulum tab to the same destination as the current tab.
     */
    private fun addReticulumTab(activeTab: TerminalTab) {
        val profileId = activeTab.profileId
        val rnsSession = reticulumSessionManager.sessions.value.values
            .firstOrNull { it.profileId == profileId }
            ?: return
        val label = activeTab.label.replace(Regex(" \\(\\d+\\)$"), "")

        viewModelScope.launch {
            _newTabLoading.value = true
            try {
                val sessionId = reticulumSessionManager.registerSession(
                    profileId = profileId,
                    label = label,
                    destinationHash = rnsSession.destinationHash,
                )
                withContext(Dispatchers.IO) {
                    reticulumSessionManager.connectSession(
                        sessionId = sessionId,
                        configDir = "", // Already initialised
                        host = "",
                        port = 0,
                    )
                }
                syncSessions()
                selectTabBySessionId(sessionId)
            } catch (e: Exception) {
                Log.e(TAG, "addReticulumTab failed", e)
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
                finishNewSshTab(sessionId)
            } catch (e: Exception) {
                Log.e(TAG, "onNewTabSessionSelected failed", e)
                sessionManager.removeSession(sessionId)
            } finally {
                _newTabLoading.value = false
            }
        }
    }

    fun killRemoteSession(sessionName: String) {
        val sel = _newTabSessionPicker.value ?: return
        val killCmd = sel.manager.killCommand?.invoke(sessionName) ?: return
        val session = sessionManager.getSession(sel.sessionId) ?: return

        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    session.client.execCommand(killCmd)
                }
                val listCmd = sel.manager.listCommand ?: return@launch
                val updated = withContext(Dispatchers.IO) {
                    try {
                        val result = session.client.execCommand(listCmd)
                        if (result.exitStatus == 0) {
                            SessionManager.parseSessionList(sel.manager, result.stdout)
                        } else emptyList()
                    } catch (_: Exception) {
                        emptyList()
                    }
                }
                if (updated.isNotEmpty()) {
                    _newTabSessionPicker.value = sel.copy(sessionNames = updated)
                } else {
                    _newTabSessionPicker.value = null
                }
            } catch (e: Exception) {
                Log.e(TAG, "killRemoteSession failed", e)
            }
        }
    }

    fun dismissNewTabSessionPicker() {
        val sel = _newTabSessionPicker.value ?: return
        _newTabSessionPicker.value = null
        sessionManager.removeSession(sel.sessionId)
    }

    private suspend fun finishNewSshTab(sessionId: String) {
        withContext(Dispatchers.IO) {
            sessionManager.openShellForSession(sessionId)
        }
        sessionManager.updateStatus(sessionId, SessionState.Status.CONNECTED)
        syncSessions()
        selectTabBySessionId(sessionId)
    }
}
