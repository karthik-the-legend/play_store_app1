package app.formkit

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.formkit.core.imaging.AndroidImageCodec
import app.formkit.core.imaging.ImageCodec
import app.formkit.core.imaging.OutputFormat
import app.formkit.core.imaging.PixelSize
import app.formkit.core.imaging.SizeTarget
import app.formkit.core.imaging.SizeTargeter
import app.formkit.core.imaging.SourceImageDecoder
import app.formkit.core.imaging.TargetOutcome
import app.formkit.core.pdf.CompressOutcome
import app.formkit.core.pdf.PageImages
import app.formkit.core.pdf.PagePlacement
import app.formkit.core.pdf.PdfCompressor
import app.formkit.core.pdf.PdfDocuments
import app.formkit.core.pdf.PdfImageCompressor
import app.formkit.core.pdf.PdfRasterizer
import app.formkit.core.pdf.PointRect
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Not a regular test: puts FormKit's shrinking side by side with how it worked before, on a real
 * photo and a PDF of text and a photo, using Android's own encoder. Does nothing unless given a photo:
 *
 * ```
 * adb push photo.jpg /data/local/tmp/formkit-clarity.jpg
 * adb shell am instrument -w -e class app.formkit.ClarityCheck \
 *   -e formkitPhoto /data/local/tmp/formkit-clarity.jpg app.formkit.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 *
 * Results go to `/sdcard/Android/data/app.formkit/files/clarity/` for scoring and looking at.
 */
@RunWith(AndroidJUnit4::class)
class ClarityCheck {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val codec = AndroidImageCodec()

    /** How photos were shrunk before: halved, then one bilinear pass, no sharpening. */
    private val before = object : ImageCodec<Bitmap> by codec {
        override fun scale(image: Bitmap, size: PixelSize): Bitmap {
            var current = image
            while (current.width / 2 >= size.width && current.height / 2 >= size.height) {
                val half = Bitmap.createScaledBitmap(current, current.width / 2, current.height / 2, true)
                if (current !== image) current.recycle()
                current = half
            }
            if (current.width == size.width && current.height == size.height) {
                return if (current === image) image.copy(Bitmap.Config.ARGB_8888, false) else current
            }
            val result = Bitmap.createScaledBitmap(current, size.width, size.height, true)
            if (current !== image && current !== result) current.recycle()
            return result
        }
    }

    @Test
    fun compareBeforeAndNow() = runBlocking {
        val path = InstrumentationRegistry.getArguments().getString(PHOTO_ARGUMENT) ?: return@runBlocking
        val out = File(context.getExternalFilesDir(null), "clarity").apply {
            deleteRecursively()
            mkdirs()
        }
        val photo = SourceImageDecoder().decode(File(path)).bitmap
        for (kilobytes in listOf(20, 50, 100)) {
            val target = SizeTarget(maxBytes = kilobytes * 1000L, allowDownscale = true)
            for ((name, imageCodec) in listOf("before" to before, "now" to codec.forPhotos)) {
                val started = System.currentTimeMillis()
                val fitted = SizeTargeter(imageCodec).fit(photo, target) as TargetOutcome.Fitted
                File(out, "photo-${kilobytes}kb-$name.jpg").writeBytes(fitted.bytes)
                Log.i(TAG, "$kilobytes KB $name: ${fitted.size} q${fitted.quality} ${fitted.bytes.size} bytes in ${System.currentTimeMillis() - started} ms")
            }
        }

        val documents = PdfDocuments(context, Dispatchers.IO)
        val pdf = textAndPhotoPdf(photo, File(out, "original.pdf"))
        val limit = 150_000L
        val nowPdf = File(out, "pdf-now.pdf")
        val started = System.currentTimeMillis()
        val outcome = PdfCompressor(documents, codec, PdfImageCompressor(documents, codec, Dispatchers.Default), Dispatchers.Default)
            .compress(pdf, limit, File(out, "work-now"), nowPdf)
        Log.i(TAG, "PDF now: $outcome in ${System.currentTimeMillis() - started} ms")
        val beforePdf = File(out, "pdf-before.pdf")
        PdfRasterizer(pdf).use { raster ->
            val pages = object : PageImages {
                override val pageCount = raster.pageCount
                override fun placement(index: Int): PagePlacement {
                    val (width, height) = raster.pageSize(index)
                    return PagePlacement(width, height, PointRect(0f, 0f, width, height))
                }
                override fun render(index: Int, maxLongEdge: Int): Bitmap = raster.render(index, 200f, maxLongEdge)
            }
            // The old approach, every page made an image, with today's resizing: only the approach differs.
            val pagesAsImages = PdfCompressor(documents, codec, PdfImageCompressor(documents, codec, Dispatchers.Default), Dispatchers.Default)
            val result = pagesAsImages.fit(pages, limit, File(out, "work-before"), beforePdf)
            Log.i(TAG, "PDF before (pages as images): $result")
        }
        for ((name, file) in listOf("before" to beforePdf, "now" to nowPdf)) {
            PdfRasterizer(file).use { raster ->
                val page = raster.render(0, 200f, 4000)
                File(out, "pdf-$name-page1.png").outputStream().use { page.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
        check(outcome is CompressOutcome.Done)
    }

    /** One A4 page of a short letter with a photo in it: the kind of PDF people need to shrink. */
    private fun textAndPhotoPdf(photo: Bitmap, output: File): File {
        PDFBoxResourceLoader.init(context)
        PDDocument().use { document ->
            val page = PDPage(PDRectangle.A4)
            document.addPage(page)
            val scaled = codec.scale(photo, PixelSize(1600, 1600 * photo.height / photo.width))
            val image = JPEGFactory.createFromImage(document, scaled, 0.95f)
            PDPageContentStream(document, page).use { content ->
                content.beginText()
                content.setFont(PDType1Font.HELVETICA_BOLD, 16f)
                content.newLineAtOffset(56f, 790f)
                content.showText("Application for the post of Junior Assistant")
                content.setFont(PDType1Font.HELVETICA, 10f)
                content.setLeading(14f)
                for (line in LETTER) {
                    content.newLine()
                    content.showText(line)
                }
                content.endText()
                content.drawImage(image, 56f, 120f, 480f, 480f * scaled.height / scaled.width)
            }
            document.save(output)
        }
        return output
    }

    private companion object {
        const val TAG = "ClarityCheck"
        const val PHOTO_ARGUMENT = "formkitPhoto"
        val LETTER = listOf(
            "Registration number: 2026/JA/004512    Date of birth: 14-08-1999    Category: General",
            "I declare that the particulars given in this application are true to the best of my knowledge.",
            "Small print matters: roll numbers like 0O8B and l1I are easy to misread once text is blurred.",
            "Address: 12, 3rd Cross, Jayanagar 4th Block, Bengaluru 560011, Karnataka.",
        )
    }
}
