package com.gamebex.client.stream.guest

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ArmsGeometryTest {
    private fun vector(name: String) = Json.parseToJsonElement(requireNotNull(javaClass.getResource("/emulator-facts/$name")).readText()).jsonObject
    private fun JsonElement.ints() = jsonArray.map { it.jsonPrimitive.int }.toIntArray()
    private val profile get() = ArmsOverlay.parseLayout(vector("arms-runtime-profile.json").getValue("profile").toString()).associateBy { it.id }

    @Test fun allEnabledNativeWidgetRectsUseFloatProfileAndNearestPhysicalPixels() {
        val facts = vector("arms-full-display-observed.json")
        val display = facts.getValue("display").ints()
        val density = facts.getValue("density_dpi").jsonPrimitive.int / 160f
        facts.getValue("controls").jsonArray.forEach { raw ->
            val row = raw.jsonObject
            assertArrayEquals(row.getValue("id").jsonPrimitive.content, row.getValue("rect").ints(),
                ArmsOverlay.widgetRect(profile.getValue(row.getValue("id").jsonPrimitive.content), display[0], display[1], density))
        }
    }

    @Test fun nativeDirectionalCellsUseNearestRatioSizesAndCenteredOffsets() {
        val layout = vector("arms-full-display-observed.json").getValue("directional_layout").jsonObject
        val widget = layout.getValue("widget").ints()
        val arms = ArmsOverlay.dpadArms(widget[0], widget[1], widget[2], layout.getValue("density").jsonPrimitive.float, layout.getValue("spacing").jsonPrimitive.float)
        layout.getValue("cells").jsonArray.zip(listOf(arms.up, arms.down, arms.left, arms.right)).forEach { (raw, actual) ->
            assertArrayEquals(raw.jsonObject.getValue("direction").jsonPrimitive.content, raw.jsonObject.getValue("rect").ints(), actual)
        }
    }

    @Test fun negativeFractionalOffsetsRoundNearestAndLegacyScreenDimensionsRemainUsable() {
        val negative = profile.getValue("L1").copy(xFrac = -0.01f, yFrac = 0.005f, sizeDp = 3f)
        assertArrayEquals(intArrayOf(-4, -2, 5, 5), ArmsOverlay.widgetRect(negative, 100, 100, 1.75f))
        assertArrayEquals(intArrayOf(74, 117, 98, 98), ArmsOverlay.widgetRect(profile.getValue("L1"), 1534, 720, 1.75f))
    }
}
