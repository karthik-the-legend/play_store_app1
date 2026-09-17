package app.formkit.core.imaging.passport

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A segmentation model's raw answer: how sure it is, from 0 to 1, that each pixel is the person. */
class Confidence(val width: Int, val height: Int, val values: FloatArray) {
    init {
        require(width > 0 && height > 0 && values.size == width * height) { "Confidence size doesn't match ${width}x$height" }
    }
}

/** A square of the photo, in pixels. */
data class CropBox(val left: Int, val top: Int, val size: Int)

/**
 * Turns the model's rough, low-resolution answer into a mask that follows the photo.
 *
 * The bundled model sees the photo squeezed to 256 × 256, so on a phone photo each of its cells
 * covers several pixels: edges come back blocky and a few pixels out, which leaves a rim of
 * background around the person, and a dark background that touches dark hair or a cap can be taken
 * for part of the head. So:
 *
 * 1. [secondPassCrop] picks a square around the head and upper body, which the model is run on
 *    again at roughly twice the detail, and [blendCrop] folds that answer back in.
 * 2. [refine] snaps the edges to the photo's own edges with a guided filter, tightens them, and
 *    drops anything not connected to the person.
 */
object MaskRefinement {

    /**
     * The square to look at again: the head and upper body, where a passport photo's detail is.
     * Null when the person already fills most of the photo, so a crop would add nothing.
     */
    fun secondPassCrop(confidence: Confidence): CropBox? {
        val width = confidence.width
        val height = confidence.height
        var left = width
        var top = height
        var right = -1
        var bottom = -1
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                if (confidence.values[row + x] >= PERSON) {
                    if (x < left) left = x
                    if (x > right) right = x
                    if (y < top) top = y
                    if (y > bottom) bottom = y
                }
            }
        }
        if (right < 0) return null
        val personWidth = right - left + 1
        val personHeight = bottom - top + 1
        // Wide enough for the shoulders; for a full-length shot, tall enough for the head and chest.
        val size = min(
            (CROP_MARGIN * max(personWidth.toFloat(), UPPER_BODY_SHARE * personHeight)).roundToInt(),
            min(width, height),
        )
        if (size < MIN_CROP || size > max(width, height) * MAX_CROP_SHARE) return null
        val centreX = (left + right) / 2
        return CropBox(
            left = (centreX - size / 2).coerceIn(0, width - size),
            top = (top - (HEADROOM * size).roundToInt()).coerceIn(0, height - size),
            size = size,
        )
    }

    /**
     * [base] with [crop]'s answer (for the square [box]) used inside the square. The two are blended
     * over a narrow band along the square's edges, except where the square meets the photo's edge,
     * so there's no seam.
     */
    fun blendCrop(base: Confidence, crop: Confidence, box: CropBox): Confidence {
        require(crop.width == box.size && crop.height == box.size) { "The crop's answer must match its box" }
        require(box.left >= 0 && box.top >= 0 && box.left + box.size <= base.width && box.top + box.size <= base.height) {
            "The box must lie inside the photo"
        }
        val values = base.values.copyOf()
        val feather = max(1f, box.size * FEATHER_SHARE)
        val atLeft = box.left == 0
        val atTop = box.top == 0
        val atRight = box.left + box.size == base.width
        val atBottom = box.top + box.size == base.height
        for (y in 0 until box.size) {
            for (x in 0 until box.size) {
                var edge = Float.MAX_VALUE
                if (!atLeft) edge = min(edge, x.toFloat())
                if (!atTop) edge = min(edge, y.toFloat())
                if (!atRight) edge = min(edge, (box.size - 1 - x).toFloat())
                if (!atBottom) edge = min(edge, (box.size - 1 - y).toFloat())
                val weight = (edge / feather).coerceIn(0f, 1f)
                val i = (box.top + y) * base.width + box.left + x
                values[i] = values[i] * (1f - weight) + crop.values[y * box.size + x] * weight
            }
        }
        return Confidence(base.width, base.height, values)
    }

    /**
     * The finished mask: edges moved onto the photo's edges (a guided filter over the brightness
     * [luma], 0–255 per pixel), tightened with a smooth step between [low] and [high], and with
     * anything not attached to the person removed.
     */
    fun refine(
        confidence: Confidence,
        luma: IntArray,
        radius: Int = GUIDE_RADIUS,
        epsilon: Float = GUIDE_EPSILON,
        low: Float = EDGE_LOW,
        high: Float = EDGE_HIGH,
    ): ForegroundMask {
        require(luma.size == confidence.values.size) { "Brightness and confidence sizes differ" }
        val width = confidence.width
        val height = confidence.height
        // Far from the person there's nothing to refine, so only the area around them is worked on.
        val area = personArea(confidence, margin = 2 * radius + AREA_MARGIN)
            ?: return ForegroundMask(width, height, ByteArray(width * height))
        val areaWidth = area.right - area.left
        val areaHeight = area.bottom - area.top
        val input = FloatArray(areaWidth * areaHeight)
        val guide = FloatArray(areaWidth * areaHeight)
        for (y in 0 until areaHeight) {
            val from = (area.top + y) * width + area.left
            val to = y * areaWidth
            for (x in 0 until areaWidth) {
                input[to + x] = confidence.values[from + x]
                guide[to + x] = luma[from + x] / 255f
            }
        }
        val filtered = guidedFilter(input, guide, areaWidth, areaHeight, radius, epsilon)
        val refined = keepMainShape(ForegroundMask.fromConfidence(areaWidth, areaHeight, filtered, low, high))
        val alpha = ByteArray(width * height)
        for (y in 0 until areaHeight) {
            System.arraycopy(refined.alpha, y * areaWidth, alpha, (area.top + y) * width + area.left, areaWidth)
        }
        return ForegroundMask(width, height, alpha)
    }

    private class Area(val left: Int, val top: Int, val right: Int, val bottom: Int)

    /** The box around every pixel the model gave any belief to, grown by [margin]; null if there are none. */
    private fun personArea(confidence: Confidence, margin: Int): Area? {
        val width = confidence.width
        val height = confidence.height
        var left = width
        var top = height
        var right = -1
        var bottom = -1
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                if (confidence.values[row + x] > ANY_BELIEF) {
                    if (x < left) left = x
                    if (x > right) right = x
                    if (y < top) top = y
                    if (y > bottom) bottom = y
                }
            }
        }
        if (right < 0) return null
        return Area(max(0, left - margin), max(0, top - margin), min(width, right + 1 + margin), min(height, bottom + 1 + margin))
    }

    /**
     * Edge-aware smoothing (He, Sun and Tang's guided filter, run at half resolution): within each
     * window the output is a straight-line function of the guide, so where the photo has an edge the
     * mask gets one in the same place, and where the photo is flat the mask is just smoothed.
     */
    internal fun guidedFilter(input: FloatArray, guide: FloatArray, width: Int, height: Int, radius: Int, epsilon: Float): FloatArray {
        val step = if (min(width, height) >= SUBSAMPLE_FROM) SUBSAMPLE else 1
        val smallGuide = Grid.downsample(guide, width, height, step)
        val smallInput = Grid.downsample(input, width, height, step)
        val w = smallGuide.width
        val h = smallGuide.height
        val r = max(1, radius / step)
        val g = smallGuide.values
        val p = smallInput.values
        val meanG = Grid.boxMean(g, w, h, r)
        val meanP = Grid.boxMean(p, w, h, r)
        val meanGG = Grid.boxMean(FloatArray(g.size) { g[it] * g[it] }, w, h, r)
        val meanGP = Grid.boxMean(FloatArray(g.size) { g[it] * p[it] }, w, h, r)
        val slope = FloatArray(g.size)
        val offset = FloatArray(g.size)
        for (i in g.indices) {
            val variance = meanGG[i] - meanG[i] * meanG[i]
            val covariance = meanGP[i] - meanG[i] * meanP[i]
            slope[i] = covariance / (variance + epsilon)
            offset[i] = meanP[i] - slope[i] * meanG[i]
        }
        val a = Grid.upsample(Grid.boxMean(slope, w, h, r), w, h, width, height)
        val b = Grid.upsample(Grid.boxMean(offset, w, h, r), w, h, width, height)
        return FloatArray(input.size) { i -> (a[i] * guide[i] + b[i]).coerceIn(0f, 1f) }
    }

    /**
     * Keeps the largest connected shape, plus any shape at least a quarter its size (an arm the
     * background cut off, say), and clears the rest: a chair or poster the model half-believed in.
     */
    internal fun keepMainShape(mask: ForegroundMask): ForegroundMask {
        val width = mask.width
        val height = mask.height
        val size = width * height
        val labels = IntArray(size)
        val strongAreas = ArrayList<Int>()
        val stack = IntArray(size)
        for (start in 0 until size) {
            if (labels[start] != 0 || (mask.alpha[start].toInt() and 0xFF) <= FAINT) continue
            val label = strongAreas.size + 1
            var strong = 0
            var depth = 0
            stack[depth++] = start
            labels[start] = label
            while (depth > 0) {
                val index = stack[--depth]
                if ((mask.alpha[index].toInt() and 0xFF) >= STRONG) strong++
                val x = index % width
                val y = index / width
                for (dy in -1..1) {
                    val ny = y + dy
                    if (ny < 0 || ny >= height) continue
                    for (dx in -1..1) {
                        val nx = x + dx
                        if (nx < 0 || nx >= width) continue
                        val neighbour = ny * width + nx
                        if (labels[neighbour] == 0 && (mask.alpha[neighbour].toInt() and 0xFF) > FAINT) {
                            labels[neighbour] = label
                            stack[depth++] = neighbour
                        }
                    }
                }
            }
            strongAreas += strong
        }
        val largest = strongAreas.maxOrNull() ?: return mask
        if (largest == 0) return mask
        val keep = BooleanArray(strongAreas.size + 1) { label -> label > 0 && strongAreas[label - 1] >= largest * KEEP_SHARE }
        val alpha = ByteArray(size) { i -> if (keep[labels[i]]) mask.alpha[i] else 0 }
        return ForegroundMask(width, height, alpha)
    }

    private const val PERSON = 0.5f
    private const val CROP_MARGIN = 1.25f
    private const val UPPER_BODY_SHARE = 0.55f
    private const val HEADROOM = 0.12f
    private const val MIN_CROP = 64
    private const val MAX_CROP_SHARE = 0.85f
    private const val FEATHER_SHARE = 0.05f

    private const val GUIDE_RADIUS = 16
    private const val GUIDE_EPSILON = 0.001f
    private const val SUBSAMPLE = 2
    private const val SUBSAMPLE_FROM = 400
    private const val EDGE_LOW = 0.5f
    private const val EDGE_HIGH = 0.7f

    /** Below this the model is sure it's background, and the smooth step maps it to nothing anyway. */
    private const val ANY_BELIEF = 0.02f
    private const val AREA_MARGIN = 8

    private const val FAINT = 8
    private const val STRONG = 128
    private const val KEEP_SHARE = 0.25f
}

