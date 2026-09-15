package app.formkit.core.monetization

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.annotation.MainThread
import app.formkit.BuildConfig
import app.formkit.core.di.ApplicationScope
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.RequestConfiguration
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdOptions
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

enum class RewardOutcome { Earned, Dismissed, Unavailable }

/** Debug builds only: why an ad did or didn't show, under the "FormKitAds" tag. */
internal fun adsLog(message: String) {
    if (BuildConfig.DEBUG) Log.d("FormKitAds", message)
}

/**
 * AdMob, following §7: nothing before consent, nothing for Pro, never blocking a user action.
 *
 * - The SDK starts on a background thread once consent allows it, then preloads one interstitial
 *   and one rewarded ad.
 * - An interstitial shows as a result first appears, only if [InterstitialPolicy] allows and one is
 *   already loaded; it never makes the user wait.
 * - A rewarded ad is shown when the user asks for one, waiting a moment for it to load.
 */
@Singleton
class AdsManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val consent: ConsentManager,
    private val proManager: ProManager,
    private val cache: EntitlementCache,
    private val clock: WallClock,
    @ApplicationScope private val appScope: CoroutineScope,
) {

    private val started = AtomicBoolean(false)
    private val sdkReady = MutableStateFlow(false)

    private var interstitial: InterstitialAd? = null
    private var loadingInterstitial = false
    private val rewarded = MutableStateFlow<RewardedAd?>(null)
    private var loadingRewarded = false

    /** Whether ads can show at all right now. */
    val adsAllowed: Flow<Boolean> = combine(consent.canRequestAds, proManager.state) { canRequest, pro ->
        BuildConfig.ADS_ENABLED && canRequest && !pro.isPro
    }

    /** Whether a rewarded ad could be offered, for showing the "Watch an ad" button. */
    val rewardedReady: StateFlow<RewardedAd?> = rewarded.asStateFlow()

    /** Starts the SDK once and preloads ads. Does nothing without consent, for Pro, or with ads off. */
    fun startIfAllowed() {
        if (!allowedNow()) {
            adsLog(
                "not starting ads: enabled=${BuildConfig.ADS_ENABLED} canRequestAds=${consent.canRequestAds.value} " +
                    "pro=${proManager.state.value.isPro}",
            )
            return
        }
        if (!started.getAndSet(true)) {
            adsLog("starting the Mobile Ads SDK")
            appScope.launch(Dispatchers.IO) {
                MobileAds.setRequestConfiguration(
                    RequestConfiguration.Builder()
                        // Students of all ages use FormKit; keep ads suitable for teens.
                        .setMaxAdContentRating(RequestConfiguration.MAX_AD_CONTENT_RATING_T)
                        .build(),
                )
                MobileAds.initialize(context) { status ->
                    adsLog("Mobile Ads SDK ready: ${status.adapterStatusMap.size} adapters")
                }
                sdkReady.value = true
                withContext(Dispatchers.Main) { preload() }
            }
        } else if (sdkReady.value) {
            appScope.launch(Dispatchers.Main) { preload() }
        }
    }

    /**
     * Call once when a finished operation's result first shows. Counts the operation and, if the
     * rules allow and an ad is already loaded, shows an interstitial.
     */
    suspend fun onOperationFinished(activity: Activity) {
        val operations = cache.recordOperation()
        val context = InterstitialContext(
            isPro = proManager.state.value.isPro,
            canRequestAds = BuildConfig.ADS_ENABLED && consent.canRequestAds.value,
            completedOperations = operations,
            lastShownAtMillis = cache.lastInterstitialAtMillis(),
            nowMillis = clock.nowMillis(),
        )
        val allowed = InterstitialPolicy.mayShow(context)
        adsLog("operation $operations finished: interstitial allowed=$allowed loaded=${interstitial != null} context=$context")
        if (!allowed) return
        val ad = interstitial
        if (ad == null) {
            startIfAllowed()
            return
        }
        interstitial = null
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() = preload()
            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                adsLog("interstitial failed to show: ${error.code} ${error.message}")
                preload()
            }
        }
        cache.recordInterstitialShown(clock.nowMillis())
        withContext(Dispatchers.Main) { ad.show(activity) }
    }

    /** Shows a rewarded ad the user asked for. Waits up to [REWARDED_WAIT_MILLIS] for one to load. */
    suspend fun showRewarded(activity: Activity): RewardOutcome = withContext(Dispatchers.Main) {
        if (!BuildConfig.ADS_ENABLED || !consent.canRequestAds.value) return@withContext RewardOutcome.Unavailable
        startIfAllowed()
        val ad = rewarded.value ?: withTimeoutOrNull(REWARDED_WAIT_MILLIS) {
            while (rewarded.value == null) delay(POLL_MILLIS)
            rewarded.value
        } ?: return@withContext RewardOutcome.Unavailable
        rewarded.value = null
        suspendCancellableCoroutine { continuation ->
            var earned = false
            ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdDismissedFullScreenContent() {
                    preload()
                    if (continuation.isActive) continuation.resume(if (earned) RewardOutcome.Earned else RewardOutcome.Dismissed)
                }

                override fun onAdFailedToShowFullScreenContent(error: AdError) {
                    preload()
                    if (continuation.isActive) continuation.resume(RewardOutcome.Unavailable)
                }
            }
            ad.show(activity) { earned = true }
        }
    }

    /** One native ad for Recent files, or null. The caller destroys it. */
    suspend fun loadNativeAd(): NativeAd? = withContext(Dispatchers.Main) {
        if (!allowedNow()) return@withContext null
        startIfAllowed()
        withTimeoutOrNull(NATIVE_WAIT_MILLIS) {
            suspendCancellableCoroutine { continuation ->
                AdLoader.Builder(context, BuildConfig.AD_UNIT_NATIVE)
                    .forNativeAd { ad ->
                        adsLog("native ad loaded")
                        if (continuation.isActive) continuation.resume(ad) else ad.destroy()
                    }
                    .withAdListener(
                        object : AdListener() {
                            override fun onAdFailedToLoad(error: LoadAdError) {
                                adsLog("native ad failed to load: ${error.code} ${error.message}")
                                if (continuation.isActive) continuation.resume(null)
                            }
                        },
                    )
                    .withNativeAdOptions(
                        NativeAdOptions.Builder().setAdChoicesPlacement(NativeAdOptions.ADCHOICES_TOP_RIGHT).build(),
                    )
                    .build()
                    .loadAd(AdRequest.Builder().build())
            }
        }
    }

    private fun allowedNow(): Boolean =
        BuildConfig.ADS_ENABLED && consent.canRequestAds.value && !proManager.state.value.isPro

    @MainThread
    private fun preload() {
        if (!allowedNow()) return
        if (interstitial == null && !loadingInterstitial) {
            loadingInterstitial = true
            InterstitialAd.load(
                context,
                BuildConfig.AD_UNIT_INTERSTITIAL,
                AdRequest.Builder().build(),
                object : InterstitialAdLoadCallback() {
                    override fun onAdLoaded(ad: InterstitialAd) {
                        adsLog("interstitial loaded")
                        interstitial = ad
                        loadingInterstitial = false
                    }

                    override fun onAdFailedToLoad(error: LoadAdError) {
                        adsLog("interstitial failed to load: ${error.code} ${error.message}")
                        loadingInterstitial = false
                    }
                },
            )
        }
        if (rewarded.value == null && !loadingRewarded) {
            loadingRewarded = true
            RewardedAd.load(
                context,
                BuildConfig.AD_UNIT_REWARDED,
                AdRequest.Builder().build(),
                object : RewardedAdLoadCallback() {
                    override fun onAdLoaded(ad: RewardedAd) {
                        adsLog("rewarded ad loaded")
                        rewarded.value = ad
                        loadingRewarded = false
                    }

                    override fun onAdFailedToLoad(error: LoadAdError) {
                        adsLog("rewarded ad failed to load: ${error.code} ${error.message}")
                        loadingRewarded = false
                    }
                },
            )
        }
    }

    private companion object {
        const val REWARDED_WAIT_MILLIS = 3_000L
        const val NATIVE_WAIT_MILLIS = 10_000L
        const val POLL_MILLIS = 100L
    }
}
