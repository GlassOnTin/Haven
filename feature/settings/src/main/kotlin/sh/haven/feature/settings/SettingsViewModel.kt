package sh.haven.feature.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import sh.haven.core.data.preferences.UserPreferencesRepository
import sh.haven.core.reticulum.ReticulumBridge
import sh.haven.core.security.BiometricAuthenticator
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val preferencesRepository: UserPreferencesRepository,
    private val authenticator: BiometricAuthenticator,
    private val reticulumBridge: ReticulumBridge,
) : ViewModel() {

    val biometricAvailable: Boolean =
        authenticator.checkAvailability(context) == BiometricAuthenticator.Availability.AVAILABLE

    val biometricEnabled: StateFlow<Boolean> = preferencesRepository.biometricEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val terminalFontSize: StateFlow<Int> = preferencesRepository.terminalFontSize
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            UserPreferencesRepository.DEFAULT_FONT_SIZE,
        )

    val theme: StateFlow<UserPreferencesRepository.ThemeMode> = preferencesRepository.theme
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            UserPreferencesRepository.ThemeMode.SYSTEM,
        )

    val sessionManager: StateFlow<UserPreferencesRepository.SessionManager> =
        preferencesRepository.sessionManager
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                UserPreferencesRepository.SessionManager.NONE,
            )

    val reticulumConfigured: StateFlow<Boolean> = preferencesRepository.reticulumConfigured
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun setBiometricEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setBiometricEnabled(enabled)
        }
    }

    fun setTerminalFontSize(sizeSp: Int) {
        viewModelScope.launch {
            preferencesRepository.setTerminalFontSize(sizeSp)
        }
    }

    fun setTheme(mode: UserPreferencesRepository.ThemeMode) {
        viewModelScope.launch {
            preferencesRepository.setTheme(mode)
        }
    }

    fun setSessionManager(manager: UserPreferencesRepository.SessionManager) {
        viewModelScope.launch {
            preferencesRepository.setSessionManager(manager)
        }
    }

    /**
     * Parse a Sideband RPC config string and save to preferences.
     * Expected format contains lines like:
     *   shared_instance_type = tcp
     *   rpc_key = <hex>
     *   shared_instance_host = 127.0.0.1
     *   shared_instance_port = 37428
     *
     * Returns true if parsing succeeded.
     */
    fun parseAndSaveReticulumConfig(configText: String): Boolean {
        var rpcKey: String? = null
        var host = UserPreferencesRepository.DEFAULT_RETICULUM_HOST
        var port = UserPreferencesRepository.DEFAULT_RETICULUM_PORT

        for (line in configText.lines()) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("rpc_key") -> {
                    rpcKey = trimmed.substringAfter("=").trim()
                }
                trimmed.startsWith("shared_instance_host") -> {
                    host = trimmed.substringAfter("=").trim()
                }
                trimmed.startsWith("shared_instance_port") -> {
                    port = trimmed.substringAfter("=").trim().toIntOrNull() ?: port
                }
            }
        }

        if (rpcKey.isNullOrBlank()) return false

        viewModelScope.launch {
            preferencesRepository.setReticulumConfig(rpcKey, host, port)
        }
        return true
    }
}
