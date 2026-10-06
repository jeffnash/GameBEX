package com.gamebex.client.stream.guest

import com.gamebex.client.stream.proto.PadState
import com.gamebex.client.together.LinkMsg
import com.gamebex.client.together.TOGETHER_PROTO
import com.gamebex.client.together.proto.Frames
import com.gamebex.client.together.proto.LinkJson
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 26b: stock RetroArch overlay parsing, RetroPad mapping, host bundle caps
 * and the Together proto bump. Fixtures are small cfgs modeled on stock
 * libretro/common-overlays files (psx landscape, neo-retropad areas).
 */
class OverlayCfgTest {
    @Test
    fun protoBumpCarriesStreamOverlay() {
        // 26b adds LinkMsg.StreamOverlay.
        assertEquals(3, TOGETHER_PROTO)
    }

    @Test
    fun streamOverlayRoundTripsAndToleratesFutureKeys() {
        val msg = LinkMsg.StreamOverlay("sess", 1, "landscape", "overlays = 0\n", listOf(LinkMsg.OverlayImage("a.png", "eA==")), true)
        assertEquals(msg, LinkJson.decode(LinkJson.encode(msg)))
        val future = LinkJson.decode("""{"t":"stream_overlay","sessionId":"s","round":2,"fromFuture":true}""")
        assertEquals(LinkMsg.StreamOverlay("s", 2), future)
    }

    @Test
    fun unknownLinkTypesAreStillDroppedNotFatal() {
        assertNull(LinkJson.decode("""{"t":"stream_overlay_xxx","sessionId":"s"}"""))
        assertNull(LinkJson.decode("not json"))
    }

    @Test
    fun parsesPagesDescsShapesAndImages() {
        val layout = OverlayCfg.parse(PSX_STYLE)
        assertEquals(2, layout.pages.size)
        val page = layout.playPage()!!
        assertEquals("landscape", page.name)
        assertEquals("gamepad.png", page.image)
        assertTrue(page.normalized)
        assertTrue(page.fullScreen)
        // 7 declared, desc6 is garbage and skipped.
        assertEquals(6, page.descs.size)
        val left = page.descs[0]
        assertEquals("left", left.input)
        assertEquals(OverlayShape.RADIAL, left.shape)
        assertEquals(0.04375f, left.x, 1e-6f)
        assertEquals(0.802083333333f, left.y, 1e-6f)
        // Half-extents scaled by the page range_mod 1.5.
        assertEquals(0.0525f * 1.5f, left.w, 1e-6f)
        assertEquals(0.0875f * 1.5f, left.h, 1e-6f)
        // Bare and quoted image refs alike.
        assertEquals("img/dpad-left.png", left.image)
        assertEquals("img/a.png", page.descs[1].image)
        // Per-desc range_mod multiplies the page one: 0.04375 * 1.5 * 2.
        assertEquals(0.13125f, page.descs[1].w, 1e-6f)
        val combo = page.descs[2]
        assertEquals("left|up", combo.input)
        assertEquals(OverlayShape.RECT, combo.shape)
    }

    @Test
    fun commentsUnknownKeysAndGarbageLinesAreSkipped() {
        val layout = OverlayCfg.parse("# nothing\n\nbogus line\nkey_without_value\n= novalue\n" + PSX_STYLE)
        assertEquals(2, layout.pages.size)
        assertEquals(6, layout.playPage()!!.descs.size)
    }

    @Test
    fun missingOverlayCountIsDerivedFromDescKeys() {
        val layout = OverlayCfg.parse(
            "overlay0_normalized = \"true\"\n" +
                "overlay0_descs = 1\n" +
                "overlay0_desc0 = \"b,0.5,0.5,radial,0.05,0.05\"\n",
        )
        assertEquals(1, layout.pages.size)
        assertEquals(1, layout.playPage()!!.descs.size)
    }

    @Test
    fun emptyCfgHasNoPlayPage() {
        val layout = OverlayCfg.parse("# stock header, no overlays\n")
        assertNull(layout.playPage())
        assertFalse(layout.mapped().usableForPlay)
    }

    @Test
    fun nonNormalizedPagesAreNotUsable() {
        val layout = OverlayCfg.parse(
            "overlays = 1\n" +
                "overlay0_descs = 1\n" +
                "overlay0_desc0 = \"a,100,200,radial,10,20\"\n",
        )
        val mapped = layout.mapped()
        assertFalse(mapped.page!!.normalized)
        assertFalse(mapped.usableForPlay)
    }

