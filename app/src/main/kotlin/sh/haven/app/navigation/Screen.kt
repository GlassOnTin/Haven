package sh.haven.app.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.ui.graphics.vector.ImageVector

enum class Screen(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    Connections("connections", "Connections", Icons.Filled.Cable),
    Terminal("terminal", "Terminal", Icons.Filled.Terminal),
    Sftp("sftp", "Files", Icons.Filled.Folder),
    Keys("keys", "Keys", Icons.Filled.VpnKey),
    Settings("settings", "Settings", Icons.Filled.Settings),
}
