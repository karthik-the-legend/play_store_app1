package app.formkit.core.pdf

import app.formkit.core.imaging.PixelSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ImageDownsamplingTest {

    @Test
    fun `a full-page photo gets the pixels the page needs at that density`() {
        // A 4000 x 3000 camera photo shown 8 x 6 inches (576 x 432 points); at 150 DPI it needs 1200 x 900.
        val target = ImageDownsampling.targetSize(PixelSize(4000, 3000), 576f, 432f, dpi = 150)

        assertEquals(PixelSize(1200, 900), target)
    }

    @Test
    fun `a photo drawn small on the page needs fewer pixels`() {
        // The same photo as a 2-inch-wide thumbnail.
        val target = ImageDownsampling.targetSize(PixelSize(4000, 3000), 144f, 108f, dpi = 150)

        assertEquals(PixelSize(300, 225), target)
    }

    @Test
    fun `the shape is kept even when the photo is stretched on the page`() {
        // Drawn twice as wide as its own shape: the width decides, and the height follows the photo's shape.
        val target = ImageDownsampling.targetSize(PixelSize(2000, 2000), 288f, 144f, dpi = 100)

        assertEquals(400, target.width)
        assertEquals(400, target.height)
    }

    @Test
    fun `a photo that already has about the right pixels is left at its size`() {
        val image = PixelSize(1250, 900)

        // Needs 1200 x 864: within 5%, not worth resampling.
        assertSame(image, ImageDownsampling.targetSize(image, 576f, 414.72f, dpi = 150))
    }

    @Test
    fun `never enlarges`() {
        val image = PixelSize(300, 200)

        assertSame(image, ImageDownsampling.targetSize(image, 576f, 384f, dpi = 300))
    }

    @Test
    fun `decoding halves only while the result still covers the target`() {
        assertEquals(4, ImageDownsampling.subsampling(PixelSize(4000, 3000), PixelSize(1000, 750)))
        assertEquals(2, ImageDownsampling.subsampling(PixelSize(4000, 3000), PixelSize(1200, 900)))
        assertEquals(1, ImageDownsampling.subsampling(PixelSize(4000, 3000), PixelSize(2100, 1575)))
    }
}
