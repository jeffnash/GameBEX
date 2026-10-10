package com.gamebex.client.stream.guest

import com.gamebex.client.stream.proto.PadState
import com.gamebex.client.together.LinkMsg
import com.gamebex.client.together.proto.LinkJson
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class NativeInteractionTest {
    private fun vector(name: String) = Json.parseToJsonElement(requireNotNull(javaClass.getResource("/emulator-facts/$name")).readText()).jsonObject
    private fun JsonElement.floats() = jsonArray.map { it.jsonPrimitive.float }
    private fun netherControls(glide: Boolean = false, latch: Boolean = false): List<ResolvedEmuControl> = vector("nether-baseline-measured.json")["controls"]!!.jsonArray.mapNotNull { raw ->
        val row = raw.jsonObject
        val resource = row["resource"]!!.jsonPrimitive.content
        if (!resource.startsWith("controller_button_")) return@mapNotNull null
        val id = resource.removePrefix("controller_button_").uppercase()
        val box = row["globalBounds"]!!.floats()
        ResolvedEmuControl(LinkMsg.EmuControl(id, x = box[0].toInt(), y = box[1].toInt(), w = (box[2] - box[0]).toInt(), h = (box[3] - box[1]).toInt(), glide = glide, latching = latch && id == "L1", output = NetherOverlay.outputFor(id)), 1604f, 720f, 1604, 720)
    }

    @Test fun allMeasuredGlidingSamplesAndReleasesMatch() {
        vector("nether-glide-observed.json")["cases"]!!.jsonArray.forEach { raw ->
            val row = raw.jsonObject
            val tester = EmuPadTester("nethersx2", netherControls(row["touch_gliding"]!!.jsonPrimitive.boolean)) { 0L }
            val down = row["down"]!!.floats()
            var state = tester.onDown(1, down[0], down[1])
            row["move"]?.takeUnless { it is JsonNull }?.floats()?.let { state = tester.onMove(1, it[0], it[1]) }
            val expected = OverlayStateAccumulator()
            row["expected_pressed_buttons"]!!.jsonArray.forEach { expected.add(NetherOverlay.outputFor(it.jsonPrimitive.content)) }
            assertEquals(row.toString(), expected.state(), state)
            assertEquals(PadState.NEUTRAL, tester.onUp(1, down[0], down[1]))
            tester.cancelAll()
            assertEquals(EmuVisual.NEUTRAL, tester.visual())
        }
    }

    @Test fun everyNativeLatchSamplePreservesPressureAndFeedback() {
        val tester = EmuPadTester("nethersx2", netherControls(latch = true)) { 0L }
        vector("nether-latch-observed.json")["samples"]!!.jsonArray.forEach { raw ->
            val row = raw.jsonObject
            val p = row["point"]!!.floats()
            val state = if (row["event"]!!.jsonPrimitive.content.endsWith("down")) tester.onDown(1, p[0], p[1]) else tester.onUp(1, p[0], p[1])
            val pressed = row["l1_pressure"]!!.jsonPrimitive.int > 0
            assertEquals(pressed, state.buttons and PadState.L1 != 0)
            assertEquals(pressed, tester.visual().pressed.isNotEmpty())
        }
    }

    @Test fun measuredDensityAndButtonShapesUseTheIntegerModel() {
        val facts = vector("nether-press-density-observed.json")
        val densities = facts["observed_densities"]!!.jsonArray.map { it.jsonPrimitive.int }
        val growth = facts["observed_unscaled_growth_px"]!!.jsonArray.map { it.jsonPrimitive.int }
        densities.forEachIndexed { index, dpi -> assertEquals(growth[index], NetherOverlay.pressedGrowth(dpi / 160f)) }
        facts["samples"]!!.jsonArray.forEach { raw ->
            val row = raw.jsonObject
            val r = row["rest_view_bounds"]!!.floats()
            val p = row["changed_bounds"]!!.floats()
            val g = NetherOverlay.pressedGrowth(row["density_dpi"]!!.jsonPrimitive.int / 160f)
            assertEquals(r[0] - g, p[0], 0f); assertEquals(r[1] - g, p[1], 0f)
            if (!row["resource_id"]!!.jsonPrimitive.content.endsWith("start")) {
                assertEquals(r[2] + g, p[2], 0f); assertEquals(r[3] + g, p[3], 0f)
            } else assertTrue(p[3] <= r[3] + g)
        }
    }

    @Test fun optionalInteractionMetadataRoundTripsAndOlderPayloadsKeepDefaults() {
        val c = LinkMsg.EmuControl("analog", 2, w = 100, h = 100, stickTravelPx = 67.08333f, stickyDpad = true, dpadDiagonals = false)
        val msg = LinkMsg.StreamEmuOverlay("s", 1, "ppsspp", listOf(c))
        assertEquals(msg, LinkJson.decode(LinkJson.encode(msg)))
        val old = LinkJson.decode("""{"t":"stream_emu_overlay","sessionId":"s","round":1,"backend":"ppsspp","controls":[{"id":"analog","kind":2,"w":100,"h":100}]}""") as LinkMsg.StreamEmuOverlay
        assertEquals(0f, old.controls.single().stickTravelPx, 0f)
        assertFalse(old.controls.single().stickyDpad); assertTrue(old.controls.single().dpadDiagonals)
        val standard = c.copy(output = OverlayOutput.Stick(false))
        fun test(backend: String, control: LinkMsg.EmuControl) = EmuPadTester(backend, listOf(ResolvedEmuControl(control, 100f, 100f, 100, 100))) { 0L }.run { onDown(1, 50f, 50f); onMove(1, 90f, 50f) }
        assertEquals(test("armsx2", standard), test("armsx2", standard.copy(stickTravelPx = 1f, stickyDpad = false, dpadDiagonals = true)))
        assertNotEquals(test("ppsspp", standard), test("ppsspp", standard.copy(stickTravelPx = 1f)))
    }

    @Test fun regularOctagonUsesVerticesOnAxesAndControllerSpecificRadii() {
        assertEquals(1.0, DolphinOverlay.octagonGateMaxR(0.0), 1e-9)
        assertEquals(1.0, DolphinOverlay.octagonGateMaxR(kotlin.math.PI / 4), 1e-9)
        assertEquals(kotlin.math.cos(kotlin.math.PI / 8), DolphinOverlay.octagonGateMaxR(kotlin.math.PI / 8), 1e-9)
        listOf(0.7937125, 0.7221375, 96.0 / 127).forEach { radius ->
            assertEquals(radius.toFloat(), DolphinOverlay.clampStickGate(2.0, 0.0, radius).first, 1e-6f)
        }
    }

    @Test fun observedSquareArtExpansionFitsTheNominalGrowth() {
        val rows = vector("nether-pressed-measured.json")["rows"]!!.jsonArray.map { it.jsonObject }
        val held = rows.first { it["image"]!!.jsonPrimitive.content.endsWith("held.png") }["colored_art_bounds"]!!.floats()
        val rest = rows.first { it["image"]!!.jsonPrimitive.content.endsWith("released.png") }["colored_art_bounds"]!!.floats()
        val nominal = netherControls().first { it.control.id == "SQUARE" }.control
        val factor = (nominal.w + 2f * NetherOverlay.pressedGrowth(1.75f)) / nominal.w
        assertEquals((rest[2] - rest[0]) * factor, held[2] - held[0], 2f)
        assertEquals((rest[3] - rest[1]) * factor, held[3] - held[1], 2f)
        assertEquals(rest[0] + rest[2], held[0] + held[2], 1f)
        assertEquals(rest[1] + rest[3], held[1] + held[3], 1f)
    }

    @Test fun nunchukSerializedBindingsPreserveItsOwnedDefaultsAndSupportedOverrides() {
        val p = com.gamebex.client.together.session.EmuOverlayReader.DolphinProfile.parse("""
            [Wiimote1]
            Extension = Nunchuk
            Nunchuk/Buttons/C = `Button 99`
            Nunchuk/Buttons/Z = `Button 104`
        """.trimIndent(), true)!!
        assertEquals(OverlayOutput.Buttons(PadState.X), p.outputFor("NUNCHUK_C", OverlayOutput.Buttons(PadState.L1)))
        assertEquals(OverlayOutput.Trigger(true), p.outputFor("NUNCHUK_Z", OverlayOutput.Trigger(true)))
        assertEquals(OverlayOutput.Buttons(PadState.A), p.outputFor("A", OverlayOutput.Buttons(PadState.A)))
    }

    @Test fun retroPriorityUsesPreviousContributionAndEqualPrioritiesCombine() {
        val rows = listOf(
            OverlayDesc("a", 0.25f, 0.5f, OverlayShape.RECT, 0.2f, 0.2f, rangeMod = 3f, rangeModExclusive = true),
            OverlayDesc("b", 0.65f, 0.5f, OverlayShape.RECT, 0.2f, 0.2f, exclusive = true),
            OverlayDesc("x", 0.65f, 0.5f, OverlayShape.RECT, 0.2f, 0.2f, exclusive = true),
        )
        val page = resolveRaPage(OverlayPage(0, normalized = true, descs = rows), RaOverlaySettings(autoScale = false), 100, 100)
        val tester = OverlayPadTester(page.descs.map { ResolvedOverlayDesc(MappedDesc(it.desc, overlayOutputFor(it.desc.input)), it, page, 100f, 100f) }, 0f, false, false)
        assertEquals(PadState.B, tester.onDown(1, 25f, 50f).buttons)
        assertEquals(PadState.B, tester.onMove(1, 65f, 50f).buttons)
        tester.onCancel(1)
        assertEquals(PadState.A or PadState.Y, tester.onDown(2, 65f, 50f).buttons)
        assertEquals(PadState.A or PadState.Y, tester.onMove(2, 65f, 50f).buttons)
        tester.cancelAll()
        assertEquals(PadState.NEUTRAL, tester.state())
    }

    @Test fun customPageScalesAboutItsOwnCenterAndFiniteDuplicateSettingsKeepTheLastValidValue() {
        val page = resolveRaPage(OverlayPage(0, rectX = 0.1f, rectY = 0.2f, rectW = 0.4f, rectH = 0.2f), RaOverlaySettings(autoScale = false, scaleLandscape = 2f), 100, 100)
        assertEquals(-0.1f, page.modX, 0.000001f)
        assertEquals(0.1f, page.modY, 0.000001f)
        val settings = DolphinOverlay.parseIni("[Android]\nControlScale=50\nControlScale=1.5\nControlScale=bad\nControlOpacity=75\nControlOpacity=NaN")
        assertEquals(50, settings.controlScale); assertEquals(75, settings.controlOpacity)
        assertEquals("1.25", effectiveIni("[Control]\nActionButtonScale=1\nActionButtonScale=1.25\nActionButtonScale=NaN")["Control/ActionButtonScale"])
    }
}
