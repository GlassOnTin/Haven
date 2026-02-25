package sh.haven.core.security

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
class BiometricAuthenticator @Inject constructor() {

    enum class Availability { AVAILABLE, NO_HARDWARE, NOT_ENROLLED }

    sealed class AuthResult {
        data object Success : AuthResult()
        data class Failure(val message: String) : AuthResult()
        data object Cancelled : AuthResult()
    }

    fun checkAvailability(context: Context): Availability {
        val biometricManager = BiometricManager.from(context)
        return when (biometricManager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.BIOMETRIC_WEAK,
        )) {
            BiometricManager.BIOMETRIC_SUCCESS -> Availability.AVAILABLE
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> Availability.NOT_ENROLLED
            else -> Availability.NO_HARDWARE
        }
    }

    suspend fun authenticate(
        activity: FragmentActivity,
        title: String = "Unlock Haven",
        subtitle: String = "Authenticate to continue",
    ): AuthResult = suspendCancellableCoroutine { cont ->
        val executor = ContextCompat.getMainExecutor(activity)

        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                if (cont.isActive) cont.resume(AuthResult.Success)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (!cont.isActive) return
                val isCancellation = errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                    errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                    errorCode == BiometricPrompt.ERROR_CANCELED
                if (isCancellation) {
                    cont.resume(AuthResult.Cancelled)
                } else {
                    cont.resume(AuthResult.Failure(errString.toString()))
                }
            }

            // onAuthenticationFailed: no-op — prompt handles retry internally
        }

        val prompt = BiometricPrompt(activity, executor, callback)
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setNegativeButtonText("Cancel")
            .build()

        prompt.authenticate(promptInfo)

        cont.invokeOnCancellation { prompt.cancelAuthentication() }
    }
}
