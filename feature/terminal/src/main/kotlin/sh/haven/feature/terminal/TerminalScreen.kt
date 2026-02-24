package sh.haven.feature.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import org.connectbot.terminal.Terminal

@Composable
fun TerminalScreen(
    navigateToProfileId: String? = null,
    viewModel: TerminalViewModel = hiltViewModel(),
) {
    val tabs by viewModel.tabs.collectAsState()
    val activeTabIndex by viewModel.activeTabIndex.collectAsState()

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

    Column(modifier = Modifier.fillMaxSize().imePadding()) {
        if (tabs.isEmpty()) {
            EmptyTerminalState()
        } else {
            // Tab row
            if (tabs.size > 1) {
                PrimaryScrollableTabRow(
                    selectedTabIndex = activeTabIndex.coerceIn(0, tabs.size - 1),
                    modifier = Modifier.fillMaxWidth(),
                    edgePadding = 8.dp,
                ) {
                    tabs.forEachIndexed { index, tab ->
                        Tab(
                            selected = activeTabIndex == index,
                            onClick = { viewModel.selectTab(index) },
                            text = { Text(tab.label, maxLines = 1) },
                        )
                    }
                }
            }

            // Terminal area
            val activeTab = tabs.getOrNull(activeTabIndex)
            if (activeTab != null) {
                val focusRequester = remember { FocusRequester() }

                // Request focus so the soft keyboard appears and Terminal receives key events
                LaunchedEffect(activeTabIndex) {
                    focusRequester.requestFocus()
                }

                Box(modifier = Modifier.weight(1f)) {
                    Terminal(
                        terminalEmulator = activeTab.emulator,
                        modifier = Modifier.fillMaxSize(),
                        keyboardEnabled = true,
                        backgroundColor = Color(0xFF1A1A2E),
                        foregroundColor = Color(0xFF00E676),
                        focusRequester = focusRequester,
                    )
                }

                // Keyboard toolbar
                KeyboardToolbar(
                    onSendBytes = { bytes -> activeTab.terminalSession.sendToSsh(bytes) },
                    focusRequester = focusRequester,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun EmptyTerminalState() {
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
            fontSize = 14.sp,
            color = Color(0xFF00E676),
        )
    }
}
