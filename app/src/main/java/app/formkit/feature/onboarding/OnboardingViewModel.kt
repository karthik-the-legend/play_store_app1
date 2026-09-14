package app.formkit.feature.onboarding

import androidx.lifecycle.ViewModel
import app.formkit.core.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    suspend fun completeOnboarding() {
        settingsRepository.setOnboardingComplete()
    }
}
