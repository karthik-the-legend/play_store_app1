package app.formkit.feature.passport

import app.formkit.core.imaging.OptionsError
import app.formkit.core.imaging.OptionsValidation
import app.formkit.core.imaging.OutputFormat
import app.formkit.core.imaging.PixelSize
import app.formkit.core.imaging.SizeTarget
import app.formkit.core.imaging.TargetInput
import app.formkit.core.imaging.passport.BrushMode
import app.formkit.core.imaging.passport.DEFAULT_DPI
import app.formkit.core.imaging.passport.HeadGuide
import app.formkit.core.imaging.passport.Icao35x45Guide
import app.formkit.core.imaging.passport.PassportPreset
import app.formkit.core.imaging.passport.Placement
import app.formkit.core.imaging.passport.Square2x2Guide
import app.formkit.core.imaging.passport.millimetresToPixels
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.roundToInt

enum class PhotoSizeChoice(val preset: PassportPreset?) {
    India(PassportPreset.India),
    UnitedStates(PassportPreset.UnitedStates),
    Square51(PassportPreset.Square51),
    Schengen(PassportPreset.Schengen),
    Custom(null),
}

enum class CustomUnit { Millimetres, Inches, Pixels }

enum class Backdrop(val argb: Int?) {
    White(0xFFFFFFFF.toInt()),
    LightBlue(0xFFD8E7F5.toInt()),
    LightGrey(0xFFE2E2E4.toInt()),
    Red(0xFFD0342C.toInt()),
    Custom(null),
}

enum class PhotoKbPreset(val kilobytes: Int?) {
    NoLimit(null), Kb20(20), Kb50(50), Kb100(100), Kb200(200), Custom(null),
}

enum class EditorMode { Position, TouchUp }

enum class SheetFormat(val mimeType: String, val extension: String) {
    Jpeg("image/jpeg", "jpg"),
    Pdf("application/pdf", "pdf"),
}

