package com.gamebex.client.stream.guest

import android.graphics.BitmapFactory
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.motionEventSpy
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.gamebex.client.stream.proto.PadState
import com.gamebex.client.together.LinkMsg
import java.util.Base64
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The latest host overlay received on the Together link (26b). The guest
 * engine publishes it; [StreamOverlayPad] renders it when the guest flag
 * `overlay_26b` ([OVERLAY_26B_FLAG]) is on. Same-process only: the viewer
 * runs in the app process that owns the guest engine.
 */
class StoredOverlay(val round: Int, val layout: OverlayLayout, val images: Map<String, ByteArray>)

object StreamOverlayStore {
    private val _flow = MutableStateFlow<StoredOverlay?>(null)
    val flow: StateFlow<StoredOverlay?> = _flow.asStateFlow()

    fun current(): StoredOverlay? = _flow.value

    /** Stores [message] for [round]; unparseable images are skipped. Pure except the store write. */
    fun publish(round: Int, message: LinkMsg.StreamOverlay) {
        val layout = OverlayCfg.parse(message.cfg, message.name)
        val images = LinkedHashMap<String, ByteArray>()
        message.images.forEach { image ->
            if (image.name.isEmpty() || image.base64.isEmpty() || images.containsKey(image.name)) return@forEach
            runCatching { Base64.getDecoder().decode(image.base64) }
                .getOrNull()?.takeIf { it.isNotEmpty() }?.let { images[image.name] = it }
        }
        _flow.value = StoredOverlay(round, layout, images)
    }

    fun clear() {
        _flow.value = null
    }
}

