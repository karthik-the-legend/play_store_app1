package app.formkit.core.monetization

import kotlin.math.abs

/** Everything that decides whether an interstitial may show after an operation finishes. */
data class InterstitialContext(
    val isPro: Boolean,
    val canRequestAds: Boolean,
    /** Operations finished since install, including the one that just finished. */
    val completedOperations: Int,
    val lastShownAtMillis: Long?,
    val nowMillis: Long,
)

/**
 * §7's rules: never for Pro, never without consent to request ads, never on a new install's first
 * two operations, and at most one every 90 seconds.
 */
object InterstitialPolicy {
    const val FREE_OPERATIONS = 2
    const val MIN_GAP_MILLIS = 90_000L

    fun mayShow(context: InterstitialContext): Boolean {
        if (context.isPro || !context.canRequestAds) return false
        if (context.completedOperations <= FREE_OPERATIONS) return false
        val last = context.lastShownAtMillis ?: return true
        // abs(): if the phone's clock was moved back, still wait out the gap instead of showing at once.
        return abs(context.nowMillis - last) >= MIN_GAP_MILLIS
    }
}
