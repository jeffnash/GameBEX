package com.gamebex.client.ui.home.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.gamebex.client.ui.console.ConsoleColors
import com.gamebex.client.ui.console.LocalConsoleGeometry
import com.gamebex.client.ui.home.model.ArtKind
import com.gamebex.client.ui.home.model.TvContent
import com.gamebex.client.ui.home.model.TvModel

/** Download-ring geometry, design px. */
private const val RING_SIZE = 150
private const val COVER_CENTER_Y = 141 // cover 238 px at top 22 px in the 378 px screen
private const val FULL_BLEED_CENTER_Y = 189 // 378 / 2

/**
 * The 646×378 screen. A channel change arms its effects in the composition that
 * shows the new content (see [TvSwapHolder]): the static burst starts in the swap
 * frame and the old content fades out under it, so there is never a bare cut
 * before the static. `detectTapGestures` leaves D-pad focus untouched.
 */
@Composable
fun TvScreen(
    tv: TvModel,
    channel: Int,
    reducedMotion: Boolean,
    onTap: () -> Unit,
    selectionKey: String = channel.toString(),
    modifier: Modifier = Modifier,
) {
    val g = LocalConsoleGeometry.current
    val currentOnTap by rememberUpdatedState(onTap)

    // The swap keys on the selection, not the position, so it fires when the
    // selected card changes (including Roll again at the same position) and
    // never when sections shift above the cursor. `lastContent` is written in a
    // SideEffect, so the remember factory sees the previous frame's content
    // without writing state during composition. The first composition is idle.
    var lastContent by remember { mutableStateOf(tv.content) }
    var everComposed by remember { mutableStateOf(false) }
    val swap = remember(selectionKey) {
        if (everComposed) TvSwapHolder.swap(lastContent, reducedMotion) else TvSwapHolder.idle()
    }
    SideEffect {
        everComposed = true
        lastContent = tv.content
    }
    LaunchedEffect(swap) {
        if (!swap.animating) return@LaunchedEffect
        var start = -1L
        while (true) {
            withFrameNanos { now ->
                if (start < 0L) start = now
                swap.advance(((now - start) / 1_000_000L).toInt())
            }
            if (!swap.animating) break
        }
    }

    Box(
        modifier
            .clip(RoundedCornerShape(g.x(14)))
            .background(ConsoleColors.Background)
            .testTag("home-tv")
            .semantics(mergeDescendants = true) {
                // One merged node so TalkBack reads the TV once.
                contentDescription = "Preview: " +
                    listOfNotNull(tv.stateLine, tv.factsLine).joinToString(", ")
            }
            .pointerInput(Unit) {
                detectTapGestures(onTap = { currentOnTap() })
            },
    ) {
        if (swap.fading.value) {
            swap.from?.let { outgoing ->
                TvContentLayer(outgoing, swap, incoming = false, Modifier.fillMaxSize())
            }
        }
        TvContentLayer(tv.content, swap, incoming = true, Modifier.fillMaxSize())
        TvScreenEffects(Modifier.fillMaxSize())
        TvStaticBurst(swap, Modifier.fillMaxSize())
        TvBanner(tv, Modifier.align(Alignment.BottomCenter))
        TvChannelIndicator(
            channel = channel,
            reducedMotion = reducedMotion,
            swapKey = selectionKey,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = g.x(14), end = g.x(20)),
        )
    }
}

/** Content plus its download ring, alpha-blended for the crossfade. */
@Composable
private fun TvContentLayer(
    content: TvContent,
    swap: TvSwapHolder,
    incoming: Boolean,
    modifier: Modifier = Modifier,
) {
    val g = LocalConsoleGeometry.current
    Box(modifier.graphicsLayer { alpha = if (incoming) swap.newAlpha.value else swap.oldAlpha }) {
        TvContentView(content, Modifier.fillMaxSize())
        (content as? TvContent.Picture)?.progress?.let { progress ->
            val centerY =
                if (content.art.kind == ArtKind.SAVE_THUMB) FULL_BLEED_CENTER_Y else COVER_CENTER_Y
            TvDownloadRing(
                progress = progress,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = g.x(centerY - RING_SIZE / 2))
                    .size(g.x(RING_SIZE)),
            )
        }
    }
}
