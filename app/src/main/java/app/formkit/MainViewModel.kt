package app.formkit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.formkit.core.settings.SettingsRepository
import app.formkit.core.settings.UserSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    settingsRepository: SettingsRepository,
) : ViewModel() {

    val uiState: StateFlow<MainUiState> = settingsRepository.settings
        .map<UserSettings, MainUiState> { MainUiState.Ready(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MainUiState.Loading)
}

sealed interface MainUiState {
    data object Loading : MainUiState
    data class Ready(val settings: UserSettings) : MainUiState
}
