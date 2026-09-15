package app.formkit.core.ui.components

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.isVisible
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.formkit.R
import app.formkit.core.monetization.rememberMonetization
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdView
import kotlin.math.roundToInt

/**
 * One native ad, drawn to look like the cards around it with a clear "Ad" label. Takes no space
 * until an ad has loaded, so put it where appearing can't push content the user is reading.
 */
@Composable
fun NativeAdCard(modifier: Modifier = Modifier) {
    val monetization = rememberMonetization()
    val allowed by monetization.adsManager().adsAllowed.collectAsStateWithLifecycle(initialValue = false)
    var ad by remember { mutableStateOf<NativeAd?>(null) }

    LaunchedEffect(allowed) {
        if (allowed && ad == null) {
            ad = monetization.adsManager().loadNativeAd()
        } else if (!allowed) {
            ad?.destroy()
            ad = null
        }
    }
    DisposableEffect(Unit) {
        onDispose { ad?.destroy() }
    }

    val current = ad ?: return
    val colors = AdColors(
        text = MaterialTheme.colorScheme.onSurface.toArgb(),
        secondaryText = MaterialTheme.colorScheme.onSurfaceVariant.toArgb(),
        badge = MaterialTheme.colorScheme.tertiaryContainer.toArgb(),
        onBadge = MaterialTheme.colorScheme.onTertiaryContainer.toArgb(),
        primary = MaterialTheme.colorScheme.primary.toArgb(),
        onPrimary = MaterialTheme.colorScheme.onPrimary.toArgb(),
    )
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = modifier,
    ) {
        AndroidView(
            factory = { context -> createAdViews(context, colors).root },
            update = { view -> (view.tag as? AdViews)?.bind(current, colors) },
        )
    }
}

private data class AdColors(
    val text: Int,
    val secondaryText: Int,
    val badge: Int,
    val onBadge: Int,
    val primary: Int,
    val onPrimary: Int,
)

private class AdViews(
    val root: NativeAdView,
    val headline: TextView,
    val body: TextView,
    val advertiser: TextView,
    val badge: TextView,
    val icon: ImageView,
    val callToAction: Button,
) {
    fun bind(ad: NativeAd, colors: AdColors) {
        headline.text = ad.headline
        headline.setTextColor(colors.text)
        body.text = ad.body
        body.setTextColor(colors.secondaryText)
        body.isVisible = !ad.body.isNullOrBlank()
        advertiser.text = ad.advertiser.orEmpty()
        advertiser.setTextColor(colors.secondaryText)
        badge.setTextColor(colors.onBadge)
        (badge.background as? GradientDrawable)?.setColor(colors.badge)
        val drawable = ad.icon?.drawable
        icon.setImageDrawable(drawable)
        icon.isVisible = drawable != null
        callToAction.text = ad.callToAction
        callToAction.setTextColor(colors.onPrimary)
        (callToAction.background as? GradientDrawable)?.setColor(colors.primary)
        callToAction.isVisible = !ad.callToAction.isNullOrBlank()
        root.setNativeAd(ad)
    }
}

private fun createAdViews(context: Context, colors: AdColors): AdViews {
    val density = context.resources.displayMetrics.density
    fun dp(value: Int) = (value * density).roundToInt()

    val icon = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(12) }
    }
    val badge = TextView(context).apply {
        text = context.getString(R.string.ad_label)
        textSize = 11f
        typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(6), dp(1), dp(6), dp(1))
        background = GradientDrawable().apply {
            cornerRadius = dp(4).toFloat()
            setColor(colors.badge)
        }
    }
    val advertiser = TextView(context).apply {
        textSize = 12f
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setPadding(dp(8), 0, 0, 0)
    }
    val badgeRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(badge)
        addView(advertiser)
    }
    val headline = TextView(context).apply {
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    }
    val texts = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        addView(badgeRow)
        addView(headline)
    }
    val topRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(icon)
        addView(texts)
    }
    val body = TextView(context).apply {
        textSize = 14f
        maxLines = 3
        ellipsize = TextUtils.TruncateAt.END
        setPadding(0, dp(8), 0, dp(8))
    }
    val callToAction = Button(context).apply {
        isAllCaps = false
        minHeight = dp(48)
        minimumHeight = dp(48)
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(colors.primary)
        }
    }
    val container = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(12), dp(12), dp(12))
        addView(topRow)
        addView(body)
        addView(callToAction, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    val root = NativeAdView(context).apply {
        addView(container)
        headlineView = headline
        bodyView = body
        advertiserView = advertiser
        iconView = icon
        callToActionView = callToAction
    }
    return AdViews(root, headline, body, advertiser, badge, icon, callToAction).also { root.tag = it }
}
