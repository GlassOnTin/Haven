package sh.haven.feature.terminal

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.connectbot.terminal.TerminalEmulator

// VT100/xterm escape sequences for special keys
private const val ESC = "\u001b"
private val KEY_ESC = byteArrayOf(0x1b)
private val KEY_TAB = byteArrayOf(0x09)
private val KEY_UP = "$ESC[A".toByteArray()
private val KEY_DOWN = "$ESC[B".toByteArray()
private val KEY_RIGHT = "$ESC[C".toByteArray()
private val KEY_LEFT = "$ESC[D".toByteArray()

@Composable
fun KeyboardToolbar(
    onSendBytes: (ByteArray) -> Unit,
    modifier: Modifier = Modifier,
) {
    var ctrlActive by remember { mutableStateOf(false) }
    var altActive by remember { mutableStateOf(false) }

    Surface(
        tonalElevation = 2.dp,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Esc
            ToolbarTextButton("Esc") { onSendBytes(KEY_ESC) }

            // Tab
            ToolbarTextButton("Tab") { onSendBytes(KEY_TAB) }

            // Ctrl (sticky toggle)
            ToolbarToggleButton(
                label = "Ctrl",
                active = ctrlActive,
                onClick = { ctrlActive = !ctrlActive },
            )

            // Alt (sticky toggle)
            ToolbarToggleButton(
                label = "Alt",
                active = altActive,
                onClick = { altActive = !altActive },
            )

            // Arrow keys
            ToolbarIconButton(Icons.Filled.KeyboardArrowUp, "Up") { onSendBytes(KEY_UP) }
            ToolbarIconButton(Icons.Filled.KeyboardArrowDown, "Down") { onSendBytes(KEY_DOWN) }
            ToolbarIconButton(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Left") { onSendBytes(KEY_LEFT) }
            ToolbarIconButton(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Right") { onSendBytes(KEY_RIGHT) }

            // Common symbols
            ToolbarTextButton("|") { sendChar('|', ctrlActive, altActive, onSendBytes); ctrlActive = false; altActive = false }
            ToolbarTextButton("~") { sendChar('~', ctrlActive, altActive, onSendBytes); ctrlActive = false; altActive = false }
            ToolbarTextButton("/") { sendChar('/', ctrlActive, altActive, onSendBytes); ctrlActive = false; altActive = false }
            ToolbarTextButton("-") { sendChar('-', ctrlActive, altActive, onSendBytes); ctrlActive = false; altActive = false }
        }
    }
}

private fun sendChar(
    char: Char,
    ctrl: Boolean,
    alt: Boolean,
    onSendBytes: (ByteArray) -> Unit,
) {
    val byte = if (ctrl && char.code in 0x40..0x7F) {
        // Ctrl+char = char AND 0x1F
        byteArrayOf((char.code and 0x1F).toByte())
    } else {
        char.toString().toByteArray()
    }

    if (alt) {
        // Alt prefix = ESC + char
        onSendBytes(byteArrayOf(0x1b) + byte)
    } else {
        onSendBytes(byte)
    }
}

@Composable
private fun ToolbarTextButton(label: String, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier.padding(horizontal = 2.dp),
    ) {
        Text(label, fontSize = 12.sp)
    }
}

@Composable
private fun ToolbarToggleButton(label: String, active: Boolean, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier.padding(horizontal = 2.dp),
        colors = if (active) {
            androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        } else {
            androidx.compose.material3.ButtonDefaults.filledTonalButtonColors()
        },
    ) {
        Text(label, fontSize = 12.sp)
    }
}

@Composable
private fun ToolbarIconButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(36.dp),
    ) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(20.dp))
    }
}
