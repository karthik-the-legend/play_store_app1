package app.formkit.core.monetization

import android.app.Activity
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

/** How the fake store answers the next purchase. */
enum class FakePurchaseOutcome { Succeed, Pending, Cancel, Fail }

data class FakeStoreSettings(
    /** Pretend there's no connection: every call fails as offline. */
    val offline: Boolean = false,
    val nextOutcome: FakePurchaseOutcome = FakePurchaseOutcome.Succeed,
    /** What the fake "Play servers" record for this user. */
    val serverState: OwnedState = OwnedState.NotOwned,
)

/**
 * A stand-in for Google Play in debug builds (see DECISIONS.md). Its "server" state lives in the
 * settings DataStore, separate from FormKit's own cache, so restarting the app, going offline,
 * refunds and pending purchases all behave the way they would with Play.
 */
class FakeProStore(
    private val dataStore: DataStore<Preferences>,
    private val latencyMillis: Long = 600,
) : ProStore {

    private val _updates = MutableSharedFlow<PurchaseUpdate>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val updates: Flow<PurchaseUpdate> = _updates.asSharedFlow()

    val settings: Flow<FakeStoreSettings> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs ->
            FakeStoreSettings(
                offline = prefs[OFFLINE] ?: false,
                nextOutcome = prefs[NEXT_OUTCOME]?.let { name -> FakePurchaseOutcome.entries.firstOrNull { it.name == name } }
                    ?: FakePurchaseOutcome.Succeed,
                serverState = prefs[SERVER_STATE]?.let { name -> OwnedState.entries.firstOrNull { it.name == name } }
                    ?: OwnedState.NotOwned,
            )
        }

    override suspend fun queryPurchase(): StoreQuery {
        delay(latencyMillis)
        val current = settings.first()
        if (current.offline) return StoreQuery.Failed(StoreFailure.Offline)
        return StoreQuery.Success(current.serverState, tokenFor(current.serverState), needsAcknowledgement = false)
    }

    override suspend fun price(): String? = if (settings.first().offline) null else FAKE_PRICE

    override suspend fun launchPurchase(activity: Activity): PurchaseLaunch = simulatePurchase()

    /** [launchPurchase] without an Activity, which the fake never needs; unit tests call this. */
    suspend fun simulatePurchase(): PurchaseLaunch {
        val current = settings.first()
        if (current.offline) return PurchaseLaunch.Failed(StoreFailure.Offline)
        if (current.serverState == OwnedState.Owned) return PurchaseLaunch.AlreadyOwned
        delay(latencyMillis)
        when (current.nextOutcome) {
            FakePurchaseOutcome.Succeed -> {
                setServerState(OwnedState.Owned)
                _updates.emit(PurchaseUpdate.Changed(OwnedState.Owned, tokenFor(OwnedState.Owned), needsAcknowledgement = true))
            }
            FakePurchaseOutcome.Pending -> {
                setServerState(OwnedState.Pending)
                _updates.emit(PurchaseUpdate.Changed(OwnedState.Pending, tokenFor(OwnedState.Pending), needsAcknowledgement = false))
            }
            FakePurchaseOutcome.Cancel -> _updates.emit(PurchaseUpdate.Cancelled)
            FakePurchaseOutcome.Fail -> _updates.emit(PurchaseUpdate.Failed(StoreFailure.Error))
        }
        return PurchaseLaunch.Started
    }

    override suspend fun acknowledge(purchaseToken: String): Boolean = !settings.first().offline

    // Debug controls, shown in Settings in debug builds.

    suspend fun setOffline(offline: Boolean) = dataStore.edit { it[OFFLINE] = offline }

    suspend fun setNextOutcome(outcome: FakePurchaseOutcome) = dataStore.edit { it[NEXT_OUTCOME] = outcome.name }

    /** Lets a pending purchase clear, the way a cash payment would a day later. */
    suspend fun completePendingPurchase() {
        if (settings.first().serverState != OwnedState.Pending) return
        setServerState(OwnedState.Owned)
        _updates.emit(PurchaseUpdate.Changed(OwnedState.Owned, tokenFor(OwnedState.Owned), needsAcknowledgement = true))
    }

    /** Like a refund: the "server" forgets the purchase. The app notices on its next successful check. */
    suspend fun refund() = setServerState(OwnedState.NotOwned)

    private suspend fun setServerState(state: OwnedState) = dataStore.edit { it[SERVER_STATE] = state.name }

    private fun tokenFor(state: OwnedState): String? = if (state == OwnedState.NotOwned) null else FAKE_TOKEN

    private companion object {
        const val FAKE_PRICE = "₹299.00 (test)"
        const val FAKE_TOKEN = "fake-purchase-token"
        val OFFLINE = booleanPreferencesKey("fake_store_offline")
        val NEXT_OUTCOME = stringPreferencesKey("fake_store_next_outcome")
        val SERVER_STATE = stringPreferencesKey("fake_store_server_state")
    }
}