@Serializable
data class PassportOptions(
    val sizeChoice: PhotoSizeChoice = PhotoSizeChoice.India,
    val customWidth: String = "",
    val customHeight: String = "",
    val customUnit: CustomUnit = CustomUnit.Millimetres,
    val customDpi: String = DEFAULT_DPI.toString(),
    val backdrop: Backdrop = Backdrop.White,
    val customColor: Int = DEFAULT_CUSTOM_COLOR,
    val sizePreset: PhotoKbPreset = PhotoKbPreset.NoLimit,
    val customKb: String = "",
    val brushMode: BrushMode = BrushMode.Erase,
    /** 0 is the smallest brush, 1 the largest. */
    val brushSize: Float = DEFAULT_BRUSH_SIZE,
    /** Null means the largest common count that fits. */
    val sheetCount: Int? = null,
    val sheetFormat: SheetFormat = SheetFormat.Jpeg,
) {
    val backgroundColor: Int get() = backdrop.argb ?: customColor

    val hasSizeLimit: Boolean get() = sizePreset != PhotoKbPreset.NoLimit

    /** The DPI written into the file: the custom one when it's valid, otherwise 300. */
    val dpi: Int
        get() = if (sizeChoice == PhotoSizeChoice.Custom && customUnit != CustomUnit.Pixels) {
            customDpi.toIntOrNull()?.takeIf { it in MIN_DPI..MAX_DPI } ?: DEFAULT_DPI
        } else {
            DEFAULT_DPI
        }

    /** Head guides for [frame]: the preset's own, or the closest standard shape for a custom size. */
    fun guide(frame: PixelSize): HeadGuide =
        sizeChoice.preset?.guide ?: if (abs(frame.aspectRatio - 1.0) < SQUARE_TOLERANCE) Square2x2Guide else Icao35x45Guide

    /** The output size in pixels, or null after recording in [errors] why it can't be worked out. */
    fun frameSize(errors: MutableSet<OptionsError> = mutableSetOf()): PixelSize? {
        sizeChoice.preset?.let { return it.pixelSize() }

        val dpiValue = customDpi.toIntOrNull()
        if (customUnit != CustomUnit.Pixels && (dpiValue == null || dpiValue !in MIN_DPI..MAX_DPI)) {
            errors += OptionsError.DpiInvalid
            return null
        }
        val width = customWidth.toFloatOrNull()
        val height = customHeight.toFloatOrNull()
        if (width == null || height == null || width <= 0f || height <= 0f) {
            errors += OptionsError.DimensionsMissing
            return null
        }
        val (pixelWidth, pixelHeight) = when (customUnit) {
            CustomUnit.Millimetres -> millimetresToPixels(width, dpiValue!!) to millimetresToPixels(height, dpiValue)
            CustomUnit.Inches -> (width * dpiValue!!).roundToInt() to (height * dpiValue).roundToInt()
            CustomUnit.Pixels -> width.roundToInt() to height.roundToInt()
        }
        return when {
            pixelWidth < TargetInput.MIN_EDGE || pixelHeight < TargetInput.MIN_EDGE -> {
                errors += OptionsError.DimensionsTooSmall
                null
            }
            pixelWidth > MAX_PHOTO_EDGE || pixelHeight > MAX_PHOTO_EDGE -> {
                errors += OptionsError.DimensionsTooLarge
                null
            }
            else -> PixelSize(pixelWidth, pixelHeight)
        }
    }

    fun validate(): OptionsValidation {
        val errors = mutableSetOf<OptionsError>()
        val frame = frameSize(errors)
        val maxBytes: Long? = when (sizePreset) {
            PhotoKbPreset.NoLimit -> NO_LIMIT_BYTES
            PhotoKbPreset.Custom -> TargetInput.kilobytes(customKb, errors)?.let { it * TargetInput.BYTES_PER_KB }
            else -> sizePreset.kilobytes?.let { it * TargetInput.BYTES_PER_KB }
        }
        if (errors.isNotEmpty() || frame == null || maxBytes == null) return OptionsValidation.Invalid(errors)
        return OptionsValidation.Valid(
            // The frame is already the exact size, so the search only trades JPEG quality.
            SizeTarget(maxBytes = maxBytes, format = OutputFormat.Jpeg, exactSize = frame, allowDownscale = false),
        )
    }

    companion object {
        const val DEFAULT_CUSTOM_COLOR = 0xFF9FC3E8.toInt()
        const val DEFAULT_BRUSH_SIZE = 0.4f
        const val MIN_DPI = 72
        const val MAX_DPI = 1200
        const val MAX_PHOTO_EDGE = 4000
        const val NO_LIMIT_BYTES = 50_000_000L
        private const val SQUARE_TOLERANCE = 0.1
    }
}

enum class PassportStep { Edit, Result, Sheet, SheetResult }

@Serializable
data class PassportSource(val path: String, val displayName: String)

@Serializable
data class PassportPhotoResult(
    val path: String,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val dpi: Int,
    /** Null when the user chose no size limit. */
    val maxBytes: Long?,
    val quality: Int?,
    val savedUri: String? = null,
    val savedName: String? = null,
) {
    val size: PixelSize get() = PixelSize(width, height)
}

@Serializable
data class SheetResult(
    val path: String,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val count: Int,
    val format: SheetFormat,
    /** A JPEG of the sheet for showing on screen; the file itself when the sheet is a JPEG. */
    val previewPath: String,
    val savedUri: String? = null,
    val savedName: String? = null,
) {
    val size: PixelSize get() = PixelSize(width, height)
}

/**
 * What survives process death. The model's mask and the touch-ups are kept as files in the
 * workspace (they're too big for saved state); the decoded photo is rebuilt from [source].
 */
@Serializable
data class PassportSession(
    val workspace: String? = null,
    val source: PassportSource? = null,
    val pendingCapture: String? = null,
    val placement: Placement? = null,
    val mode: EditorMode = EditorMode.Position,
    val options: PassportOptions = PassportOptions(),
    val step: PassportStep = PassportStep.Edit,
    val photo: PassportPhotoResult? = null,
    val sheet: SheetResult? = null,
)

fun passportFileName(sizeBytes: Long, frame: PixelSize): String {
    val size = if (sizeBytes < TargetInput.BYTES_PER_KB) "${sizeBytes}B" else "${sizeBytes / TargetInput.BYTES_PER_KB}KB"
    return "Passport_${frame.width}x${frame.height}_$size.jpg"
}

fun sheetFileName(count: Int, format: SheetFormat): String = "Passport_sheet_${count}_photos.${format.extension}"
