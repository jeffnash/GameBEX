package com.gamebex.client.stream.guest

import com.gamebex.client.stream.proto.PadState
import org.junit.Assert.assertEquals
import org.junit.Test

/** 26b pad tester: stick-direction combine and dual-trigger combos. */
class StreamOverlayPadTest {
    private fun desc(input: String, cx: Float, cy: Float): ResolvedOverlayDesc {
        val d = OverlayDesc(input, 0f, 0f, OverlayShape.RADIAL, 0.05f, 0.05f)
        val out = overlayOutputFor(input)
        check(out !is OverlayOutput.Unknown && out !is OverlayOutput.Ignored) { input }
        return ResolvedOverlayDesc(MappedDesc(d, out), cx, cy, 40f, 40f)
    }

    private fun tester(vararg descs: ResolvedOverlayDesc) =
        OverlayPadTester(descs.toList(), deadZoneFraction = 0.15f, invertRightStickY = false, floatingSticks = false)

    @Test fun cButtonsPushCombineAndCancel() {
        val t = tester(
            desc("r_y_minus", 800f, 270f),
            desc("r_y_plus", 800f, 370f),
            desc("r_x_plus", 860f, 320f),
        )
        assertEquals(-127, t.onDown(1, 800f, 270f).ry)
        assertEquals(0, t.onDown(2, 800f, 370f).ry) // Opposites cancel.
        t.onUp(1, 800f, 270f)
        t.onUp(2, 800f, 370f)
        t.onDown(1, 800f, 270f)
        val diag = t.onDown(2, 860f, 320f) // Diagonal adds.
        assertEquals(127, diag.rx)
        assertEquals(-127, diag.ry)
    }

    @Test fun dualTriggerComboSetsBothSides() {
        val t = tester(desc("l2|r2", 500f, 100f))
        val s = t.onDown(1, 500f, 100f)
        assertEquals(PadState.L2 or PadState.R2, s.buttons)
        assertEquals(255, s.lt)
        assertEquals(255, s.rt)
    }

    @Test fun stickDirAndAnalogShareOneAxis() {
        // A held analog stick plus a digital dir never exceeds ±127.
        val t = tester(desc("r_x_plus", 860f, 320f))
        t.onDown(1, 860f, 320f)
        val s = t.onDown(2, 860f, 320f)
        assertEquals(127, s.rx)
    }
}
