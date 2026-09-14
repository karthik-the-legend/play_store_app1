package app.formkit.feature.signature

import app.formkit.core.imaging.OptionsError
import app.formkit.core.imaging.OptionsValidation
import app.formkit.core.imaging.OutputFormat
import app.formkit.core.imaging.PixelSize
import app.formkit.core.imaging.SizeTarget
import app.formkit.core.imaging.signature.InkBounds
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SignatureModelsTest {

    @Test
    fun `defaults make a 20 KB JPEG on white, sized to the signature`() {
        val target = valid(SignatureOptions())

        assertEquals(20_000L, target.maxBytes)
        assertEquals(OutputFormat.Jpeg, target.format)
        assertNull(target.exactSize)
        assertTrue(target.allowDownscale)
        assertNull(target.minBytes)
    }

    @Test
    fun `a transparent background is saved as PNG`() {
        assertEquals(OutputFormat.Png, valid(SignatureOptions(background = SignatureBackground.Transparent)).format)
    }

    @Test
    fun `no limit still gives the size search a ceiling`() {
        val options = SignatureOptions(sizePreset = SignatureSizePreset.NoLimit)

        assertEquals(SignatureOptions.NO_LIMIT_BYTES, valid(options).maxBytes)
        assertFalse(options.hasSizeLimit)
    }

    @Test
    fun `form presets set exact dimensions and stop downscaling`() {
        val target = valid(SignatureOptions(dimensionPreset = SignatureDimensionPreset.P140x60))

        assertEquals(PixelSize(140, 60), target.exactSize)
        assertFalse(target.allowDownscale)
    }

    @Test
    fun `custom size and dimensions are checked`() {
        assertEquals(setOf(OptionsError.CustomSizeMissing), errors(SignatureOptions(sizePreset = SignatureSizePreset.Custom)))
        assertEquals(
            setOf(OptionsError.DimensionsTooSmall),
            errors(SignatureOptions(dimensionPreset = SignatureDimensionPreset.Custom, customWidth = "10", customHeight = "60")),
        )
        assertEquals(PixelSize(300, 80), valid(SignatureOptions(dimensionPreset = SignatureDimensionPreset.Custom, customWidth = "300", customHeight = "80")).exactSize)
    }

    @Test
    fun `dragging a corner keeps the crop inside the image and not too small`() {
        val crop = CropRect(0.2f, 0.2f, 0.8f, 0.8f)

        val pulledOut = crop.dragged(CropHandle.TopLeft, dx = -0.5f, dy = -0.1f)
        assertEquals(0f, pulledOut.left, DELTA)
        assertEquals(0.1f, pulledOut.top, DELTA)

        val squashed = crop.dragged(CropHandle.BottomRight, dx = -1f, dy = -1f)
        assertEquals(0.25f, squashed.right, DELTA)
        assertEquals(0.25f, squashed.bottom, DELTA)
    }

    @Test
    fun `moving the crop keeps its size and stops at the edge`() {
        val moved = CropRect(0.2f, 0.2f, 0.8f, 0.6f).dragged(CropHandle.Move, dx = 0.5f, dy = -0.5f)

        assertEquals(0.4f, moved.left, DELTA)
        assertEquals(1f, moved.right, DELTA)
        assertEquals(0f, moved.top, DELTA)
        assertEquals(0.4f, moved.bottom, DELTA)
    }

    @Test
    fun `a crop converts to and from pixel bounds`() {
        val bounds = CropRect(0.25f, 0.5f, 0.75f, 1f).toBounds(400, 200)

        assertEquals(InkBounds(100, 100, 300, 200), bounds)
        assertEquals(CropRect(0.25f, 0.5f, 0.75f, 1f), CropRect.of(bounds, 400, 200))
    }

    @Test
    fun `stroke points skip tiny movements and are rounded`() {
        val points = mutableListOf<Float>()

        assertTrue(points.addStrokePoint(0.123456f, 0.5f))
        assertFalse("too close to the last point", points.addStrokePoint(0.1236f, 0.5f))
        assertTrue(points.addStrokePoint(0.2f, 0.5f))

        assertEquals(4, points.size)
        assertEquals(0.1235f, points[0], 1e-6f)
    }

    @Test
    fun `undo removes only the last stroke`() {
        val first = DrawnStroke(0.01f, listOf(0.1f, 0.1f, 0.2f, 0.2f))
        val second = DrawnStroke(0.01f, listOf(0.5f, 0.5f))

        assertEquals(Drawing(listOf(first)), (Drawing() + first + second).undo())
        assertTrue(Drawing().undo().isEmpty)
    }

    @Test
    fun `a session with a drawing survives being saved and restored`() {
        val session = SignatureSession(
            workspace = "/cache/signature-1",
            mode = SignatureMode.Draw,
            crop = CropRect(0.1f, 0.2f, 0.9f, 0.8f),
            drawing = Drawing(listOf(DrawnStroke(0.016f, listOf(0.1f, 0.5f, 0.3f, 0.4f)))),
            options = SignatureOptions(inkStrength = 0.7f, background = SignatureBackground.Transparent, dimensionPreset = SignatureDimensionPreset.P160x60),
            result = SignatureResult("/cache/signature-1/signature-1.png", 7_900, 160, 60, OutputFormat.Png, maxBytes = 20_000),
        )

        val json = Json.encodeToString(SignatureSession.serializer(), session)

        assertEquals(session, Json.decodeFromString(SignatureSession.serializer(), json))
    }

    @Test
    fun `signature file names show the size`() {
        assertEquals("Signature_7KB.png", signatureFileName(7_900, OutputFormat.Png))
        assertEquals("Signature_950B.jpg", signatureFileName(950, OutputFormat.Jpeg))
    }

    private fun valid(options: SignatureOptions): SizeTarget =
        (options.validate() as? OptionsValidation.Valid)?.target
            ?: throw AssertionError("Expected valid options but got ${options.validate()}")

    private fun errors(options: SignatureOptions): Set<OptionsError> =
        (options.validate() as? OptionsValidation.Invalid)?.errors
            ?: throw AssertionError("Expected errors but options were valid")

    private companion object {
        const val DELTA = 1e-5f
    }
}
