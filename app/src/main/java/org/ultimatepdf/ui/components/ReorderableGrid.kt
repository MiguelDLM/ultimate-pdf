package org.ultimatepdf.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.grid.LazyGridItemInfo
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.toOffset
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.zIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Long-press-and-drag reordering for a LazyVerticalGrid. The dragged item follows the
 * finger; when its centre enters another item's cell, [onMove] moves it to that position.
 * Dropping past the last item (e.g. in the empty end of the last row) moves it to the end.
 * Dragging near the top or bottom edge auto-scrolls.
 *
 * The dragged item is tracked by key, and no new move is made until the grid has laid out
 * the previous one, so a stale layout can't trigger moves to the wrong place.
 */
class GridReorderState internal constructor(
    private val grid: LazyGridState,
    private val scope: CoroutineScope,
    private val onMove: (from: Int, to: Int) -> Unit,
    private val onDragEnd: () -> Unit
) {
    /** Grid content padding (start, top) in px: item offsets are measured from inside it. */
    internal var paddingStart = 0f
    internal var paddingTop = 0f

    var draggingKey by mutableStateOf<Any?>(null)
        private set

    /** Index the dragged item should have once the grid catches up with the last move. */
    private var expectedIndex = -1

    internal val scrollRequests = Channel<Float>(Channel.CONFLATED)
    private var initialOffset by mutableStateOf(Offset.Zero)
    private var dragDelta by mutableStateOf(Offset.Zero)

    private val draggingInfo: LazyGridItemInfo?
        get() = draggingKey?.let { key -> grid.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key } }

    /** Translation to apply to the dragged item so it stays under the finger. */
    val draggingOffset: Offset
        get() = draggingInfo?.let { initialOffset + dragDelta - it.offset.toOffset() } ?: Offset.Zero

    fun isDragging(key: Any) = draggingKey == key

    internal fun start(pointer: Offset): Boolean {
        val item = itemAt(toLayout(pointer)) ?: return false
        draggingKey = item.key
        expectedIndex = item.index
        initialOffset = item.offset.toOffset()
        dragDelta = Offset.Zero
        return true
    }

    internal fun drag(amount: Offset) {
        dragDelta += amount
        val dragged = draggingInfo ?: return
        val topLeft = dragged.offset.toOffset() + draggingOffset
        val center = topLeft + Offset(dragged.size.width / 2f, dragged.size.height / 2f)

        // The previous move isn't laid out yet: positions would be stale.
        if (dragged.index != expectedIndex) return

        val target = itemAt(center)?.takeIf { it.key != dragged.key } ?: endTarget(center, dragged)
        if (target != null) {
            // Moving the first visible item would make the list jump; pin the scroll position.
            if (dragged.index == grid.firstVisibleItemIndex || target.index == grid.firstVisibleItemIndex) {
                scope.launch { grid.scrollToItem(grid.firstVisibleItemIndex, grid.firstVisibleItemScrollOffset) }
            }
            onMove(dragged.index, target.index)
            expectedIndex = target.index
        } else {
            val bottom = topLeft.y + dragged.size.toSize().height
            val overscroll = when {
                dragDelta.y > 0 -> (bottom - grid.layoutInfo.viewportEndOffset).coerceAtLeast(0f)
                dragDelta.y < 0 -> (topLeft.y - grid.layoutInfo.viewportStartOffset).coerceAtMost(0f)
                else -> 0f
            }
            if (overscroll != 0f) scrollRequests.trySend(overscroll)
        }
    }

    internal fun stop() {
        if (draggingKey != null) onDragEnd()
        draggingKey = null
        expectedIndex = -1
        dragDelta = Offset.Zero
        initialOffset = Offset.Zero
    }

    /** The last item, when [center] is after it: right of it in its row, or below the last row. */
    private fun endTarget(center: Offset, dragged: LazyGridItemInfo): LazyGridItemInfo? {
        val info = grid.layoutInfo
        val last = info.visibleItemsInfo.lastOrNull() ?: return null
        if (last.index != info.totalItemsCount - 1 || last.key == dragged.key) return null
        val lastBottom = last.offset.y + last.size.height
        val afterInRow = center.y >= last.offset.y && center.y <= lastBottom && center.x > last.offset.x + last.size.width
        return last.takeIf { afterInRow || center.y > lastBottom }
    }

    /** Pointer positions are relative to the grid's edge; item offsets start inside its padding. */
    private fun toLayout(pointer: Offset) = Offset(pointer.x - paddingStart, pointer.y - paddingTop)

    private fun itemAt(position: Offset) = grid.layoutInfo.visibleItemsInfo.firstOrNull {
        position.x >= it.offset.x && position.x <= it.offset.x + it.size.width &&
            position.y >= it.offset.y && position.y <= it.offset.y + it.size.height
    }
}

@Composable
fun rememberGridReorderState(
    grid: LazyGridState,
    contentPadding: PaddingValues,
    onDragEnd: () -> Unit = {},
    onMove: (from: Int, to: Int) -> Unit
): GridReorderState {
    val scope = rememberCoroutineScope()
    val currentOnMove by rememberUpdatedState(onMove)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)
    val state = remember(grid) {
        GridReorderState(grid, scope, onMove = { from, to -> currentOnMove(from, to) }, onDragEnd = { currentOnDragEnd() })
    }
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    with(density) {
        state.paddingStart = contentPadding.calculateStartPadding(direction).toPx()
        state.paddingTop = contentPadding.calculateTopPadding().toPx()
    }
    LaunchedEffect(state) {
        for (delta in state.scrollRequests) grid.scrollBy(delta)
    }
    return state
}

/**
 * Attach to the LazyVerticalGrid itself. Events are read in the Initial pass, before the
 * grid's own scrolling: once a long press starts a drag, the gesture is claimed so the grid
 * can't turn it into a scroll, and the final "up" doesn't count as a tap on the page.
 */
@Composable
fun Modifier.reorderable(state: GridReorderState, enabled: Boolean = true): Modifier {
    if (!enabled) return this
    val haptics = LocalHapticFeedback.current
    return pointerInput(state) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var cancelled = false
            withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                while (!cancelled) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id }
                    cancelled = change == null || !change.pressed || event.changes.size > 1 ||
                        (change.position - down.position).getDistance() > viewConfiguration.touchSlop
                }
            }
            if (cancelled || !state.start(down.position)) return@awaitEachGesture
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    val delta = change.positionChange()
                    change.consume()
                    if (!change.pressed) break
                    state.drag(delta)
                }
            } finally {
                state.stop()
            }
        }
    }
}

/** Attach to each grid item: lifts the dragged one above the rest and moves it with the finger. */
fun Modifier.draggableItem(state: GridReorderState, key: Any): Modifier =
    if (state.isDragging(key)) {
        zIndex(1f).graphicsLayer {
            translationX = state.draggingOffset.x
            translationY = state.draggingOffset.y
            scaleX = 1.05f
            scaleY = 1.05f
            shadowElevation = 16f
        }
    } else {
        this
    }
