package sh.haven.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val biometricEnabled by viewModel.biometricEnabled.collectAsState()

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
            subtitle = "14sp",
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
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        },
        modifier = Modifier
            .clickable { /* TODO */ }
            .padding(horizontal = 8.dp),
    )
}
