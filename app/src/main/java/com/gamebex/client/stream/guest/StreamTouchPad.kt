package com.gamebex.client.stream.guest

import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.motionEventSpy
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.gamebex.client.stream.proto.PadState
import com.gamebex.client.ui.SpaceGrotesk
import com.gamebex.client.ui.console.ConsoleColors

/**
 * The guest's touch controls: one positioned renderer over the video
 * with a single root input handler. Geometry comes from the per-system
 * [TouchLayoutSpec]; [TouchHitTester] owns pointer capture and publishes one
 * complete [PadState] per sample.
 *
 * Publication: [onTouchState] when set, otherwise the legacy key/left-axis
 * callbacks. Only the controls' hit areas are interactive; empty picture never
 * captures. Menu/⋯ is local: activating it releases touch input and calls [onMenu].
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun StreamTouchPad(
    onTouchKey: (Int, Boolean, Long) -> Unit,
    onTouchAxes: (Float, Float, Long) -> Unit,
    onReleaseTouchInput: () -> Unit,
    modifier: Modifier = Modifier,
    unbufferedInput: Boolean = false,
    profile: TouchProfile? = null,
    prefs: TouchPrefs = TouchPrefs(),
    viewport: TouchViewport? = null,
    onTouchState: ((PadState, Long) -> Unit)? = null,
    menuOpen: Boolean = false,
    onMenu: () -> Unit = {},
) {
    val keyCallback by rememberUpdatedState(onTouchKey)
    val axesCallback by rememberUpdatedState(onTouchAxes)
    val releaseCallback by rememberUpdatedState(onReleaseTouchInput)
    val stateCallback by rememberUpdatedState(onTouchState)
    val menuCallback by rememberUpdatedState(onMenu)
    val menuOpenState by rememberUpdatedState(menuOpen)
    val effectivePrefs = remember(prefs) { prefs.validated() }
    val layoutId = remember(profile) { selectTouchLayout(profile) }
    val definition = remember(layoutId, effectivePrefs) { touchLayoutSpec(layoutId, effectivePrefs) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var active by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    val view = LocalView.current
    // Latest release action, installed by the layout below once geometry exists.
    val releaseRef = remember { arrayOf<() -> Unit>({}) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> active = true
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY -> {
                    active = false
                    releaseRef[0]()
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            releaseRef[0]()
        }
    }
    if (!active) return

    BoxWithConstraints(modifier.fillMaxSize().safeDrawingPadding().testTag(TAG_TOUCH_PAD)) {
        val density = LocalDensity.current
        val safeWidthPx = with(density) { maxWidth.toPx() }
        val safeHeightPx = with(density) { maxHeight.toPx() }
        val resolved = remember(definition, safeWidthPx, safeHeightPx, effectivePrefs, viewport, profile) {
            resolveTouchLayout(
                definition, safeWidthPx, safeHeightPx, density.density, effectivePrefs,
                viewport ?: profile?.viewport,
            )
        }
        val tester = remember(resolved, effectivePrefs) {
            TouchHitTester(
                resolved,
                deadZoneFraction = effectivePrefs.deadZonePercent / 100f,
                invertRightStickY = effectivePrefs.invertRightStickY,
            )
        }
        val legacy = remember {
            LegacyTouchPublisher(
                { key, down, atNs -> keyCallback(key, down, atNs) },
                { x, y, atNs -> axesCallback(x, y, atNs) },
            )
        }
        val session = remember(tester) { TouchPublishSession() }
        var visual by remember(tester) { mutableStateOf(TouchVisual.NEUTRAL) }

        fun publish(state: PadState, atNs: Long) {
            if (state == session.lastPublished) return
            session.lastPublished = state
            val direct = stateCallback
            if (direct != null) direct(state, atNs) else legacy.publish(state, atNs)
        }

        fun releaseAll(atNs: Long) {
            tester.cancelAll()
            publish(PadState.NEUTRAL, atNs)
            legacy.reset()
            session.lastButtons = 0
            visual = TouchVisual.NEUTRAL
            releaseCallback()
        }

        fun handleSample(state: PadState, atNs: Long) {
            publish(state, atNs)
            if (tester.pollMenuTap()) {
                releaseAll(atNs)
                menuCallback()
                return
            }
            val fresh = state.buttons and session.lastButtons.inv()
            if (fresh != 0 && effectivePrefs.haptics == TouchHaptics.LIGHT &&
                atNs - session.lastPulseNs >= HAPTIC_MIN_INTERVAL_NS
            ) {
                session.lastPulseNs = atNs
                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            }
            session.lastButtons = state.buttons
            val next = tester.visualState()
            if (next != visual) visual = next
        }

        releaseRef[0] = { releaseAll(System.nanoTime()) }
        DisposableEffect(tester) {
            onDispose {
                // Geometry swapped (settings/viewport): the old captures are
                // already dropped; publish the neutral state for the new one.
                publish(PadState.NEUTRAL, System.nanoTime())
                legacy.reset()
                session.lastButtons = 0
            }
        }
        LaunchedEffect(menuOpen) {
            if (menuOpen) releaseAll(System.nanoTime())
        }

        Box(
            Modifier.fillMaxSize()
                .motionEventSpy { event ->
                    if (unbufferedInput && (event.actionMasked == MotionEvent.ACTION_DOWN ||
                        event.actionMasked == MotionEvent.ACTION_POINTER_DOWN)
                    ) {
                        view.requestUnbufferedDispatch(event)
                    }
                }
                .pointerInput(tester) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            val nowNs = System.nanoTime()
                            val nowUptime = SystemClock.uptimeMillis()
                            event.changes.forEach { change ->
                                val id = change.id.value
                                val atNs = touchSampleNs(change.uptimeMillis, nowNs, nowUptime)
                                if (menuOpenState) {
                                    tester.onCancel(id)
                                    return@forEach
                                }
                                val state = if (change.changedToDownIgnoreConsumed()) {
                                    tester.onDown(id, change.position.x, change.position.y)
                                } else if (change.pressed) {
                                    tester.onMove(id, change.position.x, change.position.y)
                                } else {
                                    tester.onUp(id, change.position.x, change.position.y)
                                }
                                handleSample(state, atNs)
                            }
                        }
                    }
                },
        )
        val opacityFactor = effectivePrefs.opacityPercent / 55f
        Canvas(Modifier.fillMaxSize()) {
            drawTouchControls(resolved, visual, opacityFactor)
        }
        TouchLabels(resolved, visual)
    }
}

/** Per-geometry publication cursors: dedup, haptic edges, pulse budget. */
private class TouchPublishSession {
    var lastPublished: PadState = PadState.NEUTRAL
    var lastButtons: Int = 0
    var lastPulseNs: Long = 0L
}

