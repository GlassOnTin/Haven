package sh.haven.feature.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
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

    val density = LocalDensity.current
    val imeBottom = WindowInsets.ime.getBottom(density)
    val isImeVisible = imeBottom > 0

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
                var fullHeightPx by remember { mutableIntStateOf(-1) }

                // Request focus so the soft keyboard appears and Terminal receives key events
                LaunchedEffect(activeTabIndex) {
                    focusRequester.requestFocus()
                }

                Box(
                    modifier = Modifier.weight(1f).clipToBounds(),
                    contentAlignment = Alignment.BottomStart,
                ) {
                    Terminal(
                        terminalEmulator = activeTab.emulator,
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (isImeVisible && fullHeightPx > 0) {
                                    Modifier.requiredHeight(with(density) { fullHeightPx.toDp() })
                                } else {
                                    Modifier.fillMaxSize()
                                }
                            )
                            .onSizeChanged { size ->
                                if (!isImeVisible) fullHeightPx = size.height
                            },
                        keyboardEnabled = true,
                        backgroundColor = Color(0xFF1A1A2E),
                        foregroundColor = Color(0xFF00E676),
                        focusRequester = focusRequester,
                    )
                }

                // Keyboard toolbar
                KeyboardToolbar(
                    onSendBytes = { bytes -> activeTab.terminalSession.sendToSsh(bytes) },
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
