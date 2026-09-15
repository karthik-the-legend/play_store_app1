package app.formkit.core.pdf

import android.content.Context
import app.formkit.core.di.IoDispatcher
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

sealed interface PdfOpenResult {
    /** [readable] is a file Android's renderer can open: the original, or an unlocked copy. */
    data class Opened(val pageCount: Int, val readable: File, val wasProtected: Boolean) : PdfOpenResult

    data object NeedsPassword : PdfOpenResult
    data object WrongPassword : PdfOpenResult

    /** Locked to a certificate rather than a password; FormKit can't open these. */
    data object CertificateProtected : PdfOpenResult
    data object Unreadable : PdfOpenResult
}

/** A JPEG to put on its own page, at [image] on a page of the given size in points. */
data class ImagePage(val jpeg: File, val pageWidth: Float, val pageHeight: Float, val image: PointRect)

/**
 * Everything FormKit does with PdfBox: unlocking, merging, extracting pages and writing pages of
 * images. Rendering uses Android's own [android.graphics.pdf.PdfRenderer] (see [PdfRasterizer]).
 * PdfBox buffers in temporary files rather than memory, so large documents don't run out of memory.
 */
@Singleton
class PdfDocuments @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    /**
     * Opens [file] with [password] (none for an unprotected file). A protected file that opens is
     * saved without its protection to [unlockedCopy], because Android's renderer can't take a
     * password on most supported versions.
     */
    suspend fun open(file: File, password: String?, unlockedCopy: File): PdfOpenResult = withContext(ioDispatcher) {
        ensureResourceLoader()
        try {
            PDDocument.load(file, password.orEmpty(), memorySetting()).use { document ->
                val pageCount = document.numberOfPages
                if (pageCount < 1) return@withContext PdfOpenResult.Unreadable
                if (!document.isEncrypted) return@withContext PdfOpenResult.Opened(pageCount, file, wasProtected = false)
                document.isAllSecurityToBeRemoved = true
                unlockedCopy.parentFile?.mkdirs()
                document.save(unlockedCopy)
                PdfOpenResult.Opened(pageCount, unlockedCopy, wasProtected = true)
            }
        } catch (_: InvalidPasswordException) {
            if (password.isNullOrEmpty()) PdfOpenResult.NeedsPassword else PdfOpenResult.WrongPassword
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            classifyFailure(file)
        } catch (_: LinkageError) {
            // Certificate security needs BouncyCastle, which is left out on purpose.
            classifyFailure(file)
        } catch (_: RuntimeException) {
            classifyFailure(file)
        }
    }

    /** Joins [sources] (unprotected files) into [output], in order. */
    suspend fun merge(sources: List<File>, output: File) = withContext(ioDispatcher) {
        require(sources.size >= 2) { "Merging needs at least two documents" }
        ensureResourceLoader()
        output.parentFile?.mkdirs()
        PDFMergerUtility().apply {
            sources.forEach { addSource(it) }
            destinationFileName = output.path
            mergeDocuments(memorySetting())
        }
    }

    /** Copies the pages at [pageIndices] (zero-based, in that order) of [source] into [output]. */
    suspend fun extractPages(source: File, pageIndices: List<Int>, output: File) = withContext(ioDispatcher) {
        require(pageIndices.isNotEmpty()) { "Pick at least one page" }
        ensureResourceLoader()
        output.parentFile?.mkdirs()
        PDDocument.load(source, "", memorySetting()).use { document ->
            PDDocument(memorySetting()).use { extracted ->
                for (index in pageIndices) {
                    ensureActive()
                    extracted.importPage(document.getPage(index))
                }
                // Imported pages still point at the source's resources, so save before it closes.
                extracted.save(output)
            }
        }
    }

    /** Writes one image per page. The JPEG bytes go into the PDF as they are, without re-encoding. */
    suspend fun writeImagePages(pages: List<ImagePage>, output: File) = withContext(ioDispatcher) {
        require(pages.isNotEmpty()) { "A PDF needs at least one page" }
        ensureResourceLoader()
        output.parentFile?.mkdirs()
        PDDocument(memorySetting()).use { document ->
            for (page in pages) {
                ensureActive()
                val pdPage = PDPage(PDRectangle(page.pageWidth, page.pageHeight))
                document.addPage(pdPage)
                val image = JPEGFactory.createFromByteArray(document, page.jpeg.readBytes())
                PDPageContentStream(document, pdPage).use { content ->
                    content.drawImage(image, page.image.left, page.image.bottom, page.image.width, page.image.height)
                }
            }
            document.save(output)
        }
    }

    private fun memorySetting(): MemoryUsageSetting =
        MemoryUsageSetting.setupTempFileOnly().setTempDir(File(context.cacheDir, TEMP_DIR).apply { mkdirs() })

    private fun ensureResourceLoader() {
        synchronized(this) {
            if (!PDFBoxResourceLoader.isReady()) PDFBoxResourceLoader.init(context)
        }
    }

    private fun classifyFailure(file: File): PdfOpenResult =
        if (runCatching { containsAscii(file, CERTIFICATE_FILTER) }.getOrDefault(false)) {
            PdfOpenResult.CertificateProtected
        } else {
            PdfOpenResult.Unreadable
        }

    private fun containsAscii(file: File, needle: String): Boolean {
        val pattern = needle.toByteArray(Charsets.US_ASCII)
        file.inputStream().buffered().use { input ->
            var matched = 0
            while (true) {
                val byte = input.read()
                if (byte < 0) return false
                matched = when (byte.toByte()) {
                    pattern[matched] -> matched + 1
                    pattern[0] -> 1
                    else -> 0
                }
                if (matched == pattern.size) return true
            }
        }
    }

    private companion object {
        const val TEMP_DIR = "pdfbox-temp"
        const val CERTIFICATE_FILTER = "Adobe.PubSec"
    }
}
