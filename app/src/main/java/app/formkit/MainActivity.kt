package app.formkit

import android.app.UiModeManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.getSystemService
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.formkit.core.monetization.AdsManager
import app.formkit.core.monetization.ConsentManager
import app.formkit.core.settings.ThemeMode
import app.formkit.core.ui.components.ProEventMessages
import app.formkit.core.ui.theme.FormKitTheme
import app.formkit.navigation.FormKitNavHost
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    @Inject lateinit var consentManager: ConsentManager
    @Inject lateinit var adsManager: AdsManager

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)

        var uiState: MainUiState by mutableStateOf(MainUiState.Loading)
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { uiState = it }
            }
        }
        // Settings decide the theme and whether onboarding shows, so hold the splash until
        // they're read instead of flashing the wrong screen.
        splashScreen.setKeepOnScreenCondition { uiState is MainUiState.Loading }

        enableEdgeToEdge()

        setContent {
            val state = uiState as? MainUiState.Ready ?: return@setContent
            val themeMode = state.settings.themeMode
            val darkTheme = when (themeMode) {
                ThemeMode.System -> isSystemInDarkTheme()
                ThemeMode.Light -> false
                ThemeMode.Dark -> true
            }

            // Status and navigation bar icons follow the app's theme, not the system's.
            DisposableEffect(darkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme },
                    navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme },
                )
                onDispose {}
            }
            LaunchedEffect(themeMode) { applyNightModeToSystemUi(themeMode) }

            // Fixed at first composition: finishing onboarding navigates away itself, and a
            // changing start destination would rebuild the graph.
            val startWithOnboarding = rememberSaveable { !state.settings.onboardingComplete }

            FormKitTheme(darkTheme = darkTheme) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    FormKitNavHost(startWithOnboarding = startWithOnboarding)
                }
                ProEventMessages()
            }
        }

        // Ad consent waits until onboarding is done, so Google's form never covers the welcome
        // screens. Without consent there are simply no ads; every tool keeps working.
        lifecycleScope.launch {
            viewModel.uiState.first { it is MainUiState.Ready && it.settings.onboardingComplete }
            if (consentManager.gather(this@MainActivity)) adsManager.startIfAllowed()
        }
    }

    /** Lets Android 12+ draw the launch splash in the user's chosen theme on the next start. */
    private fun applyNightModeToSystemUi(themeMode: ThemeMode) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val mode = when (themeMode) {
            ThemeMode.System -> UiModeManager.MODE_NIGHT_AUTO
            ThemeMode.Light -> UiModeManager.MODE_NIGHT_NO
            ThemeMode.Dark -> UiModeManager.MODE_NIGHT_YES
        }
        getSystemService<UiModeManager>()?.setApplicationNightMode(mode)
    }
}
