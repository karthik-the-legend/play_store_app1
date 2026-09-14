package app.formkit.feature.resize

import android.graphics.Bitmap
import app.formkit.core.di.DefaultDispatcher
import app.formkit.core.imaging.AndroidImageCodec
import app.formkit.core.imaging.OutputFormat
import app.formkit.core.imaging.PixelSize
import app.formkit.core.imaging.SizeTarget
import app.formkit.core.imaging.SizeTargeter
import app.formkit.core.imaging.SourceImageDecoder
import app.formkit.core.imaging.TargetOutcome
import app.formkit.core.imaging.TargetProgress
import app.formkit.core.imaging.centerCropToAspect
import app.formkit.core.imaging.flattenOntoWhite
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

sealed interface ResizeOutcome {
    data class Done(
        val file: File,
        /** Read back from disk, not from the encoder. */
        val sizeBytes: Long,
        val size: PixelSize,
        val quality: Int?,
        val cropped: Boolean,
    ) : ResizeOutcome

    data class TooLarge(val smallestBytes: Long, val size: PixelSize, val canDownscale: Boolean) : ResizeOutcome
    data class TooSmall(val largestBytes: Long, val size: PixelSize) : ResizeOutcome
}

class ResizeProcessor @Inject constructor(
    private val decoder: SourceImageDecoder,
    private val codec: AndroidImageCodec,
    @DefaultDispatcher private val dispatcher: CoroutineDispatcher,
) {

    suspend fun resize(
        source: File,
        target: SizeTarget,
        output: File,
        onProgress: (TargetProgress) -> Unit = {},
    ): ResizeOutcome = withContext(dispatcher) {
        val decoded = decoder.decode(source)
        val bitmaps = mutableListOf<Bitmap>(decoded.bitmap)
        try {
            var working = decoded.bitmap
            var cropped = false

            target.exactSize?.let { exact ->
                val crop = centerCropToAspect(working, exact)
                if (crop !== working) {
                    bitmaps += crop
                    working = crop
                    cropped = true
                }
            }
            if (target.format == OutputFormat.Jpeg && working.hasAlpha()) {
                working = flattenOntoWhite(working).also { bitmaps += it }
            }

            when (val outcome = SizeTargeter(codec).fit(working, target, onProgress)) {
                is TargetOutcome.Fitted -> {
                    output.parentFile?.mkdirs()
                    output.writeBytes(outcome.bytes)
                    val written = output.length()
                    check(written <= target.maxBytes) { "Wrote $written bytes, over the ${target.maxBytes} byte limit" }
                    ResizeOutcome.Done(output, written, outcome.size, outcome.quality, cropped)
                }
                is TargetOutcome.TooLarge -> ResizeOutcome.TooLarge(outcome.smallestBytes, outcome.size, outcome.canDownscale)
                is TargetOutcome.TooSmall -> ResizeOutcome.TooSmall(outcome.largestBytes, outcome.size)
            }
        } finally {
            bitmaps.forEach { it.recycle() }
        }
    }
}
