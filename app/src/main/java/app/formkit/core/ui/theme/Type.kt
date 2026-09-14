package app.formkit.core.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Four text styles and no more. Material's fifteen slots all map onto these, so components
// that pick their own slot (dialogs, list items, buttons) still stay on-scale. System font:
// no bundled font files, and it already covers Devanagari and Kannada for later translations.

private val Display = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.SemiBold,
    fontSize = 28.sp,
    lineHeight = 36.sp,
)

private val Title = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.SemiBold,
    fontSize = 18.sp,
    lineHeight = 24.sp,
)

private val Body = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.Normal,
    fontSize = 16.sp,
    lineHeight = 24.sp,
)

private val Label = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.Medium,
    fontSize = 14.sp,
    lineHeight = 20.sp,
    letterSpacing = 0.1.sp,
)

internal val FormKitTypography = Typography(
    displayLarge = Display,
    displayMedium = Display,
    displaySmall = Display,
    headlineLarge = Display,
    headlineMedium = Display,
    headlineSmall = Display,
    titleLarge = Title,
    titleMedium = Title,
    titleSmall = Title,
    bodyLarge = Body,
    bodyMedium = Body,
    bodySmall = Body,
    labelLarge = Label,
    labelMedium = Label,
    labelSmall = Label,
)
