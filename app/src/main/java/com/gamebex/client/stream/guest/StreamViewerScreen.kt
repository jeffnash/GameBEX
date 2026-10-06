package com.gamebex.client.stream.guest

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.gamebex.client.R
import com.gamebex.client.stream.proto.PadState
import com.gamebex.client.together.TogetherCopy
import com.gamebex.client.ui.SpaceGrotesk
import com.gamebex.client.ui.console.ConsoleColors
import kotlinx.coroutines.delay

/**
 * The guest's full-screen viewer. The picture keeps the
 * stream's aspect ratio on black. Local guest controls hold real player-2 input;
 * Menu shows Leave stream and Show/Hide stats, which hide again after [MENU_MS].
 */
@Composable
fun StreamViewerScreen(
    ui: ViewerUi,
    onSurface: (android.view.Surface?) -> Unit,
    onLeave: () -> Unit,
    onToggleStats: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onTouchKey: (Int, Boolean, Long) -> Unit = { _, _, _ -> },
    onTouchAxes: (Float, Float, Long) -> Unit = { _, _, _ -> },
    onReleaseTouchInput: () -> Unit = {},
    hudEnabled: Boolean = true,
    touchPadHidden: Boolean = false,
    unbufferedInput: Boolean = false,
    fixedSurface: Boolean = false,
    surfaceOpaque: Boolean = false,
    physicalControllerConnected: Boolean = false,
    touchProfile: TouchProfile? = null,
    touchPrefs: TouchPrefs = TouchPrefs(),
    touchViewport: TouchViewport? = null,
    onTouchState: ((PadState, Long) -> Unit)? = null,
    onTouchMenu: () -> Unit = {},
    useHostOverlay: Boolean = false,
    /** The Touch controls page writes through these; the activity persists them per layout. */
    onTouchPrefsChange: (TouchPrefs) -> Unit = {},
    onResetTouchPrefs: () -> Unit = {},
) {
    var menu by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf(true) }
    var touchPage by remember { mutableStateOf(false) }
    var effectiveSize by remember { mutableStateOf<Int?>(null) }
    val surfaceCallback by rememberUpdatedState(onSurface)
    val releaseTouchCallback by rememberUpdatedState(onReleaseTouchInput)
    val overlays = viewerOverlays(ui.status, ui.waitingForVideo, hudEnabled, touchPadHidden, physicalControllerConnected)
    val loading = overlays.loading
    val controlsVisible = overlays.menu
    LaunchedEffect(loading, ui.status) {
        if (!controlsVisible) menu = false
    }
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
            .testTag(TAG_ROOT),
    ) {
        if (ui.status != ViewerStatus.ENDED) {
            AndroidView(
                factory = { ctx ->
                    SurfaceView(ctx).apply {
                        if (surfaceOpaque) holder.setFormat(android.graphics.PixelFormat.OPAQUE)
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(h: SurfaceHolder) = surfaceCallback(h.surface)
                            override fun surfaceChanged(h: SurfaceHolder, format: Int, w: Int, hgt: Int) = Unit
                            override fun surfaceDestroyed(h: SurfaceHolder) {
                                releaseTouchCallback()
                                surfaceCallback(null)
                            }
                        })
                    }
                },
                update = { view ->
                    if (fixedSurface) view.holder.setFixedSize(ui.width.coerceAtLeast(1), ui.height.coerceAtLeast(1))
                    else view.holder.setSizeFromLayout()
                },
                modifier = Modifier
                    .align(Alignment.Center)
                    .aspectRatio(ui.width.coerceAtLeast(1).toFloat() / ui.height.coerceAtLeast(1).toFloat())
                    .testTag(TAG_VIDEO),
            )
        }
        if (overlays.touchPad) {
            StreamOverlayPad(
                onTouchKey, onTouchAxes, onReleaseTouchInput,
                unbufferedInput = unbufferedInput,
                profile = touchProfile,
                prefs = touchPrefs,
                viewport = touchViewport,
                onTouchState = onTouchState,
                menuOpen = menu,
                onMenu = onTouchMenu,
                useHostOverlay = useHostOverlay,
                onEffectiveSize = { effectiveSize = it },
            )
        }
        if (loading) {
            LoadingOverlay(ui, onLeave)
        }
        if (ui.status == ViewerStatus.ENDED) {
            Column(
                Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(ui.message ?: TogetherCopy.streamClosed, color = Color.White, fontSize = 18.sp)
                Button(onClick = onBack) { Text(TogetherCopy.back) }
            }
        }
        if (ui.status != ViewerStatus.ENDED) {
            ui.message?.let { message ->
                Banner(message, Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(12.dp))
            }
            if (overlays.hud && hint && !menu) {
                Banner(
                    if (loading) TogetherCopy.leaveStreamHint else "${TogetherCopy.leaveStreamHint} · Menu for stats",
                    Modifier.align(Alignment.TopCenter).padding(top = if (loading) 12.dp else 60.dp),
                )
            }
            if (overlays.hud && ui.weakWifi) {
                Banner(
                    TogetherCopy.weakWifi,
                    if (loading) Modifier.align(Alignment.BottomStart).padding(start = 12.dp, end = 12.dp, bottom = 76.dp)
                    else Modifier.align(Alignment.TopCenter).padding(top = if (ui.linkLost) 144.dp else 104.dp),
                )
            }
            if (overlays.hud && ui.linkLost) {
                Banner(
                    if (ui.status == ViewerStatus.WATCHING) TogetherCopy.streamLinkLostStillGuest(ui.hostName) else TogetherCopy.streamLinkLostGuest(ui.hostName),
                    Modifier.align(Alignment.TopCenter).padding(top = if (loading) 12.dp else 104.dp).testTag(TAG_LINK_LOST),
                    background = Color(0xE6B26A00),
                )
            }
            if (overlays.hud && ui.showStats && ui.stats.isNotEmpty()) {
                Column(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 12.dp, end = 12.dp)
                        .background(Color(0xB0000000), RoundedCornerShape(6.dp))
                        .padding(8.dp)
                        .testTag(TAG_STATS),
                ) {
                    ui.stats.forEach { Text(it, color = Color.White, fontSize = 12.sp) }
                }
            }
            if (controlsVisible) {
                OutlinedButton(
                    onClick = { menu = !menu },
                    modifier = Modifier.align(Alignment.TopStart).safeDrawingPadding().padding(12.dp).testTag(TAG_MENU),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text("Menu", fontFamily = SpaceGrotesk, color = ConsoleColors.TextPrimary)
                }
            }
            if (menu && controlsVisible) {
                Row(
                    Modifier.align(Alignment.TopCenter).safeDrawingPadding().padding(top = 60.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Button(onClick = onLeave) { Text(TogetherCopy.leaveStream) }
                    if (overlays.hud) {
                        OutlinedButton(onClick = {
                            onToggleStats()
                            menu = false
                        }) {
                            Text(if (ui.showStats) TogetherCopy.hideStats else TogetherCopy.showStats, color = Color.White)
                        }
                    }
                    if (overlays.touchPad) {
                        OutlinedButton(onClick = {
                            touchPage = true
                            menu = false
                        }, modifier = Modifier.testTag(TAG_TOUCH_PAGE_OPEN)) {
                            Text("Touch controls", color = Color.White)
                        }
                    }
                }
            }
            if (touchPage && controlsVisible) {
                TouchControlsPage(
                    prefs = touchPrefs,
                    effectiveSize = effectiveSize,
                    onChange = onTouchPrefsChange,
                    onReset = onResetTouchPrefs,
                    onClose = { touchPage = false },
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }
}

private const val TAG_TOUCH_PAGE_OPEN: String = "stream-menu-touch"

/**
 * The Touch controls settings page (viewer Menu → Touch controls). The size
 * row shows the effective (clamped) value when the pad had to shrink the
 * requested size to keep hit areas apart.
 */
@Composable
private fun TouchControlsPage(
    prefs: TouchPrefs,
    effectiveSize: Int?,
    onChange: (TouchPrefs) -> Unit,
    onReset: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val valid = prefs.validated()
    Box(
        modifier.fillMaxSize()
            .background(Color(0xD0000000))
            .clickable(onClick = {})
            .testTag("touch-controls-page"),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.width(420.dp)
                .background(ConsoleColors.Background, RoundedCornerShape(12.dp))
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Touch controls", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            val eff = effectiveSize ?: valid.sizePercent
            Text(
                if (eff != valid.sizePercent) "Size: ${valid.sizePercent}% (using $eff% — clamped to avoid overlap)"
                else "Size: ${valid.sizePercent}%",
                color = Color.White, fontSize = 14.sp,
                modifier = Modifier.testTag("touch-size-value"),
            )
            Slider(
                value = valid.sizePercent.toFloat(),
                onValueChange = { onChange(valid.copy(sizePercent = it.toInt())) },
                valueRange = TouchPrefs.SIZE_MIN.toFloat()..TouchPrefs.SIZE_MAX.toFloat(),
                steps = (TouchPrefs.SIZE_MAX - TouchPrefs.SIZE_MIN) / TouchPrefs.SIZE_STEP - 1,
                modifier = Modifier.fillMaxWidth().testTag("touch-size-slider"),
            )
            Text(
                "Opacity: ${valid.opacityPercent}%",
                color = Color.White, fontSize = 14.sp,
                modifier = Modifier.testTag("touch-opacity-value"),
            )
            Slider(
                value = valid.opacityPercent.toFloat(),
                onValueChange = { onChange(valid.copy(opacityPercent = it.toInt())) },
                valueRange = TouchPrefs.OPACITY_MIN.toFloat()..TouchPrefs.OPACITY_MAX.toFloat(),
                steps = (TouchPrefs.OPACITY_MAX - TouchPrefs.OPACITY_MIN) / TouchPrefs.OPACITY_STEP - 1,
                modifier = Modifier.fillMaxWidth().testTag("touch-opacity-slider"),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        onChange(valid.copy(stickMode = if (valid.stickMode == TouchStickMode.FIXED) TouchStickMode.FLOATING else TouchStickMode.FIXED))
                    },
                    modifier = Modifier.testTag("touch-stick-mode"),
                ) { Text("Stick: ${valid.stickMode.name.lowercase().replaceFirstChar { it.uppercase() }}", color = Color.White) }
                OutlinedButton(
                    onClick = { onChange(valid.copy(swapSides = !valid.swapSides)) },
                    modifier = Modifier.testTag("touch-swap"),
                ) { Text(if (valid.swapSides) "Sides: swapped" else "Sides: normal", color = Color.White) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        onChange(valid.copy(haptics = if (valid.haptics == TouchHaptics.OFF) TouchHaptics.LIGHT else TouchHaptics.OFF))
                    },
                    modifier = Modifier.testTag("touch-haptics"),
                ) { Text("Haptics: ${if (valid.haptics == TouchHaptics.OFF) "Off" else "Light"}", color = Color.White) }
                OutlinedButton(onClick = onReset, modifier = Modifier.testTag("touch-reset")) {
                    Text("Reset", color = Color.White)
                }
                Button(onClick = onClose, modifier = Modifier.testTag("touch-close")) { Text("Done") }
            }
        }
    }
}

