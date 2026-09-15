package app.formkit.core.monetization

import android.app.Activity
import android.content.Context
import app.formkit.BuildConfig
import com.google.android.ump.ConsentDebugSettings
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Google's User Messaging Platform: asks for ad consent where the law requires it (the EEA, the
 * UK and some US states) before any ad is requested. Elsewhere it returns straight away.
 */
@Singleton
class ConsentManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val consentInformation: ConsentInformation by lazy { UserMessagingPlatform.getConsentInformation(context) }
    private val requestedThisLaunch = AtomicBoolean(false)

    private val _canRequestAds = MutableStateFlow(false)
    val canRequestAds: StateFlow<Boolean> = _canRequestAds.asStateFlow()

    private val _privacyOptionsRequired = MutableStateFlow(false)

    /** True where users must be able to change their choice later; Settings then shows the entry. */
    val privacyOptionsRequired: StateFlow<Boolean> = _privacyOptionsRequired.asStateFlow()

    /**
     * Updates consent once per launch, showing Google's form only if it's needed. Returns whether
     * ads may be requested. A failure never blocks the app; it just means no ads this time.
     */
    suspend fun gather(activity: Activity): Boolean {
        // A choice made on an earlier launch lets ads start loading while the update runs.
        publish()
        if (requestedThisLaunch.getAndSet(true)) return consentInformation.canRequestAds()

        val parameters = ConsentRequestParameters.Builder()
            .apply {
                if (BuildConfig.CONSENT_DEBUG_EEA) {
                    setConsentDebugSettings(
                        ConsentDebugSettings.Builder(context)
                            .setDebugGeography(ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_EEA)
                            .setForceTesting(true)
                            .build(),
                    )
                }
            }
            .build()

        val updated = suspendCancellableCoroutine<Boolean> { continuation ->
            consentInformation.requestConsentInfoUpdate(
                activity,
                parameters,
                { if (continuation.isActive) continuation.resume(true) },
                { error ->
                    adsLog("consent update failed: ${error.errorCode} ${error.message}")
                    if (continuation.isActive) continuation.resume(false)
                },
            )
        }
        if (updated) {
            suspendCancellableCoroutine<Unit> { continuation ->
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { _ ->
                    if (continuation.isActive) continuation.resume(Unit)
                }
            }
        }
        publish()
        adsLog("consent updated=$updated status=${consentInformation.consentStatus} canRequestAds=${consentInformation.canRequestAds()}")
        return consentInformation.canRequestAds()
    }

    /** Google's form for changing the ad consent choice, from Settings. */
    fun showPrivacyOptions(activity: Activity) {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { _ -> publish() }
    }

    private fun publish() {
        _canRequestAds.value = consentInformation.canRequestAds()
        _privacyOptionsRequired.value =
            consentInformation.privacyOptionsRequirementStatus == ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
    }
}
