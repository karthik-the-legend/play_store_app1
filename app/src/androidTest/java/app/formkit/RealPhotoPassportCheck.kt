package app.formkit

import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.formkit.core.imaging.AndroidImageCodec
import app.formkit.core.imaging.PixelSize
import app.formkit.core.imaging.SourceImageDecoder
import app.formkit.core.imaging.passport.ForegroundMask
import app.formkit.core.imaging.passport.HeadFraming
import app.formkit.core.imaging.passport.MaskRefinement
import app.formkit.core.imaging.passport.MediaPipePersonSegmenter
import app.formkit.core.imaging.passport.PassportPreset
import app.formkit.core.imaging.passport.Placement
import app.formkit.feature.passport.PassportRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Not a regular test: a way to run FormKit's background removal on a real photo and keep what it
 * made, so changes can be judged on photos that went wrong. It does nothing unless given a photo
 * already on the device:
 *
 * ```
 * adb push photo.jpg /data/local/tmp/formkit-check.jpg
 * gradlew :app:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=app.formkit.RealPhotoPassportCheck \
 *   -Pandroid.testInstrumentationRunnerArguments.formkitPhoto=/data/local/tmp/formkit-check.jpg
 * ```
 *
 * The mask and the framed passport photo, for the app as it is and for a few alternatives, are
 * written to `/sdcard/Android/data/app.formkit/files/check/`. Real photos stay out of the repository.
 */
@RunWith(AndroidJUnit4::class)
class RealPhotoPassportCheck {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun cutOutTheGivenPhoto() = runBlocking {
        val path = InstrumentationRegistry.getArguments().getString(PHOTO_ARGUMENT) ?: return@runBlocking
        val out = File(context.getExternalFilesDir(null), "check").apply {
            deleteRecursively()
            mkdirs()
        }
        val codec = AndroidImageCodec()
        val renderer = PassportRenderer(codec)

        // The same working copy the passport tool makes.
        var photo = SourceImageDecoder().decode(File(path), maxLongEdge = WORKING_LONG_EDGE * 2).bitmap
        val size = PixelSize(photo.width, photo.height)
        if (size.longEdge > WORKING_LONG_EDGE) {
            photo = codec.scale(photo, size.scaledBy(WORKING_LONG_EDGE.toDouble() / size.longEdge))
        }

        val segmenter = MediaPipePersonSegmenter(context, Dispatchers.Default)
        save(File(out, "photo.png"), photo)

        // What the app does now.
        val started = SystemClock.elapsedRealtime()
        val current = segmenter.segment(photo)
        Log.i(TAG, "current: segmented and refined in ${SystemClock.elapsedRealtime() - started} ms")
        write(out, "current", photo, current, renderer)

        // The pieces, for comparison, timed now that the model is loaded.
        var mark = SystemClock.elapsedRealtime()
        val whole = segmenter.confidence(photo)
        val onePass = SystemClock.elapsedRealtime() - mark
        write(out, "before", photo, ForegroundMask.fromConfidence(whole.width, whole.height, whole.values), renderer)
        mark = SystemClock.elapsedRealtime()
        val closer = segmenter.closerConfidence(photo)
        val bothPasses = SystemClock.elapsedRealtime() - mark
        write(out, "closer-only", photo, ForegroundMask.fromConfidence(closer.width, closer.height, closer.values), renderer)
        val luma = lumaOf(photo)
        mark = SystemClock.elapsedRealtime()
        MaskRefinement.refine(closer, luma)
        val refining = SystemClock.elapsedRealtime() - mark
        mark = SystemClock.elapsedRealtime()
        renderer.cutout(photo, current)
        val cutting = SystemClock.elapsedRealtime() - mark
        Log.i(TAG, "timings: one pass $onePass ms, both passes $bothPasses ms, refining $refining ms, cut-out $cutting ms")
        for ((name, settings) in VARIANTS) {
            val (radius, epsilon, low, high) = settings
            write(out, name, photo, MaskRefinement.refine(closer, luma, radius.toInt(), epsilon, low, high), renderer)
        }
    }

    private fun write(out: File, name: String, photo: Bitmap, mask: ForegroundMask, renderer: PassportRenderer) {
        save(File(out, "$name-mask.png"), maskImage(mask))
        val cutout = renderer.cutout(photo, mask)
        val frame = PassportPreset.India.pixelSize()
        val head = HeadFraming.measureHead(mask)
        val placement = head?.let { HeadFraming.placementFor(it, PassportPreset.India.guide) }
            ?: Placement.cover(PixelSize(photo.width, photo.height), frame)
        save(File(out, "$name-passport.png"), renderer.render(cutout, placement, frame, Color.WHITE))
        Log.i(TAG, "$name: coverage ${mask.coverage()}, head $head")
    }

    private fun lumaOf(bitmap: Bitmap): IntArray {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return IntArray(pixels.size) { i ->
            val c = pixels[i]
            (((c shr 16) and 0xFF) * 77 + ((c shr 8) and 0xFF) * 150 + (c and 0xFF) * 29) shr 8
        }
    }

    private fun maskImage(mask: ForegroundMask): Bitmap {
        val pixels = IntArray(mask.width * mask.height) { i ->
            val a = mask.alpha[i].toInt() and 0xFF
            Color.rgb(a, a, a)
        }
        return Bitmap.createBitmap(pixels, mask.width, mask.height, Bitmap.Config.ARGB_8888)
    }

    private fun save(file: File, bitmap: Bitmap) {
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private companion object {
        const val TAG = "RealPhotoCheck"
        const val PHOTO_ARGUMENT = "formkitPhoto"
        const val WORKING_LONG_EDGE = 1600

        /** Guided-filter radius, epsilon, and the smooth step's low and high ends. */
        val VARIANTS = listOf(
            "r8-e3" to listOf(8f, 1e-3f, 0.4f, 0.6f),
            "r16-e3" to listOf(16f, 1e-3f, 0.4f, 0.6f),
            "r16-e4" to listOf(16f, 1e-4f, 0.4f, 0.6f),
            "r24-e4" to listOf(24f, 1e-4f, 0.4f, 0.6f),
            "r16-e3-tight" to listOf(16f, 1e-3f, 0.5f, 0.7f),
        )
    }
}