private const val HAPTIC_MIN_INTERVAL_NS: Long = 80_000_000L

internal const val TAG_TOUCH_PAD = "stream-touch-pad"
internal const val TAG_TOUCH_STICK = "stream-touch-stick"
internal fun touchKeyTag(key: Int) = "stream-touch-key-$key"
internal fun touchControlTag(id: String) = "stream-touch-control-$id"

internal fun touchSampleNs(eventUptimeMs: Long, nowNs: Long, nowUptimeMs: Long): Long =
    nowNs - (nowUptimeMs - eventUptimeMs).coerceAtLeast(0L) * 1_000_000L

private data class TouchDrawStyle(val outline: Float, val fill: Float, val pressed: Boolean, val dimmed: Boolean)

private fun DrawScope.drawTouchControls(resolved: ResolvedTouchLayout, visual: TouchVisual, opacityFactor: Float) {
    val outlineWidth = 1.5.dp.toPx()
    fun style(pressed: Boolean, dimmed: Boolean): TouchDrawStyle {
        val outline = (if (dimmed) 0.35f else 0.55f) * opacityFactor
        val fill = (if (dimmed) 0.06f else 0.10f) * opacityFactor
        return TouchDrawStyle(outline.coerceIn(0f, 1f), fill.coerceIn(0f, 1f), pressed, dimmed)
    }
    fun TouchRectPx.dimmed(): Boolean = intersects(resolved.content)
    resolved.dpads.forEachIndexed { index, dpad ->
        val sector = visual.dpadSectors[index] ?: TouchHitTester.HAT_SECTOR_NEUTRAL
        drawDpad(dpad.cx, dpad.cy, dpad.radiusPx, sector, style(sector != TouchHitTester.HAT_SECTOR_NEUTRAL, dpad.bounds().dimmed()), outlineWidth)
    }
    resolved.sticks.forEachIndexed { index, stick ->
        val knob = visual.stickKnobs[index] ?: (0f to 0f)
        val active = knob.first != 0f || knob.second != 0f
        val yellow = stick.control.id == "cstick"
        drawStick(stick, knob, style(active, stick.bounds().dimmed()), outlineWidth, yellow)
    }
    resolved.clusters.forEach { cluster ->
        val yellow = cluster.control.id == "c"
        cluster.buttons.forEach { b ->
            val pressed = "${cluster.control.id}:${b.def.id}" in visual.pressedButtons
            val st = style(pressed, TouchRectPx(b.cx - b.hitRadiusPx, b.cy - b.hitRadiusPx, b.cx + b.hitRadiusPx, b.cy + b.hitRadiusPx).dimmed())
            drawFaceButton(b.cx, b.cy, b.artRadiusPx, st, outlineWidth, yellow)
        }
    }
    resolved.keys.forEach { key ->
        val pressed = key.control.id in visual.pressedButtons
        val st = style(pressed, key.rect.dimmed())
        if (key.control.circular) {
            drawFaceButton(key.cx, key.cy, key.radiusPx * 0.85f, st, outlineWidth, yellow = false)
        } else {
            drawKeyRect(key.rect, key.control.roundDp.dp.toPx(), st, outlineWidth)
        }
    }
    resolved.menu?.let { menu ->
        drawKeyRect(menu.rect, 12.dp.toPx(), style(visual.menuPressed, dimmed = false), outlineWidth)
    }
}

