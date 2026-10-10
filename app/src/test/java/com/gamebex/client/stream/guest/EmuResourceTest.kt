package com.gamebex.client.stream.guest

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.floor

class EmuResourceTest {
    private fun vector(name: String): JsonObject = Json.parseToJsonElement(requireNotNull(javaClass.getResource("/emulator-facts/$name")).readText()).jsonObject
    private fun JsonElement.ints() = jsonArray.map { it.jsonPrimitive.int }.toIntArray()

    @Test fun installedNetherDrawableSetResolvesBaseAndKnobForEveryAnalogAxis() {
        val resources = vector("nether-complete-drawable-resource-facts.json")["drawables"]!!.jsonArray.map { it.jsonObject }
            .associateBy { it["name"]!!.jsonPrimitive.content }
        val layouts = vector("nether-resources.json")["layouts"]!!.jsonArray.map { it.jsonObject }
            .filter { it["resource"]!!.jsonPrimitive.content.substringAfter('/') in setOf("layout_touchscreen_controller_analog_stick", "layout_touchscreen_controller_analog_sticks") }
        val axes = layouts.map { layout -> layout["nodes"]!!.jsonArray.mapNotNull { node ->
            node.jsonObject["attributes"]!!.jsonObject["id"]?.jsonObject?.get("value")?.jsonPrimitive?.content?.takeIf { it.startsWith("id/controller_axis_") }
        } }
        assertEquals(listOf(1, 2), axes.map { it.size })
        axes.flatten().forEach { axis ->
            for (name in listOf(NetherOverlay.ANALOG_BASE_DRAWABLE, NetherOverlay.ANALOG_STICK_DRAWABLE)) {
                val resolved = resources[name]
                assertNotNull("$axis must resolve installed drawable/$name", resolved)
                assertTrue(resolved!!["resource_id"]!!.jsonPrimitive.content.removePrefix("0x").toLong(16) != 0L)
                assertEquals("drawable/$name", resolved["resource_key"]!!.jsonPrimitive.content)
                assertEquals("res/drawable/$name.xml", resolved["resource_path"]!!.jsonPrimitive.content)
            }
        }
        assertFalse(resources.containsKey("controller_analog_base"))
        assertFalse(resources.containsKey("controller_analog_stick"))
    }

    @Test fun installedResourceSetLocatesEveryGcControlInBothOrientations() {
        val entries = vector("dolphin-complete-integer-facts.json")["integer_resources"]!!.jsonArray
        assertEquals(244, entries.size)
        val values = entries.map { it.jsonObject }.mapNotNull { row ->
            val value = row["values"]!!.jsonArray.map { it.jsonObject }.firstOrNull { it["configuration"]!!.jsonPrimitive.content.isEmpty() } ?: return@mapNotNull null
            row["resource"]!!.jsonPrimitive.content.substringAfter('/') to value["value"]!!.jsonPrimitive.int
        }.toMap()
        val ids = listOf("A", "B", "X", "Y", "Z", "START", "L", "R", "DPAD", "STICK_MAIN", "STICK_C")
        for ((w, h) in listOf(1604 to 720, 720 to 1604)) {
            val present = ids.filter { id ->
                val prefix = DolphinOverlay.gcPositionResource(id)!!
                DolphinOverlay.resourcePosition(prefix, "X", w, h, values::get) != null &&
                    DolphinOverlay.resourcePosition(prefix, "Y", w, h, values::get) != null
            }
            assertEquals(ids, present)
            for (prefix in listOf("WIIMOTE_O_UP", "WIIMOTE_UP", "NUNCHUK_BUTTON_C", "NUNCHUK_BUTTON_Z", "NUNCHUK_STICK")) {
                assertNotNull(DolphinOverlay.resourcePosition(prefix, "X", w, h, values::get))
                assertNotNull(DolphinOverlay.resourcePosition(prefix, "Y", w, h, values::get))
            }
        }
        assertEquals("BUTTON_UP", DolphinOverlay.gcPositionResource("DPAD"))
        assertEquals("STICK_MAIN", DolphinOverlay.gcPositionResource("STICK_MAIN"))
        assertEquals("STICK_C", DolphinOverlay.gcPositionResource("STICK_C"))
    }

