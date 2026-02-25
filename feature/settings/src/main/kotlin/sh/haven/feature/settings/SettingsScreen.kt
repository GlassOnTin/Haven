package sh.haven.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import sh.haven.core.data.preferences.UserPreferencesRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val biometricEnabled by viewModel.biometricEnabled.collectAsState()
    val fontSize by viewModel.terminalFontSize.collectAsState()
    var showFontSizeDialog by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Settings") })

        if (viewModel.biometricAvailable) {
            SettingsToggleItem(
                icon = Icons.Filled.Fingerprint,
                title = "Biometric unlock",
                subtitle = "Require biometrics to open Haven",
                checked = biometricEnabled,
                onCheckedChange = viewModel::setBiometricEnabled,
            )
        }
        SettingsItem(
            icon = Icons.Filled.Lock,
            title = "sudo auto-fill",
            subtitle = "Auto-fill sudo password via biometrics",
        )
        SettingsItem(
            icon = Icons.Filled.TextFields,
            title = "Terminal font size",
            subtitle = "${fontSize}sp",
            onClick = { showFontSizeDialog = true },
        )
        SettingsItem(
            icon = Icons.Filled.ColorLens,
            title = "Theme",
            subtitle = "System default",
        )
        SettingsItem(
            icon = Icons.Filled.Info,
            title = "About Haven",
            subtitle = "v0.1.0 — Open source SSH client",
        )
    }

    if (showFontSizeDialog) {
        FontSizeDialog(
            currentSize = fontSize,
            onDismiss = { showFontSizeDialog = false },
            onConfirm = { newSize ->
                viewModel.setTerminalFontSize(newSize)
                showFontSizeDialog = false
            },
        )
    }
}

@Composable
private fun FontSizeDialog(
    currentSize: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var sliderValue by remember { mutableFloatStateOf(currentSize.toFloat()) }
    val displaySize = sliderValue.toInt()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Terminal font size") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "Sample text",
                    fontFamily = FontFamily.Monospace,
                    fontSize = displaySize.sp,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
                Text(
                    text = "${displaySize}sp",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Slider(
                    value = sliderValue,
                    onValueChange = { sliderValue = it },
                    valueRange = UserPreferencesRepository.MIN_FONT_SIZE.toFloat()..
                        UserPreferencesRepository.MAX_FONT_SIZE.toFloat(),
                    steps = UserPreferencesRepository.MAX_FONT_SIZE -
                        UserPreferencesRepository.MIN_FONT_SIZE - 1,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(displaySize) }) {
                Text("OK")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun SettingsToggleItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        },
        trailingContent = {
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        },
        modifier = Modifier
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 8.dp),
    )
}

@Composable
private fun SettingsItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit = {},
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        },
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
    )
}