/** Float images: box means and resizing, for the guided filter and edge colours. */
internal object Grid {

    class Sized(val width: Int, val height: Int, val values: FloatArray)

    /** The mean over a (2r+1)² window, narrower at the borders. Two passes of running sums, so O(n). */
    fun boxMean(source: FloatArray, width: Int, height: Int, radius: Int): FloatArray {
        val across = FloatArray(source.size)
        for (y in 0 until height) {
            val row = y * width
            var sum = 0.0
            var first = 0
            var last = -1
            for (x in 0 until width) {
                val wantLast = min(width - 1, x + radius)
                while (last < wantLast) sum += source[row + ++last]
                val wantFirst = max(0, x - radius)
                while (first < wantFirst) sum -= source[row + first++]
                across[row + x] = (sum / (last - first + 1)).toFloat()
            }
        }
        val result = FloatArray(source.size)
        for (x in 0 until width) {
            var sum = 0.0
            var first = 0
            var last = -1
            for (y in 0 until height) {
                val wantLast = min(height - 1, y + radius)
                while (last < wantLast) sum += across[++last * width + x]
                val wantFirst = max(0, y - radius)
                while (first < wantFirst) sum -= across[first++ * width + x]
                result[y * width + x] = (sum / (last - first + 1)).toFloat()
            }
        }
        return result
    }