    @Test fun installedLayoutFactsResolveAllMeasuredNetherBounds() {
        val facts = vector("nether-resources.json")
        val layout = facts["layouts"]!!.jsonArray.first { it.jsonObject["resource"]!!.jsonPrimitive.content.endsWith("_analog_stick") }.jsonObject
        val nodes = layout["nodes"]!!.jsonArray.mapNotNull { raw ->
            val attributes = raw.jsonObject["attributes"]!!.jsonObject.mapValues { it.value.jsonObject["value"]!!.jsonPrimitive.content }
            val id = attributes["id"]?.removePrefix("id/") ?: return@mapNotNull null
            ResourceOverlayNode(id, attributes)
        }
        val baseline = vector("nether-baseline-measured.json")
        val viewport = baseline["viewport"]!!.ints()
        val bounds = resolveResourceOverlayBounds(nodes, viewport[2], viewport[3], 1.75f)
        baseline["controls"]!!.jsonArray.forEach { value ->
            val control = value.jsonObject
            val box = bounds[control["resource"]!!.jsonPrimitive.content]!!
            val expected = control["globalBounds"]!!.ints()
            assertArrayEquals(expected, intArrayOf(box[0] + viewport[0], box[1] + viewport[1], box[0] + viewport[0] + box[2], box[1] + viewport[1] + box[3]))
        }
    }

    @Test fun measuredNetherProfileTransformsRoundEveryEdge() {
        val baseline = vector("nether-baseline-measured.json")["controls"]!!.jsonArray.associate { value ->
            val c = value.jsonObject; c["resource"]!!.jsonPrimitive.content to c["globalBounds"]!!.ints()
        }
        vector("nether-transform-measured.json")["cases"]!!.jsonArray.forEach { raw ->
            val case = raw.jsonObject
            val input = case["input"]!!.jsonObject.values.firstOrNull()?.jsonObject ?: JsonObject(emptyMap())
            case["controls"]!!.jsonArray.forEach { value ->
                val control = value.jsonObject; val id = control["resource"]!!.jsonPrimitive.content
                val original = baseline[id]!!
                val name = when (id) { "controller_button_l1" -> "L1Button"; "controller_button_cross" -> "CrossButton"; "controller_axis_left" -> "LeftAxis"; else -> "" }
                fun number(suffix: String, default: Float) = input["${name}Landscape$suffix"]?.jsonPrimitive?.content?.toFloat() ?: default
                val box = NetherOverlay.transformedRect(original[0], original[1], original[2] - original[0], original[3] - original[1], number("XTranslation", 0f), number("YTranslation", 0f), number("Scale", 1f))
                assertArrayEquals(control["bounds"]!!.ints(), intArrayOf(box[0], box[1], box[0] + box[2], box[1] + box[3]))
            }
        }
    }

    @Test fun allMeasuredConsoleSamplesUseTouchGainAndNativeGainAtTheirOwnBoundaries() {
        val box = vector("nether-axis-model.json")["view_rect_xywh"]!!.ints()
        listOf("nether-console-observed.json" to 1.33f, "nether-console-axisgain1.json" to 1f).forEach { (name, gain) ->
            vector(name)["cases"]!!.jsonArray.forEach { raw ->
                val c = raw.jsonObject; val point = c["point"]!!.ints()
                val s = NetherOverlay.axisSample((point[0] - box[0]).toFloat(), (point[1] - box[1]).toFloat(), box[2], box[3], 35)
                val bytes = listOf(s.nx, s.ny).map { floor(127.5 * (1 + (it * gain).coerceIn(-1f, 1f))).toInt() }.toIntArray()
                assertArrayEquals(c["observed_console_left_axes"]!!.ints(), bytes)
            }
        }
        vector("nether-native-conversion-observed.json")["cases"]!!.jsonArray.forEach { raw ->
            val c = raw.jsonObject
            val bytes = c["wire_left_axes"]!!.ints().map { floor(127.5 * (1 + (it / 127f * 1.33f).coerceIn(-1f, 1f))).toInt() }.toIntArray()
            assertArrayEquals(c["observed_console_left_axes"]!!.ints(), bytes)
        }
    }

    @Test fun exportedArmsProfileRetainsEachSettingAndUnknownControls() {
        val profile = vector("arms-runtime-profile.json")["profile"]!!.jsonObject
        val expected = profile["layout"]!!.jsonObject["buttons"]!!.jsonArray
        val buttons = ArmsOverlay.parseLayout(profile.toString())
        assertEquals(expected.size, buttons.size)
        expected.zip(buttons).forEach { (raw, actual) ->
            val e = raw.jsonObject
            assertEquals(e["id"]!!.jsonPrimitive.content, actual.id)
            assertEquals(e["x"]!!.jsonPrimitive.float, actual.xFrac, 0f)
            assertEquals(e["y"]!!.jsonPrimitive.float, actual.yFrac, 0f)
            assertEquals(e["size"]!!.jsonPrimitive.float, actual.sizeDp, 0f)
            assertEquals(e["on"]!!.jsonPrimitive.boolean, actual.enabled)
            assertEquals(e["hold"]!!.jsonPrimitive.boolean, actual.tapToHold)
            assertEquals(e["turbo"]!!.jsonPrimitive.int, actual.turbo)
        }
        assertEquals(OverlayOutput.Ignored, ArmsOverlay.outputFor("MACRO1"))
        assertTrue(ArmsOverlay.parseLayout("{\"layout\":{\"buttons\":[{\"id\":\"A\",\"x\":0.5,\"y\":0.5,\"size\":-2}]}}").isEmpty())
    }

