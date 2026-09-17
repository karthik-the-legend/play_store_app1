package app.formkit

import android.Manifest
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.formkit.core.imaging.AndroidImageCodec
import app.formkit.core.imaging.OutputFormat
import app.formkit.core.imaging.PixelSize
import app.formkit.core.imaging.SizeTarget
import app.formkit.core.imaging.SourceImageDecoder
import app.formkit.core.storage.FileExporter
import app.formkit.feature.resize.ResizeOutcome
import app.formkit.feature.resize.ResizeProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.random.Random

/** The whole resize path on a real device: decode, crop, encode, write, save, read back from disk. */
@RunWith(AndroidJUnit4::class)
class ResizePipelineTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val decoder = SourceImageDecoder()
    private val processor = ResizeProcessor(decoder, AndroidImageCodec(), Dispatchers.Default)
    private lateinit var workDir: File

    @Before
    fun setUp() {
        workDir = File(context.cacheDir, "pipeline-test").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    @After
    fun tearDown() {
        workDir.deleteRecursively()
    }

    @Test
    fun cameraPhotoReaches20KbWithDownscaling() = runBlocking {
        val photo = photo(4000, 3000, "camera.jpg")
        Log.i(TAG, "Source photo: ${photo.length()} bytes")

        val started = SystemClock.elapsedRealtime()
        val outcome = processor.resize(photo, SizeTarget(maxBytes = 20_000, allowDownscale = true), File(workDir, "out.jpg"))
        val elapsed = SystemClock.elapsedRealtime() - started

        val done = outcome as ResizeOutcome.Done
        Log.i(TAG, "4000×3000 → ${done.size} at quality ${done.quality}: ${done.sizeBytes} bytes in $elapsed ms")
        assertTrue("on disk ${done.file.length()}", done.file.length() <= 20_000)
        assertTrue("landed just under: ${done.sizeBytes}", done.sizeBytes >= 14_000)
        assertEquals(done.size, boundsOf(done.file))
    }

    @Test
    fun exactDimensionsAreExactAndUnderTheLimit() = runBlocking {
        val photo = photo(4000, 3000, "camera.jpg")
        val passport = PixelSize(413, 531)

        val done = processor.resize(photo, SizeTarget(maxBytes = 50_000, exactSize = passport), File(workDir, "out.jpg")) as ResizeOutcome.Done

        assertEquals(passport, boundsOf(done.file))
        assertTrue(done.file.length() <= 50_000)
        assertTrue("a 4:3 photo is cropped to a portrait shape", done.cropped)
    }

    @Test
    fun anImpossibleTargetReportsTheSmallestSizeAndWritesNothing() = runBlocking {
        val photo = photo(3000, 3000, "noisy.jpg")
        val output = File(workDir, "out.jpg")

        val outcome = processor.resize(photo, SizeTarget(maxBytes = 10_000, exactSize = PixelSize(2000, 2000)), output)

        val tooLarge = outcome as ResizeOutcome.TooLarge
        assertTrue(tooLarge.smallestBytes > 10_000)
        assertFalse(tooLarge.canDownscale)
        assertFalse(output.exists())
    }

    @Test
    fun transparentAreasBecomeWhiteInJpeg() = runBlocking {
        val png = photo(800, 600, "logo.png", format = Bitmap.CompressFormat.PNG, transparentLeftQuarter = true)

        val done = processor.resize(png, SizeTarget(maxBytes = 100_000), File(workDir, "out.jpg")) as ResizeOutcome.Done

        val decoded = BitmapFactory.decodeFile(done.file.path)
        val pixel = decoded.getPixel(10, decoded.height / 2)
        decoded.recycle()
        assertTrue("expected white, got #${Integer.toHexString(pixel)}", Color.red(pixel) > 240 && Color.green(pixel) > 240 && Color.blue(pixel) > 240)
    }

    @Test
    fun exifRotationIsApplied() {
        val photo = photo(1200, 800, "rotated.jpg")
        ExifInterface(photo.path).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }

        assertEquals(PixelSize(800, 1200), decoder.readSize(photo))
        val decoded = decoder.decode(photo)
        assertEquals(PixelSize(800, 1200), PixelSize(decoded.bitmap.width, decoded.bitmap.height))
        decoded.bitmap.recycle()
    }

    @Test
    fun webpInputIsSupported() = runBlocking {
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        }
        val webp = photo(1600, 1200, "photo.webp", format = format)

        val outcome = processor.resize(webp, SizeTarget(maxBytes = 30_000, allowDownscale = true), File(workDir, "out.jpg"))

        assertTrue((outcome as ResizeOutcome.Done).file.length() <= 30_000)
    }

    @Test
    fun theSavedFileOnDiskIsWithinTheLimit() = runBlocking {
        // Android 9 and older only let the app write to Pictures with the storage permission, which
        // the app asks for when Save is tapped; here nobody can tap Allow. Granting it from the shell
        // doesn't always open up storage for a process that's already running (it doesn't on the
        // Android 7 emulator), so if it's still closed the check stops here. That save path was
        // checked by hand on Android 7 with the real permission prompt; see DECISIONS.md.
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            val output = InstrumentationRegistry.getInstrumentation().uiAutomation
                .executeShellCommand("pm grant ${context.packageName} ${Manifest.permission.WRITE_EXTERNAL_STORAGE}")
            // Reading to the end waits for the command to finish.
            ParcelFileDescriptor.AutoCloseInputStream(output).use { it.readBytes() }
            @Suppress("DEPRECATION")
            val pictures = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            val writable = runCatching { File(pictures, ".formkit-probe").apply { writeText("x") }.delete() }.isSuccess
            if (!writable) {
                Log.w("ResizePipelineTest", "Pictures isn't writable from the test process on API ${Build.VERSION.SDK_INT}; skipping the save check")
                return@runBlocking
            }
        }
        val photo = photo(2000, 1500, "camera.jpg")
        val done = processor.resize(photo, SizeTarget(maxBytes = 50_000, allowDownscale = true), File(workDir, "out.jpg")) as ResizeOutcome.Done
        val exporter = FileExporter(context, Dispatchers.IO)

        val exported = exporter.saveImage(done.file, "FormKit_test_${System.currentTimeMillis()}.jpg", OutputFormat.Jpeg.mimeType, 50_000)
        try {
            val onDisk = context.contentResolver.openFileDescriptor(exported.uri, "r")!!.use { it.statSize }
            assertEquals(done.file.length(), onDisk)
            assertTrue(onDisk <= 50_000)
            assertEquals(onDisk, exported.sizeBytes)
        } finally {
            exporter.delete(exported.uri)
        }
    }

    /** Gradients plus per-pixel noise, so it compresses like a real camera photo, not a flat test card. */
    private fun photo(
        width: Int,
        height: Int,
        name: String,
        format: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG,
        transparentLeftQuarter: Boolean = false,
    ): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        paint.shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(), Color.rgb(30, 90, 160), Color.rgb(230, 180, 120), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = RadialGradient(width * 0.4f, height * 0.4f, height * 0.3f, Color.rgb(250, 215, 190), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

        val random = Random(42)
        val row = IntArray(width)
        for (y in 0 until height) {
            bitmap.getPixels(row, 0, width, 0, y, width, 1)
            for (x in 0 until width) {
                val noise = random.nextInt(-20, 21)
                val color = row[x]
                val alpha = if (transparentLeftQuarter && x < width / 4) 0 else 255
                row[x] = Color.argb(
                    alpha,
                    (Color.red(color) + noise).coerceIn(0, 255),
                    (Color.green(color) + noise).coerceIn(0, 255),
                    (Color.blue(color) + noise).coerceIn(0, 255),
                )
            }
            bitmap.setPixels(row, 0, width, 0, y, width, 1)
        }

        val file = File(workDir, name)
        file.outputStream().use { bitmap.compress(format, 92, it) }
        bitmap.recycle()
        return file
    }

    private fun boundsOf(file: File): PixelSize {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, options)
        return PixelSize(options.outWidth, options.outHeight)
    }

    private companion object {
        const val TAG = "ResizePipelineTest"
    }
}
