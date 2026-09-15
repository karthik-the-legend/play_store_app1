package app.formkit.core.monetization

import android.app.Activity
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProManagerTest {

    @Test
    fun `an owned purchase is acknowledged, granted and cached`() = runTest(UnconfinedTestDispatcher()) {
        val store = ScriptedStore(StoreQuery.Success(OwnedState.Owned, "token-1", needsAcknowledgement = true))
        val cache = EntitlementCache(InMemoryPreferences())
        val manager = ProManager(store, cache, { 42L }, backgroundScope)

        manager.refresh()

        assertEquals(listOf("token-1"), store.acknowledged)
        assertEquals(CachedEntitlement(isPro = true, purchaseToken = "token-1", verifiedAtMillis = 42L), cache.entitlement.first())
        assertTrue(manager.state.value.isPro)
    }

    @Test
    fun `Pro survives an offline start because a failed check keeps the cache`() = runTest(UnconfinedTestDispatcher()) {
        val preferences = InMemoryPreferences()
        EntitlementCache(preferences).grant("token-1", nowMillis = 1L)

        // The app restarts with no connection.
        val offlineStore = ScriptedStore(StoreQuery.Failed(StoreFailure.Offline))
        val manager = ProManager(offlineStore, EntitlementCache(preferences), { 99L }, backgroundScope)
        manager.refresh()

        assertTrue(manager.state.value.isPro)
        assertEquals(1L, EntitlementCache(preferences).entitlement.first().verifiedAtMillis)
    }

    @Test
    fun `a refund revokes Pro only once the store answers successfully`() = runTest(UnconfinedTestDispatcher()) {
        val preferences = InMemoryPreferences()
        EntitlementCache(preferences).grant("token-1", nowMillis = 1L)
        val store = ScriptedStore(StoreQuery.Success(OwnedState.NotOwned, null, needsAcknowledgement = false))
        val manager = ProManager(store, EntitlementCache(preferences), { 5L }, backgroundScope)

        manager.refresh()

        assertFalse(manager.state.value.isPro)
    }

    @Test
    fun `a pending purchase is not Pro until it clears`() = runTest(UnconfinedTestDispatcher()) {
        val store = ScriptedStore(StoreQuery.Success(OwnedState.NotOwned, null, needsAcknowledgement = false))
        val manager = ProManager(store, EntitlementCache(InMemoryPreferences()), { 7L }, backgroundScope)

        manager.events.test {
            store.updatesFlow.emit(PurchaseUpdate.Changed(OwnedState.Pending, "token-2", needsAcknowledgement = false))
            assertEquals(ProEvent.PurchasePending, awaitItem())
            assertEquals(ProState(isPro = false, isPending = true), manager.state.value)

            store.updatesFlow.emit(PurchaseUpdate.Changed(OwnedState.Owned, "token-2", needsAcknowledgement = true))
            assertEquals(ProEvent.Purchased, awaitItem())
            assertEquals(ProState(isPro = true, isPending = false), manager.state.value)
            assertEquals(listOf("token-2"), store.acknowledged)
        }
    }

    @Test
    fun `restore reports what it found`() = runTest(UnconfinedTestDispatcher()) {
        val store = ScriptedStore(StoreQuery.Success(OwnedState.NotOwned, null, needsAcknowledgement = false))
        val manager = ProManager(store, EntitlementCache(InMemoryPreferences()), { 1L }, backgroundScope)

        manager.events.test {
            manager.restore()
            assertEquals(ProEvent.NothingToRestore, awaitItem())

            store.nextQuery = StoreQuery.Success(OwnedState.Owned, "token-3", needsAcknowledgement = false)
            manager.restore()
            assertEquals(ProEvent.Restored, awaitItem())

            store.nextQuery = StoreQuery.Failed(StoreFailure.Offline)
            manager.restore()
            assertEquals(ProEvent.Failed(StoreFailure.Offline), awaitItem())
        }
        assertTrue(manager.state.value.isPro)
    }

    @Test
    fun `the fake store's server state survives a restart and a refund reaches the app`() = runTest(UnconfinedTestDispatcher()) {
        val preferences = InMemoryPreferences()
        val fake = FakeProStore(preferences, latencyMillis = 0)
        val manager = ProManager(fake, EntitlementCache(preferences), { 3L }, backgroundScope)

        manager.events.test {
            // Unit tests can't create an Activity, so buy through the fake directly; ProManager
            // sees the result through the store's updates, exactly as after a real purchase screen.
            assertEquals(PurchaseLaunch.Started, fake.simulatePurchase())
            assertEquals(ProEvent.Purchased, awaitItem())
        }
        assertTrue(manager.state.value.isPro)

        // Restart offline: still Pro.
        fake.setOffline(true)
        val restarted = ProManager(FakeProStore(preferences, latencyMillis = 0), EntitlementCache(preferences), { 4L }, backgroundScope)
        restarted.refresh()
        assertTrue(restarted.state.value.isPro)

        // Back online after a refund: Pro goes.
        fake.setOffline(false)
        fake.refund()
        restarted.refresh()
        assertFalse(restarted.state.value.isPro)
    }

    /** A store whose answers the test sets. */
    private class ScriptedStore(var nextQuery: StoreQuery) : ProStore {
        val updatesFlow = MutableSharedFlow<PurchaseUpdate>()
        val acknowledged = mutableListOf<String>()
        override val updates: Flow<PurchaseUpdate> = updatesFlow
        override suspend fun queryPurchase(): StoreQuery = nextQuery
        override suspend fun price(): String? = null
        override suspend fun launchPurchase(activity: Activity): PurchaseLaunch = PurchaseLaunch.Started
        override suspend fun acknowledge(purchaseToken: String): Boolean {
            acknowledged += purchaseToken
            return true
        }
    }

    /** DataStore without files: the Windows file-rename problem in DECISIONS.md doesn't apply. */
    private class InMemoryPreferences : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        private val lock = Mutex()
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = lock.withLock {
            transform(state.value).also { state.value = it }
        }
    }
}
