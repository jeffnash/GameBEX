package com.gamebex.client.stream.guest

import android.graphics.BitmapFactory
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.os.SystemClock
import android.util.Log
import com.gamebex.client.stream.StreamDebug
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.motionEventSpy
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.gamebex.client.stream.proto.PadState
import com.gamebex.client.together.LinkMsg
import java.util.Base64
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow


internal data class StoredEmuOverlay(
    val round: Int,
    val backend: String,
    val controls: List<LinkMsg.EmuControl>,

    val art: Map<String, ByteArray>,

    val hostScreenW: Int,
    val hostScreenH: Int,
    val hostScreenX: Int = 0,
    val hostScreenY: Int = 0,
    val hostDisplayW: Int = 0,
    val hostDisplayH: Int = 0,

    val hostCropX: Int = 0,
    val hostCropY: Int = 0,
    val hostCropW: Int = 0,
    val hostCropH: Int = 0,
    val partsReceived: Int,
    val partCount: Int,
    val truncated: Boolean,
)

internal fun emuArtKey(id: String, slot: String): String = "$id/$slot"


internal object EmuOverlayStore {
    private val _flow = MutableStateFlow<StoredEmuOverlay?>(null)
    val flow: StateFlow<StoredEmuOverlay?> = _flow.asStateFlow()

    private val _statusLine = MutableStateFlow<String?>(null)
    val statusLine: StateFlow<String?> = _statusLine.asStateFlow()


    var onMissingControls: ((round: Int, missing: List<String>) -> Unit)? = null
    private var requestedRound: Int = -1
    private var dumpedRound: Int = -1
    private val receivedParts = mutableSetOf<Int>()

    fun current(): StoredEmuOverlay? = _flow.value

    fun clear() {
        _flow.value = null
        _statusLine.value = null
        requestedRound = -1
        dumpedRound = -1
        receivedParts.clear()
    }

    fun publish(round: Int, message: LinkMsg.StreamEmuOverlay) {
        if (_flow.value?.round?.let { round < it } == true || message.partCount !in 1..128 || message.partIndex < -1 || message.partIndex >= message.partCount) return
        if (message.controls.size > 128 || message.controls.any { c ->
            c.kind !in 0..2 || c.w !in 1..16384 || c.h !in 1..16384 || c.alpha !in 0..255 ||
                !c.stickTravelPx.isFinite() || c.stickTravelPx !in 0f..16384f ||
                !validRenderRect(c.renderRect) || !validRenderRect(c.knobRect) || c.textureInset !in 0..1 ||
                (c.textureInset == 1 && c.renderRect.isEmpty()) ||
                listOf(c.renderGray, c.pressedRenderGray, c.knobRenderGray).any { !it.isFinite() || it !in 0f..1f }
        }) return
        val current = _flow.value?.takeIf { it.round == round && it.backend == message.backend }
        if (message.partIndex == 0) {
            if (current == null) receivedParts.clear()
            receivedParts.add(0)
            requestedRound = -1
            val controls = message.controls.filter { it.id.isNotEmpty() }.map { incoming ->
                current?.controls?.firstOrNull { it.id == incoming.id }?.let { mergeControl(it, incoming) } ?: incoming
            }
            _flow.value = StoredEmuOverlay(
                round = round,
                backend = message.backend,
                controls = controls,
                art = mergeArt(current?.art.orEmpty(), decodeArt(message.controls)),
                hostScreenW = message.screenW,
                hostScreenH = message.screenH,
                hostScreenX = message.screenX,
                hostScreenY = message.screenY,
                hostDisplayW = message.displayW,
                hostDisplayH = message.displayH,
                hostCropX = message.cropX,
                hostCropY = message.cropY,
                hostCropW = message.cropW,
                hostCropH = message.cropH,
                partsReceived = receivedParts.size,
                partCount = message.partCount.coerceAtLeast(1),
                truncated = message.truncated,
            )
        } else if (current != null && message.partIndex != 0) {
            val merged = current.controls.toMutableList()
            message.controls.forEach { incoming ->
                if (incoming.id.isEmpty()) return@forEach
                val at = merged.indexOfFirst { it.id == incoming.id }
                if (at < 0) merged += incoming
                else merged[at] = mergeControl(merged[at], incoming)
            }
            val art = HashMap(current.art)
            art.putAll(mergeArt(current.art, decodeArt(message.controls)))
            if (message.partIndex > 0) receivedParts.add(message.partIndex)
            val received = receivedParts.size
            _flow.value = current.copy(
                controls = merged,
                art = art,
                partsReceived = received,
                truncated = current.truncated || message.truncated,
            )
        } else {
            return
        }
        afterPublish(round)
    }


    private fun validRenderRect(rect: List<Float>): Boolean = rect.isEmpty() ||
        (rect.size == 4 && rect.all(Float::isFinite) && rect[2] > 0f && rect[2] <= 16384f && rect[3] > 0f && rect[3] <= 16384f)

