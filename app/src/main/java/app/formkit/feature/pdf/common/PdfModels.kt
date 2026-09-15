package app.formkit.feature.pdf.common

import kotlinx.serialization.Serializable

@Serializable
enum class PdfStatus { Ready, NeedsPassword, WrongPassword, CertificateProtected, Unreadable }

/** A PDF the user picked, copied into the tool's workspace so it survives process death. */
@Serializable
data class PickedPdf(
    val id: String,
    val originalPath: String,
    val displayName: String,
    val sizeBytes: Long,
    val status: PdfStatus,
    /** The file to read pages from: the original, or an unlocked copy of a protected file. */
    val readablePath: String? = null,
    val pageCount: Int = 0,
    val wasProtected: Boolean = false,
) {
    val isReady: Boolean get() = status == PdfStatus.Ready && readablePath != null
    val needsPassword: Boolean get() = status == PdfStatus.NeedsPassword || status == PdfStatus.WrongPassword
}

@Serializable
data class PickedImage(val id: String, val path: String, val displayName: String, val sizeBytes: Long)

object PdfNames {
    private const val MAX_BASE_LENGTH = 50
    private const val FALLBACK = "FormKit"

    /** "Bank statement (2).pdf" becomes "Bank statement (2)", safe to use in a new file name. */
    fun baseName(displayName: String): String {
        val withoutExtension = if (displayName.endsWith(".pdf", ignoreCase = true)) displayName.dropLast(4) else displayName.substringBeforeLast('.', displayName)
        val cleaned = withoutExtension
            .map { if (it.isISOControl() || it in "\\/:*?\"<>|") '_' else it }
            .joinToString("")
            .trim()
            .trim('.')
            .take(MAX_BASE_LENGTH)
            .trim()
        // Photo Picker and some file managers only expose numbers; those names mean nothing later.
        return if (cleaned.isEmpty() || cleaned.all { it.isDigit() }) FALLBACK else cleaned
    }
}
