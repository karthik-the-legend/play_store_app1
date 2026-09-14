package app.formkit

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.formkit.core.imaging.AndroidImageCodec
import app.formkit.core.imaging.OptionsValidation
import app.formkit.core.imaging.PixelSize
import app.formkit.core.imaging.SizeTarget
import app.formkit.core.imaging.SourceImageDecoder
import app.formkit.feature.signature.CropRect
import app.formkit.feature.signature.Drawing
import app.formkit.feature.signature.DrawnStroke
import app.formkit.feature.signature.SignatureBackground
import app.formkit.feature.signature.SignatureDimensionPreset
import app.formkit.feature.signature.SignatureOptions
import app.formkit.feature.signature.SignatureOutcome
import app.formkit.feature.signature.SignatureProcessor
import app.formkit.feature.signature.SignatureSizePreset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.random.Random

/** The signature path on a real device: decode, clean, crop, fit, encode, and what's in the file. */
@RunWith(AndroidJUnit4::class)
class SignaturePipelineTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val processor = SignatureProcessor(SourceImageDecoder(), AndroidImageCodec(), Dispatchers.Default)
    private lateinit var workDir: File

    @Before
    fun setUp() {
        workDir = File(context.cacheDir, "signature-test").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    @After
    fun tearDown() {
        workDir.deleteRecursively()
    }

    @Test
    fun aPhotoOnLinedShadowedPaperCleansToWhite() = runBlocking {
        val work = processor.loadPhoto(signaturePhoto())
        val cleaned = processor.clean(work, SignatureOptions.DEFAULT_INK_STRENGTH)

        val bounds = requireNotNull(cleaned.inkBounds) { "No ink found" }
        Log.i(TAG, "Ink bounds $bounds in ${cleaned.mask.width}×${cleaned.mask.height}")
        assertTrue("bounds $bounds should hold the whole signature", bounds.left <= 430 && bounds.right >= 1170 && bounds.top <= 420 && bounds.bottom >= 610)
        assertTrue("bounds $bounds shouldn't stretch along the ruled lines", bounds.width < 1100)

        val target = target(SignatureOptions(sizePreset = SignatureSizePreset.NoLimit))
        val done = processor.exportPhoto(work, SignatureOptions.DEFAULT_INK_STRENGTH, CropRect.of(bounds, cleaned.mask.width, cleaned.mask.height), target, File(workDir, "white.jpg")) as SignatureOutcome.Done

        val pixels = pixelsOf(done.file)
        val paper = pixels.count { luminance(it) >= 245 }
        val midTones = pixels.count { luminance(it) in 120..244 }
        Log.i(TAG, "White export ${done.size}: paper ${paper * 100 / pixels.size}%, mid-tones ${midTones * 100 / pixels.size}%")
        assertTrue("paper should be clean white, got ${paper * 100 / pixels.size}%", paper >= pixels.size * 0.70)
        assertTrue("texture or shadow left behind: ${midTones * 100 / pixels.size}% mid-tones", midTones <= pixels.size * 0.08)
        assertCornersWhite(done.file)
    }

    @Test
    fun formDimensionsAreExactAndUnderTheLimit() = runBlocking {
        val work = processor.loadPhoto(signaturePhoto())
        val cleaned = processor.clean(work, SignatureOptions.DEFAULT_INK_STRENGTH)
        val crop = CropRect.of(requireNotNull(cleaned.inkBounds), cleaned.mask.width, cleaned.mask.height)
        val target = target(SignatureOptions(sizePreset = SignatureSizePreset.Kb20, dimensionPreset = SignatureDimensionPreset.P140x60))

        val done = processor.exportPhoto(work, SignatureOptions.DEFAULT_INK_STRENGTH, crop, target, File(workDir, "form.jpg")) as SignatureOutcome.Done

        assertEquals(PixelSize(140, 60), boundsOf(done.file))
        assertTrue(done.file.length() <= 20_000)
        assertCornersWhite(done.file)
    }

    @Test
    fun transparentPngHasTrulyTransparentPixels() = runBlocking {
        val work = processor.loadPhoto(signaturePhoto())
        val cleaned = processor.clean(work, SignatureOptions.DEFAULT_INK_STRENGTH)
        val crop = CropRect.of(requireNotNull(cleaned.inkBounds), cleaned.mask.width, cleaned.mask.height)
        val target = target(SignatureOptions(background = SignatureBackground.Transparent, dimensionPreset = SignatureDimensionPreset.P160x60))

        val done = processor.exportPhoto(work, SignatureOptions.DEFAULT_INK_STRENGTH, crop, target, File(workDir, "clear.png")) as SignatureOutcome.Done

        assertTransparentSignature(done.file)
        assertEquals(PixelSize(160, 60), boundsOf(done.file))
    }

    @Test
    fun aDrawnSignatureExportsWithTransparency() = runBlocking {
        val drawing = Drawing(
            listOf(
                DrawnStroke(0.012f, listOf(0.15f, 0.7f, 0.25f, 0.3f, 0.35f, 0.65f, 0.45f, 0.35f, 0.55f, 0.6f, 0.7f, 0.4f, 0.85f, 0.55f)),
                DrawnStroke(0.012f, listOf(0.2f, 0.8f, 0.8f, 0.78f)),
            ),
        )
        val target = target(SignatureOptions(background = SignatureBackground.Transparent, sizePreset = SignatureSizePreset.Kb20))

        val done = processor.exportDrawing(drawing, target, File(workDir, "drawn.png")) as SignatureOutcome.Done

        assertTransparentSignature(done.file)
        assertTrue(done.file.length() <= 20_000)
        assertTrue("cropped to the strokes, got ${done.size}", done.size.width > done.size.height)
    }

    @Test
    fun anEmptyDrawingIsReportedNotSaved() = runBlocking {
        val outcome = processor.exportDrawing(Drawing(), target(SignatureOptions()), File(workDir, "empty.jpg"))

        assertEquals(SignatureOutcome.NoInk, outcome)
    }

    private fun target(options: SignatureOptions): SizeTarget = (options.validate() as OptionsValidation.Valid).target

    private fun assertTransparentSignature(file: File) {
        val bitmap = BitmapFactory.decodeFile(file.path)
        try {
            assertTrue("PNG should carry alpha", bitmap.hasAlpha())
            for ((x, y) in listOf(0 to 0, bitmap.width - 1 to 0, 0 to bitmap.height - 1, bitmap.width - 1 to bitmap.height - 1)) {
                assertEquals("alpha at corner $x,$y", 0, Color.alpha(bitmap.getPixel(x, y)))
            }
            val pixels = IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }
            val clear = pixels.count { Color.alpha(it) == 0 }
            val solidInk = pixels.count { Color.alpha(it) >= 200 && Color.red(it) <= 60 && Color.green(it) <= 60 && Color.blue(it) <= 60 }
            Log.i(TAG, "${file.name}: ${clear * 100 / pixels.size}% transparent, $solidInk solid ink pixels")
            assertTrue("mostly transparent, got ${clear * 100 / pixels.size}%", clear >= pixels.size * 0.6)
            assertTrue("has solid black ink", solidInk > 0)
        } finally {
            bitmap.recycle()
        }
    }

    private fun assertCornersWhite(file: File) {
        val bitmap = BitmapFactory.decodeFile(file.path)
        try {
            for ((x, y) in listOf(1 to 1, bitmap.width - 2 to 1, 1 to bitmap.height - 2, bitmap.width - 2 to bitmap.height - 2)) {
                assertTrue("corner $x,$y should be white", luminance(bitmap.getPixel(x, y)) >= 245)
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun pixelsOf(file: File): IntArray {
        val bitmap = BitmapFactory.decodeFile(file.path)
        return try {
            IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }
        } finally {
            bitmap.recycle()
        }
    }

    private fun boundsOf(file: File): PixelSize {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, options)
        return PixelSize(options.outWidth, options.outHeight)
    }

    private fun luminance(color: Int): Int = (Color.red(color) * 77 + Color.green(color) * 150 + Color.blue(color) * 29) shr 8

    /**
     * A phone photo of a signature: off-white paper with light blue ruled lines, a dark blue
     * pen signature with an underline, a shadow darkening the right half, and per-pixel noise.
     * The ink sits within x 415–1185, y ~380–625 of the 1600×1000 frame.
     */
    private fun signaturePhoto(): File {
        val width = 1600
        val height = 1000
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(242, 238, 226))

        val rule = Paint().apply {
            color = Color.rgb(170, 195, 225)
            strokeWidth = 3f
        }
        for (y in 80 until height step 70) canvas.drawLine(0f, y.toFloat(), width.toFloat(), y.toFloat(), rule)

        val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(25, 35, 95)
            style = Paint.Style.STROKE
            strokeWidth = 9f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val signature = Path().apply {
            moveTo(420f, 560f)
            cubicTo(520f, 300f, 640f, 330f, 620f, 560f)
            cubicTo(600f, 700f, 760f, 380f, 860f, 520f)
            cubicTo(940f, 640f, 1020f, 420f, 1180f, 500f)
        }
        canvas.drawPath(signature, pen)
        canvas.drawLine(430f, 620f, 1150f, 600f, pen)

        val shadow = Paint().apply {
            shader = LinearGradient(700f, 0f, width.toFloat(), 0f, Color.TRANSPARENT, Color.argb(130, 0, 0, 0), Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), shadow)

        val random = Random(11)
        val row = IntArray(width)
        for (y in 0 until height) {
            bitmap.getPixels(row, 0, width, 0, y, width, 1)
            for (x in 0 until width) {
                val color = row[x]
                row[x] = Color.rgb(
                    (Color.red(color) + random.nextInt(-12, 13)).coerceIn(0, 255),
                    (Color.green(color) + random.nextInt(-12, 13)).coerceIn(0, 255),
                    (Color.blue(color) + random.nextInt(-12, 13)).coerceIn(0, 255),
                )
            }
            bitmap.setPixels(row, 0, width, 0, y, width, 1)
        }

        val file = File(workDir, "signature-photo.jpg")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        bitmap.recycle()
        return file
    }

    private companion object {
        const val TAG = "SignaturePipelineTest"
    }
}
