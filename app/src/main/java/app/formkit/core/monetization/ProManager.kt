package app.formkit.core.monetization

import android.app.Activity
import app.formkit.core.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

data class ProState(
    val isPro: Boolean = false,
    /** A purchase is waiting to clear. */
    val isPending: Boolean = false,
    val price: String? = null,
)

sealed interface ProEvent {
    data object Purchased : ProEvent
    data object PurchasePending : ProEvent
    data object Restored : ProEvent
    data object NothingToRestore : ProEvent
    data class Failed(val reason: StoreFailure) : ProEvent
}

/**
 * The single source of truth for Pro.
 *
 * - The cached answer is used at once, so Pro works offline and on a slow start.
 * - The store is asked again on start-up. Pro is revoked only when the store answers successfully
 *   that nothing is owned (a refund); a failed or offline check keeps the cached answer.
 * - A purchase is granted only once it's [OwnedState.Owned]; pending purchases wait. Owned
 *   purchases are acknowledged, and if that fails the next check tries again.
 */
@Singleton
class ProManager @Inject constructor(
    private val store: ProStore,
    private val cache: EntitlementCache,
    private val clock: WallClock,
    @ApplicationScope private val scope: CoroutineScope,
) {

    private val pending = MutableStateFlow(false)
    private val price = MutableStateFlow<String?>(null)
    private val refreshLock = Mutex()

    private val _events = MutableSharedFlow<ProEvent>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<ProEvent> = _events.asSharedFlow()

    val state: StateFlow<ProState> = combine(cache.entitlement, pending, price) { cached, isPending, price ->
        ProState(isPro = cached.isPro, isPending = isPending && !cached.isPro, price = price)
    }.stateIn(scope, SharingStarted.Eagerly, ProState())

    init {
        scope.launch {
            store.updates.collect { update ->
                when (update) {
                    is PurchaseUpdate.Changed -> {
                        val granted = apply(update.state, update.purchaseToken, update.needsAcknowledgement)
                        _events.tryEmit(
                            when (update.state) {
                                OwnedState.Owned -> if (granted) ProEvent.Purchased else ProEvent.Failed(StoreFailure.Error)
                                OwnedState.Pending -> ProEvent.PurchasePending
                                OwnedState.NotOwned -> ProEvent.Failed(StoreFailure.Error)
                            },
                        )
                    }
                    PurchaseUpdate.Cancelled -> Unit
                    is PurchaseUpdate.Failed -> _events.tryEmit(ProEvent.Failed(update.reason))
                }
            }
        }
    }

    /** Re-checks with the store. Safe to call often; concurrent calls run one at a time. */
    suspend fun refresh(): StoreQuery = refreshLock.withLock {
        val result = store.queryPurchase()
        if (result is StoreQuery.Success) apply(result.state, result.purchaseToken, result.needsAcknowledgement)
        if (price.value == null) price.value = store.price()
        result
    }

    suspend fun buy(activity: Activity): PurchaseLaunch {
        val launch = store.launchPurchase(activity)
        when (launch) {
            PurchaseLaunch.AlreadyOwned -> refresh()
            is PurchaseLaunch.Failed -> _events.tryEmit(ProEvent.Failed(launch.reason))
            PurchaseLaunch.Started -> Unit
        }
        return launch
    }

    /** "Restore purchases": asks the store and says what happened. */
    suspend fun restore() {
        when (val result = refresh()) {
            is StoreQuery.Success -> _events.tryEmit(
                when (result.state) {
                    OwnedState.Owned -> ProEvent.Restored
                    OwnedState.Pending -> ProEvent.PurchasePending
                    OwnedState.NotOwned -> ProEvent.NothingToRestore
                },
            )
            is StoreQuery.Failed -> _events.tryEmit(ProEvent.Failed(result.reason))
        }
    }

    /** Returns true if the user is Pro afterwards. */
    private suspend fun apply(state: OwnedState, purchaseToken: String?, needsAcknowledgement: Boolean): Boolean {
        val now = clock.nowMillis()
        return when (state) {
            OwnedState.Owned -> {
                if (needsAcknowledgement && purchaseToken != null) store.acknowledge(purchaseToken)
                cache.grant(purchaseToken, now)
                pending.update { false }
                true
            }
            OwnedState.Pending -> {
                pending.update { true }
                false
            }
            OwnedState.NotOwned -> {
                cache.revoke(now)
                pending.update { false }
                false
            }
        }
    }
}
