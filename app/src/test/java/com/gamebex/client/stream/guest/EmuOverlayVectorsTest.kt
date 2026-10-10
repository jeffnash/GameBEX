package com.gamebex.client.stream.guest

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.floor

class EmuOverlayVectorsTest {
    private fun vector(name: String): JsonObject {
        return Json.parseToJsonElement(requireNotNull(javaClass.getResource("/emulator-facts/$name")).readText()).jsonObject
    }
    private fun JsonElement.numbers() = jsonArray.map { it.jsonPrimitive.float }
    private fun JsonObject.number(key: String) = getValue(key).jsonPrimitive.float
    private fun assertNumbers(expected: JsonElement, actual: List<Number>, tolerance: Float = 0.0002f) {
        val expectedNumbers = expected.numbers()
        assertEquals(expectedNumbers.size, actual.size)
        expectedNumbers.zip(actual).forEach { (a, b) -> assertEquals(a, b.toFloat(), tolerance) }
    }
    private fun assertDirections(expected: JsonElement, actual: BooleanArray) = assertArrayEquals(expected.jsonArray.map { it.jsonPrimitive.boolean }.toBooleanArray(), actual)

    @Test fun emulatorOverlayVectors() {
        val rows = vector("emu-overlay-vectors.json")["vectors"]!!.jsonArray
        assertEquals(39, rows.size)
        rows.forEach { row ->
            val v = row.jsonObject
            val input = v["input"]!!.jsonObject
            val expected = v["expected"]!!
            when (val operation = v["operation"]!!.jsonPrimitive.content) {
                "dolphin_image_dimensions" -> {
                    val image = input["image"]!!.numbers()
                    val dims = DolphinOverlay.rasterDimensions(image[0].toInt(), image[1].toInt(), DolphinOverlay.effectiveScale(input.number("base"), input.number("userScale").toInt()), input.number("minDisplay").toInt())
                    assertNumbers(expected, listOf(dims.first, dims.second))
                }
                "dolphin_opacity" -> assertEquals(expected.jsonPrimitive.int, DolphinOverlay.controlAlpha(input.number("percent").toInt()))
                "dolphin_directions" -> {
                    val p = input["point"]!!.numbers(); val r = input["rect"]!!.numbers().map(Float::toInt)
                    assertDirections(expected, DolphinOverlay.dpadDirs(p[0].toInt(), p[1].toInt(), r[0], r[1], r[0] + r[2], r[1] + r[3], r[2], r[3]))
                }
                "arms_directions" -> {
                    val p = input["point"]!!.numbers()
                    assertDirections(expected, ArmsOverlay.dpadDirs(p[0], p[1], input.number("halfSide"), input.number("gap")))
                }
                "arms_stick" -> {
                    val p = input["delta"]!!.numbers(); val s = ArmsOverlay.stickSample(p[0], p[1], input.number("radius"))
                    assertNumbers(expected, listOf(s.nx, s.ny, s.thumbDx, s.thumbDy))
                }
                "arms_turbo_period" -> assertEquals(expected.jsonPrimitive.long, ArmsOverlay.turboPeriodMs(input.number("frames").toInt()))
                "melon_default_rectangles" -> {
                    val d = input["displayAndDpi"]!!.numbers().map(Float::toInt); val r = MelondsOverlay.defaultLayout(d[0], d[1], d[2])
                    listOf("dpad" to r.dpad, "buttons" to r.buttons, "leftShoulder" to r.l, "rightShoulder" to r.r, "select" to r.select, "start" to r.start)
                        .forEach { (name, rect) -> assertNumbers(expected.jsonObject[name]!!, rect.toList()) }
                }
                "melon_directions" -> {
                    val p = input["point"]!!.numbers(); val size = input["size"]!!.numbers()
                    assertDirections(expected, MelondsOverlay.dpadDirs(p[0], p[1], size[0], size[1]))
                }
                "flycast_default_logical_rectangles" -> {
                    val d = input["displayAndDpi"]!!.numbers().map(Float::toInt); val controls = FlycastOverlay.layoutControls(d[0], d[1], d[2])
                    assertEquals(expected.jsonArray.size, controls.size)
                    expected.jsonArray.zip(controls).forEach { (e, c) -> assertNumbers(e, listOf(c.id, c.x, c.y, c.w, c.h)) }
                }
                "straight_alpha_scale", "fractional_straight_alpha_quad" -> {
                    val image = input["image"]!!.jsonArray.map { it.jsonPrimitive.int }.toIntArray()
                    val src = input["sourceSize"]!!.numbers().map(Float::toInt); val dst = input["targetSize"]!!.numbers().map(Float::toInt)
                    val out = if (operation == "straight_alpha_scale") scaleStraightBilinear(image, src[0], src[1], dst[0], dst[1]) else {
                        val o = input["origin"]!!.numbers().map(Float::toInt); val r = input["rect"]!!.numbers()
                        renderStraightFrac(image, src[0], src[1], dst[0], dst[1], o[0], o[1], r[0], r[1], r[2], r[3])
                    }
                    assertArrayEquals(expected.jsonArray.map { it.jsonPrimitive.int }.toIntArray(), out)
                }
                else -> fail("Uncovered vector operation $operation")
            }
        }
    }