    @Test
    fun retropadMappingFollowsThe26aTable() {
        // RetroPad b/a/y/x sit where Android A/B/X/Y sit.
        assertEquals(OverlayOutput.Buttons(PadState.B), overlayOutputFor("a"))
        assertEquals(OverlayOutput.Buttons(PadState.A), overlayOutputFor("b"))
        assertEquals(OverlayOutput.Buttons(PadState.Y), overlayOutputFor("x"))
        assertEquals(OverlayOutput.Buttons(PadState.X), overlayOutputFor("y"))
        assertEquals(OverlayOutput.Buttons(PadState.L1), overlayOutputFor("l"))
        assertEquals(OverlayOutput.Buttons(PadState.R1), overlayOutputFor("r"))
        assertEquals(OverlayOutput.Trigger(left = true), overlayOutputFor("l2"))
        assertEquals(OverlayOutput.Trigger(left = false), overlayOutputFor("r2"))
        assertEquals(OverlayOutput.Buttons(PadState.L3), overlayOutputFor("l3"))
        assertEquals(OverlayOutput.Buttons(PadState.R3), overlayOutputFor("r3"))
        assertEquals(OverlayOutput.Buttons(PadState.SELECT), overlayOutputFor("select"))
        assertEquals(OverlayOutput.Buttons(PadState.START), overlayOutputFor("start"))
        assertEquals(OverlayOutput.Stick(right = false), overlayOutputFor("analog_left"))
        assertEquals(OverlayOutput.Stick(right = true), overlayOutputFor("analog_right"))
        assertEquals(OverlayOutput.Hat(true, false, false, false), overlayOutputFor("up"))
        assertEquals(OverlayOutput.Hat(false, true, true, false), overlayOutputFor("down|left"))
        assertEquals(OverlayOutput.Hat(true, false, true, false), overlayOutputFor("left|up"))
    }

    @Test
    fun menuAreasAndHotkeysAreIgnoredNeverUnknown() {
        listOf("menu_toggle", "menu", "quit", "reset", "overlay_next", "overlay_prev", "overlay_custom",
            "dpad_area", "abxy_area", "screenshot", "cheat_toggle", "disk_next", "save_state", "load_state",
            "rewind", "pause_toggle", "nul", "none").forEach { name ->
            assertEquals(name, OverlayOutput.Ignored, overlayOutputFor(name))
        }
    }

    @Test
    fun unknownInputsAreReportedForFallback() {
        assertEquals(OverlayOutput.Unknown, overlayOutputFor("lightgun_trigger"))
        assertEquals(OverlayOutput.Unknown, overlayOutputFor(""))
        // Mixed gamepad + unknown, or mixed kinds, never half-maps.
        assertEquals(OverlayOutput.Unknown, overlayOutputFor("a|lightgun_trigger"))
        assertEquals(OverlayOutput.Unknown, overlayOutputFor("a|up"))
    }

    @Test
    fun allMappedPlayPageIsUsable() {
        val mapped = OverlayCfg.parse(PSX_STYLE).mapped()
        // 4 gamepad descs; menu_toggle + dpad_area are ignored, not fallback trips.
        assertEquals(4, mapped.gamepad.size)
        assertTrue(mapped.unknown.isEmpty())
        assertTrue(mapped.usableForPlay)
    }

    @Test
    fun anythingUnmappedFallsBackTo26a() {
        val cfg = PSX_STYLE.replace(
            "overlay0_desc4 = \"menu_toggle,0.5,0.05,rect,0.02,0.02\"",
            "overlay0_desc4 = \"lightgun_trigger,0.5,0.05,rect,0.02,0.02\"",
        )
        val mapped = OverlayCfg.parse(cfg).mapped()
        assertEquals(listOf("lightgun_trigger"), mapped.unknown)
        assertFalse(mapped.usableForPlay)
    }

    @Test
    fun playPageWithNoGamepadControlsIsNotUsable() {
        val cfg = "overlays = 1\noverlay0_normalized = \"true\"\noverlay0_descs = 1\n" +
            "overlay0_desc0 = \"overlay_next,0.5,0.05,rect,0.02,0.02\"\n"
        assertFalse(OverlayCfg.parse(cfg).mapped().usableForPlay)
    }