    private fun mergeControl(into: LinkMsg.EmuControl, from: LinkMsg.EmuControl): LinkMsg.EmuControl {
        val cells = if (from.cells.isNotEmpty()) {
            from.cells.mapIndexed { i, cell ->
                val old = into.cells.getOrNull(i)
                if (old == null) cell
                else cell.copy(
                    art = cell.art.ifEmpty { old.art },
                    pressedArt = cell.pressedArt.ifEmpty { old.pressedArt },
                )
            }
        } else into.cells
        return into.copy(
            art = from.art.ifEmpty { into.art },
            pressedArt = from.pressedArt.ifEmpty { into.pressedArt },
            pressedArt2 = from.pressedArt2.ifEmpty { into.pressedArt2 },
            knobArt = from.knobArt.ifEmpty { into.knobArt },
            knobPressedArt = from.knobPressedArt.ifEmpty { into.knobPressedArt },
            cells = cells,
        )
    }

    private fun mergeArt(old: Map<String, ByteArray>, incoming: Map<String, ByteArray>): Map<String, ByteArray> = old + incoming.mapValues { (key, bytes) -> old[key]?.takeIf { it.contentEquals(bytes) } ?: bytes }

    internal fun decodeArt(controls: List<LinkMsg.EmuControl>): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        fun put(key: String, base64: String) {
            if (base64.isEmpty() || base64.length > HostOverlayBundle.MAX_IMAGE_BYTES * 4 / 3 + 4 || out.containsKey(key)) return
            runCatching { Base64.getDecoder().decode(base64) }
                .getOrNull()?.takeIf { it.isNotEmpty() && it.size <= HostOverlayBundle.MAX_IMAGE_BYTES }?.let { out[key] = it }
        }
        controls.forEach { c ->
            put(emuArtKey(c.id, "art"), c.art)
            put(emuArtKey(c.id, "pressed"), c.pressedArt)
            put(emuArtKey(c.id, "pressed2"), c.pressedArt2)
            put(emuArtKey(c.id, "knob"), c.knobArt)
            put(emuArtKey(c.id, "knobPressed"), c.knobPressedArt)
            c.cells.forEachIndexed { i, cell ->
                put(emuArtKey(c.id, "cell$i"), cell.art)
                put(emuArtKey(c.id, "cellPressed$i"), cell.pressedArt)
            }
        }
        return out
    }


    private fun LinkMsg.EmuControl.restArtMissing(): Boolean =
        !noArt && (art.isEmpty() && cells.isEmpty() || cells.any { it.art.isEmpty() })

    private fun afterPublish(round: Int) {
        val stored = _flow.value?.takeIf { it.round == round } ?: return
        val missing = stored.controls.filter { it.restArtMissing() }.map { it.id }
        if (stored.partsReceived >= stored.partCount || stored.truncated) {
            if (dumpedRound != round) {
                dumpedRound = round
                OverlayMatchDump.maybeDumpEmu(stored)
            }
            if (missing.isNotEmpty() && requestedRound != round) {
                requestedRound = round
                onMissingControls?.invoke(round, missing)
            }
            _statusLine.value = degradedLine(stored)
        } else {
            _statusLine.value = null
        }
    }

    private fun degradedLine(stored: StoredEmuOverlay): String? {
        val missing = stored.controls.count { it.restArtMissing() }
        if (missing == 0 && !stored.truncated) return null
        return "OVERLAY_DEGRADED emu ${stored.backend} $missing/${stored.controls.size} art missing"
    }


    fun degradedIds(): Set<String> =
        _flow.value?.controls?.filter { it.restArtMissing() }?.map { it.id }?.toSet() ?: emptySet()
}


internal class ResolvedEmuControl(
    val control: LinkMsg.EmuControl,
    canvasW: Float,
    canvasH: Float,
    hostW: Int,
    hostH: Int,
    hostX: Int = 0,
    hostY: Int = 0,
    displayW: Int = 0,
    displayH: Int = 0,
) {


    private val dw: Int = if (displayW > 0) displayW else hostW
    private val dh: Int = if (displayH > 0) displayH else hostH
    val sx: Float = if (dw > 0) canvasW / dw else 1f
    val sy: Float = if (dh > 0) canvasH / dh else 1f
    val x: Float = (control.x + hostX) * sx
    val y: Float = (control.y + hostY) * sy
    val w: Float = control.w * sx
    val h: Float = control.h * sy
    val cx: Float = x + w / 2f
    val cy: Float = y + h / 2f

    val renderRect: RectF? = scaledRect(control.renderRect, hostX, hostY)
    val knobRect: RectF? = scaledRect(control.knobRect, hostX, hostY)

    private fun scaledRect(rect: List<Float>, hostX: Int, hostY: Int): RectF? = if (rect.size == 4) RectF(
        (rect[0] + hostX) * sx, (rect[1] + hostY) * sy,
        (rect[0] + hostX + rect[2]) * sx, (rect[1] + hostY + rect[3]) * sy,
    ) else null

    fun contains(px: Float, py: Float): Boolean =
        px >= x && px < x + w && py >= y && py < y + h
}


