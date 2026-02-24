package sh.haven.app.navigation

import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch
import sh.haven.feature.connections.ConnectionsScreen
import sh.haven.feature.keys.KeysScreen
import sh.haven.feature.settings.SettingsScreen
import sh.haven.feature.sftp.SftpScreen
import sh.haven.feature.terminal.TerminalScreen

@Composable
fun HavenNavHost() {
    val screens = Screen.entries
    val pagerState = rememberPagerState { screens.size }
    val coroutineScope = rememberCoroutineScope()

    // Profile ID to focus when navigating to terminal
    var pendingTerminalProfileId by rememberSaveable { mutableStateOf<String?>(null) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                screens.forEachIndexed { index, screen ->
                    NavigationBarItem(
                        icon = { Icon(screen.icon, contentDescription = screen.label) },
                        label = { Text(screen.label) },
                        selected = pagerState.currentPage == index,
                        onClick = {
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(index)
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding),
        ) { page ->
            when (screens[page]) {
                Screen.Connections -> ConnectionsScreen(
                    onNavigateToTerminal = { profileId ->
                        pendingTerminalProfileId = profileId
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(Screen.Terminal.ordinal)
                        }
                    },
                )
                Screen.Terminal -> {
                    TerminalScreen(navigateToProfileId = pendingTerminalProfileId)
                    LaunchedEffect(pendingTerminalProfileId) {
                        if (pendingTerminalProfileId != null) {
                            pendingTerminalProfileId = null
                        }
                    }
                }
                Screen.Sftp -> SftpScreen()
                Screen.Keys -> KeysScreen()
                Screen.Settings -> SettingsScreen()
            }
        }
    }
}
