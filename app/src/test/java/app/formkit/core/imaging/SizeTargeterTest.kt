package app.formkit.core.imaging

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SizeTargeterTest {

    // With the default fake: bytes = 600 + pixels × (0.02 + 0.004 × quality).

    @Test
    fun `keeping dimensions lands on the highest quality under the limit`() = runTest {
        val codec = FakeCodec()
        val source = codec.image(1000, 1000)

        val fitted = fitted(SizeTargeter(codec).fit(source, SizeTarget(maxBytes = 200_000)))

        // 600 + 1,000,000 × (0.02 + 0.004q) ≤ 200,000  →  q ≤ 44.8
        assertEquals(44, fitted.quality)
        assertEquals(codec.bytesFor(PixelSize(1000, 1000), 44), fitted.bytes.size)
        assertTrue(codec.bytesFor(PixelSize(1000, 1000), 45) > 200_000)
        assertEquals(PixelSize(1000, 1000), fitted.size)
    }

    @Test
    fun `a generous limit keeps full quality in a handful of encodes`() = runTest {
        val codec = FakeCodec()
        val source = codec.image(1000, 1000)

        val fitted = fitted(SizeTargeter(codec).fit(source, SizeTarget(maxBytes = 10_000_000)))

        assertEquals(100, fitted.quality)
        // One check at the lowest quality, then the binary search.
        assertTrue("encodes=${codec.encodeCount}", codec.encodeCount <= SizeTargeter.MAX_QUALITY_STEPS + 1)
    }

    @Test
    fun `an unreachable limit is reported after a single encode`() = runTest {
        val codec = FakeCodec()
        val source = codec.image(4000, 3000)

        val outcome = SizeTargeter(codec).fit(source, SizeTarget(maxBytes = 20_000))

        assertTrue(outcome is TargetOutcome.TooLarge)
        assertEquals(1, codec.encodeCount)
    }

    @Test
    fun `exact dimensions are always honoured`() = runTest {
        val codec = FakeCodec()
        val source = codec.image(4000, 3000)
        val exact = PixelSize(413, 531)

        val fitted = fitted(SizeTargeter(codec).fit(source, SizeTarget(maxBytes = 50_000, exactSize = exact, allowDownscale = true)))

        assertEquals(exact, fitted.size)
        assertEquals(51, fitted.quality)
        assertTrue(fitted.bytes.size <= 50_000)
    }

    @Test
    fun `an impossible target at exact dimensions reports the smallest achievable size`() = runTest {
        val codec = FakeCodec()
        val source = codec.image(4000, 3000)
        val exact = PixelSize(2000, 2000)

        val outcome = SizeTargeter(codec).fit(source, SizeTarget(maxBytes = 20_000, exactSize = exact))

        // Quality 1: 600 + 4,000,000 × 0.024
        assertEquals(TargetOutcome.TooLarge(smallestBytes = 96_600, size = exact, canDownscale = false), outcome)
    }

    @Test
    fun `an impossible target while keeping dimensions offers downscaling`() = runTest {
        val codec = FakeCodec()
        val source = codec.image(1000, 1000)

        val outcome = SizeTargeter(codec).fit(source, SizeTarget(maxBytes = 20_000))

        assertEquals(TargetOutcome.TooLarge(smallestBytes = 24_600, size = PixelSize(1000, 1000), canDownscale = true), outcome)
    }

    @Test
    fun `downscaling takes a 12 MP photo to 20 KB quickly and lands just under`() = runTest {
        val codec = FakeCodec()
        val source = codec.image(4000, 3000)

        val fitted = fitted(SizeTargeter(codec).fit(source, SizeTarget(maxBytes = 20_000, allowDownscale = true)))

        assertTrue("bytes=${fitted.bytes.size}", fitted.bytes.size in 16_000..20_000)
        assertTrue(fitted.size.shortEdge >= SizeTargeter.MIN_SHORT_EDGE)
        assertTrue("quality=${fitted.quality}", fitted.quality!! >= SizeTargeter.DOWNSCALE_QUALITY_FLOOR)
        assertTrue("aspect ratio kept", kotlin.math.abs(fitted.size.aspectRatio - 4.0 / 3.0) < 0.02)
        assertTrue("encodes=${codec.encodeCount}", codec.encodeCount <= 20)
    }

    @Test
    fun `downscaling stops at a 100px short edge and reports what is achievable`() = runTest {
        val codec = FakeCodec()
        val source = codec.image(4000, 3000)

        val outcome = SizeTargeter(codec).fit(source, SizeTarget(maxBytes = 800, allowDownscale = true))

        // 133×100 at quality 1: 600 + 13,300 × 0.024 = 919
        assertEquals(TargetOutcome.TooLarge(smallestBytes = 919, size = PixelSize(133, 100), canDownscale = false), outcome)
    }

    @Test
    fun `a minimum the image cannot reach is reported as too small`() = runTest {
        val codec = FakeCodec()
        val source = codec.image(4000, 3000)
        val exact = PixelSize(140, 160)

        val outcome = SizeTargeter(codec).fit(source, SizeTarget(maxBytes = 50_000, minBytes = 20_000, exactSize = exact))

        // Quality 100: 600 + 22,400 × 0.42
        assertEquals(TargetOutcome.TooSmall(largestBytes = 10_008, size = exact), outcome)
    }

    @Test
    fun `png ignores quality so it is encoded once at fixed dimensions`() = runTest {
        val codec = FakeCodec()
        val source = codec.image(1000, 1000)
        val targeter = SizeTargeter(codec)

        val fits = targeter.fit(source, SizeTarget(maxBytes = 2_000_000, format = OutputFormat.Png))
        assertNull(fitted(fits).quality)
        assertEquals(1, codec.encodeCount)

        val tooBig = targeter.fit(source, SizeTarget(maxBytes = 1_000_000, format = OutputFormat.Png))
        assertEquals(TargetOutcome.TooLarge(1_500_600, PixelSize(1000, 1000), canDownscale = true), tooBig)
        assertEquals(2, codec.encodeCount)
    }

    @Test
    fun `png reaches a limit by downscaling`() = runTest {
        val codec = FakeCodec()
        val source = codec.image(1000, 1000)

        val fitted = fitted(SizeTargeter(codec).fit(source, SizeTarget(maxBytes = 200_000, format = OutputFormat.Png, allowDownscale = true)))

        assertTrue(fitted.bytes.size <= 200_000)
        assertTrue(fitted.size.width < 1000)
    }

    @Test
    fun `the result never exceeds the limit even when size jumps around with quality`() = runTest {
        // Odd qualities are 30% bigger than their neighbours, so size isn't monotonic in quality.
        val codec = FakeCodec(bytesPerPixel = { q -> (0.02 + 0.004 * q) * if (q % 2 == 1) 1.3 else 1.0 })
        val targeter = SizeTargeter(codec)

        for (maxBytes in listOf(30_000L, 75_000L, 150_000L, 222_222L, 333_333L)) {
            for (allowDownscale in listOf(false, true)) {
                val source = codec.image(1000, 800)
                val outcome = targeter.fit(source, SizeTarget(maxBytes = maxBytes, allowDownscale = allowDownscale))
                if (outcome is TargetOutcome.Fitted) {
                    assertTrue("max=$maxBytes got=${outcome.bytes.size}", outcome.bytes.size <= maxBytes)
                }
                codec.release(source)
            }
        }
    }

    @Test
    fun `when the first fit is far under the limit it grows once to get closer`() = runTest {
        // Small images cost 4× more per pixel than large ones, so the 640px probe underestimates
        // how many pixels fit and the first attempt lands well under the limit.
        val codec = FakeCodec(sizeMultiplier = { size -> if (size.pixels <= 307_200) 4.0 else 1.0 })
        val source = codec.image(4000, 3000)

        val fitted = fitted(SizeTargeter(codec).fit(source, SizeTarget(maxBytes = 1_000_000, allowDownscale = true)))

        assertTrue("bytes=${fitted.bytes.size}", fitted.bytes.size in 800_000..1_000_000)
        val firstAttempt = codec.encodedSizes.first { it != PixelSize(640, 480) }
        assertTrue("grew from $firstAttempt to ${fitted.size}", fitted.size.pixels > firstAttempt.pixels)
    }

    @Test
    fun `every intermediate image is released`() = runTest {
        val codec = FakeCodec()
        val source = codec.image(4000, 3000)

        SizeTargeter(codec).fit(source, SizeTarget(maxBytes = 20_000, allowDownscale = true))
        SizeTargeter(codec).fit(source, SizeTarget(maxBytes = 50_000, exactSize = PixelSize(413, 531)))

        assertEquals(setOf(source.id), codec.liveImages)
    }

    @Test
    fun `cancelling stops before the next encode`() = runTest {
        lateinit var job: Job
        val codec = FakeCodec(onEncode = { count -> if (count == 2) job.cancel() })
        val source = codec.image(1000, 1000)

        job = launch {
            SizeTargeter(codec).fit(source, SizeTarget(maxBytes = 200_000))
            fail("fit should have been cancelled")
        }
        job.join()

        assertTrue(job.isCancelled)
        assertEquals(2, codec.encodeCount)
    }

    private fun fitted(outcome: TargetOutcome): TargetOutcome.Fitted =
        outcome as? TargetOutcome.Fitted ?: throw AssertionError("Expected a fit but got $outcome")
}

