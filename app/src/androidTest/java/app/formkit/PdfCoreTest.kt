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
import app.formkit.core.pdf.PdfImageCompressor
import app.formkit.core.pdf.PdfDocuments
import app.formkit.core.pdf.PdfOpenResult
import app.formkit.core.pdf.PdfRasterizer
import app.formkit.core.pdf.PointRect
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceGray
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.cos.COSName
import java.io.ByteArrayInputStream
import java.util.zip.Deflater
import com.tom_roush.pdfbox.text.PDFTextStripper
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
    private val compressor = PdfCompressor(documents, codec, PdfImageCompressor(documents, codec, Dispatchers.Default), Dispatchers.Default)
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
    fun textStaysTextWhenShrinkingThePhotosIsEnough() = runBlocking {
        val original = textAndPhotoPdf(transparentPhoto = false)
        assertTrue("test PDF should start well over the limit: ${original.length()}", original.length() > 300_000)

        val output = File(dir, "kept-text.pdf")
        val outcome = compressor.compress(original, maxBytes = 120_000, workDir = File(dir, "work"), output = output)

        outcome as CompressOutcome.Done
        assertTrue("pages were turned into images", outcome.keptText)
        assertTrue("reported ${outcome.sizeBytes}", outcome.sizeBytes <= 120_000)
        assertEquals(output.length(), outcome.sizeBytes)
        // The words are still words: selectable, searchable, and as sharp as the original.
        val text = PDDocument.load(output).use { PDFTextStripper().getText(it) }
        assertTrue("text was lost: $text", text.contains(SENTENCE))
        assertEquals(2, pageCount(output))
    }

    @Test
    fun aPhotoWithATransparencyMaskIsShrunkAndKeepsItsMask() = runBlocking {
        val original = textAndPhotoPdf(transparentPhoto = true)
        assertTrue("test PDF should start well over the limit: ${original.length()}", original.length() > 300_000)

        val output = File(dir, "masked.pdf")
        val outcome = compressor.compress(original, maxBytes = 250_000, workDir = File(dir, "work"), output = output)

        outcome as CompressOutcome.Done
        assertTrue("the masked photo should have been recompressed, keeping the text", outcome.keptText)
        assertTrue(outcome.sizeBytes <= 250_000)
        PDDocument.load(output).use { document ->
            val resources = document.getPage(0).resources
            val image = resources.xObjectNames.map { resources.getXObject(it) }.filterIsInstance<PDImageXObject>().single()
            assertEquals(COSName.DCT_DECODE, image.cosObject.getDictionaryObject(COSName.FILTER))
            assertTrue("the transparency mask was lost", image.cosObject.containsKey(COSName.SMASK))
        }
    }

    @Test
    fun pagesBecomeImagesOnlyWhenThePhotosCantBeShrunk() = runBlocking {
        // Print colours (CMYK) are left alone, so only page images can reach the limit.
        val original = rawImagePdf(components = 4, colourSpace = COSName.DEVICECMYK)
        assertTrue("test PDF should start well over the limit: ${original.length()}", original.length() > 400_000)

        val output = File(dir, "as-images.pdf")
        val outcome = compressor.compress(original, maxBytes = 250_000, workDir = File(dir, "work"), output = output)

        outcome as CompressOutcome.Done
        assertFalse("said text was kept, but the CMYK photo can't be touched", outcome.keptText)
        assertTrue(outcome.sizeBytes <= 250_000)
        assertEquals(1, pageCount(output))
    }

    @Test
    fun aGreyPhotoKeepsItsShadesWithoutATint() = runBlocking {
        val original = rawImagePdf(components = 1, colourSpace = COSName.DEVICEGRAY)
        assertTrue("test PDF should start over the limit: ${original.length()}", original.length() > 200_000)

        val output = File(dir, "grey.pdf")
        val outcome = compressor.compress(original, maxBytes = 150_000, workDir = File(dir, "work"), output = output)

        outcome as CompressOutcome.Done
        assertTrue("the grey photo should have been recompressed, keeping the text", outcome.keptText)
        // The photo is a left-to-right ramp from black to white: check it reads back that way, grey.
        PdfRasterizer(output).use { raster ->
            val page = raster.render(0, 72f, 2000)
            val y = page.height - 300
            for (fraction in listOf(0.1f, 0.5f, 0.9f)) {
                val x = (56 + 480 * fraction).toInt()
                val pixel = page.getPixel(x, y)
                val (r, g, b) = Triple(Color.red(pixel), Color.green(pixel), Color.blue(pixel))
                assertTrue("tinted at $fraction: $r $g $b", maxOf(r, g, b) - minOf(r, g, b) <= 12)
                assertEquals("shade at $fraction", 255f * fraction, r.toFloat(), 30f)
            }
        }
    }

    @Test
    fun anImpossibleLimitSaysHowSmallItCanGo() = runBlocking {
        val heavy = photoPdf(pages = 3)

        val outcome = compressor.compress(heavy, maxBytes = 3_000, workDir = File(dir, "work"), output = File(dir, "tiny.pdf"))

        outcome as CompressOutcome.TooLarge
        assertTrue("smallest ${outcome.smallestBytes} should be over the 3 KB asked for", outcome.smallestBytes > 3_000)
        assertFalse(File(dir, "tiny.pdf").exists())
    }

    /** Two pages, each with a line of real text above a large photo. */
    private fun textAndPhotoPdf(transparentPhoto: Boolean): File {
        PDFBoxResourceLoader.init(context)
        val output = File(dir, "text-and-photo-$transparentPhoto.pdf")
        PDDocument().use { document ->
            repeat(2) { index ->
                val page = PDPage(PDRectangle.A4)
                document.addPage(page)
                val photo = noise(1600, 1200, seed = index + 20)
                val image = if (transparentPhoto) {
                    // A JPEG photo with a separate transparency mask, as Android apps often write.
                    val withAlpha = photo.copy(Bitmap.Config.ARGB_8888, true)
                    withAlpha.setHasAlpha(true)
                    withAlpha.setPixel(0, 0, Color.TRANSPARENT)
                    JPEGFactory.createFromImage(document, withAlpha, 0.95f)
                } else {
                    photo.setHasAlpha(false)
                    JPEGFactory.createFromImage(document, photo, 0.95f)
                }
                PDPageContentStream(document, page).use { content ->
                    content.beginText()
                    content.setFont(PDType1Font.HELVETICA, 14f)
                    content.newLineAtOffset(56f, 780f)
                    content.showText(SENTENCE)
                    content.endText()
                    content.drawImage(image, 56f, 300f, 480f, 360f)
                }
            }
            document.save(output)
        }
        return output
    }

    /**
     * One page with text and a 1200 x 900 photo stored raw (deflated) in the given colours: a
     * left-to-right ramp with light noise, so it's big and its shades are easy to check.
     */
    private fun rawImagePdf(components: Int, colourSpace: COSName): File {
        PDFBoxResourceLoader.init(context)
        val width = 1200
        val height = 900
        val random = Random(11)
        val raw = ByteArray(width * height * components) { i ->
            val x = (i / components) % width
            val shade = (x * 255 / (width - 1) + random.nextInt(-6, 7)).coerceIn(0, 255)
            // CMYK: the shade as black ink, so the page still looks like a ramp.
            (if (components == 4) (if (i % 4 == 3) 255 - shade else 0) else shade).toByte()
        }
        val deflater = Deflater()
        deflater.setInput(raw)
        deflater.finish()
        val buffer = ByteArray(raw.size + 1024)
        val deflated = buffer.copyOf(deflater.deflate(buffer))
        val output = File(dir, "raw-$components.pdf")
        PDDocument().use { document ->
            val page = PDPage(PDRectangle.A4)
            document.addPage(page)
            val space = if (components == 1) PDDeviceGray.INSTANCE else PDDeviceRGB.INSTANCE
            val image = PDImageXObject(document, ByteArrayInputStream(deflated), COSName.FLATE_DECODE, width, height, 8, space)
            image.cosObject.setItem(COSName.COLORSPACE, colourSpace)
            PDPageContentStream(document, page).use { content ->
                content.beginText()
                content.setFont(PDType1Font.HELVETICA, 14f)
                content.newLineAtOffset(56f, 780f)
                content.showText(SENTENCE)
                content.endText()
                content.drawImage(image, 56f, 200f, 480f, 360f)
            }
            document.save(output)
        }
        return output
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
        const val SENTENCE = "FormKit keeps this text sharp"
    }
}