internal class EmuPadTester(
    private val backend: String,
    private val controls: List<ResolvedEmuControl>,
    private val nowMs: () -> Long = SystemClock::uptimeMillis,
) {
    private class Capture(
        val index: Int,
        var px: Float,
        var py: Float,

        var ox: Float = 0f,
        var oy: Float = 0f,

        var bx: Float = 0f,
        var by: Float = 0f,
        var dirs: BooleanArray = booleanArrayOf(false, false, false, false),
    )







    private val captures = linkedMapOf<Long, Capture>()
    private val netherButtons = linkedMapOf<Long, Pair<Int, Int>>()
    private val melonFacePointers = linkedMapOf<Long, Pair<Float, Float>>()
    private val latched = linkedSetOf<Int>()
    private val started = mutableMapOf<Int, Long>()
    private val actions = ArrayDeque<OverlayActionTap>()

    fun pollActionTap(): OverlayActionTap? = if (actions.isEmpty()) null else actions.removeFirst()
    fun hasTurbo(): Boolean = (captures.values.map { it.index } + latched).any { controls[it].control.turboFrames > 0 }
    private fun at(x: Float, y: Float): Int = controls.indexOfFirst { it.contains(x, y) && (it.control.kind != 0 || it.control.output !is OverlayOutput.Ignored) }

    private fun melonFaceBox(): FloatArray? {
        val faces = controls.filter { it.control.id in MelondsOverlay.BUTTON_IDS }
        if (faces.size != 4) return null
        return floatArrayOf(faces.minOf { it.x }, faces.minOf { it.y }, faces.maxOf { it.x + it.w }, faces.maxOf { it.y + it.h })
    }

    private fun melonFacePressed(): Set<Int> {
        val box = melonFaceBox() ?: return emptySet()
        val directions = BooleanArray(4)
        melonFacePointers.values.forEach { (x, y) ->
            val dirs = MelondsOverlay.dpadDirs(x - box[0], y - box[1], box[2] - box[0], box[3] - box[1])
            dirs.indices.forEach { directions[it] = directions[it] || dirs[it] }
        }
        val names = listOf("X", "B", "Y", "A")
        return controls.indices.filter { index -> names.indexOf(controls[index].control.id).let { it >= 0 && directions[it] } }.toSet()
    }

    fun onDown(id: Long, x: Float, y: Float): PadState {
        onCancel(id)
        if (backend == "melonds") {
            val box = melonFaceBox()
            if (box != null && x >= box[0] && x < box[2] && y >= box[1] && y < box[3]) {
                melonFacePointers[id] = x to y
                return state()
            }
        }
        val index = at(x, y)
        if (backend == "nethersx2" && (index < 0 || controls[index].control.kind == 0)) {
            val button = index.takeIf { it >= 0 && !controls[it].control.latching } ?: -1
            val sticky = button.takeIf { it >= 0 && controls[it].control.glide } ?: -1
            netherButtons[id] = sticky to button
            if (index >= 0) {
                val c = controls[index].control
                if (c.latching) { if (!latched.add(index)) latched.remove(index) }
                if (c.output is OverlayOutput.MenuToggle) actions.add(OverlayActionTap.Menu)
            }
            return state()
        }
        if (index < 0) return state()
        val r = controls[index]
        val c = r.control
        if (c.output is OverlayOutput.MenuToggle) actions.add(OverlayActionTap.Menu)
        if (c.latching) { if (!latched.add(index)) latched.remove(index) }
        val cap = Capture(index, x, y, r.cx, r.cy)
        if (backend == "flycast" && c.kind == 2) { cap.ox = x; cap.oy = y }
        if (c.kind == 2 && c.recenter && backend != "flycast") {
            var dx = x - r.cx
            var dy = y - r.cy
            if (backend == "armsx2") {
                val limit = r.w / 2f * (1f - 0.66f)
                val length = kotlin.math.hypot(dx, dy)
                if (length > limit) { dx *= limit / length; dy *= limit / length }
            }
            cap.ox = r.cx + dx; cap.oy = r.cy + dy
        }
        captures[id] = cap
        started.putIfAbsent(index, nowMs())
        if (c.kind == 1) refreshDpad(cap)
        return state()
    }

    fun onMove(id: Long, x: Float, y: Float): PadState {
        if (id in netherButtons) {
            val initial = netherButtons.getValue(id).first
            val current = at(x, y).takeIf { it >= 0 && controls[it].control.kind == 0 && !controls[it].control.latching } ?: -1
            netherButtons[id] = initial to current
            return state()
        }
        if (id in melonFacePointers) { melonFacePointers[id] = x to y; return state() }
        val cap = captures[id] ?: return state()
        cap.px = x; cap.py = y
        if (controls[cap.index].control.kind == 1) refreshDpad(cap)
        return state()
    }
    fun onUp(id: Long, x: Float, y: Float): PadState { onCancel(id); return state() }
    fun onCancel(id: Long) {
        netherButtons.remove(id)
        melonFacePointers.remove(id)
        val old = captures.remove(id) ?: return
        if (old.index !in latched && captures.values.none { it.index == old.index }) started.remove(old.index)
    }
    fun cancelAll() { captures.clear(); netherButtons.clear(); melonFacePointers.clear(); latched.clear(); started.clear(); actions.clear() }

    private fun refreshDpad(cap: Capture) {
        val r = controls[cap.index]
        val x = (cap.px - r.x) / r.sx
        val y = (cap.py - r.y) / r.sy
        val c = r.control
        cap.dirs = when (backend) {
            "dolphin" -> DolphinOverlay.dpadDirs(x.toInt(), y.toInt(), 0, 0, c.w, c.h, c.w, c.h)
            "armsx2" -> ArmsOverlay.dpadDirs(x - c.w / 2f, y - c.h / 2f, c.w / 2f, c.dpadGap)
            "nethersx2" -> NetherOverlay.dpadDirs(x.toInt(), y.toInt(), c.w, c.h)
            "melonds" -> MelondsOverlay.dpadDirs(x, y, c.w.toFloat(), c.h.toFloat())
            "ppsspp" -> if (!c.stickyDpad && !r.contains(cap.px, cap.py)) BooleanArray(4) else PpssppOverlay.dpadDirs(x, y, c.w.toFloat(), c.h.toFloat(), !c.dpadDiagonals)
            else -> BooleanArray(4)
        }
    }
    private fun refreshStick(cap: Capture) { knobOffset(cap) }
    private fun turboOn(index: Int): Boolean {
        val period = ArmsOverlay.turboPeriodMs(controls[index].control.turboFrames)
        return period <= 0 || (nowMs() - (started[index] ?: nowMs())) / period % 2L == 0L
    }

    fun state(): PadState {
        val out = OverlayStateAccumulator()
        val active = captures.values.filter { !controls[it.index].control.latching }.map { it.index }.toSet() + latched + netherButtons.values.flatMap { listOf(it.first, it.second) }.filter { it >= 0 }.toSet() + (if (backend == "melonds") melonFacePressed() else emptySet())
        active.forEach { index ->
            if (!turboOn(index)) return@forEach
            val r = controls[index]
            val c = r.control
            val cap = captures.values.lastOrNull { it.index == index }
            when {
                c.kind == 1 && cap != null -> out.add(OverlayOutput.Hat(cap.dirs[0], cap.dirs[1], cap.dirs[2], cap.dirs[3]))
                c.kind == 2 && cap != null -> { val axes = stickAxis(cap); out.add(c.output, axes.first, axes.second) }
                else -> out.add(c.output)
            }
        }
        return out.state()
    }

    private fun stickAxis(cap: Capture): Pair<Int, Int> {
        val r = controls[cap.index]
        val c = r.control
        val dx = (cap.px - cap.ox) / r.sx
        val dy = (cap.py - cap.oy) / r.sy
        val axes = when (backend) {
            "dolphin" -> DolphinOverlay.clampStickGate((dx / (c.w / 2f)).toDouble(), (dy / (c.h / 2f)).toDouble(), when (c.id) { "STICK_C" -> 0.7221375; "NUNCHUK_STICK" -> 96.0 / 127; else -> 0.7937125 })
            "armsx2" -> ArmsOverlay.stickSample(dx, dy, c.w / 2f).let { it.nx to it.ny }
            "nethersx2" -> NetherOverlay.axisSample((cap.px - r.x) / r.sx, (cap.py - r.y) / r.sy, c.w, c.h, c.padPx).let { it.nx to it.ny }
            "flycast" -> {
                val width = c.knobRect.getOrNull(2) ?: c.knobSize.toFloat()
                FlycastOverlay.stickAxis(dx, 0f, width) to FlycastOverlay.stickAxis(dy, 0f, width)
            }
            "ppsspp" -> PpssppOverlay.stickSample(dx, dy, c.stickTravelPx.takeIf { it.isFinite() && it > 0f && it <= 16384f } ?: (c.w / 2f)).let { it.nx to it.ny }
            else -> 0f to 0f
        }
        return PadState.axisFromFloat(axes.first) to PadState.axisFromFloat(axes.second)
    }

    fun visual(): EmuVisual {
        val active = captures.values.filter { !controls[it.index].control.latching }.map { it.index }.toSet() + latched + netherButtons.values.flatMap { listOf(it.first, it.second) }.filter { it >= 0 }.toSet() + (if (backend == "melonds") melonFacePressed() else emptySet())
        return EmuVisual(active.filter { turboOn(it) }.toSet(),
            captures.values.filter { controls[it.index].control.kind == 2 }.associate { it.index to knobOffset(it) },
            captures.values.filter { controls[it.index].control.kind == 2 && controls[it.index].control.recenter && backend != "flycast" }.associate { cap ->
                val r = controls[cap.index]; cap.index to Offset(cap.ox - r.cx, cap.oy - r.cy)
            }, captures.values.filter { controls[it.index].control.kind == 1 }.associate { it.index to it.dirs.copyOf() })
    }

    private fun knobOffset(cap: Capture): Offset {
        val r = controls[cap.index]
        val c = r.control
        val dx = (cap.px - cap.ox) / r.sx
        val dy = (cap.py - cap.oy) / r.sy
        return when (backend) {
            "armsx2" -> ArmsOverlay.stickSample(dx, dy, c.w / 2f).let { Offset(it.thumbDx * r.sx, it.thumbDy * r.sy) }
            "nethersx2" -> NetherOverlay.axisSample((cap.px - r.x) / r.sx, (cap.py - r.y) / r.sy, c.w, c.h, c.padPx).let { Offset(it.knobDx * r.sx, it.knobDy * r.sy) }
            "dolphin" -> {
                val movement = DolphinOverlay.stickVisualDisplacement(dx, dy, c.w, c.h, when (c.id) { "STICK_C" -> 0.7221375; "NUNCHUK_STICK" -> 96.0 / 127; else -> 0.7937125 })
                Offset(movement.first * r.sx, movement.second * r.sy)
            }
            "ppsspp" -> PpssppOverlay.stickSample(dx, dy, c.stickTravelPx.takeIf { it.isFinite() && it > 0f && it <= 16384f } ?: (c.w / 2f)).let { Offset(it.thumbDx * r.sx, it.thumbDy * r.sy) }
            "flycast" -> {
                val width = c.knobRect.getOrNull(2) ?: c.knobSize.toFloat()
                Offset(FlycastOverlay.stickAxis(dx, 0f, width) * width * r.sx, FlycastOverlay.stickAxis(dy, 0f, width) * width * r.sy)
            }
            else -> {
                val axis = stickAxis(cap)
                val travel = c.w / 2
                Offset((axis.first / 127f * travel).toInt() * r.sx, (axis.second / 127f * travel).toInt() * r.sy)
            }
        }
    }

}

