package com.gamebex.client.ui.console

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import com.gamebex.client.ui.console.ConsoleDesign
import com.gamebex.client.ui.console.LocalConsoleGeometry
import com.gamebex.client.ui.presentation.GamePresentation

/** Scroll offset tracker shared with vertical nearest-X travel. */
class ShelfScroll {
    var px by mutableIntStateOf(0)
}

@Composable
fun rememberShelfScroll(): ShelfScroll = remember { ShelfScroll() }

/**
 * Horizontal shelf whose selected item stays at the anchor while content
 * scrolls beneath; at the list ends the scroll clamps instead of padding.
 */
@Composable
fun AnchoredShelf(
    items: List<GamePresentation>,
    selected: Int,
    onSelect: (Int) -> Unit,
    onActivate: (Int) -> Unit,
    itemWidth: Dp,
    itemHeight: Dp,
    modifier: Modifier = Modifier,
    gap: Dp = itemWidth * 0.257f,
    padStart: Dp = LocalConsoleGeometry.current.x(48),
    anchorX: Dp = LocalConsoleGeometry.current.anchorXLocal,
    listState: LazyListState = rememberLazyListState(),
    scroll: ShelfScroll = rememberShelfScroll(),
    renderItem: @Composable (GamePresentation, Boolean) -> Unit,
) {
    val density = LocalDensity.current
    // Keep the selection pinned near the anchor; LazyRow clamps at the ends.
    LaunchedEffect(selected, items.size) {
        if (items.isEmpty()) return@LaunchedEffect
        val i = selected.coerceIn(0, items.size - 1)
        with(density) {
            val itemPx = itemWidth.toPx()
            val gapPx = gap.toPx()
            val padPx = padStart.toPx()
            val anchorPx = anchorX.toPx()
            val center = padPx + i * (itemPx + gapPx) + itemPx / 2f
            val target = (center - anchorPx).coerceAtLeast(0f).toInt()
            listState.scrollToItem(0, target)
            // scroll.px is observed below (actual), never the request: the
            // row clamps at both ends and touch scrolls bypass this effect.
        }
    }
    // Actual scroll offset from layout: uniform items make this exact, at the
    // clamped ends and under touch scrolling alike.
    LaunchedEffect(listState, itemWidth, gap, padStart) {
        val dims = with(density) {
            Triple(itemWidth.toPx(), gap.toPx(), padStart.toPx())
        }
        val (itemPx, gapPx, padPx) = dims
        snapshotFlow {
            val first = listState.layoutInfo.visibleItemsInfo.minByOrNull { it.index }
            if (first == null) null
            else shelfActualScrollPx(padPx, itemPx, gapPx, first.index, first.offset)
        }.collect { actual -> if (actual != null) scroll.px = actual }
    }
    LazyRow(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(start = padStart),
        horizontalArrangement = Arrangement.spacedBy(gap),
    ) {
        itemsIndexed(items, key = { _, g -> g.stableId }) { i, game ->
            val focused = i == selected.coerceIn(0, items.size - 1)
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .clickable {
                        if (i == selected) onActivate(i) else onSelect(i)
                    },
            ) {
                renderItem(game, focused)
            }
        }
    }
}

/**
 * Scroll offset from one laid-out item: the content offset of [firstIndex]
 * minus its screen offset [firstOffset]. Exact only for uniform item widths.
 */
fun shelfActualScrollPx(
    padPx: Float,
    itemPx: Float,
    gapPx: Float,
    firstIndex: Int,
    firstOffset: Int,
): Int = (padPx + firstIndex * (itemPx + gapPx) - firstOffset).toInt().coerceAtLeast(0)

/** Content-coordinate center of item [i] in design px (for nearest-X travel). */
fun shelfCenterDesignPx(
    i: Int,
    itemW: Int,
    gap: Int,
    padStart: Int,
    scrollPx: Int,
    density: Float,
): Float = padStart + i * (itemW + gap) + itemW / 2f - scrollPx / density

val DefaultAnchorX: Float = ConsoleDesign.FOCUS_ANCHOR_X
