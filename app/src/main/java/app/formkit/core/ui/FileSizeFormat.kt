package app.formkit.core.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import app.formkit.R
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale

enum class SizeUnit { Bytes, Kilobytes, Megabytes }

data class DisplaySize(val amount: String, val unit: SizeUnit)

/**
 * Splits a byte count into a number and unit, counting 1 KB as 1000 bytes like the size limits
 * do. Always rounds down, so a file under a 50 KB limit never shows as "50 KB" when it's 49,700
 * bytes: the number on screen never overstates the file.
 */
fun displaySize(bytes: Long, locale: Locale = Locale.getDefault()): DisplaySize {
    require(bytes >= 0) { "bytes must not be negative" }
    return when {
        bytes < 1_000 -> DisplaySize(format(BigDecimal.valueOf(bytes), 0, locale), SizeUnit.Bytes)
        bytes < 1_000_000 -> {
            val kilobytes = BigDecimal.valueOf(bytes).movePointLeft(3)
            DisplaySize(format(kilobytes, if (bytes < 10_000) 1 else 0, locale), SizeUnit.Kilobytes)
        }
        else -> DisplaySize(format(BigDecimal.valueOf(bytes).movePointLeft(6), 1, locale), SizeUnit.Megabytes)
    }
}

private fun format(value: BigDecimal, fractionDigits: Int, locale: Locale): String =
    NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = 0
        maximumFractionDigits = fractionDigits
        roundingMode = RoundingMode.DOWN
    }.format(value)

fun Context.formatSize(bytes: Long): String {
    val size = displaySize(bytes)
    val format = when (size.unit) {
        SizeUnit.Bytes -> R.string.unit_bytes
        SizeUnit.Kilobytes -> R.string.unit_kilobytes
        SizeUnit.Megabytes -> R.string.unit_megabytes
    }
    return getString(format, size.amount)
}

@Composable
fun formatSize(bytes: Long): String = LocalContext.current.formatSize(bytes)
