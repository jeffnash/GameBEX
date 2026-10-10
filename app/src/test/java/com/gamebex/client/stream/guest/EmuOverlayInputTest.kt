package com.gamebex.client.stream.guest

import com.gamebex.client.stream.proto.PadState
import com.gamebex.client.together.LinkMsg
import org.junit.Assert.*
import org.junit.Test

class EmuOverlayInputTest {
    private fun control(id: String, x: Int = 0, y: Int = 0, w: Int = 100, h: Int = 100, output: OverlayOutput, kind: Int = 0, latching: Boolean = false, turbo: Int = 0) =
        ResolvedEmuControl(LinkMsg.EmuControl(id, kind, x = x, y = y, w = w, h = h, output = output, latching = latching, turboFrames = turbo), 1000f, 1000f, 1000, 1000)

    @Test fun dolphinLatchTogglesAtDownAndCancellationClearsEveryPointer() {
        val tester = EmuPadTester("dolphin", listOf(control("A", output = OverlayOutput.Buttons(PadState.A), latching = true), control("B", x = 200, output = OverlayOutput.Buttons(PadState.B)))) { 0L }
        assertEquals(PadState.A, tester.onDown(1, 50f, 50f).buttons)
        assertEquals(PadState.A, tester.onUp(1, 50f, 50f).buttons)
        assertEquals(PadState.A or PadState.B, tester.onDown(2, 250f, 50f).buttons)
        assertEquals(PadState.B, tester.onDown(3, 50f, 50f).buttons)
        tester.cancelAll()
        assertEquals(PadState.NEUTRAL, tester.state())
        assertEquals(EmuVisual.NEUTRAL, tester.visual())
    }

    @Test fun armsTurboStopsOnReleaseAndUsesItsOwnClock() {
        var clock = 0L
        val tester = EmuPadTester("armsx2", listOf(control("CROSS", output = OverlayOutput.Buttons(PadState.A), turbo = 2))) { clock }
        assertEquals(PadState.A, tester.onDown(1, 50f, 50f).buttons)
        assertTrue(tester.hasTurbo())
        clock = 33
        assertEquals(PadState.NEUTRAL, tester.state())
        clock = 66
        assertEquals(PadState.A, tester.state().buttons)
        tester.onUp(1, 50f, 50f)
        assertFalse(tester.hasTurbo())
        clock = 99
        assertEquals(PadState.NEUTRAL, tester.state())
    }

    @Test fun melonOverlappingCirclesCombineAndLiftOnlyTheirOwnPointer() {
        val whole = intArrayOf(100, 100, 512, 512)
        val faces = MelondsOverlay.BUTTON_QUADS.mapIndexed { i, crop ->
            val r = MelondsOverlay.fracBox(whole, crop)
            control(MelondsOverlay.BUTTON_IDS[i], r[0], r[1], r[2], r[3], MelondsOverlay.outputFor(MelondsOverlay.BUTTON_IDS[i]))
        }
        val tester = EmuPadTester("melonds", faces) { 0L }
        assertEquals(PadState.X or PadState.A, tester.onDown(1, 484f, 228f).buttons)
        assertEquals(PadState.X or PadState.A or PadState.Y, tester.onDown(2, 110f, 356f).buttons)
        assertEquals(PadState.Y, tester.onUp(1, 484f, 228f).buttons)
        tester.cancelAll()
        assertEquals(PadState.NEUTRAL, tester.state())
    }

    @Test fun localMenuEmitsOneActionAndNoControllerOutput() {
        val tester = EmuPadTester("armsx2", listOf(control("PAUSE", output = OverlayOutput.MenuToggle))) { 0L }
        assertEquals(PadState.NEUTRAL, tester.onDown(1, 50f, 50f))
        assertEquals(OverlayActionTap.Menu, tester.pollActionTap())
        tester.onMove(1, 55f, 55f)
        assertNull(tester.pollActionTap())
        tester.cancelAll()
    }

    @Test fun retroOverlappingButtonsAndExclusiveCaptureReleaseOnCancel() {
        val descs = listOf(OverlayDesc("a", 0.5f, 0.5f, OverlayShape.RECT, 0.2f, 0.2f), OverlayDesc("l2", 0.5f, 0.5f, OverlayShape.RECT, 0.2f, 0.2f))
        fun tester(rows: List<OverlayDesc>): OverlayPadTester {
            val page = resolveRaPage(OverlayPage(0, normalized = true, descs = rows), RaOverlaySettings(autoScale = false), 100, 100)
            return OverlayPadTester(page.descs.map { ResolvedOverlayDesc(MappedDesc(it.desc, overlayOutputFor(it.desc.input)), it, page, 100f, 100f) }, 0f, false, false)
        }
        val combined = tester(descs)
        assertEquals(PadState(buttons = PadState.B, lt = 255), combined.onDown(1, 50f, 50f))
        combined.cancelAll()
        assertEquals(PadState.NEUTRAL, combined.state())
        val exclusive = tester(listOf(descs[0].copy(exclusive = true), descs[1]))
        assertEquals(PadState(buttons = PadState.B), exclusive.onDown(1, 50f, 50f))
        exclusive.onCancel(1)
        assertEquals(PadState.NEUTRAL, exclusive.state())
    }

    @Test fun hitGeometryRejectsDisabledAxesAndInvalidSizes() {
        val d = OverlayDesc("a", 0.5f, 0.5f, OverlayShape.RADIAL, 0.2f, 0.2f, reachLeft = 0f, reachRight = 0f)
        val p = resolveRaPage(OverlayPage(0, descs = listOf(d)), RaOverlaySettings(autoScale = false), 100, 100)
        assertFalse(raHitTest(p.descs.single(), p, 0.5f, 0.5f))
        val mapping = overlayViewportMapping(802f, 360f, 1534, 720, 70, 0, 1604, 720)
        assertEquals(35f, mapping.offX, 0f)
        assertEquals(767f, mapping.effW, 0f)
    }
    @Test fun activeDolphinProfileOnlyAcceptsTheCompleteDigitalExpression() {
        val profile = com.gamebex.client.together.session.EmuOverlayReader.DolphinProfile.parse("""
            [GCPad1]
            Buttons/A = `Button 99`
            Buttons/B = `Button 96` | `Button 97`
            Triggers/L = `Button 104`
            Triggers/R = `Button 102`
        """.trimIndent(), false)!!
        assertEquals(OverlayOutput.Buttons(PadState.X), profile.outputFor("A", OverlayOutput.Buttons(PadState.A)))
        assertEquals(OverlayOutput.Ignored, profile.outputFor("B", OverlayOutput.Buttons(PadState.B)))
        assertEquals(OverlayOutput.Trigger(true), profile.outputFor("L", OverlayOutput.Trigger(true)))
        assertEquals(OverlayOutput.Buttons(PadState.L1), profile.outputFor("R", OverlayOutput.Trigger(false)))
        assertEquals(OverlayOutput.Buttons(PadState.Y), profile.outputFor("Y", OverlayOutput.Buttons(PadState.Y)))
        assertNull(com.gamebex.client.together.session.EmuOverlayReader.DolphinProfile.parse("[GCPad1]\nButtons/A=`Button 96`\n[GCPad2]\nButtons/A=`Button 97`", false))
    }

}
