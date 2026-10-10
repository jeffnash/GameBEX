package com.gamebex.client.stream.guest

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.motionEventSpy
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.gamebex.client.stream.proto.PadState
import com.gamebex.client.together.LinkMsg
import com.gamebex.client.data.Emu
import com.gamebex.client.launch.DirectFiles
import com.gamebex.client.together.session.EmuOverlayReader
import java.util.Base64
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow


class StoredOverlay(
    val round: Int,
    val layout: OverlayLayout,
    val images: Map<String, ByteArray>,

    val settings: RaOverlaySettings = RaOverlaySettings(),

    val hostScreenW: Int = 0,
    val hostScreenH: Int = 0,
    val hostScreenX: Int = 0,
    val hostScreenY: Int = 0,

    val hostDisplayW: Int = 0,
    val hostDisplayH: Int = 0,

    val hostCropX: Int = 0,
    val hostCropY: Int = 0,
    val hostCropW: Int = 0,
    val hostCropH: Int = 0,
    val partsReceived: Int = 1,
    val partCount: Int = 1,

    val truncated: Boolean = false,
    val cfg: String = "",
) {

    val complete: Boolean get() = partCount > 0 && partsReceived >= partCount


    fun missingRefs(): List<String> {
        val refs = linkedSetOf<String>()
        layout.pages.forEach { page ->
            page.image?.let(refs::add)
            page.descs.mapNotNullTo(refs) { it.image }
        }
        refs.removeAll(images.keys)
        return refs.toList()
    }
}

object StreamOverlayStore {
    private val _flow = MutableStateFlow<StoredOverlay?>(null)
    val flow: StateFlow<StoredOverlay?> = _flow.asStateFlow()

    private val _statusLine = MutableStateFlow<String?>(null)

    val statusLine: StateFlow<String?> = _statusLine.asStateFlow()


    var onMissingRefs: ((round: Int, missing: List<String>) -> Unit)? = null
    private var requestedRound: Int = -1
    private var dumpedRound: Int = -1
    private val receivedParts = mutableSetOf<Int>()


    var using26aFallback: Boolean = false

    fun current(): StoredOverlay? = _flow.value


    fun publish(round: Int, message: LinkMsg.StreamOverlay) {
        if (_flow.value?.round?.let { round < it } == true) return
        if (message.partCount !in 1..128 || message.partIndex < -1 || message.partIndex >= message.partCount ||
            message.cfg.length > HostOverlayBundle.MAX_CFG_CHARS || message.images.size > HostOverlayBundle.MAX_IMAGES) return
        val decoded = LinkedHashMap<String, ByteArray>()
        message.images.forEach { image ->
            if (image.name.isEmpty() || image.base64.isEmpty() || image.base64.length > HostOverlayBundle.MAX_IMAGE_BYTES * 4 / 3 + 4 || decoded.containsKey(image.name)) return@forEach
            runCatching { Base64.getDecoder().decode(image.base64) }
                .getOrNull()?.takeIf { it.isNotEmpty() && it.size <= HostOverlayBundle.MAX_IMAGE_BYTES }?.let { decoded[image.name] = it }
        }
        val current = _flow.value?.takeIf { it.round == round }
        decoded.replaceAll { name, bytes -> current?.images?.get(name)?.takeIf { it.contentEquals(bytes) } ?: bytes }
        if (message.partIndex == 0 && message.cfg.isNotBlank()) {
            if (current == null || current.cfg != message.cfg) receivedParts.clear()
            receivedParts.add(0)
            requestedRound = -1
            val images = if (current?.cfg == message.cfg) current.images + decoded else decoded
            _flow.value = StoredOverlay(
                round = round,
                layout = parseWithImageSizes(message.cfg, message.name, images),
                images = images,
                settings = message.settings.toSettings(),
                hostScreenW = message.settings.screenW,
                hostScreenH = message.settings.screenH,
                hostScreenX = message.settings.screenX,
                hostScreenY = message.settings.screenY,
                hostDisplayW = message.settings.displayW,
                hostDisplayH = message.settings.displayH,
                hostCropX = message.settings.cropX,
                hostCropY = message.settings.cropY,
                hostCropW = message.settings.cropW,
                hostCropH = message.settings.cropH,
                partsReceived = receivedParts.size,
                partCount = message.partCount.coerceAtLeast(1),
                truncated = message.truncated,
                cfg = message.cfg,
            )
        } else if (current != null && message.partIndex != 0) {
            val merged = LinkedHashMap(current.images)
            merged.putAll(decoded)
            if (message.partIndex > 0) receivedParts.add(message.partIndex)
            val received = receivedParts.size
            _flow.value = StoredOverlay(
                round = round,
                layout = if (current.cfg.isEmpty()) current.layout else parseWithImageSizes(current.cfg, current.layout.name, merged),
                images = merged,
                settings = current.settings,
                hostScreenW = current.hostScreenW,
                hostScreenH = current.hostScreenH,
                hostScreenX = current.hostScreenX,
                hostScreenY = current.hostScreenY,
                hostDisplayW = current.hostDisplayW,
                hostDisplayH = current.hostDisplayH,
                hostCropX = current.hostCropX,
                hostCropY = current.hostCropY,
                hostCropW = current.hostCropW,
                hostCropH = current.hostCropH,
                partsReceived = received,
                partCount = current.partCount,
                truncated = current.truncated || message.truncated,
                cfg = current.cfg,
            )
        } else {
            return
        }
        refreshStatus(round)
    }

