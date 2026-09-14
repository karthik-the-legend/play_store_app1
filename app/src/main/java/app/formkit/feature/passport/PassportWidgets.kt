package app.formkit.feature.passport

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.formkit.R
import app.formkit.core.imaging.PixelSize
import app.formkit.core.imaging.passport.HeadGuide
import app.formkit.core.imaging.passport.Placement
import app.formkit.core.ui.theme.Spacing
import kotlin.math.max

private const val MAX_VIEW_ZOOM = 6f
private val GuideLight = Color.White
private val GuideDark = Color(0xFF1B1B1F)

/**
 * The passport frame with the photo behind it and the head guides on top.
 *
 * In [EditorMode.Position], one or two fingers drag and pinch the photo within the frame.
 * In [EditorMode.TouchUp], one finger paints the mask and two fingers zoom the view (not the
 * photo), so small areas like stray hair can be reached. A quick tap paints a single dab.
 */
@Composable
fun PassportPreview(
    cutout: ImageBitmap?,
    cutoutVersion: Int,
    frame: PixelSize,
    placement: Placement?,
    background: Color,
    guide: HeadGuide,
    mode: EditorMode,
    brushRadius: Dp,
    isBusy: Boolean,
    busyLabel: String,
    contentDescription: String,
    onPlacementChange: (Placement) -> Unit,
    onBrushStart: (x: Float, y: Float, radius: Float) -> Unit,
    onBrushMove: (x: Float, y: Float) -> Unit,
    onBrushEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var viewScale by remember { mutableFloatStateOf(1f) }
    var viewOffset by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(mode) {
        viewScale = 1f
        viewOffset = Offset.Zero
    }

    val latestPlacement by rememberUpdatedState(placement)
    val latestOnPlacementChange by rememberUpdatedState(onPlacementChange)
    val latestOnBrushStart by rememberUpdatedState(onBrushStart)
    val latestOnBrushMove by rememberUpdatedState(onBrushMove)
    val latestOnBrushEnd by rememberUpdatedState(onBrushEnd)
    val brushRadiusPx = with(LocalDensity.current) { brushRadius.toPx() }
    val latestBrushRadiusPx by rememberUpdatedState(brushRadiusPx)
    val description = contentDescription

    Box(modifier, contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .heightIn(max = 440.dp)
                .aspectRatio(frame.width.toFloat() / frame.height)
                .clip(MaterialTheme.shapes.small)
                .background(background),
        ) {
            Canvas(
                Modifier
                    .fillMaxSize()
                    .semantics { this.contentDescription = description }
                    .pointerInput(mode, frame) {
                        // Preview pixels to frame pixels, undoing the touch-up zoom first.
                        fun toSource(point: Offset): Pair<Float, Float>? {
                            val current = latestPlacement ?: return null
                            val frameScale = frame.width / size.width.toFloat()
                            val content = (point - viewOffset) / viewScale
                            return current.toSource(content.x * frameScale, content.y * frameScale, frame)
                        }

                        fun sourceRadius(): Float {
                            val current = latestPlacement ?: return 1f
                            val frameScale = frame.width / size.width.toFloat()
                            return latestBrushRadiusPx / viewScale * frameScale / current.scaleFor(frame)
                        }

                        if (mode == EditorMode.Position) {
                            detectTransformGestures { centroid, pan, zoom, _ ->
                                val current = latestPlacement ?: return@detectTransformGestures
                                val frameScale = frame.width / size.width.toFloat()
                                var next = current.dragged(pan.x * frameScale, pan.y * frameScale, frame)
                                if (zoom != 1f) next = next.zoomed(zoom, centroid.x * frameScale, centroid.y * frameScale, frame)
                                latestOnPlacementChange(next)
                            }
                        } else {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                var pending: Offset? = down.position
                                var painting = false
                                var zoomed = false
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val pressed = event.changes.filter { it.pressed }
                                    if (pressed.isEmpty()) break
                                    if (pressed.size >= 2) {
                                        if (painting) {
                                            latestOnBrushEnd()
                                            painting = false
                                        }
                                        pending = null
                                        zoomed = true
                                        val zoom = event.calculateZoom()
                                        val pan = event.calculatePan()
                                        val centroid = event.calculateCentroid()
                                        val newScale = (viewScale * zoom).coerceIn(1f, MAX_VIEW_ZOOM)
                                        val moved = centroid - (centroid - viewOffset) * (newScale / viewScale) + pan
                                        viewOffset = Offset(
                                            moved.x.coerceIn(size.width * (1f - newScale), 0f),
                                            moved.y.coerceIn(size.height * (1f - newScale), 0f),
                                        )
                                        viewScale = newScale
                                        event.changes.forEach { it.consume() }
                                    } else if (!zoomed) {
                                        val change = pressed.first()
                                        val start = pending
                                        // Wait for real movement, so a second finger landing a moment later zooms instead of painting.
                                        if (start != null && (change.position - start).getDistance() > viewConfiguration.touchSlop / 2) {
                                            toSource(start)?.let { (x, y) -> latestOnBrushStart(x, y, sourceRadius()) }
                                            painting = true
                                            pending = null
                                        }
                                        if (painting) {
                                            change.historical.forEach { historical ->
                                                toSource(historical.position)?.let { (x, y) -> latestOnBrushMove(x, y) }
                                            }
                                            toSource(change.position)?.let { (x, y) -> latestOnBrushMove(x, y) }
                                        }
                                        change.consume()
                                    }
                                }
                                val tap = pending
                                if (tap != null && !zoomed) {
                                    toSource(tap)?.let { (x, y) ->
                                        latestOnBrushStart(x, y, sourceRadius())
                                        latestOnBrushEnd()
                                    }
                                } else if (painting) {
                                    latestOnBrushEnd()
                                }
                            }
                        }
                    },
            ) {
                // Reading the version here redraws the canvas whenever the cutout's pixels change.
                cutoutVersion
                val current = placement
                clipRect {
                    withTransform({
                        translate(viewOffset.x, viewOffset.y)
                        scale(viewScale, viewScale, Offset.Zero)
                    }) {
                        drawRect(background)
                        if (cutout != null && current != null) {
                            val scale = current.scaleFor(frame) * size.width / frame.width
                            val width = size.width
                            val height = size.height
                            withTransform({
                                translate(width / 2f, height / 2f)
                                scale(scale, scale, Offset.Zero)
                                translate(-current.centerX, -current.centerY)
                            }) {
                                drawImage(cutout, filterQuality = FilterQuality.High)
                            }
                        }
                        drawHeadGuides(guide, lineScale = 1f / viewScale)
                    }
                }
            }
            if (isBusy || cutout == null) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceContainerLow),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Spacing.medium, Alignment.CenterVertically),
                ) {
                    CircularProgressIndicator()
                    Text(busyLabel, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/**
 * Lines for the crown, eyes and chin, and an oval for the face. Dashes alternate white and dark,
 * so each guide reads as one line on a white backdrop, dark hair or anything in between.
 */
private fun DrawScope.drawHeadGuides(guide: HeadGuide, lineScale: Float) {
    val dashLength = 8.dp.toPx() * lineScale
    val lightDash = PathEffect.dashPathEffect(floatArrayOf(dashLength, dashLength))
    val darkDash = PathEffect.dashPathEffect(floatArrayOf(dashLength, dashLength), phase = dashLength)
    val lineWidth = 2.dp.toPx() * lineScale
    val inset = size.width * 0.08f

    fun guideLine(fraction: Float) {
        val start = Offset(inset, size.height * fraction)
        val end = Offset(size.width - inset, size.height * fraction)
        drawLine(GuideLight, start, end, strokeWidth = lineWidth, pathEffect = lightDash)
        drawLine(GuideDark, start, end, strokeWidth = lineWidth, pathEffect = darkDash)
    }
    guideLine(guide.crown)
    guideLine(guide.eyes)
    guideLine(guide.chin)

    val headHeight = guide.headHeight * size.height
    val ovalWidth = headHeight * FACE_WIDTH_PER_HEIGHT
    val topLeft = Offset((size.width - ovalWidth) / 2f, guide.crown * size.height)
    val ovalSize = Size(ovalWidth, headHeight)
    drawOval(GuideLight, topLeft, ovalSize, style = Stroke(width = lineWidth, pathEffect = lightDash))
    drawOval(GuideDark, topLeft, ovalSize, style = Stroke(width = lineWidth, pathEffect = darkDash))
}

private const val FACE_WIDTH_PER_HEIGHT = 0.72f

/** A round colour swatch with its name underneath. */
@Composable
fun ColorSwatch(
    color: Color,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val checkTint = if (color.luminance() > 0.5f) Color.Black else Color.White
    Column(
        modifier = modifier.selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.small),
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(color)
                .border(
                    width = if (selected) 3.dp else 1.dp,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(painterResource(R.drawable.ic_check_circle), contentDescription = null, tint = checkTint, modifier = Modifier.size(24.dp))
            }
        }
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

/** Hue, saturation and brightness sliders with a live preview. */
@Composable
fun ColorPickerDialog(initial: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val start = remember(initial) { FloatArray(3).also { android.graphics.Color.colorToHSV(initial, it) } }
    var hue by remember { mutableFloatStateOf(start[0]) }
    var saturation by remember { mutableFloatStateOf(start[1]) }
    var brightness by remember { mutableFloatStateOf(start[2]) }
    val color = Color.hsv(hue, saturation, brightness)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.passport_color_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .clip(MaterialTheme.shapes.small)
                        .background(color)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small),
                )
                Text(stringResource(R.string.passport_color_hue), style = MaterialTheme.typography.labelLarge)
                Slider(value = hue, onValueChange = { hue = it }, valueRange = 0f..359f)
                Text(stringResource(R.string.passport_color_saturation), style = MaterialTheme.typography.labelLarge)
                Slider(value = saturation, onValueChange = { saturation = it })
                Text(stringResource(R.string.passport_color_brightness), style = MaterialTheme.typography.labelLarge)
                Slider(value = brightness, onValueChange = { brightness = it })
            }
        },
        confirmButton = { TextButton(onClick = { onPick(color.toArgb()) }) { Text(stringResource(R.string.passport_color_use)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.resize_cancel)) } },
    )
}

/** Brush radius on screen for a 0–1 size setting. */
fun brushRadiusFor(size: Float): Dp = (MIN_BRUSH_DP + (MAX_BRUSH_DP - MIN_BRUSH_DP) * size.coerceIn(0f, 1f)).dp

private const val MIN_BRUSH_DP = 8f
private const val MAX_BRUSH_DP = 48f

/** Millimetres for a pixel length at [dpi], to one decimal place. */
fun pixelsToMillimetres(pixels: Int, dpi: Int): Float = (pixels * 25.4f / max(1, dpi) * 10f).toInt() / 10f