    @Test fun atlasAndCropFactsStayWithinTheirResourceImages() {
        val fly = vector("flycast-facts.json")
        val expected = fly["atlas_cells"]!!.jsonArray
        assertEquals(expected.size, FlycastOverlay.TEX_CELLS.size)
        val bindings = vector("flycast-owned-face-art-binding-observed.json")["owned_bindings"]!!.jsonArray
            .associate { it.jsonObject["owned_id"]!!.jsonPrimitive.int to it.jsonObject["atlas_rect"]!!.ints() }
        expected.zip(FlycastOverlay.TEX_CELLS).forEachIndexed { id, (row, actual) ->
            assertArrayEquals(bindings[id] ?: row.jsonObject["rect"]!!.ints(), actual)
        }
        val melon = vector("melon-resource-art-facts.json")
        melon["measured_direction_crops_xywh"]!!.jsonArray.zip(MelondsOverlay.DPAD_ARMS).forEach { (row, actual) -> assertArrayEquals(row.ints(), actual) }
        melon["measured_face_crops_xywh"]!!.jsonArray.zip(MelondsOverlay.BUTTON_QUADS).forEach { (row, actual) -> assertArrayEquals(row.ints(), actual) }
    }
    @Test fun measuredNetherOpacityBlendsResourceColorOnce() {
        val root = vector("nether-alpha-observed.json")
        val color = root["resource_color_rgb"]!!.ints()
        root["rows"]!!.jsonArray.forEach { raw ->
            val c = raw.jsonObject
            val alpha = DolphinOverlay.controlAlpha(c["opacity"]!!.jsonPrimitive.int)
            val predicted = color.map { floor(it * alpha / 255.0 + 0.5).toInt() }.toIntArray()
            assertArrayEquals(c["white_stroke_peak_on_black"]!!.ints(), predicted)
        }
        assertEquals(57, NetherOverlay.knobHalf(96))
    }

    @Test fun dolphinPositionsAreReadByResourceNameForBothOrientations() {
        val root = vector("dolphin-resource-facts.json")
        val rows = root.values.first { it is JsonArray }.jsonArray
        var count = 0
        rows.forEach { raw ->
            val row = raw.jsonObject
            val resource = row["resource"]?.jsonPrimitive?.content?.substringAfter('/') ?: return@forEach
            if (!resource.endsWith("_X") && !resource.endsWith("_Y")) return@forEach
            val observed = row["observed_values"]!!.jsonArray.mapNotNull { value -> Regex("\\(\\)\\s+(\\d+)").find(value.jsonPrimitive.content)?.groupValues?.get(1)?.toIntOrNull() }.single()
            val portrait = "_PORTRAIT_" in resource
            val width = if (portrait) 720 else 1604
            val height = if (portrait) 1604 else 720
            val axis = resource.takeLast(1)
            val name = resource.dropLast(2).removeSuffix("_PORTRAIT")
            var requested: String? = null
            val position = DolphinOverlay.resourcePosition(name, axis, width, height) { requested = it; observed }
            assertEquals(resource, requested)
            assertEquals(observed.toLong() * (if (axis == "X") width else height) / 1000, position!!.toLong())
            count++
        }
        assertTrue(count > 20)
        assertNull(DolphinOverlay.resourcePosition("BUTTON_A", "X", 1604, 720) { null })
        assertEquals(401, DolphinOverlay.resourcePosition("BUTTON_A", "X", 1604, 720) { 250 })
    }

    @Test fun measuredNetherDigitalEqualityEdgesUseIntegerThirds() {
        val root = vector("nether-dpad-observed.json")
        val box = root["view_rect_xywh"]!!.ints()
        root["cases"]!!.jsonArray.forEach { raw ->
            val c = raw.jsonObject; val point = c["point"]!!.ints()
            assertArrayEquals(c["expected_dirs"]!!.jsonArray.map { it.jsonPrimitive.boolean }.toBooleanArray(),
                NetherOverlay.dpadDirs(point[0] - box[0], point[1] - box[1], box[2], box[3]))
        }
    }

}
