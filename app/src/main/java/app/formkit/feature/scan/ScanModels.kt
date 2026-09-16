package app.formkit.feature.scan

import app.formkit.core.imaging.OptionsError
import app.formkit.core.imaging.PixelSize
import app.formkit.core.imaging.TargetInput
import app.formkit.core.imaging.scan.PageCorners
import app.formkit.core.imaging.scan.ScanFilter
import app.formkit.core.pdf.PaperSize
import app.formkit.feature.pdf.common.SavedFile
import kotlinx.serialization.Serializable

/** One photographed page: the photo, where the page is on it, and how it should look. */
@Serializable
data class ScanPage(
    val id: String,
    val photoPath: String,
    /** The photo's size with its rotation applied, as it's shown and as [corners] measure it. */
    val photoWidth: Int,
    val photoHeight: Int,
    val corners: PageCorners,
    /** What the page finder found, for Reset; null when it wasn't sure. */
    val foundCorners: PageCorners? = null,
    val filter: ScanFilter = ScanFilter.Enhanced,
    /** The finished page, small, for the list. A new file after every change. */
    val previewPath: String? = null,
) {
    val photoSize: PixelSize get() = PixelSize(photoWidth, photoHeight)
    val edgesFound: Boolean get() = foundCorners != null
}

enum class ScanSizeLimit(val kilobytes: Int?) {
    None(null),
    Kb200(200),
    Kb500(500),
    Mb1(1000),
    Mb2(2000),
    Custom(null),
}

@Serializable
data class ScanResult(
    val path: String,
    val sizeBytes: Long,
    val pageCount: Int,
    /** The limit it was made under, if there was one. */
    val maxBytes: Long?,
    val saved: SavedFile? = null,
)

@Serializable
data class ScanSession(
    val pages: List<ScanPage> = emptyList(),
    val cameraOpen: Boolean = false,
    /** The page whose corners and look are being adjusted. */
    val editingId: String? = null,
    /** New pages start with the look chosen last. */
    val filter: ScanFilter = ScanFilter.Enhanced,
    val paper: PaperSize = PaperSize.A4,
    val limit: ScanSizeLimit = ScanSizeLimit.Kb500,
    val customKb: String = "",
    val result: ScanResult? = null,
) {
    val editing: ScanPage? get() = editingId?.let { id -> pages.firstOrNull { it.id == id } }

    val canAddMore: Boolean get() = pages.size < MAX_PAGES

    /** The limit in bytes: null for no limit, or after recording in [errors] why a typed one can't be used. */
    fun maxBytes(errors: MutableSet<OptionsError> = mutableSetOf()): Long? = when (limit) {
        ScanSizeLimit.None -> null
        ScanSizeLimit.Custom -> TargetInput.kilobytes(customKb, errors)?.let { it * TargetInput.BYTES_PER_KB }
        else -> limit.kilobytes?.let { it * TargetInput.BYTES_PER_KB }
    }

    /** There are pages, and the size limit is one that can be used. */
    val canCreate: Boolean
        get() = pages.isNotEmpty() && mutableSetOf<OptionsError>().also { maxBytes(it) }.isEmpty()

    companion object {
        const val MAX_PAGES = 30
    }
}

/** "Scan_3_pages.pdf" */
fun scanFileName(pageCount: Int): String = "Scan_${pageCount}_pages.pdf"
