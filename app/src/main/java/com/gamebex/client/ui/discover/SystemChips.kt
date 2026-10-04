package com.gamebex.client.ui.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import com.gamebex.client.data.ConsoleItem
import com.gamebex.client.data.PlatformRegistry
import com.gamebex.client.ui.console.ConsoleColors
import com.gamebex.client.ui.console.ConsoleDesign
import com.gamebex.client.ui.console.LocalConsoleGeometry
import com.gamebex.client.ui.console.fadeTrailing
import com.gamebex.client.ui.console.isSystemReducedMotion

/**
 * One system chip. [id] is what picking it selects: the console feed id on
 * Discover, the platform folder on Search; null is the unfiltered chip
 * (Latest / All systems).
 */
data class SystemChip(val id: String?, val label: String)

/** Library display name of the feed's platform when known, else the feed's own name. */
fun systemDisplayName(item: ConsoleItem): String =
    item.platformId?.let { PlatformRegistry.byFolder[it]?.display } ?: item.name

/**
 * Discover chips: Latest, then one per console feed in source order,
 * labelled "<Display> · <count>". Feeds that resolve to the same display
 * name keep their own names so every chip reads differently.
 */
fun discoverSystemChips(consoles: List<ConsoleItem>): List<SystemChip> {
    val names = consoles.map { systemDisplayName(it) }
    val shared = names.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
    return listOf(SystemChip(null, "Latest")) + consoles.mapIndexed { i, c ->
        SystemChip(c.id, "${if (names[i] in shared) c.name else names[i]} · ${c.count}")
    }
}

/**
 * Search chips: All systems, then one per platform the source's feeds map
 * to, in feed order. Feeds without a platform can't narrow a search and are
 * left out; feeds on the same platform share one chip and add their counts.
 */
fun searchSystemChips(consoles: List<ConsoleItem>): List<SystemChip> {
    val merged = LinkedHashMap<String, Pair<String, Int>>()
    for (c in consoles) {
        val platform = c.platformId ?: continue
        val prev = merged[platform]
        merged[platform] = (prev?.first ?: systemDisplayName(c)) to ((prev?.second ?: 0) + c.count)
    }
    return listOf(SystemChip(null, "All systems")) +
        merged.map { (platform, v) -> SystemChip(platform, "${v.first} · ${v.second}") }
}

/** Index of the chip for [id]; the unfiltered chip when [id] has none. */
fun systemChipIndex(chips: List<SystemChip>, id: String?): Int =
    chips.indexOfFirst { it.id == id }.coerceAtLeast(0)

/**
 * Horizontally scrolling chip row in Library's filter-chip look.
 * [focused] is the chip under D-pad focus, or null when the row is not the
 * focus zone; that chip (else [selected]) is kept in view. A tap reports
 * the chip's index through [onPick].
 */
@Composable
fun SystemChipRow(
    chips: List<SystemChip>,
    selected: Int,
    focused: Int?,
    onPick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val g = LocalConsoleGeometry.current
    val state = rememberLazyListState()
    val fade = g.x(48)
    KeepChipInView(state, focused ?: selected, chips.size, fade)
    LazyRow(
        state = state,
        horizontalArrangement = Arrangement.spacedBy(g.x(8)),
        modifier = modifier.fadeTrailing(fade, enabled = state.canScrollForward),
    ) {
        itemsIndexed(chips) { i, chip ->
            SystemChipButton(chip.label, selected = i == selected, focused = i == focused) { onPick(i) }
        }
    }
}

/**
 * Scrolls only when chip [index] is not fully visible (clear of the
 * trailing fade); the chip before it stays in view for context.
 */
@Composable
private fun KeepChipInView(state: LazyListState, index: Int, size: Int, trailing: Dp) {
    val reducedMotion = isSystemReducedMotion()
    val trailingPx = with(LocalDensity.current) { trailing.roundToPx() }
    LaunchedEffect(index, size) {
        if (size <= 0) return@LaunchedEffect
        val i = index.coerceIn(0, size - 1)
        val info = state.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == i }
        val inView = item != null && item.offset >= info.viewportStartOffset &&
            item.offset + item.size <= info.viewportEndOffset - trailingPx
        if (inView) return@LaunchedEffect
        val target = maxOf(i - 1, 0)
        if (reducedMotion) state.scrollToItem(target) else state.animateScrollToItem(target)
    }
}

/** Unselected chips carry a dim backing so labels stay readable over Discover's hero art. */
@Composable
private fun SystemChipButton(text: String, selected: Boolean, focused: Boolean, onClick: () -> Unit) {
    val g = LocalConsoleGeometry.current
    val shape = RoundedCornerShape(g.x(ConsoleDesign.RADIUS_CHIP))
    Box(
        modifier = Modifier
            .height(g.x(36))
            .clip(shape)
            .background(if (selected) ConsoleColors.AccentDim else ConsoleColors.Background.copy(alpha = 0.6f))
            .border(
                when {
                    focused -> g.x(ConsoleDesign.FOCUS_STROKE)
                    selected -> g.x(2)
                    else -> g.x(1)
                },
                if (focused || selected) ConsoleColors.FocusRing else ConsoleColors.Steel,
                shape,
            )
            .selectable(selected = selected, onClick = onClick)
            .padding(horizontal = g.x(12)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (selected || focused) ConsoleColors.TextPrimary else ConsoleColors.TextSecondary,
            fontSize = g.t(15), lineHeight = g.t(18), maxLines = 1,
        )
    }
}