@Composable
private fun LoadingOverlay(ui: ViewerUi, onLeave: () -> Unit) {
    val animation = rememberInfiniteTransition(label = "stream-loading")
    val logoAlpha by animation.animateFloat(
        initialValue = 0.8f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(960, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "brand-pulse",
    )
    val activity by animation.animateFloat(
        initialValue = 0f,
        targetValue = 8f,
        animationSpec = infiniteRepeatable(tween(1_440, easing = LinearEasing)),
        label = "pixel-activity",
    )
    Box(Modifier.fillMaxSize().background(ConsoleColors.Background).testTag(TAG_LOADING)) {
        Column(
            Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, top = 56.dp, bottom = 84.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(
                    painterResource(R.drawable.gamebex_icon_foreground),
                    contentDescription = null,
                    modifier = Modifier.size(64.dp).alpha(logoAlpha),
                )
                Image(
                    painterResource(R.drawable.gamebex_wordmark),
                    contentDescription = "GameBEX",
                    modifier = Modifier.size(width = 172.dp, height = 28.dp),
                )
            }
            Text(
                if (ui.status == ViewerStatus.RECONNECTING) TogetherCopy.streamReconnecting else TogetherCopy.connecting(ui.hostName),
                color = ConsoleColors.TextPrimary,
                fontFamily = SpaceGrotesk,
                fontWeight = FontWeight.Medium,
                fontSize = 18.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 16.dp),
            )
            Text(
                TogetherCopy.streamWaitingForHost,
                color = ConsoleColors.TextSecondary,
                fontFamily = SpaceGrotesk,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
            Canvas(
                Modifier.padding(top = 20.dp).size(width = 120.dp, height = 8.dp)
                    .semantics { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate },
            ) {
                val pixel = size.height
                val gap = (size.width - 8 * pixel) / 7
                repeat(8) { index ->
                    val trail = (activity - index + 8f) % 8f
                    val brightness = (1f - trail / 3f).coerceIn(0f, 1f)
                    drawRect(
                        color = lerp(ConsoleColors.Steel, ConsoleColors.Accent, brightness),
                        topLeft = Offset(index * (pixel + gap), 0f),
                        size = Size(pixel, pixel),
                    )
                }
            }
        }
        OutlinedButton(
            onClick = onLeave,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp),
            shape = RoundedCornerShape(8.dp),
        ) {
            Text(TogetherCopy.leaveStream, color = ConsoleColors.TextPrimary, fontFamily = SpaceGrotesk)
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

const val TAG_MENU: String = "stream-menu"
const val TAG_ROOT: String = "stream-viewer"
const val TAG_VIDEO: String = "stream-video"
const val TAG_LOADING: String = "stream-loading"
const val TAG_STATS: String = "stream-stats"
const val TAG_LINK_LOST: String = "stream-link-lost"
private const val HINT_MS = 5_000L
private const val MENU_MS = 4_000L

internal data class ViewerOverlays(val loading: Boolean, val menu: Boolean, val touchPad: Boolean, val hud: Boolean)

internal fun viewerOverlays(status: ViewerStatus, waitingForVideo: Boolean, hudEnabled: Boolean, touchPadHidden: Boolean, physicalControllerConnected: Boolean = false): ViewerOverlays {
    val loading = status != ViewerStatus.ENDED &&
        (status == ViewerStatus.CONNECTING || status == ViewerStatus.RECONNECTING || waitingForVideo)
    val controls = status == ViewerStatus.WATCHING && !loading
    return ViewerOverlays(loading, controls, controls && !touchPadHidden && !physicalControllerConnected, hudEnabled)
}
