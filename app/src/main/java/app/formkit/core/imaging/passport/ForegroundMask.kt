package app.formkit.core.imaging.passport

import kotlinx.serialization.Serializable
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** How much each source pixel belongs to the person: 0 is background, 255 is person. */
class ForegroundMask(val width: Int, val height: Int, val alpha: ByteArray) {
    init {
        require(width > 0 && height > 0 && alpha.size == width * height) { "Mask size doesn't match ${width}x$height" }
    }

    fun at(x: Int, y: Int): Int = alpha[y * width + x].toInt() and 0xFF

    fun copy() = ForegroundMask(width, height, alpha.copyOf())

    companion object {
        /**
         * Turns a segmentation model's per-pixel confidence (0–1) into a mask. A smoothstep between
         * [low] and [high] sharpens the soft, blurry edge the model produces without making it jagged.
         */
        fun fromConfidence(width: Int, height: Int, confidence: FloatArray, low: Float = 0.35f, high: Float = 0.65f): ForegroundMask {
            require(confidence.size == width * height) { "Confidence size doesn't match ${width}x$height" }
            val alpha = ByteArray(width * height) { i ->
                val t = ((confidence[i] - low) / (high - low)).coerceIn(0f, 1f)
                (t * t * (3f - 2f * t) * 255f + 0.5f).toInt().toByte()
            }
            return ForegroundMask(width, height, alpha)
        }
    }
}

enum class BrushMode { Erase, Restore }

/** One touch-up stroke in source pixels. Points are x,y pairs. */
@Serializable
data class BrushStroke(val mode: BrushMode, val radius: Float, val points: List<Float>)

/** A mask-pixel rectangle touched by an edit; [right] and [bottom] are exclusive. */
data class DirtyRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val isEmpty: Boolean get() = right <= left || bottom <= top

    fun union(other: DirtyRect): DirtyRect = when {
        isEmpty -> other
        other.isEmpty -> this
        else -> DirtyRect(min(left, other.left), min(top, other.top), max(right, other.right), max(bottom, other.bottom))
    }

    companion object {
        val Empty = DirtyRect(0, 0, 0, 0)
    }
}

object MaskBrush {
    /** Dabs are placed this fraction of the radius apart, so fast strokes stay continuous. */
    const val DAB_SPACING = 0.25f
    private const val SOLID_FRACTION = 0.6f

    /**
     * Paints [stroke] onto [mask] in place and returns the area it changed: one dab at the first
     * point, then [applySegment] for each following point. Live painting calls the same two steps
     * point by point, so undo's replay reproduces exactly what the user saw.
     */
    fun apply(mask: ForegroundMask, stroke: BrushStroke): DirtyRect {
        val points = stroke.points
        if (points.size < 2) return DirtyRect.Empty
        var dirty = dab(mask, points[0], points[1], stroke.radius, stroke.mode)
        var i = 2
        while (i + 1 < points.size) {
            dirty = dirty.union(applySegment(mask, points[i - 2], points[i - 1], points[i], points[i + 1], stroke.radius, stroke.mode))
            i += 2
        }
        return dirty
    }

    /** Dabs along a segment, excluding its start (which the previous step already painted). */
    fun applySegment(mask: ForegroundMask, fromX: Float, fromY: Float, toX: Float, toY: Float, radius: Float, mode: BrushMode): DirtyRect {
        val dx = toX - fromX
        val dy = toY - fromY
        val length = sqrt(dx * dx + dy * dy)
        val dabs = max(1, ceil(length / max(1f, radius * DAB_SPACING)).toInt())
        var dirty = DirtyRect.Empty
        for (d in 1..dabs) {
            val t = d.toFloat() / dabs
            dirty = dirty.union(dab(mask, fromX + dx * t, fromY + dy * t, radius, mode))
        }
        return dirty
    }

    /** Solid in the inner 60% of the radius, fading to nothing at the edge, so touch-ups blend in. */
    private fun dab(mask: ForegroundMask, cx: Float, cy: Float, radius: Float, mode: BrushMode): DirtyRect {
        val left = max(0, floor(cx - radius).toInt())
        val top = max(0, floor(cy - radius).toInt())
        val right = min(mask.width, ceil(cx + radius).toInt() + 1)
        val bottom = min(mask.height, ceil(cy + radius).toInt() + 1)
        if (right <= left || bottom <= top) return DirtyRect.Empty
        val solidRadius = radius * SOLID_FRACTION
        for (y in top until bottom) {
            for (x in left until right) {
                val dx = x + 0.5f - cx
                val dy = y + 0.5f - cy
                val distance = sqrt(dx * dx + dy * dy)
                if (distance > radius) continue
                val strength = if (distance <= solidRadius) 1f else 1f - (distance - solidRadius) / (radius - solidRadius)
                val index = y * mask.width + x
                val current = mask.alpha[index].toInt() and 0xFF
                val updated = when (mode) {
                    BrushMode.Erase -> (current * (1f - strength)).toInt()
                    BrushMode.Restore -> max(current, (255f * strength).toInt())
                }
                mask.alpha[index] = updated.toByte()
            }
        }
        return DirtyRect(left, top, right, bottom)
    }
}

/** Touch-ups as a list, so undo is dropping the last one and replaying the rest on the model's mask. */
@Serializable
data class MaskEdits(val strokes: List<BrushStroke> = emptyList()) {
    val canUndo: Boolean get() = strokes.isNotEmpty()

    operator fun plus(stroke: BrushStroke) = copy(strokes = strokes + stroke)

    fun undo() = copy(strokes = strokes.dropLast(1))

    fun applyTo(base: ForegroundMask): ForegroundMask = base.copy().also { mask -> strokes.forEach { MaskBrush.apply(mask, it) } }
}
