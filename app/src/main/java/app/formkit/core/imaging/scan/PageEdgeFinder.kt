package app.formkit.core.imaging.scan

import app.formkit.core.imaging.PixelSize
import app.formkit.core.imaging.signature.GrayImage
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class EdgeFinding(
    val corners: PageCorners,
    /** From 0 to 1: how much of the outline's weakest side lies along a real edge in the photo. */
    val confidence: Float,
)

/**
 * Looks for a document's outline in a small greyscale photo (about [WORKING_LONG_EDGE] pixels on
 * its long side). Returns nothing rather than a guess: the user then places the corners by hand.
 *
 * 1. A light blur hides paper grain and JPEG noise. Edges are found with a Sobel filter and thinned
 *    to one pixel, and each edge pixel keeps the direction it faces.
 * 2. A Hough transform: every edge pixel votes for the straight lines through it that face about
 *    the same way it does. Long straight edges, like a page's sides, collect the most votes, even
 *    where a finger or shadow breaks them. Each winning line is then fitted exactly to its pixels.
 * 3. Two roughly parallel lines crossed with two more make a candidate outline. Opposite sides
 *    may lean towards each other, because a page photographed at an angle is a trapezoid.
 * 4. Every candidate is checked side by side: how much of each side runs along an edge facing
 *    the right way, and whether the inside is lighter (or darker) than the outside all round. A box
 *    printed on the page has paper on both sides of its lines, so it can't pass for the page.
 * 5. The large, well-supported outline wins if even its weakest side is convincing.
 */
object PageEdgeFinder {
    const val WORKING_LONG_EDGE = 480

    fun find(image: GrayImage): EdgeFinding? {
        val width = image.width
        val height = image.height
        if (min(width, height) < MIN_IMAGE_EDGE) return null
        val smooth = boxBlur(boxBlur(image.pixels, width, height, 2), width, height, 1)
        val edges = EdgeMap.of(smooth, width, height)
        if (edges.pixels.size < MIN_EDGE_PIXELS) return null
        val lines = strongestLines(edges, width, height).map { refine(it, edges, width) }
        if (lines.size < 4) return null
        val directional = directionalEdges(edges, width, height)
        return bestOutline(lines, directional, smooth, width, height)
    }

    /** Edge pixels one pixel wide, each with the direction its brightness changes in (0–179°). */
    private class EdgeMap(val angles: IntArray, val pixels: IntArray) {
        companion object {
            fun of(image: IntArray, width: Int, height: Int): EdgeMap {
                val magnitude = IntArray(width * height)
                val gradientX = IntArray(width * height)
                val gradientY = IntArray(width * height)
                for (y in 1 until height - 1) {
                    for (x in 1 until width - 1) {
                        val i = y * width + x
                        val topLeft = image[i - width - 1]
                        val top = image[i - width]
                        val topRight = image[i - width + 1]
                        val bottomLeft = image[i + width - 1]
                        val bottom = image[i + width]
                        val bottomRight = image[i + width + 1]
                        val gx = (topRight + 2 * image[i + 1] + bottomRight) - (topLeft + 2 * image[i - 1] + bottomLeft)
                        val gy = (bottomLeft + 2 * bottom + bottomRight) - (topLeft + 2 * top + topRight)
                        gradientX[i] = gx
                        gradientY[i] = gy
                        magnitude[i] = abs(gx) + abs(gy)
                    }
                }

                val angles = IntArray(width * height) { -1 }
                val pixels = ArrayList<Int>()
                for (y in 2 until height - 2) {
                    for (x in 2 until width - 2) {
                        val i = y * width + x
                        val strength = magnitude[i]
                        if (strength < MIN_GRADIENT) continue
                        val gx = gradientX[i]
                        val gy = gradientY[i]
                        // Keep only the strongest pixel across the edge.
                        val across = when {
                            abs(gy) * 2 < abs(gx) -> 1
                            abs(gx) * 2 < abs(gy) -> width
                            (gx > 0) == (gy > 0) -> width + 1
                            else -> width - 1
                        }
                        if (strength < magnitude[i - across] || strength <= magnitude[i + across]) continue
                        var degrees = (atan2(gy.toDouble(), gx.toDouble()) * DEGREES_PER_RADIAN).roundToInt()
                        if (degrees < 0) degrees += 180
                        if (degrees >= 180) degrees -= 180
                        angles[i] = degrees
                        pixels += i
                    }
                }
                return EdgeMap(angles, pixels.toIntArray())
            }
        }
    }

