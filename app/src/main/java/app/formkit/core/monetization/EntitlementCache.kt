package app.formkit.core.monetization

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

data class CachedEntitlement(
    val isPro: Boolean = false,
    val purchaseToken: String? = null,
    /** When Google Play last confirmed the answer, or null if it never has. */
    val verifiedAtMillis: Long? = null,
)

/**
 * What FormKit remembers between launches: whether the user owns Pro (so Pro works offline), how
 * many operations they've finished, and when the last interstitial showed. Kept in the settings
 * DataStore, on the phone only.
 */
@Singleton
class EntitlementCache @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {

    private val preferences: Flow<Preferences> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }

    val entitlement: Flow<CachedEntitlement> = preferences
        .map { prefs -> CachedEntitlement(prefs[PRO_UNLOCKED] ?: false, prefs[PRO_TOKEN], prefs[PRO_VERIFIED_AT]) }
        .distinctUntilChanged()

    suspend fun grant(purchaseToken: String?, nowMillis: Long) {
        dataStore.edit { prefs ->
            prefs[PRO_UNLOCKED] = true
            if (purchaseToken != null) prefs[PRO_TOKEN] = purchaseToken else prefs.remove(PRO_TOKEN)
            prefs[PRO_VERIFIED_AT] = nowMillis
        }
    }

    /** Only after the store answered successfully that nothing is owned, such as after a refund. */
    suspend fun revoke(nowMillis: Long) {
        dataStore.edit { prefs ->
            prefs[PRO_UNLOCKED] = false
            prefs.remove(PRO_TOKEN)
            prefs[PRO_VERIFIED_AT] = nowMillis
        }
    }

    /** Counts one finished operation and returns the new total. */
    suspend fun recordOperation(): Int {
        var total = 0
        dataStore.edit { prefs ->
            total = (prefs[COMPLETED_OPERATIONS] ?: 0) + 1
            prefs[COMPLETED_OPERATIONS] = total
        }
        return total
    }

    suspend fun lastInterstitialAtMillis(): Long? = preferences.first()[LAST_INTERSTITIAL_AT]

    suspend fun recordInterstitialShown(nowMillis: Long) {
        dataStore.edit { it[LAST_INTERSTITIAL_AT] = nowMillis }
    }

    internal companion object {
        val PRO_UNLOCKED = booleanPreferencesKey("pro_unlocked")
        val PRO_TOKEN = stringPreferencesKey("pro_purchase_token")
        val PRO_VERIFIED_AT = longPreferencesKey("pro_verified_at")
        val COMPLETED_OPERATIONS = intPreferencesKey("completed_operations")
        val LAST_INTERSTITIAL_AT = longPreferencesKey("last_interstitial_at")
    }
}
