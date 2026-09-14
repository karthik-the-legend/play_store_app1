package app.formkit.feature.resize

import app.formkit.core.imaging.OptionsError
import app.formkit.core.imaging.OptionsValidation
import app.formkit.core.imaging.OutputFormat
import app.formkit.core.imaging.PixelSize
import app.formkit.core.imaging.SizeTarget
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResizeOptionsTest {

    @Test
    fun `defaults are 50 KB JPEG at original dimensions with downscaling allowed`() {
        val target = valid(ResizeOptions())

        assertEquals(50_000L, target.maxBytes)
        assertNull(target.minBytes)
        assertNull(target.exactSize)
        assertTrue(target.allowDownscale)
        assertEquals(OutputFormat.Jpeg, target.format)
    }

    @Test
    fun `sizes count 1000 bytes per KB`() {
        assertEquals(20_000L, valid(ResizeOptions(sizePreset = SizePreset.Kb20)).maxBytes)
        assertEquals(1_000_000L, valid(ResizeOptions(sizePreset = SizePreset.Mb1)).maxBytes)
    }

    @Test
    fun `a custom size comes from the text field and must be in range`() {
        assertEquals(35_000L, valid(ResizeOptions(sizePreset = SizePreset.Custom, customKb = "35")).maxBytes)
        assertEquals(setOf(OptionsError.CustomSizeMissing), errors(ResizeOptions(sizePreset = SizePreset.Custom, customKb = "")))
        assertEquals(setOf(OptionsError.CustomSizeTooSmall), errors(ResizeOptions(sizePreset = SizePreset.Custom, customKb = "4")))
        assertEquals(setOf(OptionsError.CustomSizeTooLarge), errors(ResizeOptions(sizePreset = SizePreset.Custom, customKb = "50001")))
    }

    @Test
    fun `exact dimensions switch downscaling off`() {
        val target = valid(ResizeOptions(dimensionPreset = DimensionPreset.P413x531, allowDownscale = true))

        assertEquals(PixelSize(413, 531), target.exactSize)
        assertFalse(target.allowDownscale)
    }

    @Test
    fun `custom dimensions must be complete and in range`() {
        val custom = ResizeOptions(dimensionPreset = DimensionPreset.Custom)

        assertEquals(PixelSize(600, 800), valid(custom.copy(customWidth = "600", customHeight = "800")).exactSize)
        assertEquals(setOf(OptionsError.DimensionsMissing), errors(custom.copy(customWidth = "600")))
        assertEquals(setOf(OptionsError.DimensionsTooSmall), errors(custom.copy(customWidth = "10", customHeight = "800")))
        assertEquals(setOf(OptionsError.DimensionsTooLarge), errors(custom.copy(customWidth = "9000", customHeight = "800")))
    }

    @Test
    fun `a minimum must be below the limit`() {
        val withMinimum = ResizeOptions(sizePreset = SizePreset.Kb20, showMinimum = true)

        assertEquals(10_000L, valid(withMinimum.copy(minKb = "10")).minBytes)
        assertEquals(setOf(OptionsError.MinimumAboveMaximum), errors(withMinimum.copy(minKb = "20")))
        assertEquals(setOf(OptionsError.MinimumTooSmall), errors(withMinimum.copy(minKb = "0")))
        assertNull("an empty minimum is optional", valid(withMinimum.copy(minKb = "")).minBytes)
    }

    @Test
    fun `a hidden minimum is ignored`() {
        assertNull(valid(ResizeOptions(showMinimum = false, minKb = "999")).minBytes)
    }

    @Test
    fun `export names keep the original name and show the size`() {
        assertEquals("IMG_2031_48KB.jpg", exportFileName("IMG_2031.HEIC", 48_213, OutputFormat.Jpeg))
        assertEquals("my_photo_1_120KB.png", exportFileName("my photo (1).png", 120_999, OutputFormat.Png))
        assertEquals("FormKit_900B.jpg", exportFileName(".jpg", 900, OutputFormat.Jpeg))
        assertEquals("picker IDs aren't names", "FormKit_19KB.jpg", exportFileName("19.jpg", 19_054, OutputFormat.Jpeg))
    }

    @Test
    fun `a session survives being saved and restored as JSON`() {
        val session = ResizeSession(
            workspace = "/cache/resize-1",
            source = SourceImage("/cache/resize-1/source", "IMG.jpg", 2_400_000, 4000, 3000),
            options = ResizeOptions(sizePreset = SizePreset.Custom, customKb = "35", dimensionPreset = DimensionPreset.P200x230),
            result = ResizeResult("/cache/resize-1/result-1.jpg", 34_900, 200, 230, 88, OutputFormat.Jpeg, 35_000, cropped = true),
        )

        val json = Json.encodeToString(ResizeSession.serializer(), session)

        assertEquals(session, Json.decodeFromString(ResizeSession.serializer(), json))
    }

    private fun valid(options: ResizeOptions): SizeTarget =
        (options.validate() as? OptionsValidation.Valid)?.target
            ?: throw AssertionError("Expected valid options but got ${options.validate()}")

    private fun errors(options: ResizeOptions): Set<OptionsError> =
        (options.validate() as? OptionsValidation.Invalid)?.errors
            ?: throw AssertionError("Expected errors but options were valid")
}
