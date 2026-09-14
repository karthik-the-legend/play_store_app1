package app.formkit.core.imaging

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.min
import kotlin.math.sqrt

data class SizeTarget(
    val maxBytes: Long,
    val minBytes: Long? = null,
    val format: OutputFormat = OutputFormat.Jpeg,
    /** Output exactly these dimensions. Overrides [allowDownscale]. */
    val exactSize: PixelSize? = null,
    val allowDownscale: Boolean = false,
) {
    init {
        require(maxBytes > 0) { "maxBytes must be positive" }
        require(minBytes == null || minBytes in 0..maxBytes) { "minBytes must be between 0 and maxBytes" }
    }
}

data class TargetProgress(val size: PixelSize, val quality: Int?)

sealed interface TargetOutcome {
    /** [bytes] is never larger than the target's maximum. [quality] is null for PNG. */
    class Fitted(val bytes: ByteArray, val size: PixelSize, val quality: Int?) : TargetOutcome

    /** Even the smallest encoding allowed was over the limit. */
    data class TooLarge(val smallestBytes: Long, val size: PixelSize, val canDownscale: Boolean) : TargetOutcome

    /** The best result under the maximum is still below the requested minimum. */
    data class TooSmall(val largestBytes: Long, val size: PixelSize) : TargetOutcome
}

/**
 * Finds the largest encoding of an image that is no bigger than a byte limit.
 *
 * - **Exact size** or **keep dimensions:** binary search over JPEG quality 1–100 at those
 *   dimensions, keeping the largest result under the limit.
 * - **Allow downscale:** a small probe estimates how many pixels fit, so a 12 MP photo aimed at
 *   20 KB starts near the right size instead of running full-resolution searches. Each attempt
 *   searches quality 50–100. If nothing fits, the image shrinks by a step estimated from how far
 *   over it was, down to a 100px short edge. If the first fit lands well under the limit, it
 *   grows once to get closer.
 *
 * The spec's fixed ×0.9 steps (12 at most) can't take a 12 MP photo to 20 KB, and quality 1 at
 * full size looks terrible, hence the estimate and the quality floor. See DECISIONS.md.
 */
class SizeTargeter<I>(private val codec: ImageCodec<I>) {

    suspend fun fit(
        source: I,
        target: SizeTarget,
        onProgress: (TargetProgress) -> Unit = {},
    ): TargetOutcome {
        val sourceSize = codec.sizeOf(source)
        val exact = target.exactSize
        return when {
            exact != null -> atFixedSize(source, exact, target, canDownscale = false, onProgress)
            !target.allowDownscale -> atFixedSize(source, sourceSize, target, canDownscale = true, onProgress)
            else -> withDownscale(source, sourceSize, target, onProgress)
        }
    }

    private suspend fun atFixedSize(
        source: I,
        size: PixelSize,
        target: SizeTarget,
        canDownscale: Boolean,
        onProgress: (TargetProgress) -> Unit,
    ): TargetOutcome {
        val search = withImageAt(source, size) { searchQuality(it, size, target, MIN_QUALITY, onProgress) }
        val best = search.best ?: return TargetOutcome.TooLarge(search.smallestBytes, size, canDownscale)
        return checkMinimum(TargetOutcome.Fitted(best.bytes, size, best.quality), target)
    }

    private suspend fun withDownscale(
        source: I,
        sourceSize: PixelSize,
        target: SizeTarget,
        onProgress: (TargetProgress) -> Unit,
    ): TargetOutcome {
        val floorEdge = min(MIN_SHORT_EDGE, sourceSize.shortEdge)
        var scale = estimateStartScale(source, sourceSize, target, onProgress)
        var bestFit: TargetOutcome.Fitted? = null
        var lastMiss: TargetOutcome.TooLarge? = null
        var grown = false

        for (attempt in 1..MAX_SCALE_ATTEMPTS) {
            val size = sizeAt(sourceSize, scale, floorEdge)
            val atFloor = size.shortEdge <= floorEdge
            val qualityFloor = if (atFloor || attempt == MAX_SCALE_ATTEMPTS) MIN_QUALITY else DOWNSCALE_QUALITY_FLOOR
            val search = withImageAt(source, size) { searchQuality(it, size, target, qualityFloor, onProgress) }
            val best = search.best

            if (best != null) {
                val fitted = TargetOutcome.Fitted(best.bytes, size, best.quality)
                if (bestFit == null || fitted.bytes.size > bestFit.bytes.size) bestFit = fitted
                val farUnder = fitted.bytes.size < target.maxBytes * UNDERSHOOT_RATIO
                if (grown || scale >= 1.0 || !farUnder) break
                grown = true
                scale = min(1.0, scale * sqrt(target.maxBytes * AIM_RATIO / fitted.bytes.size))
            } else {
                lastMiss = TargetOutcome.TooLarge(search.smallestBytes, size, canDownscale = false)
                // After growing past the limit, the earlier fit is the answer.
                if (bestFit != null || atFloor) break
                val step = sqrt(target.maxBytes * AIM_RATIO / search.smallestBytes)
                scale *= step.coerceIn(MIN_SCALE_STEP, MAX_SCALE_STEP)
            }
        }

        val fit = bestFit ?: return checkNotNull(lastMiss)
        return checkMinimum(fit, target)
    }