class FakeImage(val id: Int, val size: PixelSize)

/** Byte sizes follow a formula, so every expected value in the tests can be worked out by hand. */
class FakeCodec(
    private val bytesPerPixel: (quality: Int) -> Double = { q -> 0.02 + 0.004 * q },
    private val pngBytesPerPixel: Double = 1.5,
    private val headerBytes: Int = 600,
    private val sizeMultiplier: (PixelSize) -> Double = { 1.0 },
    private val onEncode: (count: Int) -> Unit = {},
) : ImageCodec<FakeImage> {

    private var nextId = 0
    val liveImages = mutableSetOf<Int>()
    val encodedSizes = mutableListOf<PixelSize>()
    var encodeCount = 0
        private set

    fun image(width: Int, height: Int): FakeImage = create(PixelSize(width, height))

    fun bytesFor(size: PixelSize, quality: Int, format: OutputFormat = OutputFormat.Jpeg): Int {
        val perPixel = if (format == OutputFormat.Png) pngBytesPerPixel else bytesPerPixel(quality)
        return headerBytes + (size.pixels * perPixel * sizeMultiplier(size)).toInt()
    }

    override fun sizeOf(image: FakeImage): PixelSize = image.size

    override fun encode(image: FakeImage, format: OutputFormat, quality: Int): ByteArray {
        check(image.id in liveImages) { "Encoding a released image" }
        encodeCount++
        encodedSizes += image.size
        onEncode(encodeCount)
        return ByteArray(bytesFor(image.size, quality, format))
    }

    override fun scale(image: FakeImage, size: PixelSize): FakeImage {
        check(image.id in liveImages) { "Scaling a released image" }
        return create(size)
    }

    override fun release(image: FakeImage) {
        check(liveImages.remove(image.id)) { "Image ${image.id} released twice" }
    }

    private fun create(size: PixelSize) = FakeImage(nextId++, size).also { liveImages += it.id }
}