    /** The line x·cos(θ) + y·sin(θ) = ρ, with θ in degrees and pixel centres at half-pixel positions. */
    private class Line(val degrees: Double, val rho: Double, val votes: Int) {
        val cos = cos(degrees / DEGREES_PER_RADIAN)
        val sin = sin(degrees / DEGREES_PER_RADIAN)
    }

    private fun strongestLines(edges: EdgeMap, width: Int, height: Int): List<Line> {
        val rhoMax = ceil(hypot(width.toDouble(), height.toDouble())).toInt() + 1
        val rhoBins = 2 * rhoMax + 1
        val votes = IntArray(ANGLES * rhoBins)
        for (index in edges.pixels) {
            val x = index % width + 0.5
            val y = index / width + 0.5
            val angle = edges.angles[index]
            for (spread in -VOTE_SPREAD..VOTE_SPREAD) {
                val theta = (angle + spread).mod(ANGLES)
                val rho = (x * COS[theta] + y * SIN[theta]).roundToInt() + rhoMax
                votes[theta * rhoBins + rho]++
            }
        }

        val minVotes = max(MIN_LINE_VOTES, (min(width, height) * MIN_LINE_FRACTION).roundToInt())
        val peaks = ArrayList<Line>()
        for (theta in 0 until ANGLES) {
            for (rhoBin in 0 until rhoBins) {
                val count = votes[theta * rhoBins + rhoBin]
                if (count >= minVotes && isPeak(votes, rhoBins, rhoMax, theta, rhoBin, count)) {
                    peaks += Line(theta.toDouble(), (rhoBin - rhoMax).toDouble(), count)
                }
            }
        }
        return peaks.sortedByDescending { it.votes }.take(MAX_LINES)
    }

    private fun isPeak(votes: IntArray, rhoBins: Int, rhoMax: Int, theta: Int, rhoBin: Int, count: Int): Boolean {
        for (dTheta in -PEAK_ANGLE_WINDOW..PEAK_ANGLE_WINDOW) {
            var otherTheta = theta + dTheta
            var rho = rhoBin - rhoMax
            // Past 0° or 180° the same line is described with the opposite sign.
            if (otherTheta < 0) {
                otherTheta += ANGLES
                rho = -rho
            } else if (otherTheta >= ANGLES) {
                otherTheta -= ANGLES
                rho = -rho
            }
            for (dRho in -PEAK_RHO_WINDOW..PEAK_RHO_WINDOW) {
                if (dTheta == 0 && dRho == 0) continue
                val otherBin = rho + dRho + rhoMax
                if (otherBin !in 0 until rhoBins) continue
                val other = votes[otherTheta * rhoBins + otherBin]
                // A flat top gives one line: ties go to the bin scanned first.
                val scannedFirst = dTheta < 0 || (dTheta == 0 && dRho < 0)
                if (other > count || (other == count && scannedFirst)) return false
            }
        }
        return true
    }

    /** Fits the line exactly through the edge pixels that voted for it (total least squares). */
    private fun refine(line: Line, edges: EdgeMap, width: Int): Line {
        var count = 0
        var sumX = 0.0
        var sumY = 0.0
        var sumXX = 0.0
        var sumYY = 0.0
        var sumXY = 0.0
        for (index in edges.pixels) {
            if (angleBetween(edges.angles[index].toDouble(), line.degrees) > REFINE_ANGLE) continue
            val x = index % width + 0.5
            val y = index / width + 0.5
            if (abs(x * line.cos + y * line.sin - line.rho) > REFINE_DISTANCE) continue
            count++
            sumX += x
            sumY += y
            sumXX += x * x
            sumYY += y * y
            sumXY += x * y
        }
        if (count < MIN_REFINE_PIXELS) return line
        val meanX = sumX / count
        val meanY = sumY / count
        val spreadXX = sumXX / count - meanX * meanX
        val spreadYY = sumYY / count - meanY * meanY
        val spreadXY = sumXY / count - meanX * meanY
        // The points spread furthest along the line; its normal is a quarter turn from that.
        val along = 0.5 * atan2(2 * spreadXY, spreadXX - spreadYY) * DEGREES_PER_RADIAN
        val normal = ((along + 90.0) % 180.0 + 180.0) % 180.0
        val radians = normal / DEGREES_PER_RADIAN
        return Line(normal, meanX * cos(radians) + meanY * sin(radians), line.votes)
    }

