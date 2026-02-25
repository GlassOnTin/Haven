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
import sh.haven.core.security.BiometricAuthenticator
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val preferencesRepository: UserPreferencesRepository,
    private val authenticator: BiometricAuthenticator,
) : ViewModel() {

    val biometricAvailable: Boolean =
        authenticator.checkAvailability(context) == BiometricAuthenticator.Availability.AVAILABLE

    val biometricEnabled: StateFlow<Boolean> = preferencesRepository.biometricEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun setBiometricEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setBiometricEnabled(enabled)
        }
    }
}
