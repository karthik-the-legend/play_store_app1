package app.formkit.core.pdf

import kotlin.math.max

/**
 * Shares a PDF's byte limit between its pages. Some bytes are held back for the PDF's own
 * structure; every page gets at least its floor (the smallest it can be encoded), and what's left
 * goes to pages in proportion to their weight, so a page of photos gets more than a page of text.
 */
object ByteBudget {
    /** Header, catalogue, cross-reference table and trailer. */
    const val BASE_OVERHEAD_BYTES = 2_000L

    /** Page object, image dictionary and content stream, per page. */
    const val OVERHEAD_PER_PAGE_BYTES = 700L

    /** A JPEG's headers alone take about this much. */
    const val MIN_PAGE_BYTES = 700L

    fun overhead(pageCount: Int): Long = BASE_OVERHEAD_BYTES + OVERHEAD_PER_PAGE_BYTES * pageCount

    /** The smallest PDF these pages can make. */
    fun smallestTotal(floors: List<Long>): Long = overhead(floors.size) + floors.sumOf { max(it, MIN_PAGE_BYTES) }

    /**
     * Per-page limits that add up to no more than [totalBytes] minus the overhead, scaled by
     * [scale] (below 1 after a rebuilt PDF came out over). Null if the floors alone don't fit.
     */
    fun split(totalBytes: Long, weights: List<Long>, floors: List<Long>, scale: Double = 1.0): List<Long>? {
        require(weights.isNotEmpty() && weights.size == floors.size) { "One weight and one floor per page" }
        require(scale > 0.0 && scale <= 1.0) { "scale must be in (0, 1]" }
        val available = ((totalBytes - overhead(weights.size)) * scale).toLong()
        val pageFloors = floors.map { max(it, MIN_PAGE_BYTES) }
        val floorTotal = pageFloors.sum()
        if (available < floorTotal) return null
        val extra = available - floorTotal
        val safeWeights = weights.map { max(it, 1L) }
        val weightTotal = safeWeights.sum().toDouble()
        return safeWeights.indices.map { page -> pageFloors[page] + (extra * (safeWeights[page] / weightTotal)).toLong() }
    }
}