    /**
     * One bit per direction (eight steps of 22.5°) for each pixel, set when an edge facing about
     * that way is within [SUPPORT_RADIUS] pixels. Checking a candidate's side is then one lookup
     * per point.
     */
    private fun directionalEdges(edges: EdgeMap, width: Int, height: Int): IntArray {
        val bits = IntArray(width * height)
        for (index in edges.pixels) {
            val position = edges.angles[index] / DIRECTION_STEP
            val bin = position.toInt() % DIRECTIONS
            val nearest = if (position - position.toInt() >= 0.5) (bin + 1) % DIRECTIONS else (bin + DIRECTIONS - 1) % DIRECTIONS
            bits[index] = (1 shl bin) or (1 shl nearest)
        }
        val across = IntArray(bits.size)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                var merged = 0
                for (k in max(0, x - SUPPORT_RADIUS)..min(width - 1, x + SUPPORT_RADIUS)) merged = merged or bits[row + k]
                across[row + x] = merged
            }
        }
        val result = IntArray(bits.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var merged = 0
                for (k in max(0, y - SUPPORT_RADIUS)..min(height - 1, y + SUPPORT_RADIUS)) merged = merged or across[k * width + x]
                result[y * width + x] = merged
            }
        }
        return result
    }

    private class Candidate(val finding: EdgeFinding, val score: Double)

    private fun bestOutline(lines: List<Line>, directional: IntArray, smooth: IntArray, width: Int, height: Int): EdgeFinding? {
        val minSeparation = min(width, height) * MIN_SIDE_FRACTION
        val pairs = ArrayList<Pair<Line, Line>>()
        for (i in lines.indices) {
            for (j in i + 1 until lines.size) {
                val a = lines[i]
                val b = lines[j]
                if (angleBetween(a.degrees, b.degrees) <= PARALLEL_TOLERANCE && separation(a, b) >= minSeparation) pairs += a to b
            }
        }

        val xs = DoubleArray(4)
        val ys = DoubleArray(4)
        var best: Candidate? = null
        for (i in pairs.indices) {
            for (j in i + 1 until pairs.size) {
                val (a1, a2) = pairs[i]
                val (b1, b2) = pairs[j]
                if (angleBetween(a1.degrees, b1.degrees) < MIN_CROSSING_ANGLE) continue
                // Going round the outline: a1 meets b1, then b2; a2 meets b2, then b1.
                if (!intersect(a1, b1, xs, ys, 0) || !intersect(a1, b2, xs, ys, 1) ||
                    !intersect(a2, b2, xs, ys, 2) || !intersect(a2, b1, xs, ys, 3)
                ) {
                    continue
                }
                if ((0 until 4).any { !onPhoto(xs[it], ys[it], width, height) }) continue
                val candidate = judge(xs, ys, directional, smooth, width, height) ?: continue
                if (best == null || candidate.score > best.score) best = candidate
            }
        }
        return best?.finding
    }

    private fun judge(xs: DoubleArray, ys: DoubleArray, directional: IntArray, smooth: IntArray, width: Int, height: Int): Candidate? {
        val photo = PixelSize(width, height)
        val corners = PageCorners.fromPixels(xs, ys, photo)
        if (!corners.isUsable(photo)) return null
        val p = corners.toPixels(photo)
        val area = polygonArea(p) / (width.toDouble() * height)
        if (area < MIN_AREA_FRACTION || !cornersAreSquareEnough(p)) return null

        val centreX = (p[0] + p[2] + p[4] + p[6]) / 4.0
        val centreY = (p[1] + p[3] + p[5] + p[7]) / 4.0
        var weakest = 1.0
        var total = 0.0
        var insideLighter: Boolean? = null
        for (side in 0 until 4) {
            val next = (side + 1) % 4
            val evidence = sideEvidence(
                p[2 * side].toDouble(), p[2 * side + 1].toDouble(), p[2 * next].toDouble(), p[2 * next + 1].toDouble(),
                centreX, centreY, directional, smooth, width, height,
            )
            if (abs(evidence.contrast) < MIN_CONTRAST) return null
            val lighter = evidence.contrast > 0
            if (insideLighter != null && lighter != insideLighter) return null
            insideLighter = lighter
            weakest = min(weakest, evidence.support)
            total += evidence.support
        }
        val mean = total / 4
        if (weakest < MIN_SIDE_SUPPORT || mean < MIN_MEAN_SUPPORT) return null
        return Candidate(EdgeFinding(corners, weakest.toFloat()), weakest * mean * sqrt(area))
    }

    private class SideEvidence(val support: Double, val contrast: Double)

    /**
     * How much of the side from (x0, y0) to (x1, y1) lies on an edge facing across it, and how much
     * lighter the inside (towards the centre) is than the outside, on average.
     */
    private fun sideEvidence(
        x0: Double,
        y0: Double,
        x1: Double,
        y1: Double,
        centreX: Double,
        centreY: Double,
        directional: IntArray,
        smooth: IntArray,
        width: Int,
        height: Int,
    ): SideEvidence {
        val length = hypot(x1 - x0, y1 - y0)
        var normalX = -(y1 - y0) / length
        var normalY = (x1 - x0) / length
        if ((centreX - (x0 + x1) / 2) * normalX + (centreY - (y0 + y1) / 2) * normalY < 0) {
            normalX = -normalX
            normalY = -normalY
        }
        var normalDegrees = atan2(normalY, normalX) * DEGREES_PER_RADIAN
        if (normalDegrees < 0) normalDegrees += 180.0
        val bit = 1 shl ((normalDegrees / DIRECTION_STEP).toInt() % DIRECTIONS)

        val samples = max(MIN_SAMPLES, (length * (1 - 2 * END_SKIP) / SAMPLE_SPACING).toInt())
        var hits = 0
        var contrastSum = 0L
        var contrastCount = 0
        for (k in 0 until samples) {
            val t = END_SKIP + (1 - 2 * END_SKIP) * (k + 0.5) / samples
            val x = x0 + (x1 - x0) * t
            val y = y0 + (y1 - y0) * t
            val column = x.toInt()
            val row = y.toInt()
            if (x >= 0 && y >= 0 && column < width && row < height && directional[row * width + column] and bit != 0) hits++
            val inside = brightnessAt(smooth, width, height, x + normalX * CONTRAST_OFFSET, y + normalY * CONTRAST_OFFSET)
            val outside = brightnessAt(smooth, width, height, x - normalX * CONTRAST_OFFSET, y - normalY * CONTRAST_OFFSET)
            if (inside >= 0 && outside >= 0) {
                contrastSum += inside - outside
                contrastCount++
            }
        }
        return SideEvidence(hits.toDouble() / samples, if (contrastCount == 0) 0.0 else contrastSum.toDouble() / contrastCount)
    }

    /** No corner sharper than [MIN_CORNER_DEGREES] or flatter than 180° minus that. */
    private fun cornersAreSquareEnough(p: FloatArray): Boolean {
        for (i in 0 until 4) {
            val previous = (i + 3) % 4
            val next = (i + 1) % 4
            val ax = (p[2 * previous] - p[2 * i]).toDouble()
            val ay = (p[2 * previous + 1] - p[2 * i + 1]).toDouble()
            val bx = (p[2 * next] - p[2 * i]).toDouble()
            val by = (p[2 * next + 1] - p[2 * i + 1]).toDouble()
            val lengths = hypot(ax, ay) * hypot(bx, by)
            if (lengths <= 0.0) return false
            val degrees = acos(((ax * bx + ay * by) / lengths).coerceIn(-1.0, 1.0)) * DEGREES_PER_RADIAN
            if (degrees < MIN_CORNER_DEGREES || degrees > 180 - MIN_CORNER_DEGREES) return false
        }
        return true
    }

    private fun intersect(a: Line, b: Line, xs: DoubleArray, ys: DoubleArray, index: Int): Boolean {
        val determinant = a.cos * b.sin - a.sin * b.cos
        if (abs(determinant) < 1e-9) return false
        xs[index] = (a.rho * b.sin - b.rho * a.sin) / determinant
        ys[index] = (a.cos * b.rho - b.cos * a.rho) / determinant
        return true
    }

    private fun onPhoto(x: Double, y: Double, width: Int, height: Int): Boolean {
        val margin = max(width, height) * OUTSIDE_MARGIN
        return x >= -margin && y >= -margin && x <= width + margin && y <= height + margin
    }

    /** Distance between two nearly parallel lines. */
    private fun separation(a: Line, b: Line): Double {
        // Lines either side of 0°/180° describe nearly the same direction with opposite signs.
        val sameSign = abs(a.degrees - b.degrees) <= 90.0
        return abs(a.rho - if (sameSign) b.rho else -b.rho)
    }

    private fun angleBetween(a: Double, b: Double): Double {
        val difference = abs(a - b) % 180.0
        return min(difference, 180.0 - difference)
    }

    private fun brightnessAt(image: IntArray, width: Int, height: Int, x: Double, y: Double): Int {
        if (x < 0 || y < 0) return -1
        val column = x.toInt()
        val row = y.toInt()
        return if (column < width && row < height) image[row * width + column] else -1
    }

    private fun boxBlur(source: IntArray, width: Int, height: Int, radius: Int): IntArray {
        val size = 2 * radius + 1
        val across = IntArray(source.size)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                var sum = 0
                for (k in -radius..radius) sum += source[row + (x + k).coerceIn(0, width - 1)]
                across[row + x] = sum / size
            }
        }
        val result = IntArray(source.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var sum = 0
                for (k in -radius..radius) sum += across[(y + k).coerceIn(0, height - 1) * width + x]
                result[y * width + x] = sum / size
            }
        }
        return result
    }

    private const val DEGREES_PER_RADIAN = 180.0 / PI
    private const val ANGLES = 180
    private val COS = DoubleArray(ANGLES) { cos(it / DEGREES_PER_RADIAN) }
    private val SIN = DoubleArray(ANGLES) { sin(it / DEGREES_PER_RADIAN) }

    private const val MIN_IMAGE_EDGE = 48

    /** Sobel strength (|gx| + |gy|) an edge needs; a 30-level step between paper and table gives about 40. */
    private const val MIN_GRADIENT = 24
    private const val MIN_EDGE_PIXELS = 40

    /** Each edge pixel votes for lines within this many degrees of its own direction. */
    private const val VOTE_SPREAD = 8
    private const val MIN_LINE_VOTES = 20
    private const val MIN_LINE_FRACTION = 0.12
    private const val PEAK_ANGLE_WINDOW = 3
    private const val PEAK_RHO_WINDOW = 6
    private const val MAX_LINES = 16

    private const val REFINE_ANGLE = 15.0
    private const val REFINE_DISTANCE = 2.0
    private const val MIN_REFINE_PIXELS = 10

    private const val DIRECTIONS = 8
    private const val DIRECTION_STEP = 22.5
    private const val SUPPORT_RADIUS = 2

    /** Opposite sides of a page seen at an angle can lean this far towards each other. */
    private const val PARALLEL_TOLERANCE = 40.0
    private const val MIN_CROSSING_ANGLE = 45.0
    private const val MIN_SIDE_FRACTION = 0.2
    private const val OUTSIDE_MARGIN = 0.02
    private const val MIN_AREA_FRACTION = 0.15
    private const val MIN_CORNER_DEGREES = 35.0

    private const val MIN_SAMPLES = 12
    private const val SAMPLE_SPACING = 2.0

    /** Corners are rounded or dog-eared more often than sides, so the ends of each side don't count. */
    private const val END_SKIP = 0.06
    private const val CONTRAST_OFFSET = 4.0
    private const val MIN_CONTRAST = 6.0
    private const val MIN_SIDE_SUPPORT = 0.5
    private const val MIN_MEAN_SUPPORT = 0.65
}
