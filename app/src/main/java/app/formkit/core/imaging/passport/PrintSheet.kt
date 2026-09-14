package app.formkit.core.imaging.passport

import app.formkit.core.imaging.PixelSize
import kotlin.math.max

/** A photo's position on a print sheet, in sheet pixels. */
data class SheetSlot(val left: Int, val top: Int, val width: Int, val height: Int)

data class SheetLayout(
    val sheet: PixelSize,
    val slots: List<SheetSlot>,
    /** Empty border around the grid; 0 when the photos tile edge to edge. */
    val margin: Int,
    /** Space between photos; 0 when they share cut lines. */
    val gap: Int,
) {
    val landscape: Boolean get() = sheet.width > sheet.height
}

/**
 * Tiles passport photos onto a 4×6 inch sheet, the size photo shops and home printers take.
 * Photos keep their exact pixel size, so the sheet prints them at true size at 300 DPI.
 *
 * Photos get a margin and gaps when they fit that way. When they don't (six 2×2 inch photos
 * fill a 4×6 sheet exactly), they tile edge to edge and share cut lines, like a photo shop print.
 */
object PrintSheets {
    const val SHEET_SHORT_INCHES = 4
    const val SHEET_LONG_INCHES = 6

    private data class Spacing(val margin: Int, val gap: Int)

    private val ROOMY = Spacing(margin = 30, gap = 24)
    private val EDGE_TO_EDGE = Spacing(margin = 0, gap = 0)
    private val SPACINGS = listOf(ROOMY, EDGE_TO_EDGE)
    private val COMMON_COUNTS = listOf(1, 2, 4, 6, 8, 10, 12)

    fun sheetSize(dpi: Int = DEFAULT_DPI) = PixelSize(SHEET_SHORT_INCHES * dpi, SHEET_LONG_INCHES * dpi)

    /** The most photos that fit on one sheet. */
    fun capacity(photo: PixelSize, dpi: Int = DEFAULT_DPI): Int =
        SPACINGS.maxOf { spacing -> orientations(dpi).maxOf { sheet -> cellCount(photo, sheet, spacing) } }

    /** Counts to offer: the common ones that fit. */
    fun countOptions(photo: PixelSize, dpi: Int = DEFAULT_DPI): List<Int> {
        val capacity = capacity(photo, dpi)
        return COMMON_COUNTS.filter { it <= capacity }
    }

    fun defaultCount(photo: PixelSize, dpi: Int = DEFAULT_DPI): Int = countOptions(photo, dpi).lastOrNull() ?: 0

    /** Null if [count] photos don't fit on one sheet. */
    fun layout(photo: PixelSize, count: Int, dpi: Int = DEFAULT_DPI): SheetLayout? {
        require(count > 0) { "count must be positive" }
        for (spacing in SPACINGS) {
            // Portrait first on ties; otherwise whichever leaves fewer empty grid cells.
            val sheet = orientations(dpi)
                .filter { cellCount(photo, it, spacing) >= count }
                .minByOrNull { emptyCells(photo, it, spacing, count) }
                ?: continue
            return build(photo, sheet, spacing, count)
        }
        return null
    }

    private fun build(photo: PixelSize, sheet: PixelSize, spacing: Spacing, count: Int): SheetLayout {
        val (columns, _) = grid(photo, sheet, spacing)
        val usedColumns = minOf(columns, count)
        val usedRows = (count + usedColumns - 1) / usedColumns
        val blockWidth = usedColumns * photo.width + (usedColumns - 1) * spacing.gap
        val blockHeight = usedRows * photo.height + (usedRows - 1) * spacing.gap
        val startX = (sheet.width - blockWidth) / 2
        val startY = (sheet.height - blockHeight) / 2
        val slots = (0 until count).map { index ->
            SheetSlot(
                left = startX + (index % usedColumns) * (photo.width + spacing.gap),
                top = startY + (index / usedColumns) * (photo.height + spacing.gap),
                width = photo.width,
                height = photo.height,
            )
        }
        return SheetLayout(sheet, slots, spacing.margin, spacing.gap)
    }

    private fun emptyCells(photo: PixelSize, sheet: PixelSize, spacing: Spacing, count: Int): Int {
        val (columns, _) = grid(photo, sheet, spacing)
        val usedColumns = minOf(columns, count)
        val usedRows = (count + usedColumns - 1) / usedColumns
        return usedColumns * usedRows - count
    }

    private fun cellCount(photo: PixelSize, sheet: PixelSize, spacing: Spacing): Int =
        grid(photo, sheet, spacing).let { (columns, rows) -> columns * rows }

    private fun orientations(dpi: Int): List<PixelSize> {
        val portrait = sheetSize(dpi)
        return listOf(portrait, PixelSize(portrait.height, portrait.width))
    }

    private fun grid(photo: PixelSize, sheet: PixelSize, spacing: Spacing): Pair<Int, Int> {
        val columns = max(0, (sheet.width - 2 * spacing.margin + spacing.gap) / (photo.width + spacing.gap))
        val rows = max(0, (sheet.height - 2 * spacing.margin + spacing.gap) / (photo.height + spacing.gap))
        return columns to rows
    }
}

object JpegDensity {

    /**
     * Records [dpi] in the JPEG's JFIF header so print dialogs and photo apps know its true size.
     * The header is edited in place when it's there (same length, so a KB limit still holds);
     * otherwise an 18-byte JFIF header is inserted after the start marker.
     */
    fun withDpi(jpeg: ByteArray, dpi: Int): ByteArray {
        require(dpi in 1..65_535) { "dpi out of range" }
        require(jpeg.size >= 4 && jpeg[0] == 0xFF.toByte() && jpeg[1] == 0xD8.toByte()) { "Not a JPEG" }
        if (hasJfifHeader(jpeg)) {
            return jpeg.copyOf().also { writeDensity(it, dpi) }
        }
        val header = byteArrayOf(
            0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10,
            'J'.code.toByte(), 'F'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 0x00,
            0x01, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        )
        val result = ByteArray(jpeg.size + header.size)
        result[0] = jpeg[0]
        result[1] = jpeg[1]
        header.copyInto(result, destinationOffset = 2)
        jpeg.copyInto(result, destinationOffset = 2 + header.size, startIndex = 2)
        writeDensity(result, dpi)
        return result
    }

    fun readDpi(jpeg: ByteArray): Int? {
        if (!hasJfifHeader(jpeg) || jpeg[13].toInt() != 1) return null
        return ((jpeg[14].toInt() and 0xFF) shl 8) or (jpeg[15].toInt() and 0xFF)
    }

    private fun hasJfifHeader(jpeg: ByteArray): Boolean =
        jpeg.size >= 18 &&
            jpeg[2] == 0xFF.toByte() && jpeg[3] == 0xE0.toByte() &&
            jpeg[6] == 'J'.code.toByte() && jpeg[7] == 'F'.code.toByte() &&
            jpeg[8] == 'I'.code.toByte() && jpeg[9] == 'F'.code.toByte() && jpeg[10].toInt() == 0

    private fun writeDensity(jpeg: ByteArray, dpi: Int) {
        jpeg[13] = 1 // units: dots per inch
        jpeg[14] = (dpi shr 8).toByte()
        jpeg[15] = dpi.toByte()
        jpeg[16] = (dpi shr 8).toByte()
        jpeg[17] = dpi.toByte()
    }
}
