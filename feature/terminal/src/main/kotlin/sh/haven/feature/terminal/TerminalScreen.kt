package sh.haven.feature.terminal

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.hilt.navigation.compose.hiltViewModel
import org.connectbot.terminal.Terminal
import sh.haven.core.data.preferences.UserPreferencesRepository

@Composable
fun TerminalScreen(
    navigateToProfileId: String? = null,
    isActive: Boolean = false,
    terminalModifier: Modifier = Modifier,
    fontSize: Int = UserPreferencesRepository.DEFAULT_FONT_SIZE,
    onNavigateToConnections: () -> Unit = {},
    onSelectionActiveChanged: (Boolean) -> Unit = {},
    viewModel: TerminalViewModel = hiltViewModel(),
) {
    val tabs by viewModel.tabs.collectAsState()
    val activeTabIndex by viewModel.activeTabIndex.collectAsState()
    val ctrlActive by viewModel.ctrlActive.collectAsState()
    val altActive by viewModel.altActive.collectAsState()
    val navigateToConnections by viewModel.navigateToConnections.collectAsState()
    val newTabSessionPicker by viewModel.newTabSessionPicker.collectAsState()
    val newTabLoading by viewModel.newTabLoading.collectAsState()
    val view = LocalView.current

    LaunchedEffect(navigateToConnections) {
        if (navigateToConnections) {
            onNavigateToConnections()
            viewModel.onNavigatedToConnections()
        }
    }

    // Show/hide keyboard when this tab becomes active/inactive
    LaunchedEffect(isActive) {
        val window = (view.context as? Activity)?.window ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(window, view)
        if (isActive && tabs.isNotEmpty()) {
            controller.show(WindowInsetsCompat.Type.ime())
        } else if (!isActive) {
            controller.hide(WindowInsetsCompat.Type.ime())
        }
    }

    // Sync tabs with session manager on each composition
    LaunchedEffect(Unit) {
        // Re-sync periodically via snapshotFlow
    }
    viewModel.syncSessions()

    // Navigate to specific tab if requested
    LaunchedEffect(navigateToProfileId) {
        if (navigateToProfileId != null) {
            viewModel.selectTabByProfileId(navigateToProfileId)
        }
    }

    // Session picker dialog for new tab
    newTabSessionPicker?.let { selection ->
        NewTabSessionPickerDialog(
            managerLabel = selection.managerLabel,
            sessionNames = selection.sessionNames,
            canKill = selection.manager.killCommand != null,
            onSelect = { name -> viewModel.onNewTabSessionSelected(selection.sessionId, name) },
            onKill = { name -> viewModel.killRemoteSession(name) },
            onNewSession = { viewModel.onNewTabSessionSelected(selection.sessionId, null) },
            onDismiss = { viewModel.dismissNewTabSessionPicker() },
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (tabs.isEmpty()) {
            EmptyTerminalState(fontSize = fontSize)
        } else {
            // Tab row — always show when tabs exist so "+" button is accessible
            PrimaryScrollableTabRow(
                selectedTabIndex = activeTabIndex.coerceIn(0, tabs.size - 1),
                modifier = Modifier.fillMaxWidth(),
                edgePadding = 8.dp,
            ) {
                tabs.forEachIndexed { index, tab ->
                    Tab(
                        selected = activeTabIndex == index,
                        onClick = { viewModel.selectTab(index) },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(tab.label, maxLines = 1)
                                IconButton(
                                    onClick = { viewModel.closeTab(tab.sessionId) },
                                    modifier = Modifier.size(20.dp),
                                ) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = "Close tab",
                                        modifier = Modifier.size(14.dp),
                                    )
                                }
                            }
                        },
                    )
                }
                // "+" tab for adding new tab
                Tab(
                    selected = false,
                    onClick = { viewModel.addTab() },
                    enabled = !newTabLoading,
                    text = {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = "New tab",
                            modifier = Modifier.size(18.dp),
                        )
                    },
                )
            }

            // Terminal area
            val activeTab = tabs.getOrNull(activeTabIndex)
            if (activeTab != null) {
                // key() forces Terminal recreation when switching tabs, ensuring
                // the emulator and keyboard input are bound to the correct session.
                key(activeTab.sessionId) {
                    val focusRequester = remember { FocusRequester() }

                    LaunchedEffect(Unit) {
                        focusRequester.requestFocus()
                    }

                    var selectionController by remember {
                        mutableStateOf<org.connectbot.terminal.SelectionController?>(null)
                    }

                    // Notify parent when selection state changes.
                    // isSelectionActive is backed by Compose MutableState, so
                    // this block recomposes when selection starts/ends.
                    val selectionActive = selectionController?.isSelectionActive == true
                    LaunchedEffect(selectionActive) {
                        onSelectionActiveChanged(selectionActive)
                        if (selectionActive && selectionController != null) {
                            expandSelectionToWord(selectionController!!, activeTab.emulator)
                        }
                    }

                    Box(modifier = Modifier.weight(1f).then(terminalModifier)) {
                        Terminal(
                            terminalEmulator = activeTab.emulator,
                            modifier = Modifier.fillMaxSize(),
                            initialFontSize = fontSize.sp,
                            keyboardEnabled = true,
                            backgroundColor = Color(0xFF1A1A2E),
                            foregroundColor = Color(0xFF00E676),
                            focusRequester = focusRequester,
                            onSelectionControllerAvailable = { selectionController = it },
                        )
                    }

                    // Show selection toolbar when selecting, keyboard toolbar otherwise
                    if (selectionActive && selectionController != null) {
                        SelectionToolbar(
                            controller = selectionController!!,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        KeyboardToolbar(
                            onSendBytes = { bytes -> activeTab.sendInput(bytes) },
                            focusRequester = focusRequester,
                            ctrlActive = ctrlActive,
                            altActive = altActive,
                            onToggleCtrl = viewModel::toggleCtrl,
                            onToggleAlt = viewModel::toggleAlt,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyTerminalState(fontSize: Int) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1A1A2E))
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Connect to a server to start a session.",
            fontFamily = FontFamily.Monospace,
            fontSize = fontSize.sp,
            color = Color(0xFF00E676),
        )
    }
}

@Composable
private fun NewTabSessionPickerDialog(
    managerLabel: String,
    sessionNames: List<String>,
    canKill: Boolean = false,
    onSelect: (String) -> Unit,
    onKill: (String) -> Unit = {},
    onNewSession: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$managerLabel sessions") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                sessionNames.forEach { name ->
                    ListItem(
                        headlineContent = { Text(name) },
                        trailingContent = if (canKill) {
                            {
                                IconButton(onClick = { onKill(name) }) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = "Kill session",
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        } else null,
                        modifier = Modifier.clickable { onSelect(name) },
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                ListItem(
                    headlineContent = {
                        Text(
                            "New session",
                            color = MaterialTheme.colorScheme.primary,
                        )
                    },
                    leadingContent = {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                    modifier = Modifier.clickable { onNewSession() },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
