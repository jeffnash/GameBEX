package com.gamebex.client.stream.guest

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.gamebex.client.together.TogetherCopy
import kotlinx.coroutines.delay

/**
 * The guest's full-screen viewer. The picture keeps the
 * stream's aspect ratio on black. A tap shows the two actions, Leave stream
 * and Show/Hide stats, which hide again after [MENU_MS]; every on-screen
 * hint names something that can be tapped or pressed.
 */
@Composable
fun StreamViewerScreen(
    ui: ViewerUi,
    onSurface: (android.view.Surface?) -> Unit,
    onLeave: () -> Unit,
    onToggleStats: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        delay(HINT_MS)
        hint = false
    }
    LaunchedEffect(menu) {
        if (menu) {
            delay(MENU_MS)
            menu = false
        }
    }
    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag(TAG_ROOT)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { menu = !menu },
    ) {
        if (ui.status != ViewerStatus.ENDED) {
            AndroidView(
                factory = { ctx ->
                    SurfaceView(ctx).apply {
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(h: SurfaceHolder) = onSurface(h.surface)
                            override fun surfaceChanged(h: SurfaceHolder, format: Int, w: Int, hgt: Int) = Unit
                            override fun surfaceDestroyed(h: SurfaceHolder) = onSurface(null)
                        })
                    }
                },
                modifier = Modifier
                    .align(Alignment.Center)
                    .aspectRatio(ui.width.coerceAtLeast(1).toFloat() / ui.height.coerceAtLeast(1).toFloat())
                    .testTag(TAG_VIDEO),
            )
        }
        when (ui.status) {
            ViewerStatus.CONNECTING -> Banner(TogetherCopy.connecting(ui.hostName), Modifier.align(Alignment.Center))
            ViewerStatus.RECONNECTING -> Banner(TogetherCopy.streamReconnecting, Modifier.align(Alignment.Center))
            ViewerStatus.ENDED -> Column(
                Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(ui.message ?: TogetherCopy.streamClosed, color = Color.White, fontSize = 18.sp)
                Button(onClick = onBack) { Text(TogetherCopy.back) }
            }
            ViewerStatus.WATCHING -> Unit
        }
        if (ui.status != ViewerStatus.ENDED) {
            if (hint) {
                Banner("${TogetherCopy.leaveStreamHint} · ${TogetherCopy.tapForMenu}", Modifier.align(Alignment.TopCenter).padding(top = 12.dp))
            }
            if (ui.weakWifi) Banner(TogetherCopy.weakWifi, Modifier.align(Alignment.BottomStart).padding(12.dp))
            if (ui.linkLost) {
                Banner(
                    if (ui.status == ViewerStatus.WATCHING) TogetherCopy.streamLinkLostStillGuest(ui.hostName) else TogetherCopy.streamLinkLostGuest(ui.hostName),
                    Modifier.align(Alignment.TopCenter).padding(top = 12.dp).testTag(TAG_LINK_LOST),
                    background = Color(0xE6B26A00),
                )
            }
            if (ui.showStats && ui.stats.isNotEmpty()) {
                Column(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp)
                        .background(Color(0xB0000000), RoundedCornerShape(6.dp))
                        .padding(8.dp)
                        .testTag(TAG_STATS),
                ) {
                    ui.stats.forEach { Text(it, color = Color.White, fontSize = 12.sp) }
                }
            }
            if (menu) {
                Row(
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Button(onClick = onLeave) { Text(TogetherCopy.leaveStream) }
                    OutlinedButton(onClick = {
                        onToggleStats()
                        menu = false
                    }) {
                        Text(if (ui.showStats) TogetherCopy.hideStats else TogetherCopy.showStats, color = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
private fun Banner(text: String, modifier: Modifier = Modifier, background: Color = Color(0xB0000000)) {
    Text(
        text,
        color = Color.White,
        fontSize = 14.sp,
        modifier = modifier
            .background(background, RoundedCornerShape(6.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

const val TAG_ROOT: String = "stream-viewer"
const val TAG_VIDEO: String = "stream-video"
const val TAG_STATS: String = "stream-stats"
const val TAG_LINK_LOST: String = "stream-link-lost"
private const val HINT_MS = 5_000L
private const val MENU_MS = 4_000L
