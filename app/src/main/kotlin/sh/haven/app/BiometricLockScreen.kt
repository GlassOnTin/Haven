package sh.haven.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import sh.haven.core.security.BiometricAuthenticator

@Composable
fun BiometricLockScreen(
    authenticator: BiometricAuthenticator,
    onUnlocked: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Auto-unlock if biometrics are no longer available (user unenrolled)
    if (activity == null || authenticator.checkAvailability(context) != BiometricAuthenticator.Availability.AVAILABLE) {
        LaunchedEffect(Unit) { onUnlocked() }
        return
    }

    // Trigger counter: increment to re-launch authentication
    var authTrigger by remember { mutableStateOf(0) }

    LaunchedEffect(authTrigger) {
        errorMessage = null
        when (val result = authenticator.authenticate(activity)) {
            is BiometricAuthenticator.AuthResult.Success -> onUnlocked()
            is BiometricAuthenticator.AuthResult.Failure -> errorMessage = result.message
            is BiometricAuthenticator.AuthResult.Cancelled -> errorMessage = "Authentication cancelled"
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Fingerprint,
                contentDescription = "Biometric unlock",
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "Haven is locked",
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Authenticate to continue",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (errorMessage != null) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = errorMessage!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(modifier = Modifier.height(32.dp))
            Button(onClick = { authTrigger++ }) {
                Text("Unlock")
            }
        }
    }
}
