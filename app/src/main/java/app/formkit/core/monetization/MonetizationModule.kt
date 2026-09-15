package app.formkit.core.monetization

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import app.formkit.BuildConfig
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Perks that last until the app process ends. */
@Singleton
class SessionPerks @Inject constructor() {
    private val _watermarkFreeSheets = MutableStateFlow(false)

    /** Earned by watching a rewarded ad. */
    val watermarkFreeSheets: StateFlow<Boolean> = _watermarkFreeSheets.asStateFlow()

    private val _proOfferDismissed = MutableStateFlow(false)
    val proOfferDismissed: StateFlow<Boolean> = _proOfferDismissed.asStateFlow()

    fun unlockWatermarkFreeSheets() {
        _watermarkFreeSheets.value = true
    }

    fun dismissProOffer() {
        _proOfferDismissed.value = true
    }
}

@Module
@InstallIn(SingletonComponent::class)
object MonetizationModule {

    @Provides
    @Singleton
    fun provideWallClock(): WallClock = WallClock { System.currentTimeMillis() }

    /** The fake store in debug builds unless built with -Pformkit.fakeStore=false (see DECISIONS.md). */
    @Provides
    @Singleton
    fun provideProStore(settings: DataStore<Preferences>, play: Lazy<PlayProStore>): ProStore =
        if (BuildConfig.USE_FAKE_STORE) FakeProStore(settings) else play.get()
}

/** How composables reach the monetization singletons without every screen's ViewModel passing them. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface MonetizationEntryPoint {
    fun proManager(): ProManager
    fun adsManager(): AdsManager
    fun consentManager(): ConsentManager
    fun sessionPerks(): SessionPerks
    fun proStore(): ProStore
}

fun Context.monetization(): MonetizationEntryPoint =
    EntryPointAccessors.fromApplication(applicationContext, MonetizationEntryPoint::class.java)

@Composable
fun rememberMonetization(): MonetizationEntryPoint {
    val context = LocalContext.current.applicationContext
    return remember(context) { context.monetization() }
}
