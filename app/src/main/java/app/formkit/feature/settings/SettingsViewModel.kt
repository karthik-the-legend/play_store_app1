package app.formkit.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.formkit.core.di.IoDispatcher
import app.formkit.core.settings.SettingsRepository
import app.formkit.core.settings.ThemeMode
import app.formkit.core.storage.CacheJanitor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class SettingsUiState(
    val themeMode: ThemeMode = ThemeMode.System,
    /** Null while the size is being measured. */
    val tempFilesBytes: Long? = null,
    val isClearing: Boolean = false,
)

sealed interface SettingsEvent {
    data class TempFilesCleared(val freedBytes: Long) : SettingsEvent
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val cacheJanitor: CacheJanitor,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val tempFilesBytes = MutableStateFlow<Long?>(null)
    private val isClearing = MutableStateFlow(false)

    private val _events = Channel<SettingsEvent>(Channel.BUFFERED)
    val events: Flow<SettingsEvent> = _events.receiveAsFlow()

    val uiState: StateFlow<SettingsUiState> =
        combine(settingsRepository.settings, tempFilesBytes, isClearing) { settings, bytes, clearing ->
            SettingsUiState(themeMode = settings.themeMode, tempFilesBytes = bytes, isClearing = clearing)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    init {
        viewModelScope.launch {
            tempFilesBytes.value = withContext(ioDispatcher) { cacheJanitor.sizeBytes() }
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { settingsRepository.setThemeMode(mode) }
    }

    fun clearTempFiles() {
        if (isClearing.value) return
        isClearing.value = true
        viewModelScope.launch {
            val freed = withContext(ioDispatcher) { cacheJanitor.clear() }
            tempFilesBytes.value = withContext(ioDispatcher) { cacheJanitor.sizeBytes() }
            isClearing.value = false
            _events.send(SettingsEvent.TempFilesCleared(freed))
        }
    }
}