    /** Averages [step] × [step] blocks. */
    fun downsample(source: FloatArray, width: Int, height: Int, step: Int): Sized {
        if (step == 1) return Sized(width, height, source)
        val w = ceil(width / step.toFloat()).toInt()
        val h = ceil(height / step.toFloat()).toInt()
        val sums = FloatArray(w * h)
        val counts = IntArray(w * h)
        for (y in 0 until height) {
            val row = (y / step) * w
            for (x in 0 until width) {
                val i = row + x / step
                sums[i] += source[y * width + x]
                counts[i]++
            }
        }
        for (i in sums.indices) sums[i] /= counts[i]
        return Sized(w, h, sums)
    }

    /** Bilinear resize of a small grid up to [width] × [height], matching [downsample]'s block centres. */
    fun upsample(source: FloatArray, sourceWidth: Int, sourceHeight: Int, width: Int, height: Int): FloatArray {
        if (sourceWidth == width && sourceHeight == height) return source
        val scaleX = sourceWidth / width.toFloat()
        val scaleY = sourceHeight / height.toFloat()
        val result = FloatArray(width * height)
        for (y in 0 until height) {
            val sy = ((y + 0.5f) * scaleY - 0.5f).coerceIn(0f, (sourceHeight - 1).toFloat())
            val y0 = sy.toInt()
            val y1 = min(y0 + 1, sourceHeight - 1)
            val fy = sy - y0
            for (x in 0 until width) {
                val sx = ((x + 0.5f) * scaleX - 0.5f).coerceIn(0f, (sourceWidth - 1).toFloat())
                val x0 = sx.toInt()
                val x1 = min(x0 + 1, sourceWidth - 1)
                val fx = sx - x0
                val top = source[y0 * sourceWidth + x0] * (1 - fx) + source[y0 * sourceWidth + x1] * fx
                val bottom = source[y1 * sourceWidth + x0] * (1 - fx) + source[y1 * sourceWidth + x1] * fx
                result[y * width + x] = top * (1 - fy) + bottom * fy
            }
        }
        return result
    }
}
