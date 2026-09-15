package app.formkit.core.monetization

import android.app.Activity
import kotlinx.coroutines.flow.Flow

/** The one product FormKit sells: a lifetime unlock. */
const val PRO_PRODUCT_ID = "formkit_pro_lifetime"

enum class OwnedState {
    NotOwned,

    /** Paid with a method that clears later, like cash at a shop. Not Pro until it clears. */
    Pending,
    Owned,
}

enum class StoreFailure {
    /** No connection, or Google Play couldn't be reached. Keep what's cached. */
    Offline,

    /** Google Play isn't available on this phone, or the product can't be sold here. */
    Unavailable,
    Error,
}

/** What the store says the user owns right now. */
sealed interface StoreQuery {
    data class Success(val state: OwnedState, val purchaseToken: String?, val needsAcknowledgement: Boolean) : StoreQuery
    data class Failed(val reason: StoreFailure) : StoreQuery
}

sealed interface PurchaseLaunch {
    /** The store's purchase screen is showing; the outcome arrives through [ProStore.updates]. */
    data object Started : PurchaseLaunch
    data object AlreadyOwned : PurchaseLaunch
    data class Failed(val reason: StoreFailure) : PurchaseLaunch
}

sealed interface PurchaseUpdate {
    data class Changed(val state: OwnedState, val purchaseToken: String?, val needsAcknowledgement: Boolean) : PurchaseUpdate
    data object Cancelled : PurchaseUpdate
    data class Failed(val reason: StoreFailure) : PurchaseUpdate
}

/**
 * The store that sells Pro. Google Play in real builds; a fake in debug builds so the flows can be
 * tested without a Play Console app (see DECISIONS.md).
 */
interface ProStore {
    /** Purchase results, including ones that finish later (pending purchases). */
    val updates: Flow<PurchaseUpdate>

    suspend fun queryPurchase(): StoreQuery

    /** The localised price, like "₹299.00", or null if the store can't say right now. */
    suspend fun price(): String?

    suspend fun launchPurchase(activity: Activity): PurchaseLaunch

    /** Play refunds purchases that aren't acknowledged within three days. */
    suspend fun acknowledge(purchaseToken: String): Boolean
}

/** The current time, replaceable in tests. */
fun interface WallClock {
    fun nowMillis(): Long
}
