package app.formkit.core.monetization

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClient.ProductType
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryProductDetailsResult
import com.android.billingclient.api.QueryPurchasesParams
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Google Play Billing for the one lifetime product. There's no server, so purchases are checked
 * on the phone with Google Play itself (see DECISIONS.md).
 */
@Singleton
class PlayProStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : ProStore {

    private val _updates = MutableSharedFlow<PurchaseUpdate>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val updates: Flow<PurchaseUpdate> = _updates.asSharedFlow()

    private val purchasesListener = PurchasesUpdatedListener { result, purchases ->
        when (result.responseCode) {
            BillingResponseCode.OK -> purchases?.firstOrNull { PRO_PRODUCT_ID in it.products }?.let { _updates.tryEmit(it.toUpdate()) }
            BillingResponseCode.USER_CANCELED -> _updates.tryEmit(PurchaseUpdate.Cancelled)
            // Owned already, perhaps bought on another phone. The token isn't known here; the next
            // check fills it in and acknowledges if needed.
            BillingResponseCode.ITEM_ALREADY_OWNED -> _updates.tryEmit(PurchaseUpdate.Changed(OwnedState.Owned, null, needsAcknowledgement = false))
            else -> _updates.tryEmit(PurchaseUpdate.Failed(failureFor(result.responseCode)))
        }
    }

    private val client: BillingClient by lazy {
        BillingClient.newBuilder(context)
            .setListener(purchasesListener)
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .enableAutoServiceReconnection()
            .build()
    }

    private val connectLock = Mutex()
    private var cachedDetails: ProductDetails? = null

    override suspend fun queryPurchase(): StoreQuery {
        connect()?.let { return StoreQuery.Failed(it) }
        val params = QueryPurchasesParams.newBuilder().setProductType(ProductType.INAPP).build()
        val (code, purchases) = suspendCancellableCoroutine<Pair<Int, List<Purchase>>> { continuation ->
            client.queryPurchasesAsync(params) { result, list -> if (continuation.isActive) continuation.resume(result.responseCode to list) }
        }
        if (code != BillingResponseCode.OK) return StoreQuery.Failed(failureFor(code))
        val pro = purchases.firstOrNull { PRO_PRODUCT_ID in it.products }
            ?: return StoreQuery.Success(OwnedState.NotOwned, null, needsAcknowledgement = false)
        return when (val update = pro.toUpdate()) {
            is PurchaseUpdate.Changed -> StoreQuery.Success(update.state, update.purchaseToken, update.needsAcknowledgement)
            else -> StoreQuery.Success(OwnedState.NotOwned, null, needsAcknowledgement = false)
        }
    }

    override suspend fun price(): String? = productDetails().first?.let { offerFor(it)?.formattedPrice }

    override suspend fun launchPurchase(activity: Activity): PurchaseLaunch {
        val (details, failure) = productDetails()
        if (details == null) return PurchaseLaunch.Failed(failure ?: StoreFailure.Unavailable)
        val productParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details)
            .apply { offerFor(details)?.offerToken?.let { setOfferToken(it) } }
            .build()
        val flowParams = BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(productParams)).build()
        val result = withContext(Dispatchers.Main) { client.launchBillingFlow(activity, flowParams) }
        return when (result.responseCode) {
            // A cancel arrives through the purchases listener too, so there's nothing more to say here.
            BillingResponseCode.OK, BillingResponseCode.USER_CANCELED -> PurchaseLaunch.Started
            BillingResponseCode.ITEM_ALREADY_OWNED -> PurchaseLaunch.AlreadyOwned
            else -> PurchaseLaunch.Failed(failureFor(result.responseCode))
        }
    }

    override suspend fun acknowledge(purchaseToken: String): Boolean {
        if (connect() != null) return false
        val params = AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchaseToken).build()
        val code = suspendCancellableCoroutine<Int> { continuation ->
            client.acknowledgePurchase(params) { result -> if (continuation.isActive) continuation.resume(result.responseCode) }
        }
        return code == BillingResponseCode.OK
    }

    /** Connects if needed. Returns null once connected, or why it couldn't. */
    private suspend fun connect(): StoreFailure? = connectLock.withLock {
        if (client.isReady) return@withLock null
        val code = suspendCancellableCoroutine<Int> { continuation ->
            client.startConnection(
                object : BillingClientStateListener {
                    override fun onBillingSetupFinished(result: BillingResult) {
                        if (continuation.isActive) continuation.resume(result.responseCode)
                    }

                    override fun onBillingServiceDisconnected() {
                        if (continuation.isActive) continuation.resume(BillingResponseCode.SERVICE_DISCONNECTED)
                    }
                },
            )
        }
        if (code == BillingResponseCode.OK) null else failureFor(code)
    }

    private suspend fun productDetails(): Pair<ProductDetails?, StoreFailure?> {
        cachedDetails?.let { return it to null }
        connect()?.let { return null to it }
        val product = QueryProductDetailsParams.Product.newBuilder()
            .setProductId(PRO_PRODUCT_ID)
            .setProductType(ProductType.INAPP)
            .build()
        val params = QueryProductDetailsParams.newBuilder().setProductList(listOf(product)).build()
        val (code, result) = suspendCancellableCoroutine<Pair<Int, QueryProductDetailsResult>> { continuation ->
            client.queryProductDetailsAsync(params) { billingResult, details ->
                if (continuation.isActive) continuation.resume(billingResult.responseCode to details)
            }
        }
        if (code != BillingResponseCode.OK) return null to failureFor(code)
        val details = result.productDetailsList.firstOrNull() ?: return null to StoreFailure.Unavailable
        cachedDetails = details
        return details to null
    }

    private fun offerFor(details: ProductDetails): ProductDetails.OneTimePurchaseOfferDetails? =
        details.oneTimePurchaseOfferDetailsList?.firstOrNull() ?: details.oneTimePurchaseOfferDetails

    private fun Purchase.toUpdate(): PurchaseUpdate = when (purchaseState) {
        Purchase.PurchaseState.PURCHASED -> PurchaseUpdate.Changed(OwnedState.Owned, purchaseToken, needsAcknowledgement = !isAcknowledged)
        Purchase.PurchaseState.PENDING -> PurchaseUpdate.Changed(OwnedState.Pending, purchaseToken, needsAcknowledgement = false)
        else -> PurchaseUpdate.Changed(OwnedState.NotOwned, null, needsAcknowledgement = false)
    }

    private fun failureFor(code: Int): StoreFailure = when (code) {
        BillingResponseCode.SERVICE_TIMEOUT,
        BillingResponseCode.SERVICE_DISCONNECTED,
        BillingResponseCode.SERVICE_UNAVAILABLE,
        BillingResponseCode.NETWORK_ERROR,
        -> StoreFailure.Offline
        BillingResponseCode.BILLING_UNAVAILABLE,
        BillingResponseCode.ITEM_UNAVAILABLE,
        BillingResponseCode.FEATURE_NOT_SUPPORTED,
        -> StoreFailure.Unavailable
        else -> StoreFailure.Error
    }
}