/**
 * The guest's touch controls when the host shares its RetroArch overlay
 * (26b). When [useHostOverlay] is off, no overlay arrived, or the play page
 * is unusable ([MappedOverlay.usableForPlay] false: not normalized, no
 * gamepad controls, or anything unmapped), this renders the 26a layout for
 * [profile] instead — the explicit fallback, never silently missing
 * controls. [overlay]/[overlayImages] override the store for previews and
 * tests; null follows [StreamOverlayStore].
 *
 * Overlay coordinates are full-screen fractions like RetroArch, so this
 * covers the whole screen (no safe-area padding). Hit testing is its own
 * small tester: one pointer owns one descriptor until lift, sticks keep
 * capture outside their art, and sliding transfers between non-stick
 * descriptors (stock diagonal `up|left` descs make d-pad rolls work).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun StreamOverlayPad(
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
    overlay: OverlayLayout? = null,
    overlayImages: Map<String, ByteArray>? = null,
    useHostOverlay: Boolean = false,
    onEffectiveSize: (Int) -> Unit = {},
) {
    val stored by StreamOverlayStore.flow.collectAsState()
    val layout = overlay ?: stored?.layout
    val mapped = remember(layout) { layout?.mapped() }
    if (!useHostOverlay || mapped == null || !mapped.usableForPlay) {
        StreamTouchPad(
            onTouchKey = onTouchKey,
            onTouchAxes = onTouchAxes,
            onReleaseTouchInput = onReleaseTouchInput,
            modifier = modifier,
            unbufferedInput = unbufferedInput,
            profile = profile,
            prefs = prefs,
            viewport = viewport,
            onTouchState = onTouchState,
            menuOpen = menuOpen,
            onMenu = onMenu,
            onEffectiveSize = onEffectiveSize,
        )
        return
    }
    // The host's overlay art is full-screen fractions: no size clamp applies.
    LaunchedEffect(prefs.sizePercent) { onEffectiveSize(prefs.validated().sizePercent) }
    OverlayPadSurface(
        mapped = mapped,
        images = overlayImages ?: stored?.images ?: emptyMap(),
        onTouchKey = onTouchKey,
        onTouchAxes = onTouchAxes,
        onReleaseTouchInput = onReleaseTouchInput,
        modifier = modifier,
        unbufferedInput = unbufferedInput,
        prefs = prefs,
        onTouchState = onTouchState,
        menuOpen = menuOpen,
        onMenu = onMenu,
    )
}

internal const val TAG_OVERLAY_PAD: String = "stream-overlay-pad"
internal fun overlayControlTag(index: Int): String = "stream-overlay-control-$index"

internal class ResolvedOverlayDesc(val mapped: MappedDesc, val cx: Float, val cy: Float, val rx: Float, val ry: Float) {
    fun hit(x: Float, y: Float): Boolean = when (mapped.desc.shape) {
        OverlayShape.RECT -> abs(x - cx) <= rx && abs(y - cy) <= ry
        OverlayShape.RADIAL -> {
            val dx = (x - cx) / rx
            val dy = (y - cy) / ry
            dx * dx + dy * dy <= 1f
        }
    }
}

internal data class OverlayVisual(val pressed: Set<Int> = emptySet(), val knobs: Map<Int, Offset> = emptyMap()) {
    companion object {
        val NEUTRAL: OverlayVisual = OverlayVisual()
    }
}

/** Pointer→descriptor ownership for one resolved overlay page. Not thread-safe; input thread only. */
internal class OverlayPadTester(
    private val descs: List<ResolvedOverlayDesc>,
    private val deadZoneFraction: Float,
    private val invertRightStickY: Boolean,
    private val floatingSticks: Boolean,
) {
    private class Capture(val index: Int, var cx: Float, var cy: Float, var px: Float, var py: Float)

    private val captures = LinkedHashMap<Long, Capture>()
    private var menuArmed = false
    private var menuTap = false
    var menuRect: TouchRectPx? = null

    fun pollMenuTap(): Boolean {
        val tap = menuTap
        menuTap = false
        return tap
    }

    fun onDown(id: Long, x: Float, y: Float): PadState {
        val menu = menuRect
        if (menu != null && x >= menu.left && x <= menu.right && y >= menu.top && y <= menu.bottom) {
            menuArmed = true
            captures[id] = Capture(MENU_CAPTURE, x, y, x, y)
            return state()
        }
        val at = descs.indexOfFirst { it.hit(x, y) }
        if (at < 0) {
            // Empty picture: unassigned until lift; sliding onto controls does nothing.
            captures[id] = Capture(EMPTY_CAPTURE, x, y, x, y)
            return state()
        }
        val d = descs[at]
        val stick = d.mapped.output is OverlayOutput.Stick
        captures[id] = Capture(at, if (stick && floatingSticks) x else d.cx, if (stick && floatingSticks) y else d.cy, x, y)
        return state()
    }

    fun onMove(id: Long, x: Float, y: Float): PadState {
        val cap = captures[id] ?: return state()
        if (cap.index == MENU_CAPTURE || cap.index == EMPTY_CAPTURE) return state()
        val d = descs[cap.index]
        cap.px = x
        cap.py = y
        if (d.mapped.output is OverlayOutput.Stick) return state()
        // Slide transfer between non-stick descriptors; sticks never grab by sliding.
        val at = descs.indexOfFirst { it.hit(x, y) && it.mapped.output !is OverlayOutput.Stick }
        if (at >= 0 && at != cap.index) captures[id] = Capture(at, descs[at].cx, descs[at].cy, x, y)
        return state()
    }

    fun onUp(id: Long, x: Float, y: Float): PadState {
        val cap = captures.remove(id)
        if (cap != null && cap.index == MENU_CAPTURE) {
            val menu = menuRect
            if (menuArmed && menu != null && x >= menu.left && x <= menu.right && y >= menu.top && y <= menu.bottom) {
                menuTap = true
            }
            menuArmed = false
        }
        return state()
    }

    fun onCancel(id: Long) {
        if (captures.remove(id)?.index == MENU_CAPTURE) menuArmed = false
    }

    fun cancelAll() {
        captures.clear()
        menuArmed = false
        menuTap = false
    }

    fun state(): PadState {
        var buttons = 0
        var up = false
        var down = false
        var left = false
        var right = false
        var lx = 0
        var ly = 0
        var rx = 0
        var ry = 0
        var lt = 0
        var rt = 0
        captures.values.forEach { cap ->
            if (cap.index < 0 || cap.index >= descs.size) return@forEach
            val d = descs[cap.index]
            when (val output = d.mapped.output) {
                is OverlayOutput.Buttons -> buttons = buttons or output.bits
                is OverlayOutput.Hat -> {
                    up = up || output.up
                    down = down || output.down
                    left = left || output.left
                    right = right || output.right
                }
                is OverlayOutput.Trigger ->
                    if (output.left) {
                        buttons = buttons or PadState.L2
                        lt = 255
                    } else {
                        buttons = buttons or PadState.R2
                        rt = 255
                    }
                is OverlayOutput.Triggers -> {
                    if (output.left) {
                        buttons = buttons or PadState.L2
                        lt = 255
                    }
                    if (output.right) {
                        buttons = buttons or PadState.R2
                        rt = 255
                    }
                }
                is OverlayOutput.StickDir -> {
                    // Holders push to ±127: opposites cancel, diagonals
                    // add; coerced below.
                    if (output.right) {
                        rx += output.dx * 127
                        ry += output.dy * 127
                    } else {
                        lx += output.dx * 127
                        ly += output.dy * 127
                    }
                }
                is OverlayOutput.Stick -> {
                    val travel = minOf(d.rx, d.ry)
                    val sample = overlayStickOutput(cap.px - cap.cx, cap.py - cap.cy, travel, deadZoneFraction)
                    if (output.right) {
                        rx = sample[0]
                        ry = if (invertRightStickY) -sample[1] else sample[1]
                    } else {
                        lx = sample[0]
                        ly = sample[1]
                    }
                }
                OverlayOutput.Ignored, OverlayOutput.Unknown -> Unit
            }
        }
        return PadState(
            buttons,
            lx.coerceIn(-127, 127), ly.coerceIn(-127, 127),
            rx.coerceIn(-127, 127), ry.coerceIn(-127, 127),
            lt, rt, PadState.hatFrom(up, down, left, right),
        )
    }

    fun visual(): OverlayVisual {
        val pressed = mutableSetOf<Int>()
        val knobs = mutableMapOf<Int, Offset>()
        captures.values.forEach { cap ->
            if (cap.index < 0 || cap.index >= descs.size) return@forEach
            pressed += cap.index
            val d = descs[cap.index]
            if (d.mapped.output is OverlayOutput.Stick) {
                val travel = minOf(d.rx, d.ry)
                val dx = cap.px - cap.cx
                val dy = cap.py - cap.cy
                val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                val clamp = if (travel > 0f && dist > travel) travel / dist else 1f
                knobs[cap.index] = Offset(cap.cx + dx * clamp, cap.cy + dy * clamp)
            }
        }
        if (pressed.isEmpty()) return OverlayVisual.NEUTRAL
        return OverlayVisual(pressed, knobs)
    }

    private companion object {
        const val MENU_CAPTURE: Int = -1
        const val EMPTY_CAPTURE: Int = -2
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun OverlayPadSurface(
    mapped: MappedOverlay,
    images: Map<String, ByteArray>,
    onTouchKey: (Int, Boolean, Long) -> Unit,
    onTouchAxes: (Float, Float, Long) -> Unit,
    onReleaseTouchInput: () -> Unit,
    modifier: Modifier = Modifier,
    unbufferedInput: Boolean = false,
    prefs: TouchPrefs = TouchPrefs(),
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
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var active by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    val view = LocalView.current
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

    // Decoded once per message; corrupt entries fall back to vector art.
    val bitmaps = remember(images) {
        images.mapNotNull { (name, bytes) ->
            runCatching {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()?.let { name to it }
            }.getOrNull()
        }.toMap()
    }

    BoxWithConstraints(modifier.fillMaxSize().testTag(TAG_OVERLAY_PAD)) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val menuSizePx = with(density) { 48.dp.toPx() }
        val descs = remember(mapped, widthPx, heightPx) {
            mapped.gamepad.map { entry ->
                val d = entry.desc
                ResolvedOverlayDesc(entry, d.x * widthPx, d.y * heightPx, d.w * widthPx, d.h * heightPx)
            }
        }
        val menuRect = remember(widthPx, menuSizePx) {
            TouchRectPx(widthPx / 2f - menuSizePx / 2f, 8f, widthPx / 2f + menuSizePx / 2f, 8f + menuSizePx)
        }
        val page = mapped.page
        val tester = remember(descs, effectivePrefs) {
            OverlayPadTester(
                descs,
                deadZoneFraction = effectivePrefs.deadZonePercent / 100f,
                invertRightStickY = effectivePrefs.invertRightStickY,
                floatingSticks = effectivePrefs.stickMode == TouchStickMode.FLOATING,
            ).also { it.menuRect = menuRect }
        }
        val legacy = remember {
            LegacyTouchPublisher(
                { key, down, atNs -> keyCallback(key, down, atNs) },
                { x, y, atNs -> axesCallback(x, y, atNs) },
            )
        }
        val session = remember(tester) { OverlayPublishSession() }
        var visual by remember(tester) { mutableStateOf(OverlayVisual.NEUTRAL) }

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
            visual = OverlayVisual.NEUTRAL
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
                atNs - session.lastPulseNs >= OVERLAY_HAPTIC_MIN_INTERVAL_NS
            ) {
                session.lastPulseNs = atNs
                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            }
            session.lastButtons = state.buttons
            val next = tester.visual()
            if (next != visual) visual = next
        }

        releaseRef[0] = { releaseAll(System.nanoTime()) }
        DisposableEffect(tester) {
            onDispose {
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
        val outlineWidth = with(density) { 1.5.dp.toPx() }
        Canvas(Modifier.fillMaxSize()) {
            drawOverlayPage(page?.image?.let(bitmaps::get), size.width, size.height)
            descs.forEachIndexed { index, d ->
                drawOverlayDesc(d, bitmaps[d.mapped.desc.image], index in visual.pressed, visual.knobs[index], opacityFactor, outlineWidth)
            }
            drawOverlayMenu(menuRect, opacityFactor, outlineWidth)
        }
        OverlayPadLabels(descs, bitmaps)
    }
}

private class OverlayPublishSession {
    var lastPublished: PadState = PadState.NEUTRAL
    var lastButtons: Int = 0
    var lastPulseNs: Long = 0L
}

private const val OVERLAY_HAPTIC_MIN_INTERVAL_NS: Long = 80_000_000L

private fun DrawScope.drawOverlayPage(base: ImageBitmap?, widthPx: Float, heightPx: Float) {
    if (base == null) return
    drawImage(base, dstOffset = IntOffset.Zero, dstSize = IntSize(widthPx.toInt(), heightPx.toInt()))
}

private fun DrawScope.drawOverlayDesc(
    d: ResolvedOverlayDesc,
    art: ImageBitmap?,
    pressed: Boolean,
    knob: Offset?,
    opacityFactor: Float,
    outlineWidth: Float,
) {
    if (art != null) {
        val left = (d.cx - d.rx).roundToInt()
        val top = (d.cy - d.ry).roundToInt()
        val w = (d.rx * 2f).roundToInt().coerceAtLeast(1)
        val h = (d.ry * 2f).roundToInt().coerceAtLeast(1)
        drawImage(art, dstOffset = IntOffset(left, top), dstSize = IntSize(w, h))
        if (pressed) {
            drawRect(Color.White.copy(alpha = 0.28f), Offset(left.toFloat(), top.toFloat()), Size(w.toFloat(), h.toFloat()))
        }
        return
    }
    val outline = (if (pressed) 1.00f else 0.55f * opacityFactor).coerceIn(0f, 1f)
    val fill = (if (pressed) 0.28f else 0.10f * opacityFactor).coerceIn(0f, 1f)
    val outlineColor = Color.White.copy(alpha = outline)
    val fillColor = Color(0xFF222222).copy(alpha = fill)
    when (d.mapped.desc.shape) {
        OverlayShape.RADIAL -> {
            drawOval(fillColor, Offset(d.cx - d.rx, d.cy - d.ry), Size(d.rx * 2f, d.ry * 2f))
            drawOval(outlineColor, Offset(d.cx - d.rx, d.cy - d.ry), Size(d.rx * 2f, d.ry * 2f), style = Stroke(outlineWidth))
            if (d.mapped.output is OverlayOutput.Stick) {
                val at = knob ?: Offset(d.cx, d.cy)
                val knobR = minOf(d.rx, d.ry) * 0.45f
                drawCircle(Color.White.copy(alpha = (if (pressed) 0.40f else 0.18f) * opacityFactor + 0.12f), knobR, at)
            }
        }
        OverlayShape.RECT -> {
            val topLeft = Offset(d.cx - d.rx, d.cy - d.ry)
            val size = Size(d.rx * 2f, d.ry * 2f)
            drawRoundRect(fillColor, topLeft, size, CornerRadius(outlineWidth * 4f))
            drawRoundRect(outlineColor, topLeft, size, CornerRadius(outlineWidth * 4f), style = Stroke(outlineWidth))
        }
    }
}

private fun DrawScope.drawOverlayMenu(menu: TouchRectPx, opacityFactor: Float, outlineWidth: Float) {
    val outline = (0.55f * opacityFactor).coerceIn(0f, 1f)
    drawRoundRect(
        Color.White.copy(alpha = outline),
        Offset(menu.left, menu.top),
        Size(menu.width, menu.height),
        CornerRadius(outlineWidth * 4f),
        style = Stroke(outlineWidth),
    )
}

/** Short console-style label for a descriptor input; sticks and art-covered descs stay blank. */
private fun overlayDescLabel(input: String, hasArt: Boolean): String {
    if (hasArt) return ""
    return when (input.substringBefore('|')) {
        "a" -> "A"
        "b" -> "B"
        "x" -> "X"
        "y" -> "Y"
        "l" -> "L"
        "r" -> "R"
        "l2" -> "L2"
        "r2" -> "R2"
        "l3" -> "L3"
        "r3" -> "R3"
        "start" -> "Start"
        "select" -> "Select"
        "up" -> "▲"
        "down" -> "▼"
        "left" -> "◀"
        "right" -> "▶"
        else -> ""
    }
}

@Composable
private fun OverlayPadLabels(descs: List<ResolvedOverlayDesc>, bitmaps: Map<String, ImageBitmap>) {
    val density = LocalDensity.current
    Box(Modifier.fillMaxSize()) {
        Text(
            "⋯",
            color = Color.White.copy(alpha = 0.85f),
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.TopCenter).offset(y = 8.dp),
        )
        descs.forEachIndexed { index, d ->
            val label = overlayDescLabel(d.mapped.desc.input, bitmaps.containsKey(d.mapped.desc.image))
            if (label.isEmpty()) return@forEachIndexed
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            (d.cx - d.rx).roundToInt(),
                            (d.cy - with(density) { 9.sp.toPx() }).roundToInt(),
                        )
                    }
                    .size(with(density) { (d.rx * 2f).toDp() }, with(density) { 18.sp.toDp() })
                    .testTag(overlayControlTag(index)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
    }
}
