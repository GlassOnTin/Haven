package sh.haven.app.navigation

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import sh.haven.core.data.preferences.UserPreferencesRepository
import sh.haven.feature.connections.ConnectionsScreen
import sh.haven.feature.keys.KeysScreen
import sh.haven.feature.settings.SettingsScreen
import sh.haven.feature.sftp.SftpScreen
import sh.haven.feature.terminal.TerminalScreen
import kotlin.math.abs

@Composable
fun HavenNavHost(
    preferencesRepository: UserPreferencesRepository,
) {
    val screens = Screen.entries
    val pagerState = rememberPagerState { screens.size }
    val coroutineScope = rememberCoroutineScope()
    val terminalFontSize by preferencesRepository.terminalFontSize
        .collectAsState(initial = UserPreferencesRepository.DEFAULT_FONT_SIZE)

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
                .consumeWindowInsets(innerPadding)
                .imePadding(),
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
                    TerminalScreen(
                        navigateToProfileId = pendingTerminalProfileId,
                        isActive = pagerState.settledPage == Screen.Terminal.ordinal,
                        fontSize = terminalFontSize,
                        // Terminal composable consumes touch events, blocking pager swipe.
                        // Intercept horizontal drags at Initial pass and forward to pager.
                        terminalModifier = Modifier.pagerSwipeOverride(pagerState, coroutineScope),
                    )
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

/**
 * Intercepts horizontal drag gestures at [PointerEventPass.Initial] (before children)
 * and forwards them to the [PagerState]. Vertical drags and taps pass through to children.
 */
private fun Modifier.pagerSwipeOverride(
    pagerState: PagerState,
    scope: CoroutineScope,
): Modifier = pointerInput(pagerState) {
    val touchSlopPx = viewConfiguration.touchSlop
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var totalX = 0f
        var totalY = 0f
        var decided = false
        var isHorizontal = false

        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull() ?: break
            val delta = change.positionChange()
            totalX += delta.x
            totalY += delta.y

            if (!decided && totalX * totalX + totalY * totalY > touchSlopPx * touchSlopPx) {
                decided = true
                isHorizontal = abs(totalX) > abs(totalY)
            }

            if (isHorizontal) {
                change.consume()
                pagerState.dispatchRawDelta(-delta.x)
            }
        } while (change.pressed)

        if (isHorizontal) {
            val threshold = size.width / 4
            val target = when {
                totalX < -threshold -> pagerState.currentPage + 1
                totalX > threshold -> pagerState.currentPage - 1
                else -> pagerState.currentPage
            }.coerceIn(0, pagerState.pageCount - 1)
            scope.launch { pagerState.animateScrollToPage(target) }
        }
    }
}
