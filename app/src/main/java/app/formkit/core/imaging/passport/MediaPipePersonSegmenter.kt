package app.formkit.core.imaging.passport

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import app.formkit.core.di.DefaultDispatcher
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MediaPipe's selfie segmenter: a 244 KB model bundled with the app, so it works offline from
 * the first launch. MediaPipe scales the image down for the model and scales the confidence
 * mask back up to the input size. That answer is coarse, so the head and upper body are looked at
 * a second time and the result is refined against the photo (see [MaskRefinement]).
 */
@Singleton
class MediaPipePersonSegmenter @Inject constructor(
    @ApplicationContext private val context: Context,
    @DefaultDispatcher private val dispatcher: CoroutineDispatcher,
) : PersonSegmenter {

    // Not thread-safe, and slow to create, so one instance is made on first use and calls take turns.
    private val lock = Mutex()
    private var segmenter: ImageSegmenter? = null

    /**
     * MediaPipe's native library calls `strtod_l`, which Android only has from 8.0, so on Android 7
     * loading it takes the whole app down. Any other failure to load it is remembered too, because a
     * class whose static setup failed can never be used again in this process.
     */
    @Volatile
    private var unavailable = Build.VERSION.SDK_INT < Build.VERSION_CODES.O

    override suspend fun segment(image: Bitmap): ForegroundMask = withContext(dispatcher) {
        val input = if (image.config == Bitmap.Config.ARGB_8888) image else image.copy(Bitmap.Config.ARGB_8888, false)
        try {
            MaskRefinement.refine(closerConfidence(input), lumaOf(input))
        } finally {
            if (input !== image) input.recycle()
        }
    }

    /** The model's answer for the whole photo, with a second, closer look at the head and upper body. */
    internal suspend fun closerConfidence(input: Bitmap): Confidence {
        val whole = confidence(input)
        val box = MaskRefinement.secondPassCrop(whole) ?: return whole
        val crop = Bitmap.createBitmap(input, box.left, box.top, box.size, box.size)
        return try {
            MaskRefinement.blendCrop(whole, confidence(crop), box)
        } finally {
            if (crop !== input) crop.recycle()
        }
    }

    /** The model's answer at [input]'s size. [input] must be ARGB_8888. */
    internal suspend fun confidence(input: Bitmap): Confidence = withContext(dispatcher) {
        if (unavailable) throw SegmentationUnavailableException()
        lock.withLock {
            val result = try {
                obtainSegmenter().segment(BitmapImageBuilder(input).build())
            } catch (e: LinkageError) {
                unavailable = true
                throw SegmentationUnavailableException(e)
            }
            val masks = result.confidenceMasks().orElse(null)
            check(!masks.isNullOrEmpty()) { "The segmenter returned no mask" }
            try {
                // One mask means "person". A multi-class model puts background first.
                val personMask = if (masks.size == 1) masks[0] else masks[masks.size - 1]
                val buffer = ByteBufferExtractor.extract(personMask).order(ByteOrder.nativeOrder()).asFloatBuffer()
                val values = FloatArray(personMask.width * personMask.height)
                buffer.get(values)
                Confidence(personMask.width, personMask.height, values)
            } finally {
                masks.forEach { it.close() }
            }
        }
    }

    private fun lumaOf(bitmap: Bitmap): IntArray {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        for (i in pixels.indices) {
            val color = pixels[i]
            pixels[i] = (((color shr 16) and 0xFF) * 77 + ((color shr 8) and 0xFF) * 150 + (color and 0xFF) * 29) shr 8
        }
        return pixels
    }

    private fun obtainSegmenter(): ImageSegmenter = segmenter ?: ImageSegmenter.createFromOptions(
        context,
        ImageSegmenter.ImageSegmenterOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL_ASSET).build())
            .setRunningMode(RunningMode.IMAGE)
            .setOutputConfidenceMasks(true)
            .setOutputCategoryMask(false)
            .build(),
    ).also { segmenter = it }

    private companion object {
        const val MODEL_ASSET = "selfie_segmenter.tflite"
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class SegmenterModule {
    @Binds
    abstract fun bindPersonSegmenter(implementation: MediaPipePersonSegmenter): PersonSegmenter
}
