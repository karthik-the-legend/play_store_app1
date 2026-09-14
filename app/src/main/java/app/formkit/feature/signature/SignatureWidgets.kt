package app.formkit.feature.signature

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

// The pad is paper in every theme: white with black ink, like the file it produces.
private val PaperColor = Color.White
private val InkColor = Color.Black
private val GuideColor = Color(0xFFCFCFD6)
private val HintColor = Color(0xFF8E8E98)
private val ScrimColor = Color.Black.copy(alpha = 0.45f)
private val TouchRadius = 32.dp

/**
 * The cleaned photo with a crop frame on top. Drag a corner to resize the frame or drag inside
 * it to move it. Drags that start elsewhere are left alone, so the screen still scrolls.
 */
@Composable
fun CropOverlayImage(
    bitmap: ImageBitmap,
    crop: CropRect,
    onCropChange: (CropRect) -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val liveCrop = remember { mutableStateOf(crop) }
    LaunchedEffect(crop) { liveCrop.value = crop }
    val latestOnCropChange by rememberUpdatedState(onCropChange)
    val frameColor = MaterialTheme.colorScheme.primary

    Box(modifier, contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .heightIn(max = 360.dp)
                .aspectRatio(bitmap.width.toFloat() / bitmap.height),
        ) {
            Image(
                bitmap = bitmap,
                contentDescription = contentDescription,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize(),
            )
            Canvas(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        var handle: CropHandle? = null
                        detectDragGestures(
                            onDragStart = { start ->
                                handle = hitTest(start, liveCrop.value, size.width.toFloat(), size.height.toFloat(), TouchRadius.toPx())
                            },
                            onDragEnd = {
                                if (handle != null) latestOnCropChange(liveCrop.value)
                                handle = null
                            },
                            onDragCancel = {
                                if (handle != null) latestOnCropChange(liveCrop.value)
                                handle = null
                            },
                            onDrag = { change, amount ->
                                val active = handle ?: return@detectDragGestures
                                change.consume()
                                liveCrop.value = liveCrop.value.dragged(active, amount.x / size.width, amount.y / size.height)
                            },
                        )
                    },
            ) {
                val current = liveCrop.value
                val left = current.left * size.width
                val top = current.top * size.height
                val right = current.right * size.width
                val bottom = current.bottom * size.height

                // One even-odd path rather than four rectangles: anti-aliased rectangles leave a
                // faint seam where their fractional edges meet.
                val scrim = Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRect(Rect(0f, 0f, size.width, size.height))
                    addRect(Rect(left, top, right, bottom))
                }
                drawPath(scrim, ScrimColor)
                drawRect(frameColor, Offset(left, top), Size(right - left, bottom - top), style = Stroke(width = 2.dp.toPx()))
                for (corner in listOf(Offset(left, top), Offset(right, top), Offset(left, bottom), Offset(right, bottom))) {
                    drawCircle(Color.White, radius = 10.dp.toPx(), center = corner)
                    drawCircle(frameColor, radius = 7.dp.toPx(), center = corner)
                }
            }
        }
    }
}

private fun hitTest(point: Offset, crop: CropRect, width: Float, height: Float, touchRadius: Float): CropHandle? {
    val corners = listOf(
        CropHandle.TopLeft to Offset(crop.left * width, crop.top * height),
        CropHandle.TopRight to Offset(crop.right * width, crop.top * height),
        CropHandle.BottomLeft to Offset(crop.left * width, crop.bottom * height),
        CropHandle.BottomRight to Offset(crop.right * width, crop.bottom * height),
    )
    val nearest = corners.minBy { (_, corner) -> (corner - point).getDistance() }
    if ((nearest.second - point).getDistance() <= touchRadius) return nearest.first
    val inside = point.x in crop.left * width..crop.right * width && point.y in crop.top * height..crop.bottom * height
    return if (inside) CropHandle.Move else null
}

/** A white pad to sign on with a finger or stylus. Each finished stroke goes to [onStrokeFinished]. */
@Composable
fun SignaturePad(
    drawing: Drawing,
    strokeWidth: StrokeWidth,
    onStrokeFinished: (DrawnStroke) -> Unit,
    contentDescription: String,
    hint: String,
    modifier: Modifier = Modifier,
) {
    val inProgress = remember { mutableStateListOf<Float>() }
    val latestWidth by rememberUpdatedState(strokeWidth)
    val latestOnStrokeFinished by rememberUpdatedState(onStrokeFinished)
    val description = contentDescription

    Box(
        modifier
            .aspectRatio(Drawing.ASPECT_RATIO)
            .clip(MaterialTheme.shapes.medium)
            .background(PaperColor),
    ) {
        Canvas(
            Modifier
                .fillMaxSize()
                .semantics { this.contentDescription = description }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        // Consumed straight away so the screen doesn't scroll while signing.
                        down.consume()
                        inProgress.clear()
                        inProgress.addStrokePoint(down.position.x / size.width, down.position.y / size.height)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            change.historical.forEach { inProgress.addStrokePoint(it.position.x / size.width, it.position.y / size.height) }
                            inProgress.addStrokePoint(change.position.x / size.width, change.position.y / size.height)
                            change.consume()
                            if (!change.pressed) break
                        }
                        if (inProgress.isNotEmpty()) {
                            latestOnStrokeFinished(DrawnStroke(latestWidth.fraction, inProgress.toList()))
                        }
                        inProgress.clear()
                    }
                },
        ) {
            val lineY = size.height * 0.75f
            drawLine(GuideColor, Offset(size.width * 0.06f, lineY), Offset(size.width * 0.94f, lineY), strokeWidth = 1.dp.toPx())
            drawing.strokes.forEach { drawStroke(it.points, it.width) }
            if (inProgress.isNotEmpty()) drawStroke(inProgress, latestWidth.fraction)
        }
        if (drawing.isEmpty && inProgress.isEmpty()) {
            Text(
                text = hint,
                style = MaterialTheme.typography.bodyLarge,
                color = HintColor,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

private fun DrawScope.drawStroke(points: List<Float>, widthFraction: Float) {
    if (points.size < 2) return
    val strokePx = widthFraction * size.width
    if (points.size == 2) {
        drawCircle(InkColor, strokePx / 2, Offset(points[0] * size.width, points[1] * size.height))
        return
    }
    val path = Path().apply {
        moveTo(points[0] * size.width, points[1] * size.height)
        var i = 2
        while (i + 3 < points.size) {
            val controlX = points[i] * size.width
            val controlY = points[i + 1] * size.height
            val nextX = points[i + 2] * size.width
            val nextY = points[i + 3] * size.height
            quadraticTo(controlX, controlY, (controlX + nextX) / 2, (controlY + nextY) / 2)
            i += 2
        }
        lineTo(points[points.size - 2] * size.width, points[points.size - 1] * size.height)
    }
    drawPath(path, InkColor, style = Stroke(width = strokePx, cap = StrokeCap.Round, join = StrokeJoin.Round))
}