    @Test
    fun stickOutputHasADeadZoneLinearRangeAndSaturation() {
        assertEquals(listOf(0, 0), overlayStickOutput(0f, 0f, 36f, 0.1f).toList())
        assertEquals(listOf(0, 0), overlayStickOutput(2f, 0f, 36f, 0.1f).toList())
        // Mid-travel lands just above half scale (float, not exact decimal).
        assertEquals(listOf(64, 0), overlayStickOutput(20f, 0f, 36f, 0.1f).toList())
        assertEquals(listOf(127, 0), overlayStickOutput(100f, 0f, 36f, 0.1f).toList())
        val up = overlayStickOutput(0f, -36f, 36f, 0.1f).toList()
        assertEquals(listOf(0, -127), up)
        assertEquals(listOf(0, 0), overlayStickOutput(10f, 10f, 0f, 0.1f).toList())
    }

    @Test
    fun overlayPathTakesTheLastActiveOccurrence() {
        assertNull(HostOverlayBundle.overlayPathFromCfgText("# empty\ninput_overlay_enable = \"true\"\n"))
        assertNull(HostOverlayBundle.overlayPathFromCfgText("input_overlay = \"\"\n"))
        assertEquals(
            "overlays/b.cfg",
            HostOverlayBundle.overlayPathFromCfgText(
                "#input_overlay = \"overlays/old.cfg\"\n" +
                    "input_overlay = \"overlays/a.cfg\"\n" +
                    "input_overlay = overlays/b.cfg\n",
            ),
        )
        assertFalse(HostOverlayBundle.isReadableOverlayPath(":/overlays/bundled.cfg"))
        assertTrue(HostOverlayBundle.isReadableOverlayPath("overlays/b.cfg"))
    }

