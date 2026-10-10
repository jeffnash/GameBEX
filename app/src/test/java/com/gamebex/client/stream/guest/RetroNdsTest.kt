package com.gamebex.client.stream.guest

import com.gamebex.client.stream.proto.PadState
import com.gamebex.client.together.LinkMsg
import com.gamebex.client.together.proto.LinkJson
import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RetroNdsTest {
    private val facts get() = Json.parseToJsonElement(requireNotNull(javaClass.getResource("/emulator-facts/retro-nds-observed.json")).readText()).jsonObject
    private fun root(): File = Files.createTempDirectory(File("build/nds-tests").apply { mkdirs() }.toPath(), "nds-").toFile()
    private fun nativeConfig(): String {
        val cfg = facts.getValue("all_native_fields").jsonObject.entries.joinToString("\n", postfix = "\n") { (k, v) -> "$k=${v.jsonPrimitive.content}" }
        assertEquals(facts.getValue("semantic_compact_characters_observed").jsonPrimitive.int, cfg.length)
        return cfg + "#" + "owned padding".padEnd(facts.getValue("full_characters").jsonPrimitive.int - cfg.length - 1, ' ')
    }

    private fun tester(cfg: String, settings: RaOverlaySettings = RaOverlaySettings(autoScale = false)): Pair<OverlayPadTester, List<ResolvedOverlayDesc>> {
        val page = requireNotNull(OverlayCfg.parse(cfg).playPage())
        val resolved = resolveRaPage(page, settings, 200, 200)
        val descs = page.descs.mapIndexed { i, d -> ResolvedOverlayDesc(MappedDesc(d, overlayOutputFor(d.input)), resolved.descs[i], resolved, 200f, 200f) }
        return OverlayPadTester(descs, 0.15f, false, false, settings) to descs
    }

    private val areas = """overlays=1
overlay0_name=landscape
overlay0_normalized=true
overlay0_auto_x_separation=false
overlay0_descs=2
overlay0_desc0=dpad_area,0.25,0.5,rect,0.2,0.1
overlay0_desc0_reach_x=1.1
overlay0_desc0_reach_y=1.1
overlay0_desc1=abxy_area,0.75,0.5,rect,0.2,0.1
overlay0_desc1_reach_x=1.1
overlay0_desc1_reach_y=1.1
"""

    @Test fun oversizedNativeConfigPacksEveryFirstPageDescriptorAndArtThroughPortableStorage() {
        val root = root()
        StreamOverlayStore.clear()
        try {
            val cfg = root.resolve("NDS.cfg").apply { writeText(nativeConfig()) }
            assertEquals(35971, cfg.readText().length)
            val refs = facts.getValue("fields").jsonObject.filterKeys { it.endsWith("_overlay") }.values.map { it.jsonPrimitive.content }.toSet()
            refs.forEach { root.resolve(it).writeBytes(byteArrayOf(1, 2, 3)) }
            val parts = requireNotNull(HostOverlayBundle.packFile(cfg, "s", 1)) { "complete native first page must remain eligible" }
            assertTrue(parts.all { it.truncated && it.cfg.length <= 32000 })
            parts.forEach { StreamOverlayStore.publish(1, LinkJson.decode(LinkJson.encode(it)) as LinkMsg.StreamOverlay) }
            val stored = requireNotNull(StreamOverlayStore.current())
            val page = requireNotNull(stored.layout.playPage())
            assertEquals("gamepad1_landscape", page.name)
            assertEquals(23, page.descs.size)
            assertTrue(page.descs.last().hitDisabled)
            assertEquals(refs, page.descs.mapNotNull { it.image }.toSet())
            assertTrue(stored.images.keys.containsAll(refs))
            assertTrue(stored.complete); assertTrue(stored.truncated)
            val resolved = resolveRaPage(page, stored.settings.copy(autoScale = false), 200, 200)
            val descs = page.descs.mapIndexed { i, d -> ResolvedOverlayDesc(MappedDesc(d, overlayOutputFor(d.input)), resolved.descs[i], resolved, 200f, 200f) }
            val guest = OverlayPadTester(descs, 0.15f, false, false, stored.settings)
            for (raw in facts.getValue("area_cases").jsonArray) {
                val row = raw.jsonObject
                val d = descs[if (row.getValue("input").jsonPrimitive.content == "dpad_area") 0 else 1]
                val delta = row.getValue("normalized_displacement").jsonArray.map { it.jsonPrimitive.float }
                val expected = OverlayStateAccumulator().apply { row.getValue("native_inputs").jsonArray.forEach { add(overlayOutputFor(it.jsonPrimitive.content)) } }.state()
                assertEquals(row.toString(), expected, guest.onDown(1, d.cx + delta[0] * d.rx, d.cy + delta[1] * d.ry))
                assertEquals(PadState.NEUTRAL, guest.onUp(1, d.cx, d.cy))
            }
            assertEquals(32, HostOverlayBundle.MAX_CFG_CHARS / 1000)
            assertEquals(128, HostOverlayBundle.MAX_IMAGES); assertEquals(192000, HostOverlayBundle.MAX_IMAGE_BYTES)
            assertEquals(56000, HostOverlayBundle.MAX_IMAGE_BASE64_PER_PART)
        } finally { StreamOverlayStore.clear(); root.deleteRecursively() }
    }

    @Test fun cappedUnquotedTailCannotBecomeGuessedGeometryAndBoundsStayFixed() {
        val start = "overlays=1\noverlay0_normalized=true\noverlay0_descs=2\noverlay0_desc0=a,0.5,0.5,rect,0.2,0.2\n"
        val tail = "overlay0_desc1=b,0.5,0.5,rect,0.2,0.123456789"
        val cfg = start + "#" + " ".repeat(32000 - start.length - tail.length + 5) + "\n" + tail + "\n"
        assertTrue(cfg.length > 32000)
        val packed = HostOverlayBundle.build("s", 1, "", cfg, emptyList()).first()
        val page = requireNotNull(OverlayCfg.parse(packed.cfg).playPage())
        assertEquals(listOf("a"), page.descs.map { it.input })
        assertTrue(packed.truncated)
        val tooMany = "overlays=99\noverlay0_normalized=true\noverlay0_descs=999\n" + (0..140).joinToString("\n") { "overlay0_desc$it=a,0.5,0.5,rect,0.2,0.2" }
        val bounded = OverlayCfg.parse(tooMany)
        assertEquals(32, bounded.pages.size); assertEquals(128, bounded.pages.first().descs.size)
    }

    @Test fun commentsPreserveLiteralHashesAndEscapedQuotesInValues() {
        val cfg = """overlays=1 # count
overlay0_name="landscape#\"quoted\"" # comment
overlay0_normalized=true # normalized
overlay0_descs=1
overlay0_desc0="a,0.5,0.5,rect,0.2,0.2" # tuple
overlay0_desc0_overlay="image#\"edge\".png" # resource
"""
        val page = requireNotNull(OverlayCfg.parse(cfg).playPage())
        assertEquals("landscape#\"quoted\"", page.name)
        assertEquals("image#\"edge\".png", page.descs.single().image)
        assertEquals("image#\"edge\".cfg", HostOverlayBundle.overlayPathFromCfgText("input_overlay=\"image#\\\"edge\\\".cfg\" # path"))
    }

    @Test fun allEighteenNativeAreaCasesUseEachNominalHalfExtentAndNoInventedDeadZone() {
        val (tester, descs) = tester(areas)
        for (raw in facts.getValue("area_cases").jsonArray) {
            val row = raw.jsonObject
            val index = if (row.getValue("input").jsonPrimitive.content == "dpad_area") 0 else 1
            val delta = row.getValue("normalized_displacement").jsonArray.map { it.jsonPrimitive.float }
            val d = descs[index]
            val expected = OverlayStateAccumulator().apply { row.getValue("native_inputs").jsonArray.forEach { add(overlayOutputFor(it.jsonPrimitive.content)) } }.state()
            assertEquals(row.toString(), expected, tester.onDown(1, d.cx + delta[0] * d.rx, d.cy + delta[1] * d.ry))
            assertEquals(PadState.NEUTRAL, tester.onUp(1, d.cx, d.cy))
        }
    }

    @Test fun movementOverlapStickyPriorityAndPerPointerCancellationPreserveOtherContributions() {
        val cfg = areas.replace("overlay0_descs=2", "overlay0_descs=3") + "overlay0_desc0_range_mod=2\noverlay0_desc0_range_mod_exclusive=true\noverlay0_desc2=start,0.25,0.6,rect,0.04,0.04\n"
        val (tester, descs) = tester(cfg)
        val d = descs[0]
        assertEquals(PadState(buttons = PadState.START, hat = PadState.HAT_DOWN), tester.onDown(1, d.cx, d.cy + d.ry))
        assertEquals(PadState(hat = PadState.HAT_UP), tester.onMove(1, d.cx, d.cy - d.ry * 1.5f))
        assertEquals(PadState(buttons = PadState.B, hat = PadState.HAT_UP), tester.onDown(2, descs[1].cx + descs[1].rx, descs[1].cy))
        tester.onCancel(1)
        assertEquals(PadState(buttons = PadState.B), tester.state())
        assertEquals(PadState.NEUTRAL, tester.onUp(2, 0f, 0f))
        tester.onDown(1, d.cx, d.cy)
        tester.cancelAll(); assertEquals(PadState.NEUTRAL, tester.state()); assertTrue(tester.visual(200f, 200f).pressed.isEmpty())
    }

    @Test fun customCardinalBindingsCombineRecognizedOutputsAndLeaveLocalUnknownActionsInert() {
        val (tester, descs) = tester(areas + "overlay0_desc0_up=a|b\noverlay0_desc0_down=l2\noverlay0_desc0_left=menu_toggle\noverlay0_desc0_right=right\n")
        val d = descs[0]
        assertEquals(PadState(buttons = PadState.A or PadState.B, hat = PadState.HAT_RIGHT), tester.onDown(1, d.cx + d.rx, d.cy - d.ry))
        assertEquals(PadState(lt = 255), tester.onMove(1, d.cx, d.cy + d.ry))
        assertEquals(PadState.NEUTRAL, tester.onMove(1, d.cx - d.rx, d.cy))
        assertNull(tester.pollActionTap())
        assertEquals(PadState.NEUTRAL, tester.onUp(1, 0f, 0f))
    }

    @Test fun sensitivityFollowsEffectivePrecedencePortableRoundTripAndLegacyDefaults() {
        val root = root()
        val json = Json { encodeDefaults = true }
        fun fields(settings: RaOverlaySettings) = json.encodeToJsonElement(LinkMsg.OverlaySettingsMsg.serializer(), settings.toMessage(200, 200)).jsonObject
        try {
            root.resolve("retroarch.cfg").writeText("input_overlay_dpad_diagonal_sensitivity=10\ninput_overlay_abxy_diagonal_sensitivity=20\nvideo_driver=gl")
            root.resolve("config/melonds/melonds.cfg").apply { parentFile!!.mkdirs(); writeText("input_overlay_dpad_diagonal_sensitivity=100") }
            val settings = HostOverlayBundle.effectiveSettings(root, "melonds", "input_overlay_dpad_diagonal_sensitivity=30\ninput_overlay_abxy_diagonal_sensitivity=40")
            val decoded = LinkJson.decode(LinkJson.encode(LinkMsg.StreamOverlay("s", 1, settings = settings.toMessage(200, 200)))) as LinkMsg.StreamOverlay
            assertEquals(100f, fields(decoded.settings.toSettings()).getValue("dpadDiagonalSensitivity").jsonPrimitive.float, 0f)
            assertEquals(40f, fields(decoded.settings.toSettings()).getValue("abxyDiagonalSensitivity").jsonPrimitive.float, 0f)
            assertTrue(decoded.settings.toSettings().floatingAlpha)
            val legacy = LinkJson.decode("""{"t":"stream_overlay","sessionId":"s","round":1}""") as LinkMsg.StreamOverlay
            val defaults = fields(legacy.settings.toSettings())
            assertEquals(80f, defaults.getValue("dpadDiagonalSensitivity").jsonPrimitive.float, 0f)
            assertEquals(50f, defaults.getValue("abxyDiagonalSensitivity").jsonPrimitive.float, 0f)
            val invalid = fields(RaOverlaySettings.parse("input_overlay_dpad_diagonal_sensitivity=-1\ninput_overlay_abxy_diagonal_sensitivity=101", "input_overlay_dpad_diagonal_sensitivity=NaN"))
            assertEquals(0f, invalid.getValue("dpadDiagonalSensitivity").jsonPrimitive.float, 0f)
            assertEquals(100f, invalid.getValue("abxyDiagonalSensitivity").jsonPrimitive.float, 0f)
        } finally { root.deleteRecursively() }
    }

    @Test fun effectiveSensitivityChangesBothAreaAngularBandsAndZeroKeepsItsDiagonal() {
        for (sensitivity in listOf(0f, 100f)) {
            val (tester, descs) = tester(areas, RaOverlaySettings(autoScale = false, dpadDiagonalSensitivity = sensitivity, abxyDiagonalSensitivity = sensitivity))
            val d = descs[0]
            assertEquals(PadState(hat = if (sensitivity == 0f) PadState.HAT_RIGHT else PadState.HAT_DOWN_RIGHT), tester.onDown(1, d.cx + d.rx * 0.8f, d.cy + d.ry * 0.7f))
            tester.onCancel(1)
            val face = descs[1]
            assertEquals(PadState(buttons = if (sensitivity == 0f) PadState.B else PadState.B or PadState.A), tester.onDown(1, face.cx + face.rx * 0.8f, face.cy + face.ry * 0.7f))
            tester.onCancel(1)
            assertEquals(PadState(hat = PadState.HAT_DOWN_LEFT), tester.onDown(1, d.cx, d.cy))
        }
        assertTrue(raAreaDirections(Float.NaN, 0f, 80f).none { it })
    }

    @Test fun logicalHighlightsKeepOpposingPointersAndAreAddedOnlyInTouchMode() {
        val cfg = areas.replace("overlay0_descs=2", "overlay0_descs=4") + "overlay0_desc2=up,0.1,0.1,rect,0.02,0.02\noverlay0_desc2_reach_x=0\noverlay0_desc3=down,0.1,0.2,rect,0.02,0.02\noverlay0_desc3_reach_x=0\n"
        for (mode in listOf(RA_SHOW_INPUT_TOUCHED, RA_SHOW_INPUT_NONE, RA_SHOW_INPUT_PHYSICAL)) {
            val (tester, descs) = tester(cfg, RaOverlaySettings(autoScale = false, showInputs = mode))
            val d = descs[0]
            tester.onDown(1, d.cx, d.cy - d.ry)
            tester.onDown(2, d.cx, d.cy + d.ry)
            assertEquals(PadState.NEUTRAL, tester.state())
            assertEquals(if (mode == RA_SHOW_INPUT_TOUCHED) setOf(0, 2, 3) else setOf(0), tester.visual(200f, 200f).pressed)
            tester.onCancel(1)
            assertEquals(PadState(hat = PadState.HAT_DOWN), tester.state())
            assertEquals(if (mode == RA_SHOW_INPUT_TOUCHED) setOf(0, 3) else setOf(0), tester.visual(200f, 200f).pressed)
            tester.cancelAll(); assertTrue(tester.visual(200f, 200f).pressed.isEmpty())
        }
        assertTrue(OverlayCfg.parse(areas.replace("overlay0_descs=2", "overlay0_descs=1")).mapped().usableForPlay)
    }
}
