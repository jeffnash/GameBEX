package com.gamebex.client.ui.home.components

import kotlin.math.min

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.gamebex.client.ui.console.ConsoleColors
import com.gamebex.client.ui.console.ConsoleGeometry
import com.gamebex.client.ui.console.LocalConsoleGeometry
import com.gamebex.client.ui.home.model.CardState
import com.gamebex.client.ui.home.model.HomeCard

/** Leading glyph of a card line, drawn in place of the ☁ / ⚠ character. */
enum class StateGlyph { CLOUD, WARN }

/** Splits a `HomeCopy.cardLine` into its leading glyph, if any, and the remaining text. */
internal fun parseStateLine(line: String): Pair<StateGlyph?, String> = when {
    line.startsWith("☁") -> StateGlyph.CLOUD to line.drop(1).trimStart()
    line.startsWith("⚠") -> StateGlyph.WARN to line.drop(1).trimStart()
    else -> null to line
}

/** The card's one-line status; a leading ☁ / ⚠ renders as a pixel glyph, not text. */
@Composable
fun StateLine(
    card: HomeCard,
    modifier: Modifier = Modifier,
) {
    val g = LocalConsoleGeometry.current
    val ts = homeTextScale(g)
    val (glyph, text) = parseStateLine(card.cardLine)
    val color = stateLineColor(card.state, card.cardLine)
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        if (glyph != null) {
            val glyphMod = Modifier.size(g.x(20), g.x(14))
            when (glyph) {
                StateGlyph.CLOUD -> CloudGlyph(tint = color, modifier = glyphMod)
                StateGlyph.WARN -> WarnGlyph(tint = color, modifier = Modifier.size(g.x(15)))
            }
            Spacer(Modifier.width(g.x(7)))
        }
        Text(
            text = text,
            color = color,
            fontSize = (18f * g.scale * ts).sp,
            lineHeight = (22f * g.scale * ts).sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Offline lines ("Download when online", "Paused · offline · …") are amber in
 * any state. [line] is the precomputed `HomeCopy.cardLine`, which carries the offline tag.
 */
fun stateLineColor(state: CardState, line: String = ""): Color {
    if (state is CardState.WaitingForConnection || line.contains("offline")) {
        return ConsoleColors.Warning
    }
    return when (state) {
        is CardState.Saved, is CardState.Played, is CardState.Added,
        is CardState.Preparing,
        -> ConsoleColors.TextSecondary
        is CardState.NeedsBios, is CardState.NeedsEmulator, is CardState.NeedsFix,
        is CardState.NoRoom,
        -> ConsoleColors.Warning
        is CardState.Downloading ->
            if (state.paused) ConsoleColors.TextTertiary else ConsoleColors.Success
        is CardState.Queued ->
            if (state.armed) ConsoleColors.Success else ConsoleColors.TextTertiary
        is CardState.DownloadProblem -> ConsoleColors.Error
        is CardState.OffPhone -> ConsoleColors.Accent
        is CardState.OnSite -> ConsoleColors.TextPrimary
        CardState.WaitingForConnection -> ConsoleColors.TextTertiary
        is CardState.Unsupported, is CardState.Unavailable -> ConsoleColors.TextTertiary
        CardState.None -> ConsoleColors.TextTertiary
    }
}

private fun homeTextScale(g: ConsoleGeometry): Float = min(g.textScale, 1.1f)
