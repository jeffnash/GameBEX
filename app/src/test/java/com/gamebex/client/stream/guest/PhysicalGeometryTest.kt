package com.gamebex.client.stream.guest

import com.gamebex.client.stream.proto.PadState
import com.gamebex.client.together.LinkMsg
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PhysicalGeometryTest {
    @Test fun dolphinPrecisionVectorsRetainDoubleVisualProductsAndSingleControllerAxes() {
        val facts = Json.parseToJsonElement(requireNotNull(javaClass.getResource("/emulator-facts/dolphin-visual-precision-observed.json")).readText()).jsonObject
        facts.getValue("rows").jsonArray.forEach { raw ->
            val row = raw.jsonObject
            val displacement = row.getValue("raw_pixel_displacement").jsonPrimitive.float
            val half = row.getValue("integer_half_extent").jsonPrimitive.int
            val expected = row.getValue("visual_integer_displacement").jsonPrimitive.int
            assertEquals(expected to 0, DolphinOverlay.stickVisualDisplacement(displacement, 0f, half * 2, half * 2, 1.0))
            assertEquals(0 to expected, DolphinOverlay.stickVisualDisplacement(0f, displacement, half * 2, half * 2, 1.0))
            val normalized = row.getValue("normalized_single_precision").jsonPrimitive.double
            assertEquals(normalized.toFloat(), DolphinOverlay.clampStickGate(normalized, 0.0).first, 0f)
        }
        assertEquals(0 to 0, DolphinOverlay.stickVisualDisplacement(1f, 1f, 0, 198, 1.0))
    }

    @Test fun measuredDolphinThumbBoundsUseEvenSidesAndIntegerCentersAndMovement() {
        val facts = Json.parseToJsonElement(requireNotNull(javaClass.getResource("/emulator-facts/dolphin-visual-precision-observed.json")).readText()).jsonObject
        fun JsonElement.ints() = jsonArray.map { it.jsonPrimitive.int }
        val outer = facts.getValue("outer_rect").ints()
        val down = facts.getValue("down").ints()
        val move = facts.getValue("move").ints()
        val expected = facts.getValue("native_held_thumb_rect").ints()
        val c = LinkMsg.EmuControl("STICK_MAIN", 2, x = outer[0], y = outer[1], w = outer[2], h = outer[3], knobSize = expected[2], recenter = true, output = OverlayOutput.Stick(false))
        val tester = EmuPadTester("dolphin", listOf(ResolvedEmuControl(c, 1604f, 720f, 1604, 720))) { 0L }
        tester.onDown(1, down[0].toFloat(), down[1].toFloat())
        tester.onMove(1, move[0].toFloat(), move[1].toFloat())
        val offset = tester.visual().knobDxPx.getValue(0)
        assertEquals(RaPixelRect(expected[0], expected[1], expected[2], expected[3]), DolphinOverlay.thumbRect(c.x, c.y, c.w, c.h, c.knobSize, offset.x, offset.y))
        facts.getValue("rows").jsonArray.forEach { raw ->
            val row = raw.jsonObject
            val displacement = row.getValue("raw_pixel_displacement").jsonPrimitive.float
            if (kotlin.math.abs(displacement) <= 49f) {
                val delta = row.getValue("visual_integer_displacement").jsonPrimitive.int.toFloat()
                tester.onMove(1, down[0] + displacement, down[1].toFloat())
                assertEquals(delta, tester.visual().knobDxPx.getValue(0).x, 0f)
                tester.onMove(1, down[0].toFloat(), down[1] + displacement)
                assertEquals(delta, tester.visual().knobDxPx.getValue(0).y, 0f)
            }
        }
        tester.onUp(1, down[0].toFloat(), down[1].toFloat())
        assertTrue(tester.visual().knobDxPx.isEmpty())
        assertEquals(RaPixelRect(5, 473, 144, 144), DolphinOverlay.thumbRect(27, 446, 199, 199, 145, -49.9f, -0.9f))
    }

    @Test fun negativeRelativeControlGeometryAndTouchRemainAbsoluteAtBothDisplayScales() {
        val facts = Json.parseToJsonElement(requireNotNull(javaClass.getResource("/emulator-facts/dolphin-physical-clipping-observed.json")).readText()).jsonObject
        fun JsonElement.ints() = jsonArray.map { it.jsonPrimitive.int }
        val display = facts["host_display"]!!.ints(); val viewport = facts["host_viewport"]!!.ints()
        for (scale in listOf(1f, 0.5f)) {
            val controls = facts["controls"]!!.jsonArray.map { raw ->
                val row = raw.jsonObject; val box = row["viewport_relative_rect"]!!.ints(); val absolute = row["absolute_rect"]!!.ints()
                val control = LinkMsg.EmuControl(row["id"]!!.jsonPrimitive.content, x = box[0], y = box[1], w = box[2], h = box[3], output = OverlayOutput.Trigger(true))
                ResolvedEmuControl(control, display[0] * scale, display[1] * scale, viewport[2], viewport[3], viewport[0], viewport[1], display[0], display[1]).also {
                    assertEquals(absolute[0] * scale, it.x, 0f); assertEquals(absolute[1] * scale, it.y, 0f)
                    assertEquals(absolute[2] * scale, it.w, 0f); assertEquals(absolute[3] * scale, it.h, 0f)
                    assertTrue(it.contains(50 * scale, (absolute[1] + absolute[3] / 2f) * scale))
                }
            }
            val tester = EmuPadTester("dolphin", controls) { 0L }
            assertEquals(255, tester.onDown(1, 50 * scale, 300 * scale).lt)
            assertEquals(PadState.NEUTRAL, tester.onUp(1, 50 * scale, 300 * scale))
        }
    }
}
