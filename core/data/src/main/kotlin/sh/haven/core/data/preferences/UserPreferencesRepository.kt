package sh.haven.core.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UserPreferencesRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    private val biometricEnabledKey = booleanPreferencesKey("biometric_enabled")
    private val terminalFontSizeKey = intPreferencesKey("terminal_font_size")

    val biometricEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[biometricEnabledKey] ?: false
    }

    val terminalFontSize: Flow<Int> = dataStore.data.map { prefs ->
        prefs[terminalFontSizeKey] ?: DEFAULT_FONT_SIZE
    }

    suspend fun setBiometricEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[biometricEnabledKey] = enabled
        }
    }

    suspend fun setTerminalFontSize(sizeSp: Int) {
        dataStore.edit { prefs ->
            prefs[terminalFontSizeKey] = sizeSp.coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)
        }
    }

    companion object {
        const val DEFAULT_FONT_SIZE = 14
        const val MIN_FONT_SIZE = 8
        const val MAX_FONT_SIZE = 32
    }
}
