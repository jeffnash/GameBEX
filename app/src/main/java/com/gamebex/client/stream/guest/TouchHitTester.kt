package com.gamebex.client.stream.guest

import com.gamebex.client.stream.proto.PadState
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Pure geometry and pointer-ownership engine. No Compose, no Android:
 * pointer positions arrive in pixels and every sample publishes one complete
 * [PadState]. The caller owns timestamps and transport.
 *
 * Capture contract: a DOWN on a d-pad or stick is owned until UP/CANCEL and
 * can never activate another control; a DOWN in a face cluster may slide
 * among its members and chord regions; a DOWN on a shoulder/utility stays
 * with that button (exit/re-entry rules apply); a DOWN on empty picture
 * stays unassigned; Menu never contributes controller input.
 *
 * Geometry is frozen per instance: replacing [layout] drops all captures, so
 * the caller publishes the neutral state around the swap.
 */
internal class TouchHitTester(
    layout: ResolvedTouchLayout,
    deadZoneFraction: Float = 0.10f,
    invertRightStickY: Boolean = false,
) {
    var layout: ResolvedTouchLayout = layout
        set(value) {
            field = value
            pointers.clear()
            menuTap = false
            rebuildIndex()
        }

    var deadZoneFraction: Float = deadZoneFraction
        set(value) {
            field = value.coerceIn(0f, 0.20f)
        }
    var invertRightStickY: Boolean = invertRightStickY

    /** True when the most recent DOWN captured a stick (unbuffered dispatch hint). */
    var lastDownWasStick: Boolean = false
        private set

    private sealed interface Capture {
        data object Unassigned : Capture
        class Dpad(val index: Int, var sector: Int) : Capture
        class Stick(val index: Int, var cx: Float, var cy: Float, var ox: Int, var oy: Int) : Capture
        class Cluster(val index: Int, val pressed: MutableSet<String>) : Capture
        class Key(val index: Int, var downY: Float, var level: Int, var fullBit: Boolean, var active: Boolean) : Capture
        class Menu(var inside: Boolean) : Capture
    }

    private data class ChordEnds(val a: ResolvedFaceButton, val b: ResolvedFaceButton, val pairId: String)

    private val pointers = LinkedHashMap<Long, Capture>()
    private var menuTap = false
    private var clusterButtons: List<Map<String, ResolvedFaceButton>> = emptyList()
    private var clusterChords: List<List<ChordEnds>> = emptyList()

    init {
        rebuildIndex()
    }

    private fun rebuildIndex() {
        clusterButtons = layout.clusters.map { cluster -> cluster.buttons.associateBy { it.def.id } }
        clusterChords = layout.clusters.map { cluster ->
            val byId = cluster.buttons.associateBy { it.def.id }
            cluster.control.chords.mapNotNull { (a, b) ->
                val ra = byId[a]
                val rb = byId[b]
                if (ra == null || rb == null) null else ChordEnds(ra, rb, touchChord(a, b).let { "${it.first}+${it.second}" })
            }
        }
    }

    fun onDown(pointerId: Long, x: Float, y: Float): PadState {
        lastDownWasStick = false
        if (pointers.size >= MAX_POINTERS && pointerId !in pointers) return currentState()
        val capture = routeDown(x, y)
        pointers[pointerId] = capture
        return currentState()
    }

    fun onMove(pointerId: Long, x: Float, y: Float): PadState {
        when (val cap = pointers[pointerId]) {
            null, Capture.Unassigned -> Unit
            is Capture.Dpad -> evalDpad(layout.dpads[cap.index], cap, x, y)
            is Capture.Stick -> evalStick(layout.sticks[cap.index], cap, x, y)
            is Capture.Cluster -> evalCluster(cap.index, cap.pressed, x, y)
            is Capture.Key -> evalKey(layout.keys[cap.index], cap, x, y)
            is Capture.Menu -> cap.inside = pointInMenu(layout.menu, x, y)
        }
        return currentState()
    }

    /** UP: a Menu DOWN lifted inside the target arms [pollMenuTap]. */
    fun onUp(pointerId: Long, x: Float, y: Float): PadState {
        val cap = pointers.remove(pointerId)
        if (cap is Capture.Menu && cap.inside && pointInMenu(layout.menu, x, y)) menuTap = true
        return currentState()
    }

    /** CANCEL never fires Menu. */
    fun onCancel(pointerId: Long): PadState {
        pointers.remove(pointerId)
        return currentState()
    }

    fun cancelAll(): PadState {
        pointers.clear()
        menuTap = false
        return currentState()
    }

    fun pollMenuTap(): Boolean {
        val tap = menuTap
        menuTap = false
        return tap
    }

    fun currentState(): PadState {
        var buttons = 0
        var lx = 0
        var ly = 0
        var rx = 0
        var ry = 0
        var lt = 0
        var rt = 0
        var hat = PadState.HAT_CENTER
        var cUp = false
        var cDown = false
        var cLeft = false
        var cRight = false
        pointers.values.forEach { cap ->
            when (cap) {
                Capture.Unassigned -> Unit
                is Capture.Dpad -> {
                    if (hat == PadState.HAT_CENTER && cap.sector != HAT_SECTOR_NEUTRAL) {
                        hat = SECTOR_TO_HAT[cap.sector]
                    }
                }
                is Capture.Stick -> {
                    if (layout.sticks[cap.index].right) {
                        rx = cap.ox
                        ry = cap.oy
                    } else {
                        lx = cap.ox
                        ly = cap.oy
                    }
                }
                is Capture.Cluster -> {
                    val byId = clusterButtons[cap.index]
                    cap.pressed.forEach { id ->
                        when (val out = byId[id]?.def?.output) {
                            is TouchOutput.Buttons -> buttons = buttons or out.bits
                            is TouchOutput.CButton -> when (out.dir) {
                                TouchOutput.CDir.UP -> cUp = true
                                TouchOutput.CDir.DOWN -> cDown = true
                                TouchOutput.CDir.LEFT -> cLeft = true
                                TouchOutput.CDir.RIGHT -> cRight = true
                            }
                            else -> Unit
                        }
                    }
                }
                is Capture.Key -> {
                    if (!cap.active) return@forEach
                    when (val out = layout.keys[cap.index].control.output) {
                        is TouchOutput.Buttons -> buttons = buttons or out.bits
                        is TouchOutput.Trigger -> {
                            if (out.analogDrag) {
                                if (out.left) {
                                    if (cap.level > lt) lt = cap.level
                                    if (cap.fullBit) buttons = buttons or PadState.L2
                                } else {
                                    if (cap.level > rt) rt = cap.level
                                    if (cap.fullBit) buttons = buttons or PadState.R2
                                }
                            } else if (out.left) {
                                buttons = buttons or PadState.L2
                                lt = 255
                            } else {
                                buttons = buttons or PadState.R2
                                rt = 255
                            }
                        }
                        is TouchOutput.HalfTrigger -> {
                            buttons = buttons or out.bit
                            if (out.left) {
                                if (out.axisValue > lt) lt = out.axisValue
                            } else {
                                if (out.axisValue > rt) rt = out.axisValue
                            }
                        }
                        else -> Unit
                    }
                }
                is Capture.Menu -> Unit
            }
        }
        if (cRight != cLeft) rx = (rx + (if (cRight) 127 else -127)).coerceIn(-127, 127)
        if (cDown != cUp) ry = (ry + (if (cDown) 127 else -127)).coerceIn(-127, 127)
        return PadState(buttons = buttons, lx = lx, ly = ly, rx = rx, ry = ry, lt = lt, rt = rt, hat = hat)
    }

    fun visualState(): TouchVisual {
        val pressed = mutableSetOf<String>()
        val sectors = mutableMapOf<Int, Int>()
        val knobs = mutableMapOf<Int, Pair<Float, Float>>()
        var menuPressed = false
        pointers.values.forEach { cap ->
            when (cap) {
                Capture.Unassigned -> Unit
                is Capture.Dpad -> sectors[cap.index] = cap.sector
                is Capture.Stick -> knobs[cap.index] = (cap.ox / 127f) to (cap.oy / 127f)
                is Capture.Cluster -> {
                    val clusterId = layout.clusters[cap.index].control.id
                    cap.pressed.forEach { pressed += "$clusterId:$it" }
                }
                is Capture.Key -> if (cap.active) pressed += layout.keys[cap.index].control.id
                is Capture.Menu -> if (cap.inside) menuPressed = true
            }
        }
        return TouchVisual(pressed, sectors, knobs, menuPressed)
    }

    private fun routeDown(x: Float, y: Float): Capture {
        if (pointInMenu(layout.menu, x, y)) return Capture.Menu(inside = true)
        layout.dpads.forEachIndexed { index, dpad ->
            if (hypot(x - dpad.cx, y - dpad.cy) <= dpad.radiusPx) {
                val cap = Capture.Dpad(index, HAT_SECTOR_NEUTRAL)
                evalDpad(dpad, cap, x, y)
                return cap
            }
        }
        layout.sticks.forEachIndexed { index, stick ->
            if (hypot(x - stick.cx, y - stick.cy) <= stick.baseRadiusPx) {
                if (pointers.values.any { it is Capture.Stick && it.index == index }) {
                    return Capture.Unassigned
                }
                val cap = if (stick.floating) Capture.Stick(index, x, y, 0, 0)
                else Capture.Stick(index, stick.cx, stick.cy, 0, 0)
                evalStick(stick, cap, x, y)
                lastDownWasStick = true
                return cap
            }
        }
        layout.clusters.forEachIndexed { index, cluster ->
            if (pointInClusterEnvelope(index, x, y)) {
                val pressed = mutableSetOf<String>()
                evalCluster(index, pressed, x, y)
                return Capture.Cluster(index, pressed)
            }
        }
        layout.keys.forEachIndexed { index, key ->
            if (pointInKey(key, x, y, 0f)) {
                val cap = Capture.Key(index, y, 0, false, true)
                evalKey(key, cap, x, y)
                return cap
            }
        }
        return Capture.Unassigned
    }

    private fun evalDpad(dpad: ResolvedDpad, cap: Capture.Dpad, x: Float, y: Float) {
        val dx = x - dpad.cx
        val dy = y - dpad.cy
        val dist = hypot(dx, dy)
        val r = dpad.radiusPx
        if (dist <= NEUTRAL_OUTER * r) {
            cap.sector = HAT_SECTOR_NEUTRAL
            return
        }
        if (dist < NEUTRAL_INNER * r) return // Hysteresis band: keep the current sector.
        var theta = Math.toDegrees(atan2(-dy.toDouble(), dx.toDouble()))
        if (theta < 0.0) theta += 360.0
        // Float-safe half-open boundaries: exact 22.5° belongs to the next sector.
        val raw = (floor((theta + 22.5 + SECTOR_EPSILON_DEG) / 45.0).toInt()) % 8
        cap.sector = applySectorHysteresis(cap.sector, raw, theta)
    }

    private fun applySectorHysteresis(current: Int, raw: Int, theta: Double): Int {
        if (current == HAT_SECTOR_NEUTRAL || current == raw) return raw
        val step = (raw - current + 8) % 8
        if (step != 1 && step != 7) return raw // Jumped more than one sector: accept it.
        val boundary = if (step == 1) (22.5 + 45.0 * current) % 360.0 else (22.5 + 45.0 * (current + 7)) % 360.0
        val past = if (step == 1) (theta - boundary + 360.0) % 360.0 else (boundary - theta + 360.0) % 360.0
        return if (past >= SECTOR_HYSTERESIS_DEG) raw else current
    }

    private fun evalStick(stick: ResolvedStick, cap: Capture.Stick, x: Float, y: Float) {
        val vx = x - cap.cx
        val vy = y - cap.cy
        val dist = hypot(vx, vy)
        val travel = stick.travelPx
        val dead = deadZoneFraction * travel
        if (travel <= 0f || dist <= dead) {
            cap.ox = 0
            cap.oy = 0
            return
        }
        val mag = ((dist - dead) / (travel - dead)).coerceIn(0f, 1f)
        var oy = vy / dist * mag
        if (stick.right && invertRightStickY) oy = -oy
        cap.ox = (vx / dist * mag * 127f).roundToInt().coerceIn(-127, 127)
        cap.oy = (oy * 127f).roundToInt().coerceIn(-127, 127)
    }

    private fun evalCluster(index: Int, pressed: MutableSet<String>, x: Float, y: Float) {
        pressed.clear()
        if (!pointInClusterEnvelope(index, x, y)) return
        val density = layout.density
        var bestA: ResolvedFaceButton? = null
        var bestB: ResolvedFaceButton? = null
        var bestN = Float.MAX_VALUE
        var bestId = ""
        clusterChords[index].forEach { chord ->
            val a = chord.a
            val b = chord.b
            val vx = b.cx - a.cx
            val vy = b.cy - a.cy
            val denom = vx * vx + vy * vy
            if (denom <= 0f) return@forEach
            val t = ((x - a.cx) * vx + (y - a.cy) * vy) / denom
            if (t < CHORD_T_MIN || t > CHORD_T_MAX) return@forEach
            val px = a.cx + t * vx
            val py = a.cy + t * vy
            val n = hypot(x - px, y - py)
            if (n > CHORD_N_DP * density) return@forEach
            if (hypot(x - a.cx, y - a.cy) > a.hitRadiusPx + CHORD_REACH_DP * density) return@forEach
            if (hypot(x - b.cx, y - b.cy) > b.hitRadiusPx + CHORD_REACH_DP * density) return@forEach
            if (n < bestN - 0.001f || (n <= bestN + 0.001f && chord.pairId < bestId)) {
                bestA = a
                bestB = b
                bestN = n
                bestId = chord.pairId
            }
        }
        if (bestA != null && bestB != null) {
            pressed += bestA!!.def.id
            pressed += bestB!!.def.id
            return
        }
        var best: ResolvedFaceButton? = null
        var bestDist = Float.MAX_VALUE
        layout.clusters[index].buttons.forEach { b ->
            val d = hypot(x - b.cx, y - b.cy)
            if (d <= b.hitRadiusPx && d < bestDist) {
                best = b
                bestDist = d
            }
        }
        if (best != null) pressed += best!!.def.id
    }

    private fun evalKey(key: ResolvedKey, cap: Capture.Key, x: Float, y: Float) {
        val out = key.control.output
        if (out is TouchOutput.Trigger && out.analogDrag) {
            val t = ((y - cap.downY) / (ANALOG_DRAG_DP * layout.density)).coerceIn(0f, 1f)
            cap.level = (t * 255f).roundToInt()
            cap.fullBit = if (t >= ANALOG_FULL_ON) true else if (t < ANALOG_FULL_OFF) false else cap.fullBit
            cap.active = true // Capture persists outside the artwork while dragging.
            return
        }
        cap.active = pointInKey(key, x, y, KEY_EXIT_DP * layout.density)
    }

    private fun pointInMenu(menu: ResolvedMenu?, x: Float, y: Float): Boolean {
        if (menu == null) return false
        val r = menu.rect
        return x >= r.left && x < r.right && y >= r.top && y < r.bottom
    }

    private fun pointInKey(key: ResolvedKey, x: Float, y: Float, marginPx: Float): Boolean {
        if (key.control.circular) {
            return hypot(x - key.cx, y - key.cy) <= key.radiusPx + marginPx
        }
        val r = key.rect
        return x >= r.left - marginPx && x < r.right + marginPx && y >= r.top - marginPx && y < r.bottom + marginPx
    }

    private fun pointInClusterEnvelope(index: Int, x: Float, y: Float): Boolean {
        val margin = CLUSTER_EXIT_DP * layout.density
        return layout.clusters[index].buttons.any { b -> hypot(x - b.cx, y - b.cy) <= b.hitRadiusPx + margin }
    }

    companion object {
        const val MAX_POINTERS: Int = 32
        const val HAT_SECTOR_NEUTRAL: Int = 8
        /** Wire hat per sector index [Right, UpRight, Up, UpLeft, Left, DownLeft, Down, DownRight]. */
        val SECTOR_TO_HAT: IntArray = intArrayOf(
            PadState.HAT_RIGHT, PadState.HAT_UP_RIGHT, PadState.HAT_UP, PadState.HAT_UP_LEFT,
            PadState.HAT_LEFT, PadState.HAT_DOWN_LEFT, PadState.HAT_DOWN, PadState.HAT_DOWN_RIGHT,
        )
        private const val NEUTRAL_INNER: Float = 0.20f
        private const val NEUTRAL_OUTER: Float = 0.16f
        private const val SECTOR_HYSTERESIS_DEG: Double = 3.0
        private const val SECTOR_EPSILON_DEG: Double = 1e-4
        private const val CHORD_T_MIN: Float = 0.35f
        private const val CHORD_T_MAX: Float = 0.65f
        private const val CHORD_N_DP: Float = 8f
        private const val CHORD_REACH_DP: Float = 10f
        private const val CLUSTER_EXIT_DP: Float = 12f
        private const val KEY_EXIT_DP: Float = 8f
        private const val ANALOG_DRAG_DP: Float = 48f
        private const val ANALOG_FULL_ON: Float = 0.95f
        private const val ANALOG_FULL_OFF: Float = 0.90f
    }
}

/** Pressed visuals for the renderer: button ids, d-pad sectors, stick knobs. */
internal data class TouchVisual(
    val pressedButtons: Set<String>,
    val dpadSectors: Map<Int, Int>,
    val stickKnobs: Map<Int, Pair<Float, Float>>,
    val menuPressed: Boolean,
) {
    companion object {
        val NEUTRAL: TouchVisual = TouchVisual(emptySet(), emptyMap(), emptyMap(), false)
    }
}
