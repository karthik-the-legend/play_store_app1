package app.formkit.feature.pdf.common

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/**
 * A column whose items can be dragged into a new order by a handle. [itemContent] receives the
 * [Modifier] to put on its drag handle. Moves are reported one step at a time through [onMove],
 * so the list the caller holds is always the truth. Meant for short lists (up to ~50 items).
 */
@Composable
fun <T> ReorderableColumn(
    items: List<T>,
    key: (T) -> Any,
    onMove: (from: Int, to: Int) -> Unit,
    modifier: Modifier = Modifier,
    spacing: Dp = 8.dp,
    itemContent: @Composable (item: T, index: Int, handle: Modifier, isDragging: Boolean) -> Unit,
) {
    var draggingKey by remember { mutableStateOf<Any?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val heights = remember { mutableStateMapOf<Any, Int>() }
    val latestItems by rememberUpdatedState(items)
    val latestOnMove by rememberUpdatedState(onMove)
    val spacingPx = with(LocalDensity.current) { spacing.toPx() }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(spacing)) {
        items.forEachIndexed { index, item ->
            val itemKey = key(item)
            key(itemKey) {
                val dragging = draggingKey == itemKey
                val handle = Modifier.pointerInput(itemKey) {
                    detectDragGestures(
                        onDragStart = {
                            draggingKey = itemKey
                            dragOffset = 0f
                        },
                        onDragEnd = {
                            draggingKey = null
                            dragOffset = 0f
                        },
                        onDragCancel = {
                            draggingKey = null
                            dragOffset = 0f
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            dragOffset += amount.y
                            val current = latestItems.indexOfFirst { key(it) == itemKey }
                            if (current >= 0) {
                                val next = latestItems.getOrNull(current + 1)?.let { heights[key(it)] }
                                val previous = latestItems.getOrNull(current - 1)?.let { heights[key(it)] }
                                if (dragOffset > 0 && next != null && dragOffset > (next + spacingPx) / 2) {
                                    latestOnMove(current, current + 1)
                                    dragOffset -= next + spacingPx
                                } else if (dragOffset < 0 && previous != null && -dragOffset > (previous + spacingPx) / 2) {
                                    latestOnMove(current, current - 1)
                                    dragOffset += previous + spacingPx
                                }
                            }
                        },
                    )
                }
                Box(
                    modifier = Modifier
                        .onSizeChanged { heights[itemKey] = it.height }
                        .zIndex(if (dragging) 1f else 0f)
                        .graphicsLayer { translationY = if (dragging) dragOffset else 0f }
                        .then(if (dragging) Modifier.shadow(6.dp) else Modifier),
                ) {
                    itemContent(item, index, handle, dragging)
                }
            }
        }
    }
}