    /** Estimates the scale at which a mid-quality encoding fits, from a small probe. */
    private suspend fun estimateStartScale(
        source: I,
        sourceSize: PixelSize,
        target: SizeTarget,
        onProgress: (TargetProgress) -> Unit,
    ): Double {
        if (sourceSize.longEdge <= PROBE_LONG_EDGE) return 1.0
        val probeSize = sourceSize.scaledBy(PROBE_LONG_EDGE.toDouble() / sourceSize.longEdge)
        val probeBytes = withImageAt(source, probeSize) {
            encode(it, probeSize, target.format, PROBE_QUALITY, onProgress)
        }
        val bytesPerPixel = probeBytes.size.toDouble() / probeSize.pixels
        val fittingPixels = target.maxBytes * AIM_RATIO / bytesPerPixel
        return min(1.0, sqrt(fittingPixels / sourceSize.pixels))
    }

    private suspend fun searchQuality(
        image: I,
        size: PixelSize,
        target: SizeTarget,
        qualityFloor: Int,
        onProgress: (TargetProgress) -> Unit,
    ): Search {
        if (target.format == OutputFormat.Png) {
            val bytes = encode(image, size, OutputFormat.Png, MAX_QUALITY, onProgress)
            val best = if (bytes.size <= target.maxBytes) Encoded(bytes, quality = null) else null
            return Search(best, bytes.size.toLong())
        }

        // Lowest quality first. If even that is over the limit there's nothing to search, and the
        // user hears "can't reach it" after one encode instead of eight at full resolution.
        val floorBytes = encode(image, size, OutputFormat.Jpeg, qualityFloor, onProgress)
        if (floorBytes.size > target.maxBytes) return Search(best = null, smallestBytes = floorBytes.size.toLong())

        var best = Encoded(floorBytes, qualityFloor)
        var low = qualityFloor + 1
        var high = MAX_QUALITY
        var steps = 0
        while (low <= high && steps < MAX_QUALITY_STEPS) {
            steps++
            val quality = (low + high) / 2
            val bytes = encode(image, size, OutputFormat.Jpeg, quality, onProgress)
            if (bytes.size <= target.maxBytes) {
                // Size isn't strictly monotonic in quality, so keep the biggest fit seen, not the last.
                if (bytes.size > best.bytes.size) best = Encoded(bytes, quality)
                low = quality + 1
            } else {
                high = quality - 1
            }
        }
        return Search(best, floorBytes.size.toLong())
    }

    private suspend fun encode(
        image: I,
        size: PixelSize,
        format: OutputFormat,
        quality: Int,
        onProgress: (TargetProgress) -> Unit,
    ): ByteArray {
        currentCoroutineContext().ensureActive()
        onProgress(TargetProgress(size, if (format == OutputFormat.Jpeg) quality else null))
        return codec.encode(image, format, quality)
    }

    private inline fun <R> withImageAt(source: I, size: PixelSize, block: (I) -> R): R {
        val image = if (size == codec.sizeOf(source)) source else codec.scale(source, size)
        try {
            return block(image)
        } finally {
            if (image !== source) codec.release(image)
        }
    }

    private fun sizeAt(sourceSize: PixelSize, scale: Double, floorEdge: Int): PixelSize {
        if (scale >= 1.0) return sourceSize
        val scaled = sourceSize.scaledBy(scale)
        if (scaled.shortEdge >= floorEdge) return scaled
        return sourceSize.scaledBy(floorEdge.toDouble() / sourceSize.shortEdge)
    }

    private fun checkMinimum(fit: TargetOutcome.Fitted, target: SizeTarget): TargetOutcome {
        val minBytes = target.minBytes ?: return fit
        return if (fit.bytes.size < minBytes) TargetOutcome.TooSmall(fit.bytes.size.toLong(), fit.size) else fit
    }

    private class Encoded(val bytes: ByteArray, val quality: Int?)

    private class Search(val best: Encoded?, val smallestBytes: Long)

    companion object {
        const val MIN_QUALITY = 1
        const val MAX_QUALITY = 100
        const val MAX_QUALITY_STEPS = 8
        const val DOWNSCALE_QUALITY_FLOOR = 50
        const val MAX_SCALE_ATTEMPTS = 12
        const val MIN_SHORT_EDGE = 100
        const val PROBE_LONG_EDGE = 640
        const val PROBE_QUALITY = 75
        private const val AIM_RATIO = 0.9
        private const val UNDERSHOOT_RATIO = 0.8
        private const val MIN_SCALE_STEP = 0.3
        private const val MAX_SCALE_STEP = 0.9
    }
}