private fun DrawScope.drawFaceButton(cx: Float, cy: Float, artR: Float, st: TouchDrawStyle, outlineWidth: Float, yellow: Boolean) {
    val center = Offset(cx, cy)
    val fill = if (st.pressed) {
        if (yellow) Color(0xFFE8B400).copy(alpha = 0.45f) else ConsoleColors.Accent.copy(alpha = 0.28f)
    } else {
        Color.Black.copy(alpha = st.fill)
    }
    drawCircle(fill, artR, center)
    drawCircle(Color.White.copy(alpha = if (st.pressed) 1f else st.outline), artR, center, style = Stroke(outlineWidth))
}

private fun DrawScope.drawKeyRect(rect: TouchRectPx, roundPx: Float, st: TouchDrawStyle, outlineWidth: Float) {
    val topLeft = Offset(rect.left, rect.top)
    val size = Size(rect.width, rect.height)
    val radius = CornerRadius(roundPx, roundPx)
    drawRoundRect(
        if (st.pressed) ConsoleColors.Accent.copy(alpha = 0.28f) else Color.Black.copy(alpha = st.fill),
        topLeft, size, radius,
    )
    drawRoundRect(
        Color.White.copy(alpha = if (st.pressed) 1f else st.outline),
        topLeft, size, radius, style = Stroke(outlineWidth),
    )
}

private fun DrawScope.drawStick(stick: ResolvedStick, knob: Pair<Float, Float>, st: TouchDrawStyle, outlineWidth: Float, yellow: Boolean) {
    val center = Offset(stick.cx, stick.cy)
    drawCircle(Color.Black.copy(alpha = st.fill), stick.baseRadiusPx, center)
    drawCircle(Color.White.copy(alpha = (0.40f * (st.outline / 0.55f).coerceIn(0.4f, 1f)).coerceIn(0f, 1f)), stick.baseRadiusPx, center, style = Stroke(outlineWidth))
    val knobCenter = Offset(center.x + knob.first * stick.travelPx, center.y + knob.second * stick.travelPx)
    val knobColor = when {
        yellow && st.pressed -> Color(0xFFE8B400).copy(alpha = 0.55f)
        yellow -> Color(0xFFE8B400).copy(alpha = 0.25f)
        st.pressed -> ConsoleColors.Accent.copy(alpha = 0.40f)
        else -> Color.White.copy(alpha = 0.18f)
    }
    drawCircle(knobColor, stick.knobRadiusPx, knobCenter)
}

/** Sector index to (x, y) with y negative up; index 8 is neutral. */
private val DPAD_SECTOR_ARMS = arrayOf(
    1 to 0, 1 to -1, 0 to -1, -1 to -1, -1 to 0, -1 to 1, 0 to 1, 1 to 1,
)

