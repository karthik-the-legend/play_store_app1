package app.formkit

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.formkit.core.imaging.AndroidImageCodec
import app.formkit.core.imaging.OutputFormat
import app.formkit.core.imaging.PixelSize
import app.formkit.core.imaging.SizeTarget
import app.formkit.core.imaging.SizeTargeter
import app.formkit.core.imaging.TargetOutcome
import app.formkit.core.imaging.passport.ForegroundMask
import app.formkit.core.imaging.passport.JpegDensity
import app.formkit.core.imaging.passport.MediaPipePersonSegmenter
import app.formkit.core.imaging.passport.PassportPreset
import app.formkit.core.imaging.passport.Placement
import app.formkit.core.imaging.passport.PrintSheets
import app.formkit.feature.passport.PassportRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Rendering, exporting and printing passport photos on a real device, plus the bundled segmenter. */
@RunWith(AndroidJUnit4::class)
class PassportPipelineTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val codec = AndroidImageCodec()
    private val renderer = PassportRenderer(codec)
    private lateinit var workDir: File

    @Before
    fun setUp() {
        workDir = File(context.cacheDir, "passport-test").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    @After
    fun tearDown() {
        workDir.deleteRecursively()
    }

    @Test
    fun renderedPhotoIsExactlyThePresetSizeWithTheChosenBackground() {
        val (photo, mask) = portraitWithMask(1200, 1600)
        val cutout = renderer.cutout(photo, mask)
        val frame = PassportPreset.India.pixelSize()
        val background = 0xFFD8E7F5.toInt()

        val rendered = renderer.render(cutout, Placement.cover(PixelSize(1200, 1600), frame), frame, background)

        assertEquals(PixelSize(413, 531), PixelSize(rendered.width, rendered.height))
        assertEquals("corner shows the new background", background, rendered.getPixel(2, 2))
        val middle = rendered.getPixel(206, 300)
        assertTrue("the person stays: #${Integer.toHexString(middle)}", Color.red(middle) > 150 && Color.blue(middle) < 100)
    }

    @Test
    fun exportedPhotoKeepsItsSizeLimitAndCarries300Dpi() {
        val (photo, mask) = portraitWithMask(1200, 1600)
        val frame = PassportPreset.India.pixelSize()
        val rendered = renderer.render(renderer.cutout(photo, mask), Placement.cover(PixelSize(1200, 1600), frame), frame, Color.WHITE)

        val fitted = runBlocking {
            SizeTargeter(codec).fit(rendered, SizeTarget(maxBytes = 50_000, format = OutputFormat.Jpeg, exactSize = frame))
        } as TargetOutcome.Fitted
        val tagged = JpegDensity.withDpi(fitted.bytes, 300)
        val file = File(workDir, "photo.jpg").apply { writeBytes(tagged) }

        assertTrue(file.length() <= 50_000)
        assertEquals(300, JpegDensity.readDpi(file.readBytes()))
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        assertEquals(PixelSize(413, 531), PixelSize(bounds.outWidth, bounds.outHeight))
    }

    @Test
    fun printSheetIs4x6InchesAt300DpiInJpegAndPdf() {
        val photo = Bitmap.createBitmap(413, 531, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(200, 80, 60)) }
        val layout = PrintSheets.layout(PixelSize(413, 531), 8)!!

        val sheet = renderer.renderSheet(photo, layout, watermark = "Made with FormKit")
        assertEquals(PixelSize(1800, 1200), PixelSize(sheet.width, sheet.height))
        val firstSlot = layout.slots.first()
        assertEquals(Color.rgb(200, 80, 60), sheet.getPixel(firstSlot.left + 50, firstSlot.top + 50))
        assertEquals(Color.WHITE, sheet.getPixel(5, 5))

        val pdf = File(workDir, "sheet.pdf")
        renderer.writeSheetPdf(sheet, 300, pdf)
        ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { document ->
                assertEquals(1, document.pageCount)
                document.openPage(0).use { page ->
                    // 72 points per inch: 6 × 4 inches.
                    assertEquals(432, page.width)
                    assertEquals(288, page.height)
                }
            }
        }
    }

    @Test
    fun theBundledSegmenterLoadsAndReturnsAMaskAtTheImageSize() = runBlocking {
        val image = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(120, 130, 140)) }

        val mask = MediaPipePersonSegmenter(context, Dispatchers.Default).segment(image)

        assertEquals(640, mask.width)
        assertEquals(480, mask.height)
    }

    /** An orange "person" block in the middle of a grey photo, with a mask that matches it. */
    private fun portraitWithMask(width: Int, height: Int): Pair<Bitmap, ForegroundMask> {
        val photo = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val alpha = ByteArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val person = x in width / 4 until width * 3 / 4 && y > height / 6
                photo.setPixel(x, y, if (person) Color.rgb(230, 120, 40) else Color.rgb(90, 90, 90))
                if (person) alpha[y * width + x] = 255.toByte()
            }
        }
        return photo to ForegroundMask(width, height, alpha)
    }
}
