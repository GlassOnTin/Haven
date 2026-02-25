package sh.haven.core.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UserPreferencesRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    private val biometricEnabledKey = booleanPreferencesKey("biometric_enabled")

    val biometricEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[biometricEnabledKey] ?: false
    }

    suspend fun setBiometricEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[biometricEnabledKey] = enabled
        }
    }
}
