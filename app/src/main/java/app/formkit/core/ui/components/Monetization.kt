package app.formkit.core.ui.components

import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.formkit.R
import app.formkit.core.monetization.ProEvent
import app.formkit.core.monetization.StoreFailure
import app.formkit.core.monetization.rememberMonetization
import app.formkit.core.ui.theme.Spacing
import kotlinx.coroutines.launch

/**
 * Put on a result screen with a key unique to that result, such as its file path. The first time
 * that result shows, it counts as a finished operation and may show an interstitial (§7). Remembered
 * across rotation and process death, so a result is never counted twice.
 */
@Composable
fun ResultShownEffect(resultKey: String) {
    val monetization = rememberMonetization()
    val activity = LocalActivity.current
    var handledKey by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(resultKey) {
        if (handledKey == resultKey || activity == null) return@LaunchedEffect
        handledKey = resultKey
        monetization.adsManager().onOperationFinished(activity)
    }
}

/** A quiet, dismissible Pro offer for result screens. Never shown to Pro users. */
@Composable
fun ProOfferCard(modifier: Modifier = Modifier) {
    val monetization = rememberMonetization()
    val pro by monetization.proManager().state.collectAsStateWithLifecycle()
    val dismissed by monetization.sessionPerks().proOfferDismissed.collectAsStateWithLifecycle()
    if (pro.isPro || dismissed) return
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()
    // A neutral card, so the tonal Get Pro button stands out against it.
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(Spacing.medium), verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
            Text(stringResource(R.string.pro_offer_title), style = MaterialTheme.typography.titleMedium)
            val price = pro.price
            Text(
                if (price != null) stringResource(R.string.pro_offer_body, price) else stringResource(R.string.pro_offer_body_no_price),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.small, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { monetization.sessionPerks().dismissProOffer() }) {
                    Text(stringResource(R.string.pro_offer_dismiss))
                }
                FilledTonalButton(
                    onClick = { activity?.let { scope.launch { monetization.proManager().buy(it) } } },
                    enabled = activity != null && !pro.isPending,
                ) {
                    Text(stringResource(if (pro.isPending) R.string.pro_pending_short else R.string.pro_offer_action))
                }
            }
        }
    }
}

/** Short messages for purchase results, wherever the user is when they arrive. */
@Composable
fun ProEventMessages() {
    val monetization = rememberMonetization()
    val context = LocalContext.current
    LaunchedEffect(monetization) {
        monetization.proManager().events.collect { event ->
            val message = when (event) {
                ProEvent.Purchased -> R.string.pro_event_purchased
                ProEvent.PurchasePending -> R.string.pro_event_pending
                ProEvent.Restored -> R.string.pro_event_restored
                ProEvent.NothingToRestore -> R.string.pro_event_nothing_to_restore
                is ProEvent.Failed -> when (event.reason) {
                    StoreFailure.Offline -> R.string.pro_event_offline
                    StoreFailure.Unavailable -> R.string.pro_event_unavailable
                    StoreFailure.Error -> R.string.pro_event_error
                }
            }
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }
}
