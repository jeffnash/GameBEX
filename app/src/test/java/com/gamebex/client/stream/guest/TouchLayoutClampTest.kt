package com.gamebex.client.stream.guest

import com.gamebex.client.stream.proto.PadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Wide inward shift and the per-layout size clamp. */
class TouchLayoutClampTest {
    private fun key(id: String, anchor: TouchAnchor, rect: TouchRectPx): ResolvedKey =
        ResolvedKey(KeyControl(id, anchor, id, rect.width, rect.height, output = TouchOutput.Buttons(PadState.A)), rect)

    private fun layout(
        wide: Boolean,
        dpads: List<ResolvedDpad> = emptyList(),
        sticks: List<ResolvedStick> = emptyList(),
        clusters: List<ResolvedCluster> = emptyList(),
        keys: List<ResolvedKey> = emptyList(),
        menu: ResolvedMenu? = null,
    ): ResolvedTouchLayout = ResolvedTouchLayout(
        TouchLayoutId.GENERIC, wide, 1f, TouchRectPx(0f, 0f, 915f, 411f),
        dpads, sticks, clusters, keys, menu,
    )

    @Test fun leftSecondaryShiftsRightUntilItClearsOuterGroups() {
        val dpad = ResolvedDpad(DpadControl("dpad", TouchAnchor.LS, 120f), 100f, 300f, 60f)
        val outer = key("k", TouchAnchor.LP, TouchRectPx(120f, 260f, 180f, 320f))
        val shifted = touchShiftWideSecondaries(layout(wide = true, dpads = listOf(dpad), keys = listOf(outer)), 915f, 1f)
        val moved = shifted.dpads.single()
        assertTrue("cx=${moved.cx}", moved.cx > 100f)
        assertEquals(60f, moved.radiusPx) // sizes preserved
        val m = TOUCH_CLEARANCE_DP
        assertFalse(moved.bounds().grown(m).intersects(outer.rect.grown(m)))
    }

    @Test fun rightSecondaryShiftsLeftAndPrimariesNeverMove() {
        val stick = ResolvedStick(StickControl("s", TouchAnchor.RS, right = true), true, 815f, 300f, 56f, 20f, 36f, false)
        val primary = ResolvedDpad(DpadControl("dpad", TouchAnchor.LP, 120f), 100f, 100f, 60f)
        val outer = key("k", TouchAnchor.RP, TouchRectPx(735f, 260f, 795f, 320f))
        val shifted = touchShiftWideSecondaries(
            layout(wide = true, dpads = listOf(primary), sticks = listOf(stick), keys = listOf(outer)), 915f, 1f,
        )
        val moved = shifted.sticks.single()
        assertTrue("cx=${moved.cx}", moved.cx < 815f)
        assertEquals(100f, shifted.dpads.single().cx)
        assertEquals(TouchRectPx(735f, 260f, 795f, 320f), shifted.keys.single().rect)
    }

    @Test fun alreadyClearGroupsSideBarsAndMenuOnlyCollisionsStayPut() {
        val dpad = ResolvedDpad(DpadControl("dpad", TouchAnchor.LS, 120f), 100f, 300f, 60f)
        val far = key("k", TouchAnchor.RP, TouchRectPx(735f, 60f, 795f, 120f))
        val menuRect = TouchRectPx(60f, 260f, 140f, 340f)
        val menu = ResolvedMenu(MenuControl(), menuRect)
        val wide = layout(wide = true, dpads = listOf(dpad), keys = listOf(far), menu = menu)
        assertEquals(100f, touchShiftWideSecondaries(wide, 915f, 1f).dpads.single().cx)
        val sideBar = layout(wide = false, dpads = listOf(dpad), keys = listOf(far))
        assertEquals(sideBar, touchShiftWideSecondaries(sideBar, 915f, 1f))
    }

    @Test fun cleanSizesPassThroughUnclampedOnTheReferencePhone() {
        TouchLayoutId.entries.forEach { id ->
            val definition = touchLayoutSpec(id, TouchPrefs())
            assertEquals("$id", 100, effectiveTouchSizePercent(definition, 915f, 411f, 1f, TouchPrefs(), null))
        }
    }

    @Test fun clampedSizeIsTheLargestCleanStepOnASmallScreen() {
        TouchLayoutId.entries.forEach { id ->
            val definition = touchLayoutSpec(id, TouchPrefs())
            val eff = effectiveTouchSizePercent(definition, 800f, 360f, 1f, TouchPrefs(sizePercent = 125), null)
            assertTrue("$id eff=$eff", eff in TouchPrefs.SIZE_MIN..125)
            assertEquals("$id", 0, (eff - TouchPrefs.SIZE_MIN) % TouchPrefs.SIZE_STEP)
            val layout = resolveTouchLayout(definition, 800f, 360f, 1f, TouchPrefs(sizePercent = eff), null)
            if (eff > TouchPrefs.SIZE_MIN) {
                assertEquals("$id", emptyList<String>(), touchLayoutOverlaps(layout))
            }
            if (eff < 125) {
                val up = resolveTouchLayout(definition, 800f, 360f, 1f, TouchPrefs(sizePercent = eff + TouchPrefs.SIZE_STEP), null)
                assertTrue("$id should overlap at ${eff + TouchPrefs.SIZE_STEP}", touchLayoutOverlaps(up).isNotEmpty())
            }
        }
    }
}