private fun DrawScope.drawDpad(cx: Float, cy: Float, r: Float, sector: Int, st: TouchDrawStyle, outlineWidth: Float) {
    val armW = r * 0.58f
    val armL = r * 0.92f
    val resting = Color.Black.copy(alpha = st.fill)
    val restingEdge = Color.White.copy(alpha = st.outline)
    val lit = ConsoleColors.Accent.copy(alpha = 0.45f)
    val litEdge = Color.White.copy(alpha = 1f)
    // Joined cross: vertical bar, horizontal bar, center cap.
    drawRoundRect(resting, Offset(cx - armW / 2f, cy - armL), Size(armW, armL * 2f), CornerRadius(armW / 3f, armW / 3f))
    drawRoundRect(resting, Offset(cx - armL, cy - armW / 2f), Size(armL * 2f, armW), CornerRadius(armW / 3f, armW / 3f))
    drawRoundRect(restingEdge, Offset(cx - armW / 2f, cy - armL), Size(armW, armL * 2f), CornerRadius(armW / 3f, armW / 3f), style = Stroke(outlineWidth))
    drawRoundRect(restingEdge, Offset(cx - armL, cy - armW / 2f), Size(armL * 2f, armW), CornerRadius(armW / 3f, armW / 3f), style = Stroke(outlineWidth))
    if (sector in 0..7) {
        val (dx, dy) = DPAD_SECTOR_ARMS[sector]
        if (dy != 0) {
            val top = if (dy < 0) cy - armL else cy
            drawRoundRect(lit, Offset(cx - armW / 2f, top), Size(armW, armL), CornerRadius(armW / 4f, armW / 4f))
            drawRoundRect(litEdge, Offset(cx - armW / 2f, top), Size(armW, armL), CornerRadius(armW / 4f, armW / 4f), style = Stroke(outlineWidth))
        }
        if (dx != 0) {
            val left = if (dx < 0) cx - armL else cx
            drawRoundRect(lit, Offset(left, cy - armW / 2f), Size(armL, armW), CornerRadius(armW / 4f, armW / 4f))
            drawRoundRect(litEdge, Offset(left, cy - armW / 2f), Size(armL, armW), CornerRadius(armW / 4f, armW / 4f), style = Stroke(outlineWidth))
        }
    }
}

/**
 * Labels and semantics over the drawn shapes. These boxes carry no pointer
 * input: the root handler owns every touch.
 */
