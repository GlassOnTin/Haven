package sh.haven.feature.settings

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
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
    val theme by viewModel.theme.collectAsState()
    val sessionManager by viewModel.sessionManager.collectAsState()
    val reticulumConfigured by viewModel.reticulumConfigured.collectAsState()
    val reticulumRpcKey by viewModel.reticulumRpcKey.collectAsState()
    var showFontSizeDialog by remember { mutableStateOf(false) }
    var showSessionManagerDialog by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }

    val context = LocalContext.current

    val packageInfo = remember {
        context.packageManager.getPackageInfo(context.packageName, 0)
    }

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
            icon = Icons.Filled.Terminal,
            title = "Session persistence",
            subtitle = if (sessionManager == UserPreferencesRepository.SessionManager.NONE) {
                "None"
            } else {
                sessionManager.label
            },
            onClick = { showSessionManagerDialog = true },
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
            subtitle = theme.label,
            onClick = { showThemeDialog = true },
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        // Reticulum section
        SettingsItem(
            icon = Icons.Filled.ContentPaste,
            title = "Sideband RPC key",
            subtitle = if (reticulumConfigured) {
                "Configured: ${reticulumRpcKey.take(16)}..."
            } else {
                "Not configured — paste from Sideband"
            },
            onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = clipboard.primaryClip?.getItemAt(0)?.text?.toString()?.trim()
                if (clip != null) {
                    // Try to parse Sideband config format or raw hex key
                    val rpcKey = parseSidebandRpcKey(clip)
                    if (rpcKey != null) {
                        viewModel.setReticulumRpcKey(rpcKey)
                        Toast.makeText(context, "RPC key saved", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "No valid RPC key in clipboard", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(context, "Clipboard empty", Toast.LENGTH_SHORT).show()
                }
            },
        )
        if (reticulumConfigured) {
            SettingsItem(
                icon = Icons.Filled.Delete,
                title = "Clear RPC key",
                subtitle = "Remove Sideband configuration",
                onClick = { viewModel.clearReticulumConfig() },
            )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        SettingsItem(
            icon = Icons.Filled.Info,
            title = "About Haven",
            subtitle = "v${packageInfo.versionName}",
            onClick = { showAboutDialog = true },
        )

    }

    if (showAboutDialog) {
        AboutDialog(
            versionName = packageInfo.versionName ?: "unknown",
            versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            },
            onDismiss = { showAboutDialog = false },
            onOpenGitHub = {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_URL))
                context.startActivity(intent)
            },
        )
    }

    if (showThemeDialog) {
        ThemeDialog(
            currentTheme = theme,
            onDismiss = { showThemeDialog = false },
            onSelect = { selected ->
                viewModel.setTheme(selected)
                showThemeDialog = false
            },
        )
    }

    if (showSessionManagerDialog) {
        SessionManagerDialog(
            current = sessionManager,
            onDismiss = { showSessionManagerDialog = false },
            onSelect = { selected ->
                viewModel.setSessionManager(selected)
                showSessionManagerDialog = false
            },
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

private const val GITHUB_URL = "https://github.com/GlassOnTin/Haven"

/**
 * Parse an RPC key from clipboard text.
 * Accepts either:
 * - Raw hex string (32+ hex chars)
 * - Sideband config format containing "rpc_key = <hex>"
 */
private fun parseSidebandRpcKey(text: String): String? {
    // Try Sideband config format: "rpc_key = <hex>"
    val configMatch = Regex("""rpc_key\s*=\s*([0-9a-fA-F]{32,})""").find(text)
    if (configMatch != null) return configMatch.groupValues[1].lowercase()

    // Try raw hex (at least 32 chars, all hex)
    val trimmed = text.trim()
    if (trimmed.length >= 32 && trimmed.all { it in "0123456789abcdefABCDEF" }) {
        return trimmed.lowercase()
    }

    return null
}

@Composable
private fun AboutDialog(
    versionName: String,
    versionCode: Long,
    onDismiss: () -> Unit,
    onOpenGitHub: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("About Haven") },
        text = {
            Column {
                Text(
                    text = "Haven",
                    style = MaterialTheme.typography.headlineSmall,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Open source SSH client for Android",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Version $versionName (build $versionCode)",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
        dismissButton = {
            TextButton(onClick = onOpenGitHub) {
                Text("GitHub")
            }
        },
    )
}

@Composable
private fun ThemeDialog(
    currentTheme: UserPreferencesRepository.ThemeMode,
    onDismiss: () -> Unit,
    onSelect: (UserPreferencesRepository.ThemeMode) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Theme") },
        text = {
            Column {
                UserPreferencesRepository.ThemeMode.entries.forEach { mode ->
                    ListItem(
                        headlineContent = { Text(mode.label) },
                        leadingContent = {
                            RadioButton(
                                selected = mode == currentTheme,
                                onClick = null,
                            )
                        },
                        modifier = Modifier.clickable(role = Role.RadioButton) {
                            onSelect(mode)
                        },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun SessionManagerDialog(
    current: UserPreferencesRepository.SessionManager,
    onDismiss: () -> Unit,
    onSelect: (UserPreferencesRepository.SessionManager) -> Unit,
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Session persistence") },
        text = {
            Column {
                UserPreferencesRepository.SessionManager.entries.forEach { manager ->
                    ListItem(
                        headlineContent = {
                            if (manager.url != null) {
                                Text(
                                    text = manager.label,
                                    color = MaterialTheme.colorScheme.primary,
                                    textDecoration = TextDecoration.Underline,
                                    modifier = Modifier.clickable {
                                        context.startActivity(
                                            Intent(Intent.ACTION_VIEW, Uri.parse(manager.url))
                                        )
                                    },
                                )
                            } else {
                                Text(manager.label)
                            }
                        },
                        leadingContent = {
                            RadioButton(
                                selected = manager == current,
                                onClick = null,
                            )
                        },
                        modifier = Modifier.clickable(role = Role.RadioButton) {
                            onSelect(manager)
                        },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
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
