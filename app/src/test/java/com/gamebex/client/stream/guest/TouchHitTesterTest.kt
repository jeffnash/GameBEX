package com.gamebex.client.stream.guest

import com.gamebex.client.stream.proto.PadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class TouchHitTesterTest {
    private fun resolved(
        id: TouchLayoutId = TouchLayoutId.GENERIC,
        w: Float = 915f,
        h: Float = 411f,
        prefs: TouchPrefs = TouchPrefs(),
    ): ResolvedTouchLayout = resolveTouchLayout(touchLayoutSpec(id, prefs), w, h, 1f, prefs, null)

    private fun polar(cx: Float, cy: Float, deg: Double, dist: Float): Pair<Float, Float> {
        val rad = Math.toRadians(deg)
        return (cx + dist * cos(rad).toFloat()) to (cy - dist * sin(rad).toFloat())
    }

    private fun clusterButton(layout: ResolvedTouchLayout, clusterId: String, buttonId: String): ResolvedFaceButton =
        layout.clusters.first { it.control.id == clusterId }.buttons.first { it.def.id == buttonId }

    private fun key(layout: ResolvedTouchLayout, id: String): ResolvedKey =
        layout.keys.first { it.control.id == id }

    @Test
    fun dpadEdgeDownCapturesButOutsideStaysUnassigned() {
        val layout = resolved()
        val dpad = layout.dpads.first()
        val t = TouchHitTester(layout)
        val (ex, ey) = polar(dpad.cx, dpad.cy, 0.0, dpad.radiusPx)
        assertEquals(PadState.HAT_RIGHT, t.onDown(1, ex, ey).hat)
        t.onUp(1, ex, ey)
        val (ox, oy) = polar(dpad.cx, dpad.cy, 0.0, dpad.radiusPx + 0.6f)
        assertEquals(PadState.NEUTRAL, t.onDown(2, ox, oy))
        val (ix, iy) = polar(dpad.cx, dpad.cy, 0.0, dpad.radiusPx * 0.5f)
        assertEquals(PadState.NEUTRAL, t.onMove(2, ix, iy)) // Unassigned slides capture nothing.
        assertEquals(PadState.NEUTRAL, t.onUp(2, ix, iy))
    }

    @Test
    fun keyRectEdgesAreHalfOpen() {
        val layout = resolved()
        val l = key(layout, "l")
        val t = TouchHitTester(layout)
        val cy = (l.rect.top + l.rect.bottom) / 2f
        assertEquals(PadState.L1, t.onDown(1, l.rect.left, cy).buttons)
        t.onUp(1, l.rect.left, cy)
        assertEquals(PadState.NEUTRAL, t.onDown(2, l.rect.right, cy))
        t.onUp(2, l.rect.right, cy)
    }

    @Test
    fun allEightSectorsEmitAtomicHats() {
        val layout = resolved()
        val dpad = layout.dpads.first()
        val expected = listOf(
            0.0 to PadState.HAT_RIGHT,
            45.0 to PadState.HAT_UP_RIGHT,
            90.0 to PadState.HAT_UP,
            135.0 to PadState.HAT_UP_LEFT,
            180.0 to PadState.HAT_LEFT,
            225.0 to PadState.HAT_DOWN_LEFT,
            270.0 to PadState.HAT_DOWN,
            315.0 to PadState.HAT_DOWN_RIGHT,
        )
        expected.forEach { (deg, hat) ->
            val t = TouchHitTester(layout)
            val (x, y) = polar(dpad.cx, dpad.cy, deg, dpad.radiusPx * 0.5f)
            val state = t.onDown(1, x, y)
            assertEquals("sector at $deg", hat, state.hat)
            assertEquals(PadState.NEUTRAL.buttons, state.buttons)
        }
    }

    @Test
    fun diagonalBoundariesAreHalfOpen() {
        val layout = resolved()
        val dpad = layout.dpads.first()
        val t = TouchHitTester(layout)
        val (ax, ay) = polar(dpad.cx, dpad.cy, 22.5, dpad.radiusPx * 0.5f)
        assertEquals(PadState.HAT_UP_RIGHT, t.onDown(1, ax, ay).hat)
        t.onUp(1, ax, ay)
        val (bx, by) = polar(dpad.cx, dpad.cy, 22.4, dpad.radiusPx * 0.5f)
        assertEquals(PadState.HAT_RIGHT, t.onDown(2, bx, by).hat)
        t.onUp(2, bx, by)
    }

    @Test
    fun sectorHoldsUntilThreeDegreesPastTheBoundary() {
        val layout = resolved()
        val dpad = layout.dpads.first()
        val t = TouchHitTester(layout)
        val (sx, sy) = polar(dpad.cx, dpad.cy, 0.0, dpad.radiusPx * 0.5f)
        assertEquals(PadState.HAT_RIGHT, t.onDown(1, sx, sy).hat)
        val (hx, hy) = polar(dpad.cx, dpad.cy, 24.0, dpad.radiusPx * 0.5f)
        assertEquals(PadState.HAT_RIGHT, t.onMove(1, hx, hy).hat) // 1.5 deg past: hold.
        val (nx, ny) = polar(dpad.cx, dpad.cy, 26.0, dpad.radiusPx * 0.5f)
        assertEquals(PadState.HAT_UP_RIGHT, t.onMove(1, nx, ny).hat) // 3.5 deg past: switch.
        t.onUp(1, nx, ny)
    }

    @Test
    fun dpadNeutralRadiiHaveHysteresis() {
        val layout = resolved()
        val dpad = layout.dpads.first()
        val t = TouchHitTester(layout)
        assertEquals(PadState.HAT_CENTER, t.onDown(1, dpad.cx, dpad.cy).hat)
        val (ax, ay) = polar(dpad.cx, dpad.cy, 0.0, dpad.radiusPx * 0.10f)
        assertEquals(PadState.HAT_CENTER, t.onMove(1, ax, ay).hat)
        val (bx, by) = polar(dpad.cx, dpad.cy, 0.0, dpad.radiusPx * 0.5f)
        assertEquals(PadState.HAT_RIGHT, t.onMove(1, bx, by).hat)
        val (cx, cy) = polar(dpad.cx, dpad.cy, 0.0, dpad.radiusPx * 0.18f)
        assertEquals(PadState.HAT_RIGHT, t.onMove(1, cx, cy).hat) // Band keeps the sector.
        val (dx, dy) = polar(dpad.cx, dpad.cy, 0.0, dpad.radiusPx * 0.15f)
        assertEquals(PadState.HAT_CENTER, t.onMove(1, dx, dy).hat)
        t.onUp(1, dx, dy)
    }

    @Test
    fun thumbRollsThroughAllSectorsWithoutLifting() {
        val layout = resolved()
        val dpad = layout.dpads.first()
        val t = TouchHitTester(layout)
        val seen = mutableListOf<Int>()
        val (sx, sy) = polar(dpad.cx, dpad.cy, 0.0, dpad.radiusPx * 0.5f)
        seen += t.onDown(1, sx, sy).hat
        (1..7).forEach { k ->
            val (x, y) = polar(dpad.cx, dpad.cy, k * 45.0, dpad.radiusPx * 0.5f)
            seen += t.onMove(1, x, y).hat
        }
        assertEquals(
            listOf(
                PadState.HAT_RIGHT, PadState.HAT_UP_RIGHT, PadState.HAT_UP, PadState.HAT_UP_LEFT,
                PadState.HAT_LEFT, PadState.HAT_DOWN_LEFT, PadState.HAT_DOWN, PadState.HAT_DOWN_RIGHT,
            ),
            seen,
        )
        assertEquals(PadState.HAT_CENTER, t.onUp(1, sx, sy).hat)
    }

    @Test
    fun dpadKeepsDirectionOutsideAndNeutralizesAtCenter() {
        val layout = resolved()
        val dpad = layout.dpads.first()
        val t = TouchHitTester(layout)
        val (sx, sy) = polar(dpad.cx, dpad.cy, 90.0, dpad.radiusPx * 0.5f)
        assertEquals(PadState.HAT_UP, t.onDown(1, sx, sy).hat)
        val (fx, fy) = polar(dpad.cx, dpad.cy, 90.0, dpad.radiusPx * 2f)
        assertEquals(PadState.HAT_UP, t.onMove(1, fx, fy).hat)
        assertEquals(PadState.HAT_CENTER, t.onMove(1, dpad.cx, dpad.cy).hat)
        t.onUp(1, dpad.cx, dpad.cy)
    }

    @Test
    fun dpadSlideOntoTheStickNeverDrivesIt() {
        val layout = resolved()
        val dpad = layout.dpads.first()
        val stick = layout.sticks.first { !it.right }
        val t = TouchHitTester(layout)
        val (sx, sy) = polar(dpad.cx, dpad.cy, 270.0, dpad.radiusPx * 0.5f)
        t.onDown(1, sx, sy)
        val state = t.onMove(1, stick.cx, stick.cy)
        assertEquals(0, state.lx)
        assertEquals(0, state.ly)
        assertTrue(state.hat != PadState.HAT_CENTER)
        t.onUp(1, stick.cx, stick.cy)
    }

    @Test
    fun faceSlideReleasesAndPressesWithoutLifting() {
        val layout = resolved()
        val a = clusterButton(layout, "face", "a")
        val b = clusterButton(layout, "face", "b")
        val t = TouchHitTester(layout)
        assertEquals(PadState.A, t.onDown(1, a.cx, a.cy).buttons)
        val slid = t.onMove(1, b.cx, b.cy)
        assertEquals(PadState.B, slid.buttons)
        // The diamond middle is a gap: nothing held, still captured.
        val top = clusterButton(layout, "face", "y")
        val midX = (a.cx + top.cx) / 2f
        val midY = (a.cy + top.cy) / 2f
        assertEquals(0, t.onMove(1, midX, midY).buttons)
        assertEquals(PadState.A, t.onMove(1, a.cx, a.cy).buttons)
        t.onUp(1, a.cx, a.cy)
    }

    @Test
    fun adjacentBridgePressesBothButOppositesNeverChord() {
        val layout = resolved()
        val top = clusterButton(layout, "face", "y")
        val right = clusterButton(layout, "face", "b")
        val bottom = clusterButton(layout, "face", "a")
        val t = TouchHitTester(layout)
        val bridge = t.onDown(1, (top.cx + right.cx) / 2f, (top.cy + right.cy) / 2f)
        assertEquals(PadState.Y or PadState.B, bridge.buttons)
        t.onUp(1, top.cx, top.cy)
        val opposite = t.onDown(2, (top.cx + bottom.cx) / 2f, (top.cy + bottom.cy) / 2f)
        assertEquals(0, opposite.buttons)
        t.onUp(2, top.cx, top.cy)
    }

    @Test
    fun n64ChordIsABAndGcChordsFanOutFromA() {
        val n64 = resolved(TouchLayoutId.N64)
        val t = TouchHitTester(n64)
        val a = clusterButton(n64, "ab", "a")
        val b = clusterButton(n64, "ab", "b")
        assertEquals(PadState.A or PadState.X, t.onDown(1, (a.cx + b.cx) / 2f, (a.cy + b.cy) / 2f).buttons)
        t.onUp(1, a.cx, a.cy)

        val gc = resolved(TouchLayoutId.GC_CORE)
        val g = TouchHitTester(gc)
        val ga = clusterButton(gc, "face", "a")
        val gb = clusterButton(gc, "face", "b")
        val gx = clusterButton(gc, "face", "x")
        assertEquals(PadState.B or PadState.A, g.onDown(1, (ga.cx + gb.cx) / 2f, (ga.cy + gb.cy) / 2f).buttons)
        g.onUp(1, ga.cx, ga.cy)
        assertEquals(PadState.B or PadState.Y, g.onDown(2, (ga.cx + gx.cx) / 2f, (ga.cy + gx.cy) / 2f).buttons)
        g.onUp(2, ga.cx, ga.cy)
    }

    @Test
    fun twoFingersShareOneButtonUntilTheLastLifts() {
        val layout = resolved()
        val a = clusterButton(layout, "face", "a")
        val t = TouchHitTester(layout)
        assertEquals(PadState.A, t.onDown(1, a.cx, a.cy).buttons)
        assertEquals(PadState.A, t.onDown(2, a.cx, a.cy).buttons)
        assertEquals(PadState.A, t.onUp(1, a.cx, a.cy).buttons)
        assertEquals(PadState.NEUTRAL, t.onUp(2, a.cx, a.cy))
    }

    @Test
    fun stickHasADeadZoneContinuousOutputAndSaturation() {
        val layout = resolved()
        val stick = layout.sticks.first { !it.right }
        val t = TouchHitTester(layout)
        assertEquals(PadState.NEUTRAL, t.onDown(1, stick.cx, stick.cy))
        assertEquals(0, t.onMove(1, stick.cx + 3.6f, stick.cy).lx)
        val small = t.onMove(1, stick.cx + 4f, stick.cy)
        assertTrue(small.lx in 1..4)
        assertEquals(0, small.ly)
        val half = t.onMove(1, stick.cx + 18f, stick.cy)
        assertTrue(half.lx in 54..58)
        val full = t.onMove(1, stick.cx + 36f, stick.cy)
        assertEquals(127, full.lx)
        val beyond = t.onMove(1, stick.cx + 100f, stick.cy)
        assertEquals(127, beyond.lx)
        assertEquals(0, beyond.ly)
        val up = t.onMove(1, stick.cx, stick.cy - 36f)
        assertEquals(-127, up.ly)
        val left = t.onMove(1, stick.cx - 36f, stick.cy)
        assertEquals(-127, left.lx)
        assertEquals(PadState.NEUTRAL, t.onUp(1, stick.cx, stick.cy))
    }

    @Test
    fun fixedStickRespondsImmediatelyButFloatingStartsNeutral() {
        val layout = resolved(TouchLayoutId.PSX_DUALSHOCK)
        val left = layout.sticks.first { !it.right }
        val right = layout.sticks.first { it.right }
        assertFalse(left.floating)
        assertTrue(right.floating)
        val t = TouchHitTester(layout)
        assertTrue(t.onDown(1, left.cx + 18f, left.cy).lx > 0)
        // onDown returns the aggregate state (pointer 1 still held), so the
        // floating stick's own neutral start shows on the right axes only.
        val floatingDown = t.onDown(2, right.cx + 18f, right.cy)
        assertEquals(0, floatingDown.rx)
        assertEquals(0, floatingDown.ry)
        assertTrue(t.onMove(2, right.cx + 28f, right.cy).rx > 0)
        t.onUp(1, left.cx, left.cy)
        assertEquals(PadState.NEUTRAL, t.onUp(2, right.cx, right.cy))
    }

    @Test
    fun stickOwnershipNeedsAFreshDownAfterRelease() {
        val layout = resolved()
        val stick = layout.sticks.first { !it.right }
        val t = TouchHitTester(layout)
        t.onDown(1, stick.cx, stick.cy)
        t.onMove(1, stick.cx + 36f, stick.cy)
        assertEquals(127, t.onDown(2, stick.cx, stick.cy).lx) // Second finger ignored.
        assertEquals(127, t.onMove(2, stick.cx - 36f, stick.cy).lx)
        assertEquals(PadState.NEUTRAL, t.onUp(1, stick.cx, stick.cy))
        assertEquals(PadState.NEUTRAL, t.onMove(2, stick.cx - 36f, stick.cy)) // Still unassigned.
        assertEquals(-127, t.onDown(2, stick.cx - 36f, stick.cy).lx) // Fresh DOWN works.
        t.onUp(2, stick.cx, stick.cy)
    }

    @Test
    fun bothSticksDriveIndependently() {
        val layout = resolved(TouchLayoutId.PSX_DUALSHOCK)
        val left = layout.sticks.first { !it.right }
        val right = layout.sticks.first { it.right }
        val t = TouchHitTester(layout)
        t.onDown(1, left.cx, left.cy)
        t.onMove(1, left.cx - 36f, left.cy)
        t.onDown(2, right.cx, right.cy)
        val state = t.onMove(2, right.cx, right.cy + 36f)
        assertEquals(-127, state.lx)
        assertEquals(0, state.ly)
        assertEquals(0, state.rx)
        assertEquals(127, state.ry)
        t.onUp(1, left.cx, left.cy)
        t.onUp(2, right.cx, right.cy)
    }

    @Test
    fun invertRightYAndDeadZonePrefsApply() {
        val layout = resolved(TouchLayoutId.PSX_DUALSHOCK, prefs = TouchPrefs(invertRightStickY = true, deadZonePercent = 20))
        val right = layout.sticks.first { it.right }
        val t = TouchHitTester(layout, deadZoneFraction = 0.20f, invertRightStickY = true)
        t.onDown(1, right.cx, right.cy)
        assertEquals(0, t.onMove(1, right.cx + 7f, right.cy).rx)
        assertTrue(t.onMove(1, right.cx + 8f, right.cy).rx in 2..6)
        assertEquals(127, t.onMove(1, right.cx, right.cy - 36f).ry)
        t.onUp(1, right.cx, right.cy)
    }

    @Test
    fun cButtonsCombineDiagonallyAndOppositesCancel() {
        val layout = resolved(TouchLayoutId.N64)
        val up = clusterButton(layout, "c", "cup")
        val right = clusterButton(layout, "c", "cright")
        val down = clusterButton(layout, "c", "cdown")
        val t = TouchHitTester(layout)
        val single = t.onDown(1, up.cx, up.cy)
        assertEquals(0, single.rx)
        assertEquals(-127, single.ry)
        val diagonal = t.onDown(2, right.cx, right.cy)
        assertEquals(127, diagonal.rx)
        assertEquals(-127, diagonal.ry)
        t.onUp(2, right.cx, right.cy)
        t.onDown(3, down.cx, down.cy)
        val cancelled = t.currentState()
        assertEquals(0, cancelled.rx)
        assertEquals(0, cancelled.ry)
        t.onUp(1, up.cx, up.cy)
        val released = t.currentState()
        assertEquals(0, released.rx)
        assertEquals(127, released.ry)
        t.onUp(3, down.cx, down.cy)
    }

    @Test
    fun gcHalfPressNeverEmitsAFullPress() {
        val core = resolved(TouchLayoutId.GC_CORE)
        val lhalf = key(core, "lhalf")
        val t = TouchHitTester(core)
        val half = t.onDown(1, lhalf.cx, lhalf.cy)
        assertEquals(PadState.L3, half.buttons)
        assertEquals(0, half.lt)
        t.onUp(1, lhalf.cx, lhalf.cy)

        val standalone = resolved(TouchLayoutId.GC_STANDALONE)
        val slhalf = key(standalone, "lhalf")
        val s = TouchHitTester(standalone)
        val shalf = s.onDown(1, slhalf.cx, slhalf.cy)
        assertEquals(0, shalf.buttons)
        assertEquals(128, shalf.lt)
        s.onUp(1, slhalf.cx, slhalf.cy)

        val l = key(core, "l")
        val full = t.onDown(2, l.cx, l.cy)
        assertEquals(PadState.L2, full.buttons)
        assertEquals(255, full.lt)
        t.onUp(2, l.cx, l.cy)
    }

    @Test
    fun digitalTriggerStaysHeldUntilItsLastPointerLifts() {
        val layout = resolved(TouchLayoutId.PSX_DIGITAL)
        val l2 = key(layout, "l2")
        val t = TouchHitTester(layout)
        val first = t.onDown(1, l2.cx, l2.cy)
        assertEquals(PadState.L2, first.buttons)
        assertEquals(255, first.lt)
        t.onDown(2, l2.cx, l2.cy)
        val held = t.onUp(1, l2.cx, l2.cy)
        assertEquals(PadState.L2, held.buttons)
        assertEquals(255, held.lt)
        assertEquals(PadState.NEUTRAL, t.onUp(2, l2.cx, l2.cy))
    }

    @Test
    fun analogDragRisesWithDistanceAndLatchesFullPress() {
        val prefs = TouchPrefs(analogDragTriggers = true)
        val layout = resolved(TouchLayoutId.DREAMCAST, prefs = prefs)
        val l = key(layout, "l")
        val t = TouchHitTester(layout)
        val down = t.onDown(1, l.cx, l.cy)
        assertEquals(0, down.lt)
        assertEquals(0, down.buttons)
        val half = t.onMove(1, l.cx, l.cy + 24f)
        assertTrue(half.lt in 120..135)
        assertEquals(0, half.buttons)
        val full = t.onMove(1, l.cx, l.cy + 48f)
        assertEquals(255, full.lt)
        assertEquals(PadState.L2, full.buttons)
        val backoff = t.onMove(1, l.cx, l.cy + 44f)
        assertEquals(PadState.L2, backoff.buttons) // 0.917 keeps the latched bit.
        val released = t.onMove(1, l.cx, l.cy + 40f)
        assertEquals(0, released.buttons and PadState.L2) // 0.833 clears below 0.90.
        assertTrue(released.lt in 200..225)
        assertEquals(PadState.NEUTRAL, t.onUp(1, l.cx, l.cy))
    }

    @Test
    fun shoulderExitsAndReentersWithoutHandingOff() {
        val layout = resolved()
        val l = key(layout, "l")
        val t = TouchHitTester(layout)
        assertEquals(PadState.L1, t.onDown(1, l.cx, l.cy).buttons)
        assertEquals(0, t.onMove(1, l.cx, l.cy - 100f).buttons)
        assertEquals(PadState.L1, t.onMove(1, l.cx, l.cy).buttons)
        assertEquals(PadState.NEUTRAL, t.onUp(1, l.cx, l.cy - 100f))
    }

    @Test
    fun menuTapNeedsDownAndUpInsideAndContributesNothing() {
        val layout = resolved()
        val menu = layout.menu!!
        val mcx = (menu.rect.left + menu.rect.right) / 2f
        val mcy = (menu.rect.top + menu.rect.bottom) / 2f
        val t = TouchHitTester(layout)
        assertEquals(PadState.NEUTRAL, t.onDown(1, mcx, mcy))
        assertEquals(PadState.NEUTRAL, t.onUp(1, mcx, mcy))
        assertTrue(t.pollMenuTap())
        assertFalse(t.pollMenuTap())
        t.onDown(2, mcx, mcy)
        t.onMove(2, mcx + 200f, mcy + 200f)
        t.onUp(2, mcx + 200f, mcy + 200f)
        assertFalse(t.pollMenuTap())
        t.onDown(3, mcx, mcy)
        t.onCancel(3)
        assertFalse(t.pollMenuTap())
    }

    @Test
    fun thirtyTwoPointerSlotsThenIgnore() {
        val layout = resolved()
        val t = TouchHitTester(layout)
        repeat(32) { t.onDown(it + 1L, 0f, 0f) }
        val dpad = layout.dpads.first()
        val (x, y) = polar(dpad.cx, dpad.cy, 0.0, dpad.radiusPx * 0.5f)
        assertEquals(PadState.NEUTRAL, t.onDown(33, x, y)) // No slot left: ignored.
        t.onUp(1, 0f, 0f)
        assertEquals(PadState.HAT_RIGHT, t.onDown(33, x, y).hat)
        assertEquals(PadState.NEUTRAL, t.cancelAll())
    }

    @Test
    fun emptyDownSlidesOntoControlsWithoutEffect() {
        val layout = resolved()
        val a = clusterButton(layout, "face", "a")
        val t = TouchHitTester(layout)
        assertEquals(PadState.NEUTRAL, t.onDown(1, 0f, 0f))
        assertEquals(PadState.NEUTRAL, t.onMove(1, a.cx, a.cy))
        assertEquals(PadState.NEUTRAL, t.onUp(1, a.cx, a.cy))
    }

    @Test
    fun cancelAllNeutralizesAndKeepsWorking() {
        val layout = resolved(TouchLayoutId.N64)
        val stick = layout.sticks.first { !it.right }
        val a = clusterButton(layout, "ab", "a")
        val t = TouchHitTester(layout)
        t.onDown(1, stick.cx, stick.cy)
        t.onMove(1, stick.cx + 36f, stick.cy)
        t.onDown(2, a.cx, a.cy)
        assertEquals(PadState.NEUTRAL, t.cancelAll())
        assertEquals(PadState.NEUTRAL, t.cancelAll())
        assertEquals(PadState.A, t.onDown(2, a.cx, a.cy).buttons)
        t.onUp(2, a.cx, a.cy)
    }

    @Test
    fun replacingGeometryDropsCaptures() {
        val layout = resolved()
        val t = TouchHitTester(layout)
        val a = clusterButton(layout, "face", "a")
        assertEquals(PadState.A, t.onDown(1, a.cx, a.cy).buttons)
        t.layout = resolved(TouchLayoutId.N64)
        assertEquals(PadState.NEUTRAL, t.currentState())
    }
}