internal data class EmuVisual(
    val pressed: Set<Int> = emptySet(),
    val knobDxPx: Map<Int, Offset> = emptyMap(),
    val outerDxPx: Map<Int, Offset> = emptyMap(),
    val dpadDirs: Map<Int, BooleanArray> = emptyMap(),
) {
    companion object {
        val NEUTRAL: EmuVisual = EmuVisual()
    }
}

internal const val TAG_EMU_PAD: String = "stream-emu-pad"


@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun EmuOverlayPad(
    stored: StoredEmuOverlay,
    onTouchKey: (Int, Boolean, Long) -> Unit,
    onTouchAxes: (Float, Float, Long) -> Unit,
    onReleaseTouchInput: () -> Unit,
    modifier: Modifier = Modifier,
    unbufferedInput: Boolean = false,
    prefs: TouchPrefs = TouchPrefs(),
    onTouchState: ((PadState, Long) -> Unit)? = null,
    menuOpen: Boolean = false,
    onMenu: () -> Unit = {},

    videoCrop: VideoCropSignal? = null,
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



    val rawTextureIds = stored.controls.filter { stored.backend == "flycast" && it.textureInset == 1 }.map { it.id }.toSet()
    val bitmaps = remember(stored.art, rawTextureIds) {
        stored.art.mapNotNull { (key, bytes) ->
            runCatching {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inPremultiplied = key.substringBeforeLast('/') !in rawTextureIds })
                    ?.asImageBitmap()?.let { key to it }
            }.getOrNull()
        }.toMap()
    }
    val flycastTextures = remember(bitmaps, rawTextureIds) {
        bitmaps.filterKeys { it.substringBeforeLast('/') in rawTextureIds }.mapValues { FlycastTexture(it.value.asAndroidBitmap()) }
    }
    val degraded = remember(stored) { EmuOverlayStore.degradedIds() }
    val dolphinThumbs = remember(bitmaps, stored.backend, view.resources) {
        if (stored.backend != "dolphin") emptyMap() else bitmaps.filterKeys { it.endsWith("/knob") || it.endsWith("/knobPressed") }
            .mapValues { BitmapDrawable(view.resources, it.value.asAndroidBitmap()) }
    }

    BoxWithConstraints(modifier.fillMaxSize().testTag(TAG_EMU_PAD)) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val metadata = with(com.gamebex.client.together.session.EmuOverlayReader) { stored.controls.map { it.stripArt() } }
        val controls = remember(metadata, widthPx, heightPx, stored.hostScreenW, stored.hostScreenH, stored.hostScreenX, stored.hostScreenY, stored.hostDisplayW, stored.hostDisplayH) {
            metadata.map {
                ResolvedEmuControl(
                    it, widthPx, heightPx, stored.hostScreenW, stored.hostScreenH,
                    stored.hostScreenX, stored.hostScreenY, stored.hostDisplayW, stored.hostDisplayH,
                )
            }
        }


        if (StreamDebug.flag("overlay_boxes")) {
            LaunchedEffect(controls, widthPx, heightPx) {
                Log.i("GameBEXStream", "EMU_BOX canvas=${widthPx.toInt()}x${heightPx.toInt()} backend=${stored.backend} host=${stored.hostScreenW}x${stored.hostScreenH} viewport=${stored.hostScreenX},${stored.hostScreenY},${stored.hostScreenW}x${stored.hostScreenH} display=${stored.hostDisplayW}x${stored.hostDisplayH} n=${controls.size}")
                controls.forEach { r ->
                    val left = (r.x + 0.5f).toInt()
                    val top = (r.y + 0.5f).toInt()
                    val w = (r.w + 0.5f).toInt().coerceAtLeast(1)
                    val h = (r.h + 0.5f).toInt().coerceAtLeast(1)
                    Log.i("GameBEXStream", "EMU_BOX id=${r.control.id} kind=${r.control.kind} left=$left top=$top w=$w h=$h")
                }
            }
        }
        val tester = remember(stored.backend, controls) { EmuPadTester(stored.backend, controls) }
        val legacy = remember {
            LegacyTouchPublisher(
                { key, down, atNs -> keyCallback(key, down, atNs) },
                { x, y, atNs -> axesCallback(x, y, atNs) },
            )
        }
        val session = remember(tester) { EmuPublishSession() }
        var visual by remember(tester) { mutableStateOf(EmuVisual.NEUTRAL) }

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
            visual = EmuVisual.NEUTRAL
            releaseCallback()
        }

        fun handleSample(state: PadState, atNs: Long) {
            publish(state, atNs)
            if (tester.pollActionTap() != null) {
                releaseAll(atNs)
                menuCallback()
                return
            }
            val fresh = state.buttons and session.lastButtons.inv()
            if (fresh != 0 && effectivePrefs.haptics == TouchHaptics.LIGHT &&
                atNs - session.lastPulseNs >= EMU_HAPTIC_MIN_INTERVAL_NS
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

        LaunchedEffect(tester.hasTurbo(), visual.pressed) {
            if (!tester.hasTurbo()) return@LaunchedEffect
            while (true) {
                delay(16L)
                handleSample(tester.state(), System.nanoTime())
                if (!tester.hasTurbo()) return@LaunchedEffect
            }
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
        Canvas(Modifier.fillMaxSize()) {
            clipRect(0f, 0f, size.width, size.height) {


                val clip = videoCrop?.let { s ->
                    videoCropPlacement(
                        s.cropX, s.cropY, s.cropW, s.cropH, s.hostDisplayW, s.hostDisplayH,
                        size.width.toInt(), size.height.toInt(), s.infoW, s.infoH,
                    )
                }
                fun drawRest() {
                    controls.forEachIndexed { index, r ->
                        if (index in visual.pressed) return@forEachIndexed
                        drawEmuControl(
                            backend = stored.backend,
                            r = r,
                            art = { slot -> r.control.id.let { bitmaps[emuArtKey(it, slot)] } },
                            cellArt = { i, pressed -> bitmaps[emuArtKey(r.control.id, if (pressed) "cellPressed$i" else "cell$i")] },
                            pressed = false,
                            knobOffset = visual.knobDxPx[index],
                            outerOffset = visual.outerDxPx[index],
                            dirs = visual.dpadDirs[index],
                            degraded = r.control.id in degraded,
                            dolphinThumb = dolphinThumbs[emuArtKey(r.control.id, "knob")],
                            flycastTexture = { slot -> flycastTextures[emuArtKey(r.control.id, slot)] },
                        )
                    }
                }
                if (clip == null) {
                    drawRest()
                } else {
                    clipRect(
                        clip.x.toFloat(), clip.y.toFloat(),
                        (clip.x + clip.w).toFloat(), (clip.y + clip.h).toFloat(),
                        ClipOp.Difference,
                    ) { drawRest() }
                }
                controls.forEachIndexed { index, r ->
                    if (index !in visual.pressed) return@forEachIndexed
                    drawEmuControl(
                        backend = stored.backend,
                        r = r,
                        art = { slot -> r.control.id.let { bitmaps[emuArtKey(it, slot)] } },
                        cellArt = { i, pressed -> bitmaps[emuArtKey(r.control.id, if (pressed) "cellPressed$i" else "cell$i")] },
                        pressed = true,
                        knobOffset = visual.knobDxPx[index],
                        outerOffset = visual.outerDxPx[index],
                        dirs = visual.dpadDirs[index],
                        degraded = r.control.id in degraded,
                        dolphinThumb = dolphinThumbs[emuArtKey(r.control.id, "knobPressed")] ?: dolphinThumbs[emuArtKey(r.control.id, "knob")],
                        flycastTexture = { slot -> flycastTextures[emuArtKey(r.control.id, slot)] },
                    )
                }
            }
        }
    }
}

private class EmuPublishSession {
    var lastPublished: PadState = PadState.NEUTRAL
    var lastButtons: Int = 0
    var lastPulseNs: Long = 0L
}

private const val EMU_HAPTIC_MIN_INTERVAL_NS: Long = 80_000_000L


private fun DrawScope.drawEmuControl(
    backend: String,
    r: ResolvedEmuControl,
    art: (String) -> ImageBitmap?,
    cellArt: (Int, Boolean) -> ImageBitmap?,
    pressed: Boolean,
    knobOffset: Offset?,
    outerOffset: Offset?,
    dirs: BooleanArray?,
    degraded: Boolean,
    dolphinThumb: BitmapDrawable? = null,
    flycastTexture: (String) -> FlycastTexture? = { null },
) {
    val c = r.control
    val alpha = (c.alpha / 255f).coerceIn(0f, 1f)
    when (c.kind) {
        1 -> drawEmuDpad(backend, r, art, cellArt, pressed, dirs, alpha, degraded)
        2 -> drawEmuStick(backend, r, art, pressed, knobOffset, outerOffset, alpha, degraded, dolphinThumb, flycastTexture)
        else -> {
            val growX = if (pressed) c.pressedGrowPx * r.sx else 0f
            val growY = if (pressed) c.pressedGrowPx * r.sy else 0f
            val image = (if (pressed) art("pressed") ?: art("art") else art("art"))
            if (image != null) {
                if (backend == "flycast" && r.renderRect != null) {
                    drawFractionalImage(image, r.renderRect, c.alpha, if (pressed) c.pressedRenderGray else c.renderGray,
                        c.textureInset, if (pressed) flycastTexture("pressed") ?: flycastTexture("art") else flycastTexture("art"))
                    return
                }
                val left = r.x - growX
                val top = r.y - growY
                val w = r.w + growX * 2f
                val h = r.h + growY * 2f
                fun drawSprite() {
                    drawImage(
                        image,
                        dstOffset = IntOffset((left + 0.5f).toInt(), (top + 0.5f).toInt()),
                        dstSize = IntSize((w + 0.5f).toInt().coerceAtLeast(1), (h + 0.5f).toInt().coerceAtLeast(1)),
                        alpha = alpha,
                        filterQuality = FilterQuality.Medium,
                    )
                }
                if (backend == "armsx2" && pressed) {
                    val s = if (ArmsOverlay.hasPressedSprite(c.id)) ArmsOverlay.PRESSED_SCALE_SPRITE else ArmsOverlay.PRESSED_SCALE_PLAIN
                    scale(s, s, Offset(r.cx, r.cy)) { drawSprite() }
                } else drawSprite()
                return
            }
            if (degraded) drawEmuOutline(r.x, r.y, r.w, r.h, pressed, alpha)
        }
    }
}


private fun DrawScope.drawEmuDpad(
    backend: String,
    r: ResolvedEmuControl,
    art: (String) -> ImageBitmap?,
    cellArt: (Int, Boolean) -> ImageBitmap?,
    pressed: Boolean,
    dirs: BooleanArray?,
    alpha: Float,
    degraded: Boolean,
 ) {
    val image = if (backend == "dolphin" && dirs != null) dolphinDpadVisual(dirs, art) else art("art") to 0f
    if (image.first != null) {
        rotate(image.second, Offset(r.cx, r.cy)) {
            drawImage(image.first!!, dstOffset = IntOffset(r.x.roundToInt(), r.y.roundToInt()), dstSize = IntSize(r.w.roundToInt().coerceAtLeast(1), r.h.roundToInt().coerceAtLeast(1)), alpha = alpha, filterQuality = FilterQuality.Medium)
        }
    } else if (degraded && r.control.cells.isEmpty()) drawEmuOutline(r.x, r.y, r.w, r.h, pressed, alpha)
    r.control.cells.forEachIndexed { index, cell ->
        val active = dirs?.getOrNull(index) == true
        val bitmap = cellArt(index, active) ?: cellArt(index, false) ?: return@forEachIndexed
        if (image.first != null && !active) return@forEachIndexed
        val growX = if (active) r.control.pressedGrowPx * r.sx else 0f
        val growY = if (active) r.control.pressedGrowPx * r.sy else 0f
        val x = r.x + (cell.x - r.control.x) * r.sx - growX
        val y = r.y + (cell.y - r.control.y) * r.sy - growY
        drawImage(bitmap, dstOffset = IntOffset(x.roundToInt(), y.roundToInt()), dstSize = IntSize((cell.w * r.sx + 2 * growX).roundToInt().coerceAtLeast(1), (cell.h * r.sy + 2 * growY).roundToInt().coerceAtLeast(1)), alpha = alpha, filterQuality = FilterQuality.Medium)
    }
}


private fun dolphinDpadVisual(
    dirs: BooleanArray,
    art: (String) -> ImageBitmap?,
 ): Pair<ImageBitmap?, Float> {
    val up = dirs[0]; val down = dirs[1]; val left = dirs[2]; val right = dirs[3]
    if ((up || down) && (left || right)) return art("pressed2") to when { up && left -> 0f; up && right -> 90f; down && right -> 180f; else -> 270f }
    return if (dirs.any { it }) art("pressed") to when { up -> 0f; right -> 90f; down -> 180f; else -> 270f } else art("art") to 0f
}


private fun DrawScope.drawEmuStick(
    backend: String,
    r: ResolvedEmuControl,
    art: (String) -> ImageBitmap?,
    pressed: Boolean,
    knobOffset: Offset?,
    outerOffset: Offset?,
    alpha: Float,
    degraded: Boolean,
    dolphinThumb: BitmapDrawable?,
    flycastTexture: (String) -> FlycastTexture?,
 ) {
    val c = r.control
    val ox = outerOffset?.x ?: 0f
    val oy = outerOffset?.y ?: 0f
    val padX = c.padPx * r.sx
    val padY = c.padPx * r.sy
    val base = if (pressed && backend != "dolphin") art("pressed") ?: art("art") else art("art")
    if (backend == "flycast" && r.renderRect != null) {
        if (base != null) drawFractionalImage(base, r.renderRect, c.alpha, if (pressed) c.pressedRenderGray else c.renderGray,
            c.textureInset, if (pressed) flycastTexture("pressed") ?: flycastTexture("art") else flycastTexture("art"))
        else if (degraded) drawEmuOutline(r.x, r.y, r.w, r.h, pressed, alpha)
        val knob = if (pressed) art("knobPressed") ?: art("knob") else art("knob")
        if (knob != null && r.knobRect != null) {
            val rect = RectF(r.knobRect).apply { offset(knobOffset?.x ?: 0f, knobOffset?.y ?: 0f) }
            drawFractionalImage(knob, rect, c.alpha, c.knobRenderGray,
                c.textureInset, if (pressed) flycastTexture("knobPressed") ?: flycastTexture("knob") else flycastTexture("knob"))
        }
        return
    }
    fun drawBase() {
        if (base != null) drawImage(base, dstOffset = IntOffset((r.x + ox + padX).roundToInt(), (r.y + oy + padY).roundToInt()),
            dstSize = IntSize((r.w - padX * 2).roundToInt().coerceAtLeast(1), (r.h - padY * 2).roundToInt().coerceAtLeast(1)), alpha = alpha, filterQuality = FilterQuality.Medium)
        else if (degraded) drawEmuOutline(r.x + ox, r.y + oy, r.w, r.h, pressed, alpha)
    }
    val baseOnTop = backend == "dolphin" && pressed
    if (!baseOnTop) drawBase()
    val knob = if (pressed) art("knobPressed") ?: art("knob") else art("knob")
    if (knob != null && c.knobSize > 0) {
        if (backend == "dolphin" && dolphinThumb != null) {
            val bounds = DolphinOverlay.thumbRect(((r.x + ox) / r.sx).roundToInt(), ((r.y + oy) / r.sy).roundToInt(), c.w, c.h, c.knobSize,
                ((knobOffset?.x ?: 0f) / r.sx).roundToInt().toFloat(), ((knobOffset?.y ?: 0f) / r.sy).roundToInt().toFloat())
            dolphinThumb.alpha = c.alpha
            dolphinThumb.setBounds((bounds.left * r.sx).roundToInt(), (bounds.top * r.sy).roundToInt(),
                ((bounds.left + bounds.width) * r.sx).roundToInt(), ((bounds.top + bounds.height) * r.sy).roundToInt())
            drawIntoCanvas { dolphinThumb.draw(it.nativeCanvas) }
            if (baseOnTop) drawBase()
            return
        }
        val kw = c.knobSize * r.sx
        val kh = c.knobSize * r.sy
        val x = r.cx + ox + (knobOffset?.x ?: 0f) - kw / 2f
        val y = r.cy + oy + (knobOffset?.y ?: 0f) - kh / 2f
        drawImage(knob, dstOffset = IntOffset(x.toInt(), y.toInt()), dstSize = IntSize(kw.roundToInt().coerceAtLeast(1), kh.roundToInt().coerceAtLeast(1)), alpha = alpha, filterQuality = FilterQuality.Medium)
    }
    if (baseOnTop) drawBase()
}

private fun DrawScope.drawFractionalImage(image: ImageBitmap, rect: RectF, alpha: Int, gray: Float, inset: Int, texture: FlycastTexture?) {
    if (inset == 1) {
        texture?.raster(rect, alpha, gray, inset)?.let { (sampled, offset) ->
            drawImage(sampled, dstOffset = offset, filterQuality = FilterQuality.None)
        }
        return
    }
    val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        this.alpha = alpha
        colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
            gray, 0f, 0f, 0f, 0f, 0f, gray, 0f, 0f, 0f, 0f, 0f, gray, 0f, 0f, 0f, 0f, 0f, 1f, 0f,
        )))
    }
    drawIntoCanvas { it.nativeCanvas.drawBitmap(image.asAndroidBitmap(), null, rect, paint) }
}

private fun DrawScope.drawEmuOutline(x: Float, y: Float, w: Float, h: Float, pressed: Boolean, alpha: Float) {
    drawRect(
        Color.White.copy(alpha = ((if (pressed) 0.9f else 0.55f) * alpha).coerceIn(0f, 1f)),
        Offset(x, y),
        androidx.compose.ui.geometry.Size(w, h),
        style = Stroke(2f),
    )
}


