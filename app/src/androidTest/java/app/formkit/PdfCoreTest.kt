package app.formkit

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.formkit.core.imaging.AndroidImageCodec
import app.formkit.core.imaging.OutputFormat
import app.formkit.core.imaging.PixelSize
import app.formkit.core.pdf.CompressOutcome
import app.formkit.core.pdf.ImagePage
import app.formkit.core.pdf.PageLayout
import app.formkit.core.pdf.PageMargin
import app.formkit.core.pdf.PageOrientation
import app.formkit.core.pdf.PaperSize
import app.formkit.core.pdf.PdfCompressor
import app.formkit.core.pdf.PdfDocuments
import app.formkit.core.pdf.PdfOpenResult
import app.formkit.core.pdf.PdfRasterizer
import app.formkit.core.pdf.PointRect
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

/** The PDF engine on a real device: PdfBox for structure, Android's renderer for pixels. */
@RunWith(AndroidJUnit4::class)
class PdfCoreTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val codec = AndroidImageCodec()
    private val documents = PdfDocuments(context, Dispatchers.IO)
    private val compressor = PdfCompressor(documents, codec, Dispatchers.Default)
    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = File(context.cacheDir, "pdf-core-test").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun anUnprotectedPdfOpensAsItIs() = runBlocking {
        val plain = asset("plain_3pages.pdf")

        val result = documents.open(plain, password = null, unlockedCopy = File(dir, "unused.pdf"))

        assertEquals(PdfOpenResult.Opened(3, plain, wasProtected = false), result)
        assertFalse(File(dir, "unused.pdf").exists())
    }

    @Test
    fun lockedPdfsAskForThePasswordAndOpenWithTheRightOne() = runBlocking {
        for (name in listOf("locked_rc4128.pdf", "locked_aes128.pdf", "locked_aes256.pdf")) {
            val locked = asset(name)
            val unlocked = File(dir, "unlocked-$name")

            assertEquals(name, PdfOpenResult.NeedsPassword, documents.open(locked, null, unlocked))
            assertEquals(name, PdfOpenResult.WrongPassword, documents.open(locked, "not-it", unlocked))
            val opened = documents.open(locked, PASSWORD, unlocked) as PdfOpenResult.Opened

            assertTrue(name, opened.wasProtected)
            assertEquals(unlocked, opened.readable)
            PdfRasterizer(opened.readable).use { raster ->
                assertEquals(name, 3, raster.pageCount)
                val page = raster.render(0, dpi = 72f, maxLongEdge = 2000)
                assertEquals(PixelSize(595, 842), PixelSize(page.width, page.height))
                assertTrue("$name should show text", hasDarkPixels(page))
            }
        }
    }

    @Test
    fun somethingThatIsNotAPdfIsUnreadable() = runBlocking {
        val junk = File(dir, "junk.pdf").apply { writeText("definitely not a PDF") }

        assertEquals(PdfOpenResult.Unreadable, documents.open(junk, null, File(dir, "junk-unlocked.pdf")))
    }

    @Test
    fun mergeKeepsEveryPageInOrderAndExtractPicksPages() = runBlocking {
        val plain = asset("plain_3pages.pdf")
        val unlocked = (documents.open(asset("locked_aes256.pdf"), PASSWORD, File(dir, "aes.pdf")) as PdfOpenResult.Opened).readable
        val merged = File(dir, "merged.pdf")

        documents.merge(listOf(plain, unlocked), merged)
        assertEquals(6, pageCount(merged))

        val extracted = File(dir, "extracted.pdf")
        documents.extractPages(merged, listOf(5, 0), extracted)
        assertEquals(2, pageCount(extracted))
    }

    @Test
    fun imagePagesAreWrittenAtTheirPaperSize() = runBlocking {
        val photo = noise(1200, 1600)
        val jpeg = File(dir, "photo.jpg").apply { writeBytes(codec.encode(photo, OutputFormat.Jpeg, 85)) }
        val placement = PageLayout.place(PixelSize(1200, 1600), PaperSize.A4, PageOrientation.Auto, PageMargin.Small)
        val output = File(dir, "images.pdf")

        documents.writeImagePages(listOf(ImagePage(jpeg, placement.pageWidth, placement.pageHeight, placement.image)), output)

        ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                assertEquals(1, renderer.pageCount)
                renderer.openPage(0).use { page ->
                    // A4 is 595.28 × 841.89 points; the renderer reports whole points, dropping the fraction.
                    assertEquals(595, page.width)
                    assertEquals(841, page.height)
                }
            }
        }
        // The JPEG goes in as it is, so the PDF is barely bigger than the photo.
        assertTrue("PDF ${output.length()} vs JPEG ${jpeg.length()}", output.length() < jpeg.length() + 4_000)
    }

    @Test
    fun aPhotoHeavyPdfCompressesUnderTheLimit() = runBlocking {
        val heavy = photoPdf(pages = 3)
        assertTrue("test PDF should start well over the limit: ${heavy.length()}", heavy.length() > 400_000)

        val output = File(dir, "compressed.pdf")
        val outcome = compressor.compress(heavy, maxBytes = 120_000, workDir = File(dir, "work"), output = output)

        outcome as CompressOutcome.Done
        assertTrue("reported ${outcome.sizeBytes}", outcome.sizeBytes <= 120_000)
        assertEquals(output.length(), outcome.sizeBytes)
        assertEquals(3, pageCount(output))
        PdfRasterizer(output).use { assertEquals(612f to 792f, it.pageSize(1)) }
    }

    @Test
    fun anImpossibleLimitSaysHowSmallItCanGo() = runBlocking {
        val heavy = photoPdf(pages = 3)

        val outcome = compressor.compress(heavy, maxBytes = 3_000, workDir = File(dir, "work"), output = File(dir, "tiny.pdf"))

        outcome as CompressOutcome.TooLarge
        assertTrue("smallest ${outcome.smallestBytes} should be over the 3 KB asked for", outcome.smallestBytes > 3_000)
        assertFalse(File(dir, "tiny.pdf").exists())
    }

    private fun photoPdf(pages: Int): File {
        val output = File(dir, "photos-$pages.pdf")
        val imagePages = (0 until pages).map { index ->
            val jpeg = File(dir, "noise-$index.jpg").apply { writeBytes(codec.encode(noise(1275, 1650, seed = index), OutputFormat.Jpeg, 95)) }
            ImagePage(jpeg, 612f, 792f, PointRect(0f, 0f, 612f, 792f))
        }
        runBlocking { documents.writeImagePages(imagePages, output) }
        return output
    }

    /** Coloured noise with some structure: hard to compress, like a photographed page. */
    private fun noise(width: Int, height: Int, seed: Int = 7): Bitmap {
        val random = Random(seed)
        val pixels = IntArray(width * height) { i ->
            val x = i % width
            val y = i / width
            Color.rgb((x * 255 / width + random.nextInt(60)) and 0xFF, (y * 255 / height + random.nextInt(60)) and 0xFF, random.nextInt(256))
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun hasDarkPixels(bitmap: Bitmap): Boolean {
        for (y in 0 until bitmap.height step 4) {
            for (x in 0 until bitmap.width step 4) {
                if (Color.red(bitmap.getPixel(x, y)) < 100) return true
            }
        }
        return false
    }

    private fun pageCount(file: File): Int = PdfRasterizer(file).use { it.pageCount }

    private fun asset(name: String): File {
        val file = File(dir, name)
        instrumentation.context.assets.open(name).use { input -> file.outputStream().use { input.copyTo(it) } }
        return file
    }

    private companion object {
        const val PASSWORD = "formkit123"
    }
}
