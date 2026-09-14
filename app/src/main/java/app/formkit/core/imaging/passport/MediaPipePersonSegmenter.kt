package app.formkit.core.imaging.passport

import android.content.Context
import android.graphics.Bitmap
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
 * mask back up to the input size.
 */
@Singleton
class MediaPipePersonSegmenter @Inject constructor(
    @ApplicationContext private val context: Context,
    @DefaultDispatcher private val dispatcher: CoroutineDispatcher,
) : PersonSegmenter {

    // Not thread-safe, and slow to create, so one instance is made on first use and calls take turns.
    private val lock = Mutex()
    private var segmenter: ImageSegmenter? = null

    override suspend fun segment(image: Bitmap): ForegroundMask = withContext(dispatcher) {
        lock.withLock {
            val input = if (image.config == Bitmap.Config.ARGB_8888) image else image.copy(Bitmap.Config.ARGB_8888, false)
            try {
                val result = obtainSegmenter().segment(BitmapImageBuilder(input).build())
                val masks = result.confidenceMasks().orElse(null)
                check(!masks.isNullOrEmpty()) { "The segmenter returned no mask" }
                try {
                    // One mask means "person". A multi-class model puts background first.
                    val personMask = if (masks.size == 1) masks[0] else masks[masks.size - 1]
                    val buffer = ByteBufferExtractor.extract(personMask).order(ByteOrder.nativeOrder()).asFloatBuffer()
                    val confidence = FloatArray(personMask.width * personMask.height)
                    buffer.get(confidence)
                    ForegroundMask.fromConfidence(personMask.width, personMask.height, confidence)
                } finally {
                    masks.forEach { it.close() }
                }
            } finally {
                if (input !== image) input.recycle()
            }
        }
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