    private fun parseWithImageSizes(cfg: String, name: String, images: Map<String, ByteArray>): OverlayLayout {
        val layout = OverlayCfg.parse(cfg, name)
        val dimensions = layout.pages.mapNotNull { page ->
            val bytes = images[page.image] ?: return@mapNotNull null
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true; inScaled = false }
            runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) }
            if (opts.outWidth > 0 && opts.outHeight > 0) page.index to (opts.outWidth to opts.outHeight) else null
        }.toMap()
        return if (dimensions.isEmpty()) layout else OverlayCfg.parse(cfg, name, dimensions)
    }

    private fun refreshStatus(round: Int) {
        val stored = _flow.value?.takeIf { it.round == round } ?: run {
            _statusLine.value = null
            return
        }
        if (!stored.complete) {
            _statusLine.value = null
            return
        }
        if (dumpedRound != round) {
            dumpedRound = round
            OverlayMatchDump.maybeDumpRa(stored)
        }
        val missing = stored.missingRefs()
        if (missing.isEmpty()) {
            _statusLine.value = null
            return
        }
        if (requestedRound != round) {
            requestedRound = round
            runCatching { onMissingRefs?.invoke(round, missing) }
        }
        _statusLine.value = "OVERLAY_DEGRADED ${missing.size} image${if (missing.size == 1) "" else "s"} missing"
        runCatching {
            android.util.Log.w("GameBEXStream", "overlay degraded, missing: ${missing.take(8).joinToString()}")
        }
    }


    fun degradedRefs(): List<String> {
        val stored = _flow.value ?: return emptyList()
        if (!stored.complete || requestedRound != stored.round) return emptyList()
        return stored.missingRefs()
    }

    fun clear() {
        _flow.value = null
        _statusLine.value = null
        requestedRound = -1
        dumpedRound = -1
        using26aFallback = false
        receivedParts.clear()
    }
}


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
    overlaySettings: RaOverlaySettings? = null,
    useHostOverlay: Boolean = false,
    onEffectiveSize: (Int) -> Unit = {},

    videoCrop: VideoCropSignal? = null,
) {
    val stored by StreamOverlayStore.flow.collectAsState()


    val emuStored by EmuOverlayStore.flow.collectAsState()
    if (useHostOverlay && overlay == null && emuStored != null) {
        StreamOverlayStore.using26aFallback = false
        LaunchedEffect(prefs.sizePercent) { onEffectiveSize(prefs.validated().sizePercent) }
        EmuOverlayPad(
            stored = emuStored!!,
            onTouchKey = onTouchKey,
            onTouchAxes = onTouchAxes,
            onReleaseTouchInput = onReleaseTouchInput,
            modifier = modifier,
            unbufferedInput = unbufferedInput,
            prefs = prefs,
            onTouchState = onTouchState,
            menuOpen = menuOpen,
            onMenu = onMenu,
            videoCrop = videoCrop,
        )
        return
    }
    val hostLayout = overlay ?: stored?.takeIf { useHostOverlay }?.layout
    val hostImages = overlayImages ?: stored?.takeIf { useHostOverlay }?.images
    val hostSettings = overlaySettings ?: stored?.takeIf { useHostOverlay }?.settings
    val hostMapped = remember(hostLayout) { hostLayout?.mapped() }
    if (useHostOverlay && hostMapped != null && hostMapped.usableForPlay) {
        StreamOverlayStore.using26aFallback = false

        LaunchedEffect(prefs.sizePercent) { onEffectiveSize(prefs.validated().sizePercent) }
        OverlayPadSurface(
            layout = hostLayout!!,
            images = hostImages ?: emptyMap(),
            settings = hostSettings ?: RaOverlaySettings(),
            degraded = if (overlay != null) emptySet() else StreamOverlayStore.degradedRefs().toSet(),
            hostScreenW = stored?.takeIf { useHostOverlay && overlay == null }?.hostScreenW ?: 0,
            hostScreenH = stored?.takeIf { useHostOverlay && overlay == null }?.hostScreenH ?: 0,
            hostScreenX = stored?.takeIf { useHostOverlay && overlay == null }?.hostScreenX ?: 0,
            hostScreenY = stored?.takeIf { useHostOverlay && overlay == null }?.hostScreenY ?: 0,
            hostDisplayW = stored?.takeIf { useHostOverlay && overlay == null }?.hostDisplayW ?: 0,
            hostDisplayH = stored?.takeIf { useHostOverlay && overlay == null }?.hostDisplayH ?: 0,
            onTouchKey = onTouchKey,
            onTouchAxes = onTouchAxes,
            onReleaseTouchInput = onReleaseTouchInput,
            modifier = modifier,
            unbufferedInput = unbufferedInput,
            prefs = prefs,
            onTouchState = onTouchState,
            menuOpen = menuOpen,
            onMenu = onMenu,
            videoCrop = videoCrop,
        )
        return
    }


    val context = LocalContext.current
    val view = LocalView.current
    val metrics = context.resources.displayMetrics
    val guestW = view.width.takeIf { it > 0 } ?: metrics.widthPixels
    val guestH = view.height.takeIf { it > 0 } ?: metrics.heightPixels
    val guestEmu = when (profile?.backend) {
        TouchBackend.DOLPHIN -> Emu.DOLPHIN
        TouchBackend.NETHERSX2 -> Emu.NETHER
        TouchBackend.ARMSX2 -> Emu.ARMSX2
        TouchBackend.FLYCAST -> Emu.FLYCAST
        else -> null
    }
    var nativeStock by remember(guestEmu, profile, guestW, guestH, useHostOverlay) { mutableStateOf<StoredEmuOverlay?>(null) }
    LaunchedEffect(guestEmu, profile, guestW, guestH, useHostOverlay) {
        nativeStock = withContext(Dispatchers.IO) {
            if (!useHostOverlay || guestEmu == null) null else {
                val root = context.getExternalFilesDir(null)?.parentFile?.parentFile
                root?.let {
                    EmuOverlayReader.read(context, guestEmu.pkg, if (profile?.controllerMode == TouchControllerModes.GC_STANDALONE) "gc" else profile?.folder, null,
                        screenW = guestW, screenH = guestH, density = metrics.density, dpi = metrics.densityDpi,
                        files = DirectFiles, androidData = it)
                }?.let { bundle ->
                    StoredEmuOverlay(-1, bundle.backend, bundle.controls, EmuOverlayStore.decodeArt(bundle.controls),
                        bundle.screenW, bundle.screenH, partsReceived = 1, partCount = 1, truncated = false)
                }
            }
        }
    }
    if (nativeStock != null) {
        StreamOverlayStore.using26aFallback = false
        EmuOverlayPad(nativeStock!!, onTouchKey, onTouchAxes, onReleaseTouchInput, modifier, unbufferedInput, prefs, onTouchState, menuOpen, onMenu)
        return
    }
    val stock = remember(profile?.folder, useHostOverlay) {
        if (!useHostOverlay) null
        else runCatching { GuestStockOverlay.load(context.packageManager, profile?.folder) }.getOrNull()
    }
    val stockMapped = remember(stock) { stock?.layout?.mapped() }
    if (stock != null && stockMapped != null && stockMapped.usableForPlay) {
        StreamOverlayStore.using26aFallback = false
        LaunchedEffect(prefs.sizePercent) { onEffectiveSize(prefs.validated().sizePercent) }
        OverlayPadSurface(
            layout = stock.layout,
            images = stock.images,
            settings = RaOverlaySettings(),
            degraded = emptySet(),
            hostScreenW = 0,
            hostScreenH = 0,
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
        return
    }

    if (useHostOverlay) {
        StreamOverlayStore.using26aFallback = true
        runCatching { android.util.Log.w("GameBEXStream", "overlay degraded: 26a fallback, no host/stock overlay") }
    }
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
    DisposableEffect(useHostOverlay) {
        onDispose { StreamOverlayStore.using26aFallback = false }
    }
}

internal const val TAG_OVERLAY_PAD: String = "stream-overlay-pad"


internal class ResolvedOverlayDesc(
    val mapped: MappedDesc,
    val ra: ResolvedRaDesc,
    val page: ResolvedRaPage,
    canvasW: Float,
    canvasH: Float,
    private val offX: Float = 0f,
    private val offY: Float = 0f,
) {
    private val canvasW = canvasW
    private val canvasH = canvasH
    val cx: Float = offX + (ra.modX + ra.modW / 2f) * canvasW
    val cy: Float = offY + (ra.modY + ra.modH / 2f) * canvasH
    val rx: Float = ra.modW * canvasW / 2f
    val ry: Float = ra.modH * canvasH / 2f





    fun hit(xPx: Float, yPx: Float, useRangeMod: Boolean = false): Boolean = canvasW > 0f && canvasH > 0f && raHitTest(ra, page, (xPx - offX) / canvasW, (yPx - offY) / canvasH, useRangeMod)

    fun overlayCoords(xPx: Float, yPx: Float): Pair<Float, Float> = toOverlayCoords(page, (xPx - offX) / canvasW, (yPx - offY) / canvasH)
}

internal data class OverlayVisual(val pressed: Set<Int> = emptySet(), val knobDxPx: Map<Int, Offset> = emptyMap()) {
    companion object {
        val NEUTRAL: OverlayVisual = OverlayVisual()
    }
}


internal sealed interface OverlayActionTap {
    data object Menu : OverlayActionTap

    data class NextPage(val target: String) : OverlayActionTap
}


internal class OverlayPadTester(
    private val descs: List<ResolvedOverlayDesc>,
    private val deadZoneFraction: Float,
    private val invertRightStickY: Boolean,
    private val floatingSticks: Boolean,
    private val settings: RaOverlaySettings = RaOverlaySettings(),
) {
    private class Capture(val index: Int, var cx: Float, var cy: Float, var px: Float, var py: Float)




    private val captures = linkedMapOf<Long, MutableList<Capture>>()
    private val actions = ArrayDeque<OverlayActionTap>()

    fun pollActionTap(): OverlayActionTap? = if (actions.isEmpty()) null else actions.removeFirst()

    fun onDown(id: Long, x: Float, y: Float): PadState {
        onCancel(id)
        captures[id] = mutableListOf()
        sample(id, x, y)
        return state()
    }

    fun onMove(id: Long, x: Float, y: Float): PadState {
        if (id in captures) sample(id, x, y)
        return state()
    }

    private fun sample(id: Long, x: Float, y: Float) {
        val previous = captures.getValue(id).associateBy { it.index }
        val hits = descs.indices.filter { descs[it].hit(x, y, it in previous) }
        fun priority(index: Int): Int = when {
            index in previous && descs[index].ra.desc.rangeModExclusive -> 2
            descs[index].ra.desc.exclusive -> 1
            else -> 0
        }
        val highest = hits.maxOfOrNull(::priority)
        captures[id] = hits.filter { priority(it) == highest }.map { index ->
            val d = descs[index]
            previous[index]?.apply { px = x; py = y } ?: run {
                when (d.mapped.output) {
                    OverlayOutput.MenuToggle -> actions.add(OverlayActionTap.Menu)
                    OverlayOutput.OverlayNext -> actions.add(OverlayActionTap.NextPage(d.ra.desc.nextTarget))
                    else -> Unit
                }
                Capture(index, if (floatingSticks) x else d.cx, if (floatingSticks) y else d.cy, x, y)
            }
        }.toMutableList()
    }

    fun onUp(id: Long, x: Float, y: Float): PadState { onCancel(id); return state() }
    fun onCancel(id: Long) { captures.remove(id) }
    fun cancelAll() { captures.clear(); actions.clear() }

    private fun areaOutputs(capture: Capture): List<OverlayOutput> {
        val d = descs[capture.index]
        val area = d.ra.desc
        if (!area.directionalArea || d.rx <= 0f || d.ry <= 0f) return emptyList()
        val face = area.input == "abxy_area"
        val sensitivity = (if (face) settings.abxyDiagonalSensitivity else settings.dpadDiagonalSensitivity)
            .takeIf { it.isFinite() } ?: if (face) 50f else 80f
        val directions = raAreaDirections((capture.px - d.cx) / d.rx, (capture.py - d.cy) / d.ry, sensitivity)
        val bindings = listOf(area.upInput ?: if (face) "x" else "up", area.downInput ?: if (face) "b" else "down",
            area.leftInput ?: if (face) "y" else "left", area.rightInput ?: if (face) "a" else "right")
        return bindings.mapIndexedNotNull { index, binding -> if (directions[index]) overlayOutputFor(binding) else null }
    }

    fun state(): PadState {
        val out = OverlayStateAccumulator()
        captures.values.flatten().forEach { capture ->
            val d = descs[capture.index]
            val output = d.mapped.output
            if (d.ra.desc.directionalArea) {
                areaOutputs(capture).forEach { out.add(it) }
            } else if (output is OverlayOutput.Stick) {
                val saturation = d.ra.desc.analogSaturatePct
                if (saturation > 0f && d.rx > 0f && d.ry > 0f) {
                    val nx = (capture.px - capture.cx) / (d.rx * saturation)
                    val ny = (capture.py - capture.cy) / (d.ry * saturation)
                    val axes = overlayStickOutput(nx, ny, 1f, deadZoneFraction)
                    out.add(output, axes[0], if (output.right && invertRightStickY) -axes[1] else axes[1])
                }
            } else out.add(output)
        }
        return out.state()
    }

    fun visual(canvasW: Float, canvasH: Float): OverlayVisual {
        val active = captures.values.flatten()
        val offsets = active.filter { descs[it.index].ra.desc.movable }.associate { capture ->
            val d = descs[capture.index]
            val p = d.overlayCoords(capture.px, capture.py)
            val delta = raMovableDelta(d.ra, d.page, p.first, p.second)
            capture.index to Offset(delta.first * canvasW, delta.second * canvasH)
        }
        val pressed = active.mapTo(mutableSetOf()) { it.index }
        if (settings.showInputs == RA_SHOW_INPUT_TOUCHED) {
            val logical = OverlayStateAccumulator()
            active.forEach { capture -> areaOutputs(capture).forEach { logical.add(it) } }
            descs.forEachIndexed { index, d -> if (logical.includes(d.mapped.output)) pressed += index }
        }
        return OverlayVisual(pressed, offsets)
    }

}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
@SuppressLint("ModifierParameter")
private fun OverlayPadSurface(
    layout: OverlayLayout,
    images: Map<String, ByteArray>,
    settings: RaOverlaySettings,
    degraded: Set<String>,
    hostScreenW: Int,
    hostScreenH: Int,
    hostScreenX: Int = 0,
    hostScreenY: Int = 0,
    hostDisplayW: Int = 0,
    hostDisplayH: Int = 0,
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





    val srcBitmaps = remember(images) {
        val opts = BitmapFactory.Options().apply { inScaled = false; inPremultiplied = false }
        images.mapNotNull { (name, bytes) ->
            runCatching {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                    ?.takeIf { it.width > 0 && it.height > 0 }?.let { name to it }
            }.getOrNull()
        }.toMap()
    }
    var pageIndex by remember(layout) { mutableIntStateOf(0) }
    val pageCount = layout.pages.size
    val page = layout.pages.getOrNull(pageIndex.coerceIn(0, (pageCount - 1).coerceAtLeast(0)))

    val posLogged = remember { mutableStateOf(false) }
    BoxWithConstraints(
        modifier.fillMaxSize().testTag(TAG_OVERLAY_PAD).onGloballyPositioned { coords ->
            if (StreamDebug.flag("overlay_boxes") && !posLogged.value) {
                posLogged.value = true
                val p = coords.positionInWindow()
                val w = coords.size.width
                val h = coords.size.height
                Log.i("GameBEXStream", "OVERLAY_BOX padpos=${p.x.toInt()},${p.y.toInt()} padsize=${w}x$h")
            }
        },
    ) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }




        val mapping = remember(widthPx, heightPx, hostScreenW, hostScreenH, hostScreenX, hostScreenY, hostDisplayW, hostDisplayH) {
            overlayViewportMapping(widthPx, heightPx, hostScreenW, hostScreenH, hostScreenX, hostScreenY, hostDisplayW, hostDisplayH)
        }
        val resolvedPage = remember(page, settings, mapping) {
            page?.let { resolveRaPage(it, settings, mapping.geomW, mapping.geomH) }
        }
        val mappedAll = remember(page) {
            page?.descs?.map { MappedDesc(it, overlayOutputFor(it.input)) } ?: emptyList()
        }
        val descs = remember(mappedAll, resolvedPage, mapping) {
            if (resolvedPage == null) emptyList()
            else mappedAll.mapIndexed { index, entry ->
                ResolvedOverlayDesc(entry, resolvedPage.descs[index], resolvedPage, mapping.effW, mapping.effH, mapping.offX, mapping.offY)
            }
        }





        val drawArt = remember(srcBitmaps, descs, mapping, settings) {
            descs.mapIndexedNotNull { index, d ->
                val ref = d.mapped.desc.image ?: return@mapIndexedNotNull null
                val src = srcBitmaps[ref] ?: return@mapIndexedNotNull null
                val gain = raAlphaFor(d.ra.desc, settings, false)
                prescaleArt(src, d.ra.mappedRectF(mapping), if (settings.floatingAlpha && gain > 1f) gain else 1f)?.let { index to it }
            }.toMap()
        }
        val drawHeldArt = remember(srcBitmaps, descs, mapping, settings) {
            if (!settings.floatingAlpha) emptyMap() else descs.mapIndexedNotNull { index, d ->
                val gain = raAlphaFor(d.ra.desc, settings, true)
                if (gain <= 1f && raAlphaFor(d.ra.desc, settings, false) <= 1f) return@mapIndexedNotNull null
                val src = srcBitmaps[d.mapped.desc.image] ?: return@mapIndexedNotNull null
                prescaleArt(src, d.ra.mappedRectF(mapping), gain.coerceAtLeast(1f))?.let { index to it }
            }.toMap()
        }
        val drawBase = remember(srcBitmaps, resolvedPage, mapping, page, settings) {
            val ref = page?.image ?: return@remember null
            val src = srcBitmaps[ref] ?: return@remember null
            val rp = resolvedPage ?: return@remember null
            val gain = settings.opacity.takeIf { it.isFinite() && it >= 0f } ?: 0f
            prescaleArt(src, rp.mappedRectF(mapping), if (settings.floatingAlpha && gain > 1f) gain else 1f)
        }



        if (StreamDebug.flag("overlay_boxes")) {
            LaunchedEffect(descs, mapping) {
                Log.i("GameBEXStream", "OVERLAY_BOX canvas=${widthPx.toInt()}x${heightPx.toInt()} page=$pageIndex/${page?.name} n=${descs.size} viewport=${hostScreenX},${hostScreenY},${hostScreenW}x${hostScreenH} display=${hostDisplayW}x${hostDisplayH}")
                descs.forEachIndexed { i, d ->
                    val box = d.ra.mappedRect(mapping)
                    Log.i("GameBEXStream", "OVERLAY_BOX i=$i input=${d.mapped.desc.input} image=${d.mapped.desc.image} left=${box.left} top=${box.top} w=${box.width} h=${box.height}")
                }
            }
        }
        val tester = remember(descs, effectivePrefs) {
            OverlayPadTester(
                descs,
                deadZoneFraction = effectivePrefs.deadZonePercent / 100f,
                invertRightStickY = effectivePrefs.invertRightStickY,
                floatingSticks = effectivePrefs.stickMode == TouchStickMode.FLOATING,
                settings = settings,
            )
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

        fun nextPage(target: String, atNs: Long) {
            if (pageCount == 0) return
            val next = if (target.isNotEmpty()) {
                layout.pages.indexOfFirst { it.name == target }.takeIf { it >= 0 }
                    ?: ((pageIndex + 1) % pageCount)
            } else {
                (pageIndex + 1) % pageCount
            }
            releaseAll(atNs)
            pageIndex = next
        }

        fun handleSample(state: PadState, atNs: Long) {
            publish(state, atNs)
            when (val tap = tester.pollActionTap()) {
                is OverlayActionTap.Menu -> {
                    releaseAll(atNs)
                    menuCallback()
                    return
                }
                is OverlayActionTap.NextPage -> {
                    nextPage(tap.target, atNs)
                    return
                }
                null -> Unit
            }
            val fresh = state.buttons and session.lastButtons.inv()
            if (fresh != 0 && effectivePrefs.haptics == TouchHaptics.LIGHT &&
                atNs - session.lastPulseNs >= OVERLAY_HAPTIC_MIN_INTERVAL_NS
            ) {
                session.lastPulseNs = atNs
                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            }
            session.lastButtons = state.buttons
            val next = tester.visual(mapping.effW, mapping.effH)
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
        Canvas(Modifier.fillMaxSize()) {
            val rp = resolvedPage ?: return@Canvas


            clipRect(mapping.offX, mapping.offY, mapping.offX + mapping.effW, mapping.offY + mapping.effH) {




                val clip = videoCrop?.let { s ->
                    videoCropPlacement(
                        s.cropX, s.cropY, s.cropW, s.cropH, s.hostDisplayW, s.hostDisplayH,
                        size.width.toInt(), size.height.toInt(), s.infoW, s.infoH,
                    )
                }
                if (clip == null) drawOverlayBase(drawBase, rp, mapping, settings)
                else clipRect(clip.x.toFloat(), clip.y.toFloat(), (clip.x + clip.w).toFloat(), (clip.y + clip.h).toFloat(), ClipOp.Difference) {
                    drawOverlayBase(drawBase, rp, mapping, settings)
                }
                descs.forEachIndexed { index, d ->
                    val pressed = index in visual.pressed
                    val art = if (pressed) drawHeldArt[index] ?: drawArt[index] else drawArt[index]
                    if (pressed || clip == null) drawOverlayDesc(d, art, pressed, visual.knobDxPx[index], settings, degraded, mapping)
                    else clipRect(clip.x.toFloat(), clip.y.toFloat(), (clip.x + clip.w).toFloat(), (clip.y + clip.h).toFloat(), ClipOp.Difference) {
                        drawOverlayDesc(d, art, false, visual.knobDxPx[index], settings, degraded, mapping)
                    }
                }
            }
        }
    }
}

private class OverlayPublishSession {
    var lastPublished: PadState = PadState.NEUTRAL
    var lastButtons: Int = 0
    var lastPulseNs: Long = 0L
}

private const val OVERLAY_HAPTIC_MIN_INTERVAL_NS: Long = 80_000_000L


private fun DrawScope.drawOverlayBase(
    base: Pair<ImageBitmap, IntOffset>?,
    page: ResolvedRaPage,
    mapping: OverlayViewportMapping,
    settings: RaOverlaySettings,
) {
    if (base == null) return
    drawImage(
        base.first,
        dstOffset = base.second,
        dstSize = IntSize(base.first.width, base.first.height),
        alpha = settings.opacity.takeIf { it.isFinite() }?.let { if (settings.floatingAlpha && it > 1f) 1f else it.coerceIn(0f, 1f) } ?: 0f,


        filterQuality = FilterQuality.None,
    )
}


private fun prescaleArt(src: Bitmap, rect: RaFloatRect): Pair<ImageBitmap, IntOffset>? = prescaleArt(src, rect, 1f)

private fun prescaleArt(src: Bitmap, rect: RaFloatRect, alphaGain: Float): Pair<ImageBitmap, IntOffset>? {
    if (!rect.width.isFinite() || !rect.height.isFinite() || rect.width <= 0f || rect.height <= 0f) return null
    val left = kotlin.math.floor(rect.left).toInt()
    val top = kotlin.math.floor(rect.top).toInt()
    val width = kotlin.math.ceil(rect.right).toInt() - left
    val height = kotlin.math.ceil(rect.bottom).toInt() - top
    if (width <= 0 || height <= 0 || width.toLong() * height > 16_000_000) return null
    val pixels = IntArray(src.width * src.height)
    src.getPixels(pixels, 0, src.width, 0, 0, src.width, src.height)
    val scaled = renderStraightFrac(pixels, src.width, src.height, width, height, left, top, rect.left, rect.top, rect.width, rect.height, alphaGain)
    return Bitmap.createBitmap(scaled, width, height, Bitmap.Config.ARGB_8888).asImageBitmap() to IntOffset(left, top)
}


private fun DrawScope.drawOverlayDesc(
    d: ResolvedOverlayDesc,
    art: Pair<ImageBitmap, IntOffset>?,
    pressed: Boolean,
    knobOffset: Offset?,
    settings: RaOverlaySettings,
    degraded: Set<String>,
    mapping: OverlayViewportMapping,
 ) {
    val alpha = raAlphaFor(d.ra.desc, settings, pressed)
    if (art != null) {
        val dx = knobOffset?.x ?: 0f
        val dy = knobOffset?.y ?: 0f
        drawImage(art.first, dstOffset = IntOffset(art.second.x + dx.roundToInt(), art.second.y + dy.roundToInt()),
            alpha = if (settings.floatingAlpha && alpha > 1f) 1f else alpha, filterQuality = FilterQuality.None)
    } else if (d.ra.desc.image in degraded) {
        val box = d.ra.mappedRectF(mapping)
        drawRect(Color.White.copy(alpha = alpha.coerceIn(0f, 1f)), Offset(box.left, box.top), androidx.compose.ui.geometry.Size(box.width, box.height), style = Stroke(2f))
    }
}