    @Test fun retroOverlayVectors() {
        val root = vector("retro-observed.json")
        val size = root["viewport"]!!.numbers().map(Float::toInt)
        val cases = root["settingsCases"]!!.jsonArray
        val rows = root["vectors"]!!.jsonArray
        assertEquals(8, rows.size)
        rows.forEach { row ->
            val v = row.jsonObject
            val settings = cases[v["settingsCase"]!!.jsonPrimitive.int].jsonObject
            val landscape = settings["landscape"]?.numbers() ?: listOf(1f, 0f, 0f, 0f, 0f, 0f)
            val config = RaOverlaySettings(settings.number("opacity"), settings["autoScale"]!!.jsonPrimitive.boolean,
                scaleLandscape = landscape[0], aspectAdjustLandscape = landscape[1], xSeparationLandscape = landscape[2], ySeparationLandscape = landscape[3], xOffsetLandscape = landscape[4], yOffsetLandscape = landscape[5])
            val fixture = v["fixture"]!!.jsonPrimitive.content
            val text = javaClass.classLoader!!.getResourceAsStream(fixture)!!.bufferedReader().use { it.readText() }
            val page = OverlayCfg.parse(text).pages[v["page"]!!.jsonPrimitive.int]
            val resolved = resolveRaPage(page, config, size[0], size[1])
            val expected = v["controls"]!!.jsonArray
            assertEquals(expected.size, resolved.descs.size)
            expected.zip(resolved.descs).forEach { (e, d) ->
                val c = e.jsonObject
                assertEquals(c["input"]!!.jsonPrimitive.content, d.desc.input)
                assertNumbers(c["draw"]!!, listOf(d.modX, d.modY, d.modW, d.modH), 0.000001f)
                assertNumbers(c["hit"]!!, listOf(d.hitX, d.hitY, d.hitRangeX, d.hitRangeY), 0.000001f)
                val r = d.pixelRect(size[0], size[1])
                assertNumbers(c["pixelRect"]!!, listOf(r.left, r.top, r.width, r.height), 0f)
                assertNumbers(c["alpha"]!!, listOf(raAlphaFor(d.desc, config, false), raAlphaFor(d.desc, config, true)))
            }
        }
    }

    @Test fun measuredNetherAxisModel() {
        val root = vector("nether-axis-model.json")
        val box = root["view_rect_xywh"]!!.numbers().map(Float::toInt)
        root["cases"]!!.jsonArray.forEach { element ->
            val v = element.jsonObject; val p = v["point"]!!.numbers()
            val s = NetherOverlay.axisSample(p[0] - box[0], p[1] - box[1], box[2], box[3], 35)
            assertNumbers(v["expected_knob_delta"]!!, listOf(s.knobDx, s.knobDy), 0f)
            val normalized = v["expected_normalized_axes"]!!.numbers().map { it.coerceIn(-1f, 1f) }
            assertEquals(normalized[0], s.nx, 0.000001f); assertEquals(normalized[1], s.ny, 0.000001f)
            val gain = v.number("native_gain")
            val predicted = listOf(s.nx, s.ny).map { floor(127.5 * (1 + (it * gain).coerceIn(-1f, 1f))).toInt() }
            assertNumbers(v["expected_console_axes"]!!, predicted, 0f)
        }
    }

    @Test fun malformedDescriptorsDoNotRemoveValidControls() {
        val text = """
            overlays=1
            overlay0_normalized=true
            overlay0_descs=3
            overlay0_desc0="a,0.5,0.5,rect,0.1,0.1"
            overlay0_desc1="b,NaN,0.5,rect,0.1,0.1"
            overlay0_desc2="new_action,0.2,0.2,radial,0.1,0.1"
        """.trimIndent()
        val page = OverlayCfg.parse(text).pages.single()
        assertEquals(2, page.descs.size)
        assertEquals(OverlayOutput.Ignored, overlayOutputFor(page.descs.last().input))
        assertEquals(0.4f, RaOverlaySettings.parse("input_overlay_opacity=0.4", "input_overlay_opacity=NaN").opacity, 0f)
        assertEquals(0.7f, RaOverlaySettings.parse("unrelated=1").opacity, 0f)
    }

    @Test fun partialFlycastOverridePreservesOtherComputedComponents() {
        val base = FlycastOverlay.layoutControls(1604, 720, 280)
        val cfg = FlycastOverlay.parseCfg("[vgamepad]\ndpad_scale=1.25")
        val override = FlycastOverlay.layoutControls(1604, 720, 280, cfg.elements)
        assertEquals(base[0].x, override[0].x, 0.0001f)
        assertEquals(base[1].y, override[1].y, 0.0001f)
        assertEquals(base[0].w * 1.25f, override[0].w, 0.0001f)
    }
}
