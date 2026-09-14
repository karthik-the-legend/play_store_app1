package app.formkit.core.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import app.formkit.R
import app.formkit.core.ui.theme.Spacing

// Illustrations are drawn in Compose rather than shipped as images: they pick up the theme's
// colours in light and dark mode and cost a few KB instead of a few hundred.
// All are decorative, so they're hidden from screen readers.

/** A big tinted disc with a tool glyph in the middle. */
@Composable
fun ToolIllustration(@DrawableRes icon: Int, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(160.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(64.dp),
        )
    }
}

/** Two tilted sheets of paper with a history badge, for the empty Recent files screen. */
@Composable
fun RecentFilesIllustration(modifier: Modifier = Modifier) {
    val disc = MaterialTheme.colorScheme.primaryContainer
    val paper = MaterialTheme.colorScheme.surfaceContainerLowest
    val line = MaterialTheme.colorScheme.outlineVariant
    val accent = MaterialTheme.colorScheme.primary
    val onAccent = MaterialTheme.colorScheme.onPrimary

    Canvas(modifier.size(180.dp).clearAndSetSemantics {}) {
        val u = size.minDimension / 100f
        drawCircle(disc, radius = 48 * u)
        rotate(-12f) { sheet(Offset(24 * u, 22 * u), Size(38 * u, 50 * u), paper, line, u) }
        rotate(8f) { sheet(Offset(40 * u, 26 * u), Size(38 * u, 50 * u), paper, line, u) }

        val badge = Offset(72 * u, 72 * u)
        drawCircle(accent, radius = 13 * u, center = badge)
        val tick = Path().apply {
            moveTo(badge.x - 5.5f * u, badge.y)
            lineTo(badge.x - 1.5f * u, badge.y + 4 * u)
            lineTo(badge.x + 6 * u, badge.y - 4.5f * u)
        }
        drawPath(tick, onAccent, style = Stroke(width = 3 * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

private fun DrawScope.sheet(topLeft: Offset, size: Size, paper: Color, line: Color, u: Float) {
    drawRoundRect(Color.Black.copy(alpha = 0.06f), topLeft + Offset(0f, 1.5f * u), size, CornerRadius(5 * u))
    drawRoundRect(paper, topLeft, size, CornerRadius(5 * u))
    val inset = 6 * u
    listOf(1f, 0.8f, 0.9f, 0.55f).forEachIndexed { i, fraction ->
        drawRoundRect(
            color = line,
            topLeft = topLeft + Offset(inset, 10 * u + i * 8 * u),
            size = Size((size.width - inset * 2) * fraction, 3 * u),
            cornerRadius = CornerRadius(1.5f * u),
        )
    }
}

/** A photo that was brought under its size limit, as a result card. */
@Composable
fun ResizeIllustration(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.width(240.dp).clearAndSetSemantics {},
        shape = MaterialTheme.shapes.medium,
        color = colors.surfaceContainerLowest,
        shadowElevation = 2.dp,
    ) {
        Column(Modifier.padding(Spacing.medium), verticalArrangement = Arrangement.spacedBy(Spacing.medium)) {
            val sky = colors.primaryContainer
            val hill = colors.primary.copy(alpha = 0.55f)
            val hillFront = colors.primary
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(112.dp)
                    .clip(MaterialTheme.shapes.small),
            ) {
                drawRect(sky)
                drawCircle(colors.surfaceContainerLowest, radius = size.height * 0.12f, center = Offset(size.width * 0.75f, size.height * 0.3f))
                drawPath(
                    Path().apply {
                        moveTo(0f, size.height)
                        lineTo(size.width * 0.35f, size.height * 0.45f)
                        lineTo(size.width * 0.7f, size.height)
                        close()
                    },
                    hill,
                )
                drawPath(
                    Path().apply {
                        moveTo(size.width * 0.3f, size.height)
                        lineTo(size.width * 0.65f, size.height * 0.6f)
                        lineTo(size.width, size.height * 0.9f)
                        lineTo(size.width, size.height)
                        close()
                    },
                    hillFront,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.onboarding_ill_target, stringResource(R.string.size_kb, 50)),
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.onSurfaceVariant,
                )
                Surface(shape = CircleShape, color = colors.primaryContainer) {
                    Text(
                        text = stringResource(R.string.size_kb, 48),
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = Spacing.small + 4.dp, vertical = 4.dp),
                    )
                }
            }
            LinearProgressIndicator(
                progress = { 0.96f },
                modifier = Modifier.fillMaxWidth(),
                trackColor = colors.surfaceContainerHigh,
                drawStopIndicator = {},
            )
            Text(
                text = stringResource(R.string.onboarding_ill_ready),
                style = MaterialTheme.typography.labelLarge,
                color = colors.primary,
            )
        }
    }
}

