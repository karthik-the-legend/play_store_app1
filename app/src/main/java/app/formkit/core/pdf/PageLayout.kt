package app.formkit.core.pdf

import app.formkit.core.imaging.PixelSize
import kotlin.math.max
import kotlin.math.min

/** Paper sizes in points (1/72 inch). [FitToImage] makes each page the shape of its image. */
enum class PaperSize(val widthPoints: Float, val heightPoints: Float) {
    A4(595.28f, 841.89f),
    Letter(612f, 792f),
    FitToImage(0f, 0f),
}

enum class PageOrientation { Auto, Portrait, Landscape }

enum class PageMargin(val points: Float) {
    None(0f),
    Small(18f),
    Large(36f),
}

/** A rectangle in PDF coordinates: points, measured from the page's bottom-left corner. */
data class PointRect(val left: Float, val bottom: Float, val width: Float, val height: Float)

data class PagePlacement(val pageWidth: Float, val pageHeight: Float, val image: PointRect)

object PageLayout {
    const val POINTS_PER_INCH = 72f

    /** Fit-to-image pages show the image at this density, so a phone photo prints at a sensible size. */
    const val FIT_TO_IMAGE_DPI = 150f

    /** PDF viewers are only required to handle pages up to 200 inches. */
    const val MAX_PAGE_POINTS = 14_400f
    const val MIN_PAGE_POINTS = 72f

    /**
     * Where [image] goes on its page. On A4 and Letter it's scaled to fit inside the margins and
     * centred. [PageOrientation.Auto] turns the page sideways for a landscape image.
     */
    fun place(image: PixelSize, paper: PaperSize, orientation: PageOrientation, margin: PageMargin): PagePlacement {
        val inset = margin.points
        if (paper == PaperSize.FitToImage) {
            var width = image.width * POINTS_PER_INCH / FIT_TO_IMAGE_DPI
            var height = image.height * POINTS_PER_INCH / FIT_TO_IMAGE_DPI
            val shrink = min(1f, (MAX_PAGE_POINTS - 2 * inset) / max(width, height))
            width *= shrink
            height *= shrink
            val grow = max(1f, MIN_PAGE_POINTS / min(width, height))
            width = min(width * grow, MAX_PAGE_POINTS - 2 * inset)
            height = min(height * grow, MAX_PAGE_POINTS - 2 * inset)
            return PagePlacement(width + 2 * inset, height + 2 * inset, PointRect(inset, inset, width, height))
        }

        val landscape = when (orientation) {
            PageOrientation.Portrait -> false
            PageOrientation.Landscape -> true
            PageOrientation.Auto -> image.width > image.height
        }
        val pageWidth = if (landscape) paper.heightPoints else paper.widthPoints
        val pageHeight = if (landscape) paper.widthPoints else paper.heightPoints
        val scale = min((pageWidth - 2 * inset) / image.width, (pageHeight - 2 * inset) / image.height)
        val width = image.width * scale
        val height = image.height * scale
        return PagePlacement(
            pageWidth = pageWidth,
            pageHeight = pageHeight,
            image = PointRect((pageWidth - width) / 2f, (pageHeight - height) / 2f, width, height),
        )
    }
}
