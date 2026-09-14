package app.formkit.core.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Fixed colours rather than theme colours: this stands for "no pixels here" in both themes.
private val CheckerLight = Color(0xFFFFFFFF)
private val CheckerDark = Color(0xFFE3E3E8)

/** The grey-and-white pattern that marks transparent areas behind an image. */
fun Modifier.checkerboard(cell: Dp = 8.dp): Modifier = drawBehind {
    val cellPx = cell.toPx()
    drawRect(CheckerLight)
    var row = 0
    var y = 0f
    while (y < size.height) {
        var x = if (row % 2 == 0) 0f else cellPx
        while (x < size.width) {
            drawRect(CheckerDark, Offset(x, y), Size(cellPx, cellPx))
            x += cellPx * 2
        }
        y += cellPx
        row++
    }
}