/** A passport-style photo next to a signature card. */
@Composable
fun PhotoAndSignatureIllustration(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = modifier.clearAndSetSemantics {},
        horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.width(120.dp).aspectRatio(35f / 45f),
            shape = MaterialTheme.shapes.small,
            color = colors.primaryContainer,
            shadowElevation = 2.dp,
        ) {
            val person = colors.primary
            val guide = colors.onPrimaryContainer.copy(alpha = 0.35f)
            Canvas(Modifier.padding(top = Spacing.medium)) {
                val w = size.width
                val h = size.height
                drawCircle(person, radius = w * 0.2f, center = Offset(w / 2, h * 0.33f))
                drawPath(
                    Path().apply {
                        moveTo(w * 0.14f, h)
                        cubicTo(w * 0.14f, h * 0.62f, w * 0.86f, h * 0.62f, w * 0.86f, h)
                        close()
                    },
                    person,
                )
                val dash = Stroke(width = 1.dp.toPx())
                drawLine(guide, Offset(w * 0.08f, h * 0.3f), Offset(w * 0.92f, h * 0.3f), strokeWidth = dash.width)
                drawLine(guide, Offset(w * 0.08f, h * 0.06f), Offset(w * 0.92f, h * 0.06f), strokeWidth = dash.width)
            }
        }
        Surface(
            modifier = Modifier.width(128.dp).aspectRatio(16f / 7f),
            shape = MaterialTheme.shapes.small,
            color = colors.surfaceContainerLowest,
            shadowElevation = 2.dp,
        ) {
            val ink = colors.onSurface
            Canvas(Modifier.padding(horizontal = Spacing.small, vertical = Spacing.small)) {
                val w = size.width
                val h = size.height
                val squiggle = Path().apply {
                    moveTo(0f, h * 0.75f)
                    cubicTo(w * 0.1f, h * 0.1f, w * 0.2f, -h * 0.1f, w * 0.26f, h * 0.3f)
                    cubicTo(w * 0.3f, h * 0.7f, w * 0.34f, h * 0.9f, w * 0.44f, h * 0.45f)
                    cubicTo(w * 0.5f, h * 0.2f, w * 0.56f, h * 0.8f, w * 0.64f, h * 0.55f)
                    cubicTo(w * 0.72f, h * 0.3f, w * 0.8f, h * 0.7f, w, h * 0.4f)
                }
                drawPath(squiggle, ink, style = Stroke(width = 2.5f.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

/** A shield with a tick on a phone, for the privacy promise. */
@Composable
fun PrivacyIllustration(modifier: Modifier = Modifier) {
    val disc = MaterialTheme.colorScheme.primaryContainer
    val phone = MaterialTheme.colorScheme.surfaceContainerLowest
    val outline = MaterialTheme.colorScheme.outlineVariant
    val accent = MaterialTheme.colorScheme.primary
    val onAccent = MaterialTheme.colorScheme.onPrimary

    Canvas(modifier.size(200.dp).clearAndSetSemantics {}) {
        val u = size.minDimension / 100f
        drawCircle(disc, radius = 48 * u)

        val phoneTopLeft = Offset(31 * u, 14 * u)
        val phoneSize = Size(38 * u, 72 * u)
        drawRoundRect(Color.Black.copy(alpha = 0.06f), phoneTopLeft + Offset(0f, 1.5f * u), phoneSize, CornerRadius(7 * u))
        drawRoundRect(phone, phoneTopLeft, phoneSize, CornerRadius(7 * u))
        drawRoundRect(outline, Offset(44 * u, 18 * u), Size(12 * u, 2.5f * u), CornerRadius(1.25f * u))

        val shield = Path().apply {
            moveTo(50 * u, 34 * u)
            lineTo(64 * u, 39 * u)
            lineTo(64 * u, 51 * u)
            cubicTo(64 * u, 60 * u, 58 * u, 66 * u, 50 * u, 70 * u)
            cubicTo(42 * u, 66 * u, 36 * u, 60 * u, 36 * u, 51 * u)
            lineTo(36 * u, 39 * u)
            close()
        }
        drawPath(shield, accent)
        val tick = Path().apply {
            moveTo(43.5f * u, 51.5f * u)
            lineTo(48.5f * u, 56.5f * u)
            lineTo(57 * u, 47 * u)
        }
        drawPath(tick, onAccent, style = Stroke(width = 3 * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