@Composable
private fun TouchLabels(resolved: ResolvedTouchLayout, visual: TouchVisual) {
    val density = LocalDensity.current
    fun Float.pxToDp(): androidx.compose.ui.unit.Dp = with(density) { this@pxToDp.toDp() }
    resolved.dpads.forEachIndexed { index, dpad ->
        val pressed = (visual.dpadSectors[index] ?: TouchHitTester.HAT_SECTOR_NEUTRAL) != TouchHitTester.HAT_SECTOR_NEUTRAL
        Box(
            Modifier.offset((dpad.cx - dpad.radiusPx).pxToDp(), (dpad.cy - dpad.radiusPx).pxToDp())
                .size((dpad.radiusPx * 2f).pxToDp())
                .testTag(touchControlTag(dpad.control.id))
                .semantics {
                    contentDescription = "D-pad · GameBEX control"
                    role = Role.Button
                    selected = pressed
                },
        )
    }
    resolved.sticks.forEachIndexed { index, stick ->
        val knob = visual.stickKnobs[index] ?: (0f to 0f)
        val pressed = knob.first != 0f || knob.second != 0f
        val side = if (stick.right) "Right" else "Left"
        Box(
            Modifier.offset((stick.cx - stick.baseRadiusPx).pxToDp(), (stick.cy - stick.baseRadiusPx).pxToDp())
                .size((stick.baseRadiusPx * 2f).pxToDp())
                .testTag(if (!stick.right) TAG_TOUCH_STICK else touchControlTag(stick.control.id))
                .semantics {
                    contentDescription = "$side stick · GameBEX control"
                    role = Role.Button
                    selected = pressed
                },
        )
    }
    resolved.clusters.forEach { cluster ->
        cluster.buttons.forEach { b ->
            val pressed = "${cluster.control.id}:${b.def.id}" in visual.pressedButtons
            TouchLabel(
                text = b.def.label,
                tag = touchControlTag("${cluster.control.id}:${b.def.id}"),
                leftPx = b.cx - b.artRadiusPx,
                topPx = b.cy - b.artRadiusPx,
                sizePx = b.artRadiusPx * 2f,
                pressed = pressed,
                yellow = cluster.control.id == "c",
                fontSizeSp = 18,
            )
        }
    }
    resolved.keys.forEach { key ->
        val pressed = key.control.id in visual.pressedButtons
        val w = key.rect.width
        val h = key.rect.height
        TouchLabel(
            text = key.control.label,
            tag = touchControlTag(key.control.id),
            leftPx = key.rect.left,
            topPx = key.rect.top,
            sizePx = null,
            widthPx = w,
            heightPx = h,
            pressed = pressed,
            fontSizeSp = 14,
        )
    }
    resolved.menu?.let { menu ->
        TouchLabel(
            text = "⋯",
            tag = touchControlTag(menu.control.id),
            leftPx = menu.rect.left,
            topPx = menu.rect.top,
            sizePx = menu.rect.width,
            pressed = visual.menuPressed,
            fontSizeSp = 18,
        )
    }
    // Legacy keycode markers (stream-touch-key-*) are test handles only. They
    // draw nothing and carry no input; the root handler owns every touch, so
    // taps hit-test exactly as without them.
    resolved.dpads.forEach { dpad ->
        val o = dpad.radiusPx * 0.45f
        val s = dpad.radiusPx * 0.4f
        listOf(
            KeyEvent.KEYCODE_DPAD_UP to (dpad.cx to (dpad.cy - o)),
            KeyEvent.KEYCODE_DPAD_DOWN to (dpad.cx to (dpad.cy + o)),
            KeyEvent.KEYCODE_DPAD_LEFT to ((dpad.cx - o) to dpad.cy),
            KeyEvent.KEYCODE_DPAD_RIGHT to ((dpad.cx + o) to dpad.cy),
        ).forEach { (code, center) ->
            Box(
                Modifier.offset((center.first - s / 2f).pxToDp(), (center.second - s / 2f).pxToDp())
                    .size(s.pxToDp())
                    .testTag(touchKeyTag(code)),
            )
        }
    }
    resolved.clusters.forEach { cluster ->
        cluster.buttons.forEach { b ->
            legacyKeyCode(b.def.output)?.let { code ->
                Box(
                    Modifier.offset((b.cx - b.artRadiusPx).pxToDp(), (b.cy - b.artRadiusPx).pxToDp())
                        .size((b.artRadiusPx * 2f).pxToDp())
                        .testTag(touchKeyTag(code)),
                )
            }
        }
    }
    resolved.keys.forEach { key ->
        legacyKeyCode(key.control.output)?.let { code ->
            Box(
                Modifier.offset(key.rect.left.pxToDp(), key.rect.top.pxToDp())
                    .size(key.rect.width.pxToDp(), key.rect.height.pxToDp())
                    .testTag(touchKeyTag(code)),
            )
        }
    }
}

/** The legacy keycode for a single-button output, else null. */
private fun legacyKeyCode(output: TouchOutput): Int? {
    val bits = (output as? TouchOutput.Buttons)?.bits ?: return null
    if (bits == 0 || bits and (bits - 1) != 0) return null
    return touchBitToKeyCode(bits)
}

@Composable
private fun TouchLabel(
    text: String,
    tag: String,
    leftPx: Float,
    topPx: Float,
    sizePx: Float?,
    pressed: Boolean,
    fontSizeSp: Int,
    widthPx: Float = sizePx ?: 0f,
    heightPx: Float = sizePx ?: 0f,
    yellow: Boolean = false,
) {
    val density = LocalDensity.current
    val labelColor = when {
        yellow && pressed -> Color(0xFFFFD94D)
        yellow -> Color(0xFFE8B400).copy(alpha = 0.9f)
        else -> ConsoleColors.TextPrimary.copy(alpha = if (pressed) 1f else 0.85f)
    }
    Box(
        Modifier.offset(with(density) { leftPx.toDp() }, with(density) { topPx.toDp() })
            .size(with(density) { widthPx.toDp() }, with(density) { heightPx.toDp() })
            .testTag(tag)
            .semantics {
                contentDescription = "$text · GameBEX control"
                role = Role.Button
                selected = pressed
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            fontFamily = SpaceGrotesk,
            fontSize = fontSizeSp.sp,
            fontWeight = FontWeight.Medium,
            color = labelColor,
            textAlign = TextAlign.Center,
        )
    }
}
