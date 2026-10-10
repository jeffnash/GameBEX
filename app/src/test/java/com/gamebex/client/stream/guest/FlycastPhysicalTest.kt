package com.gamebex.client.stream.guest

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class FlycastPhysicalTest {
    private val facts get() = Json.parseToJsonElement(requireNotNull(javaClass.getResource("/emulator-facts/flycast-physical-density-render-observed.json")).readText()).jsonObject

    @Test fun fractionalPhysicalDensityMatchesMeasuredPhysicalRects() {
        val inputs = facts.getValue("native_observation").jsonObject
        val w = inputs.getValue("width").jsonPrimitive.int; val h = inputs.getValue("height").jsonPrimitive.int
        val dpi = inputs.getValue("selected_physical_dpi").jsonPrimitive.float
        val controls = FlycastOverlay.layoutControls(w, h, dpi).associateBy { it.id }
        facts.getValue("expected_controls").jsonArray.filter { it.jsonObject.getValue("id").jsonPrimitive.int < 18 }.forEach { raw ->
            val row = raw.jsonObject; val c = controls.getValue(row.getValue("id").jsonPrimitive.int)
            val actual = listOf(FlycastOverlay.dcToPx(c.x, h, w, true), FlycastOverlay.dcToPx(c.y, h, w, false), c.w * h / 480f, c.h * h / 480f)
            actual.zip(row.getValue("rect").jsonArray).forEach { (a, e) -> assertEquals(row.toString(), e.jsonPrimitive.double, a.toDouble(), 0.002) }
        }
    }

    @Test fun intSignaturesAndPartialOverridesStayCompatibleAndInvalidPhysicalDpiIsBounded() {
        assertEquals(FlycastOverlay.uiScale(720, 280), FlycastOverlay.uiScale(720, 280f), 0f)
        assertEquals(FlycastOverlay.layoutControls(1604, 720, 280), FlycastOverlay.layoutControls(1604, 720, 280f))
        val base = FlycastOverlay.layoutControls(1604, 720, 264.555f)
        val settings = FlycastOverlay.parseCfg("[vgamepad]\nbuttons_y=0.2")
        val changed = FlycastOverlay.layoutControls(1604, 720, 264.555f, settings.elements)
        assertEquals(base[4].x, changed[4].x, 0f); assertEquals(base[4].w, changed[4].w, 0f)
        assertNotEquals(base[4].y, changed[4].y)
        for (invalid in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) assertTrue(FlycastOverlay.layoutControls(1604, 720, invalid).isEmpty())
    }

    @Test fun sourcedStateTintsKeepAnalogAndFastForwardFixedAndMultiplyVisibility() {
        val tint = facts.getValue("tints").jsonObject
        assertEquals(tint.getValue("ordinary_rest").jsonPrimitive.float, FlycastOverlay.tintGray(false), 0f)
        assertEquals(tint.getValue("ordinary_held").jsonPrimitive.float, FlycastOverlay.tintGray(true), 0f)
        for (pressed in listOf(false, true)) {
            assertEquals(0.5f, FlycastOverlay.tintGray(11, pressed), 0f)
            assertEquals(0.25f, FlycastOverlay.tintGray(12, pressed), 0f)
            assertEquals(0.25f, FlycastOverlay.tintGray(13, pressed), 0f)
        }
        assertEquals(0.4375f, FlycastOverlay.tintGray(9, true, 0.25f), 0f)
        assertEquals(0.375f, FlycastOverlay.tintGray(10, true, 0.5f), 0f)
        assertEquals(0.25f, FlycastOverlay.tintGray(7, false, visibility = 0.5f), 0f)
        assertEquals(0.315f, FlycastOverlay.overlayAlpha(37, 0.5f), 0.000001f)
        assertEquals(facts.getValue("steady_opacity").jsonPrimitive.float, FlycastOverlay.overlayAlpha(37), 0f)
    }
}
