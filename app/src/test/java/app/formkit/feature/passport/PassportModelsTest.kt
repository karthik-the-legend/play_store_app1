package app.formkit.feature.passport

import app.formkit.core.imaging.OptionsError
import app.formkit.core.imaging.OptionsValidation
import app.formkit.core.imaging.OutputFormat
import app.formkit.core.imaging.PixelSize
import app.formkit.core.imaging.SizeTarget
import app.formkit.core.imaging.passport.BrushMode
import app.formkit.core.imaging.passport.Icao35x45Guide
import app.formkit.core.imaging.passport.Placement
import app.formkit.core.imaging.passport.Square2x2Guide
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class PassportModelsTest {

    @Test
    fun `the default is an Indian passport photo with no size limit`() {
        val target = valid(PassportOptions())

        assertEquals(PixelSize(413, 531), target.exactSize)
        assertEquals(OutputFormat.Jpeg, target.format)
        assertFalse(target.allowDownscale)
        assertEquals(PassportOptions.NO_LIMIT_BYTES, target.maxBytes)
        assertEquals(300, PassportOptions().dpi)
    }

    @Test
    fun `a KB limit is applied to the exact photo size`() {
        val target = valid(PassportOptions(sizeChoice = PhotoSizeChoice.UnitedStates, sizePreset = PhotoKbPreset.Kb50))

        assertEquals(PixelSize(600, 600), target.exactSize)
        assertEquals(50_000L, target.maxBytes)
    }

    @Test
    fun `custom sizes convert from millimetres, inches and pixels`() {
        val custom = PassportOptions(sizeChoice = PhotoSizeChoice.Custom)

        assertEquals(PixelSize(413, 531), custom.copy(customWidth = "35", customHeight = "45").frameSize())
        assertEquals(PixelSize(600, 600), custom.copy(customWidth = "2", customHeight = "2", customUnit = CustomUnit.Inches).frameSize())
        assertEquals(PixelSize(276, 354), custom.copy(customWidth = "35", customHeight = "45", customDpi = "200").frameSize())
        assertEquals(PixelSize(200, 230), custom.copy(customWidth = "200", customHeight = "230", customUnit = CustomUnit.Pixels, customDpi = "").frameSize())
        assertEquals(200, custom.copy(customWidth = "35", customHeight = "45", customDpi = "200").dpi)
    }

    @Test
    fun `bad custom sizes are reported`() {
        val custom = PassportOptions(sizeChoice = PhotoSizeChoice.Custom)

        assertEquals(setOf(OptionsError.DimensionsMissing), errors(custom))
        assertEquals(setOf(OptionsError.DpiInvalid), errors(custom.copy(customWidth = "35", customHeight = "45", customDpi = "20")))
        assertEquals(setOf(OptionsError.DimensionsTooSmall), errors(custom.copy(customWidth = "1", customHeight = "45")))
        assertEquals(setOf(OptionsError.DimensionsTooLarge), errors(custom.copy(customWidth = "500", customHeight = "45")))
    }

    @Test
    fun `custom sizes get the closest standard head guide`() {
        val custom = PassportOptions(sizeChoice = PhotoSizeChoice.Custom)

        assertEquals(Square2x2Guide, custom.guide(PixelSize(600, 620)))
        assertEquals(Icao35x45Guide, custom.guide(PixelSize(413, 531)))
    }

    @Test
    fun `the background is the chosen swatch or the custom colour`() {
        assertEquals(0xFFFFFFFF.toInt(), PassportOptions().backgroundColor)
        assertEquals(0xFF123456.toInt(), PassportOptions(backdrop = Backdrop.Custom, customColor = 0xFF123456.toInt()).backgroundColor)
    }

    @Test
    fun `a session survives being saved and restored`() {
        val session = PassportSession(
            workspace = "/cache/passport-1",
            source = PassportSource("/cache/passport-1/picked/source", "IMG.jpg"),
            placement = Placement(812.5f, 640f, 1180f),
            mode = EditorMode.TouchUp,
            options = PassportOptions(backdrop = Backdrop.LightBlue, brushMode = BrushMode.Restore, sheetCount = 6, sheetFormat = SheetFormat.Pdf),
            step = PassportStep.Sheet,
            photo = PassportPhotoResult("/cache/passport-1/photo-1.jpg", 48_000, 413, 531, 300, 50_000, 87),
        )

        val json = Json.encodeToString(PassportSession.serializer(), session)

        assertEquals(session, Json.decodeFromString(PassportSession.serializer(), json))
    }

    @Test
    fun `file names describe the output`() {
        assertEquals("Passport_413x531_48KB.jpg", passportFileName(48_900, PixelSize(413, 531)))
        assertEquals("Passport_sheet_8_photos.pdf", sheetFileName(8, SheetFormat.Pdf))
        assertNull(PassportOptions().sheetCount)
    }

    private fun valid(options: PassportOptions): SizeTarget =
        (options.validate() as? OptionsValidation.Valid)?.target
            ?: throw AssertionError("Expected valid options but got ${options.validate()}")

    private fun errors(options: PassportOptions): Set<OptionsError> =
        (options.validate() as? OptionsValidation.Invalid)?.errors
            ?: throw AssertionError("Expected errors but options were valid")
}
