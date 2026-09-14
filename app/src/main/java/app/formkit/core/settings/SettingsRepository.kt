package app.formkit.core.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

enum class ThemeMode { System, Light, Dark }

data class UserSettings(
    val themeMode: ThemeMode = ThemeMode.System,
    val onboardingComplete: Boolean = false,
)

@Singleton
class SettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {

    val settings: Flow<UserSettings> = dataStore.data
        // A corrupt or unreadable file must never keep the app from starting.
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs ->
            UserSettings(
                themeMode = prefs[THEME_MODE]
                    ?.let { stored -> ThemeMode.entries.firstOrNull { it.name == stored } }
                    ?: ThemeMode.System,
                onboardingComplete = prefs[ONBOARDING_COMPLETE] ?: false,
            )
        }
        .distinctUntilChanged()

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[THEME_MODE] = mode.name }
    }

    suspend fun setOnboardingComplete() {
        dataStore.edit { it[ONBOARDING_COMPLETE] = true }
    }

    internal companion object {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")
    }
}
