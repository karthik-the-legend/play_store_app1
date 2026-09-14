package app.formkit.core.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import app.cash.turbine.test
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsRepositoryTest {

    @Test
    fun `a fresh install uses the system theme and shows onboarding`() = runTest {
        val settings = SettingsRepository(InMemoryPreferencesDataStore()).settings.first()

        assertEquals(ThemeMode.System, settings.themeMode)
        assertFalse(settings.onboardingComplete)
    }

    @Test
    fun `theme changes are persisted and emitted`() = runTest {
        val repository = SettingsRepository(InMemoryPreferencesDataStore())

        repository.settings.test {
            assertEquals(ThemeMode.System, awaitItem().themeMode)
            repository.setThemeMode(ThemeMode.Dark)
            assertEquals(ThemeMode.Dark, awaitItem().themeMode)
            repository.setThemeMode(ThemeMode.Light)
            assertEquals(ThemeMode.Light, awaitItem().themeMode)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `completing onboarding is persisted`() = runTest {
        val repository = SettingsRepository(InMemoryPreferencesDataStore())

        repository.setOnboardingComplete()

        assertTrue(repository.settings.first().onboardingComplete)
    }

    @Test
    fun `an unrecognised stored theme falls back to system`() = runTest {
        val store = InMemoryPreferencesDataStore()
        store.edit { it[SettingsRepository.THEME_MODE] = "Sepia" }

        assertEquals(ThemeMode.System, SettingsRepository(store).settings.first().themeMode)
    }
}

/**
 * These tests cover the repository's mapping, not DataStore's file handling. Real file-backed
 * DataStores are unreliable in JVM tests on this Windows machine: replacing the file fails
 * whenever the antivirus is still scanning the previous write.
 */
private class InMemoryPreferencesDataStore : DataStore<Preferences> {
    private val state = MutableStateFlow(emptyPreferences())
    private val mutex = Mutex()

    override val data: Flow<Preferences> = state

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        mutex.withLock { transform(state.value).also { state.value = it } }
}