    @Test
    fun coreOverrideMatchesExactIdThenDisplayName() {
        val root = Files.createTempDirectory("gbx-overlay-override").toFile()
        try {
            val exactDir = root.resolve("config/test_core").apply { mkdirs() }
            val exact = exactDir.resolve("test_core.cfg").apply { writeText("input_overlay = \"a.cfg\"\n") }
            assertEquals(exact, HostOverlayBundle.findCoreOverride(root, "test_core"))
            exact.delete()
            assertNull(HostOverlayBundle.findCoreOverride(root, "test_core"))
            // RetroArch names the dir after the display name: Mupen64Plus-Next.
            val displayDir = root.resolve("config/Mupen64Plus-Next").apply { mkdirs() }
            val display = displayDir.resolve("Mupen64Plus-Next.cfg").apply { writeText("input_overlay = \"b.cfg\"\n") }
            assertEquals(display, HostOverlayBundle.findCoreOverride(root, "mupen64plus_next"))
            // Graphics-variant core ids (the N64 route is mupen64plus_next_gles3)
            // still match the display-name dir.
            assertEquals(display, HostOverlayBundle.findCoreOverride(root, "mupen64plus_next_gles3"))
            assertEquals(display, HostOverlayBundle.findCoreOverride(root, "mupen64plus_next_vulkan"))
            assertNull(HostOverlayBundle.findCoreOverride(root, "other_core"))
            assertNull(HostOverlayBundle.findCoreOverride(root, null))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun readPacksCfgAndImagesFromSharedStorage() {
        val root = Files.createTempDirectory("gbx-overlay-read").toFile()
        try {
            root.resolve("retroarch.cfg").writeText("input_overlay = \"overlays/pad.cfg\"\n")
            val overlays = root.resolve("overlays").apply { mkdirs() }
            overlays.resolve("pad.cfg").writeText(SMALL_PAD)
            overlays.resolve("img").apply { mkdirs() }.resolve("a.png").writeBytes(byteArrayOf(1, 2, 3, 4))
            val msg = HostOverlayBundle.read(root, null, "sess", 2)!!
            assertEquals("sess", msg.sessionId)
            assertEquals(2, msg.round)
            assertEquals("landscape", msg.name)
            assertTrue(msg.cfg.contains("overlay0_desc0"))
            assertEquals(listOf("img/a.png"), msg.images.map { it.name })
            assertFalse(msg.truncated)
            // Round-trips through the real link codec.
            assertEquals(msg, LinkJson.decode(LinkJson.encode(msg)))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun readPrefersTheCoreOverrideOverTheMainCfg() {
        val root = Files.createTempDirectory("gbx-overlay-prec").toFile()
        try {
            root.resolve("retroarch.cfg").writeText("input_overlay = \"main.cfg\"\n")
            root.resolve("main.cfg").writeText(SMALL_PAD)
            val dir = root.resolve("config/Test Core").apply { mkdirs() }
            dir.resolve("Test Core.cfg").writeText("input_overlay = \"core.cfg\"\n")
            root.resolve("core.cfg").writeText(SMALL_PAD.replace("landscape", "core-pad"))
            val msg = HostOverlayBundle.read(root, "test_core", "s", 1)!!
            assertEquals("core-pad", msg.name)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun readReturnsNullWhenThereIsNothingWorthSending() {
        val root = Files.createTempDirectory("gbx-overlay-missing").toFile()
        try {
            // No cfgs at all.
            assertNull(HostOverlayBundle.read(root, null, "s", 1))
            // Overlay key missing.
            root.resolve("retroarch.cfg").writeText("input_overlay_enable = \"true\"\n")
            assertNull(HostOverlayBundle.read(root, null, "s", 1))
            // Bundled-asset path: inside RetroArch, unreadable.
            root.resolve("retroarch.cfg").writeText("input_overlay = \":/overlays/bundled.cfg\"\n")
            assertNull(HostOverlayBundle.read(root, null, "s", 1))
            // Points at nothing.
            root.resolve("retroarch.cfg").writeText("input_overlay = \"overlays/gone.cfg\"\n")
            assertNull(HostOverlayBundle.read(root, null, "s", 1))
            // No descriptors on the play page.
            root.resolve("overlays").apply { mkdirs() }.resolve("gone.cfg").writeText("overlays = 1\noverlay0_descs = 0\n")
            assertNull(HostOverlayBundle.read(root, null, "s", 1))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun unreadableAndUnsafeImagesAreDroppedAndFlagged() {
        val root = Files.createTempDirectory("gbx-overlay-drop").toFile()
        try {
            root.resolve("retroarch.cfg").writeText("input_overlay = \"pad.cfg\"\n")
            root.resolve("pad.cfg").writeText(
                "overlays = 1\noverlay0_normalized = \"true\"\noverlay0_descs = 3\n" +
                    "overlay0_desc0 = \"a,0.5,0.5,radial,0.05,0.05\"\n" +
                    "overlay0_desc0_overlay = \"img/big.png\"\n" +
                    "overlay0_desc1 = \"b,0.5,0.6,radial,0.05,0.05\"\n" +
                    "overlay0_desc1_overlay = \"../evil.png\"\n" +
                    "overlay0_desc2 = \"start,0.5,0.7,rect,0.05,0.05\"\n",
            )
            root.resolve("img").apply { mkdirs() }.resolve("big.png")
                .writeBytes(ByteArray(HostOverlayBundle.MAX_IMAGE_BYTES + 1))
            root.resolve("evil.png").writeBytes(byteArrayOf(9))
            val msg = HostOverlayBundle.read(root, null, "s", 1)!!
            assertTrue(msg.images.isEmpty())
            assertTrue(msg.truncated)
            assertTrue(msg.cfg.contains("overlay0_desc2"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun buildEnforcesTheSingleFrameCaps() {
        val packed = HostOverlayBundle.build(
            "s", 1, "landscape", "x".repeat(20_000),
            List(20) { i -> "img$i.png" to ByteArray(3_000) { i.toByte() } },
        )
        assertTrue(packed.truncated)
        assertTrue(packed.cfg.length <= HostOverlayBundle.MAX_CFG_CHARS)
        assertTrue(packed.images.size <= HostOverlayBundle.MAX_IMAGES)
        val total = packed.images.sumOf { it.name.length + it.base64.length }
        assertTrue(total <= HostOverlayBundle.MAX_IMAGE_BASE64_TOTAL + 16 * 16)
        // The worst case still fits one link JSON frame.
        assertTrue(LinkJson.encode(packed).length <= Frames.MAX_JSON)
        // Empty and oversize singles are dropped, not packed.
        val singles = HostOverlayBundle.build(
            "s", 1, "", "overlays = 0\n",
            listOf("e.png" to ByteArray(0), "big.png" to ByteArray(HostOverlayBundle.MAX_IMAGE_BYTES + 1)),
        )
        assertTrue(singles.images.isEmpty())
        assertTrue(singles.truncated)
    }

    @Test
    fun stickDirsAndTriggerCombosMap() {
        assertEquals(
            OverlayOutput.StickDir(right = true, dx = 0, dy = -1),
            overlayOutputFor("r_y_minus"),
        )
        assertEquals(
            OverlayOutput.StickDir(right = true, dx = 1, dy = 1),
            overlayOutputFor("r_x_plus|r_y_plus"),
        )
        assertEquals(OverlayOutput.Unknown, overlayOutputFor("r_x_plus|l_y_plus"))
        assertEquals(
            OverlayOutput.Triggers(left = true, right = true),
            overlayOutputFor("l2|r2"),
        )
        assertEquals(OverlayOutput.Ignored, overlayOutputFor("toggle_fast_forward"))
    }

    @Test
    fun everySeededStockOverlayIsUsableForPlay() {
        // The overlay share must engage on stock RetroArch: one unmapped name anywhere
        // rejects the whole overlay, so every cfg OverlaySeed can
        // plant maps clean here.
        val names = listOf(
            "overlay-seed/snes_snes.cfg", "overlay-seed/gameboy_gameboy.cfg",
            "overlay-seed/gba_gba.cfg", "overlay-seed/nes_nes.cfg",
            "overlay-seed/genesis_genesis.cfg", "overlay-seed/psx_psx.cfg",
            "overlay-seed/arcade_arcade.cfg", "overlay-seed/flat_dreamcast.cfg",
            "overlay-seed/flat_saturn.cfg", "overlay-n64/n64.cfg",
        )
        for (name in names) {
            val text = requireNotNull(javaClass.classLoader?.getResource(name)) { name }.readText()
            val mapped = OverlayCfg.parse(text).mapped()
            assertTrue("$name unknown=${mapped.unknown}", mapped.unknown.isEmpty())
            assertTrue("$name usable", mapped.usableForPlay)
        }
    }

    @Test
    fun realN64OverlayPacks() {
        // The full 26b host pack against the real v3 n64 overlay: the
        // cfg fits and the play page stands. (On JVM the PNG shrink is
        // a stub, so the byte budget binds and the pack truncates; on
        // device the shrink fits every image. Either way gamepad art
        // packs before the menu/fast-forward toys.)
        val dir = File(requireNotNull(javaClass.classLoader?.getResource("overlay-n64/n64.cfg")) { "n64" }.toURI()).parentFile!!
        val msg = HostOverlayBundle.packFile(File(dir, "n64.cfg"), "s", 1)!!
        assertTrue(msg.cfg.isNotBlank())
        assertTrue(msg.images.isNotEmpty())
        val refs = msg.images.map { it.name }
        assertTrue(refs.none { it.contains("rgui") || it.contains("fast_forward") || it.contains("rotate") || it.contains("analog") })
        assertTrue(refs.any { it.endsWith("A.png") })
        val mapped = OverlayCfg.parse(msg.cfg).mapped()
        assertTrue(mapped.usableForPlay)
    }

    @Test
    fun shrinkFallsBackToInputOffDevice() {
        val bytes = "not-a-png".toByteArray()
        assertTrue(HostOverlayBundle.shrinkPng(bytes).contentEquals(bytes))
    }

    private companion object {
        /** Modeled on stock psx landscape + neo-retropad areas: descs, combos, areas, ranges. */
        const val PSX_STYLE: String = """overlays = 2

overlay0_name = "landscape"
overlay0_overlay = gamepad.png
overlay0_full_screen = true
overlay0_normalized = true
overlay0_range_mod = 1.5

overlay1_name = "portrait"
overlay1_full_screen = true
overlay1_normalized = true

# landscape
overlay0_descs = 7
overlay0_desc0 = "left,0.04375,0.802083333333,radial,0.0525,0.0875"
overlay0_desc0_overlay = img/dpad-left.png
overlay0_desc1 = "a,0.95625,0.802083333333,radial,0.04375,0.0729166666667"
overlay0_desc1_overlay = "img/a.png"
overlay0_desc1_range_mod = 2.0
overlay0_desc2 = "left|up,0.0375,0.6666666667,rect,0.0375,0.0625"
overlay0_desc3 = "analog_left,0.11875,0.677083333333,radial,0.0870967,0.1548757"
overlay0_desc4 = "menu_toggle,0.5,0.05,rect,0.02,0.02"
overlay0_desc5 = "dpad_area,0.094623,0.556405,rect,0.089583,0.159259"
overlay0_desc6 = "bogus"

overlay1_descs = 1
overlay1_desc0 = "a,0.5,0.5,radial,0.05,0.05"
"""

        const val SMALL_PAD: String = """overlays = 1
overlay0_name = "landscape"
overlay0_full_screen = "true"
overlay0_normalized = "true"
overlay0_descs = 2
overlay0_desc0 = "a,0.9,0.8,radial,0.05,0.07"
overlay0_desc0_overlay = img/a.png
overlay0_desc1 = "start,0.5,0.9,rect,0.04,0.05"
"""
    }
}
