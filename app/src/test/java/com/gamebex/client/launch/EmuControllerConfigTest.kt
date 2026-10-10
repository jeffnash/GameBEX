package com.gamebex.client.launch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure writers against real on-device configs (GameSir-X5s). */
class EmuControllerConfigTest {
    private fun fixture(name: String): String =
        requireNotNull(javaClass.classLoader?.getResource("emucfg/$name")) { name }.readText()

    /** Lines that differ between two texts of equal line count. */
    private fun diff(a: String, b: String): List<Pair<String, String>> {
        val la = a.lines()
        val lb = b.lines()
        assertEquals(la.size, lb.size)
        return la.zip(lb).filter { it.first != it.second }
    }

    @Test fun dolphinRealFileAlreadyOverlayOffIsUntouched() {
        val real = fixture("dolphin_Dolphin.ini")
        assertSame(real, EmuControllerConfig.dolphinIni(real))
    }

    @Test fun dolphinOverlayOnFlipsOnlyThatLine() {
        val on = fixture("dolphin_Dolphin.ini").replace("ShowInputOverlay = False", "ShowInputOverlay = True")
        val out = EmuControllerConfig.dolphinIni(on)
        assertEquals(listOf("ShowInputOverlay = True" to "ShowInputOverlay = False"), diff(on, out))
        assertEquals(out, EmuControllerConfig.dolphinIni(out))
    }

    @Test fun dolphinMissingKeyInsertedInsideAndroidSection() {
        val noKey = fixture("dolphin_Dolphin.ini").replace("ShowInputOverlay = False\n", "")
        val out = EmuControllerConfig.dolphinIni(noKey)
        val lines = out.lines()
        val android = lines.indexOf("[Android]")
        val general = lines.indexOf("[General]")
        val at = lines.indexOf("ShowInputOverlay = False")
        assertTrue(at in (android + 1) until general)
        assertEquals("False", IniText.get(out, "Android", "ShowInputOverlay"))
        assertEquals("0", IniText.get(out, "Android", "OverlayGCController"))
        assertEquals(noKey.lines().size + 1, lines.size)
    }

    @Test fun dolphinMissingSectionAppended() {
        val out = EmuControllerConfig.dolphinIni("[General]\nISOPaths = 1\n")
        assertEquals("[General]\nISOPaths = 1\n[Android]\nShowInputOverlay = False\n", out)
    }

    @Test fun dolphinStreamIniFlipsOverlayOffToOnOnly() {
        val off = fixture("dolphin_Dolphin.ini")
        val out = EmuControllerConfig.dolphinStreamIni(off)
        assertEquals(listOf("ShowInputOverlay = False" to "ShowInputOverlay = True"), diff(off, out))
        assertSame(out, EmuControllerConfig.dolphinStreamIni(out))
    }

    @Test fun dolphinStreamIniMissingKeyInsertedInsideAndroidSection() {
        val noKey = fixture("dolphin_Dolphin.ini").replace("ShowInputOverlay = False\n", "")
        val out = EmuControllerConfig.dolphinStreamIni(noKey)
        assertEquals("True", IniText.get(out, "Android", "ShowInputOverlay"))
        assertEquals(noKey.lines().size + 1, out.lines().size)
    }

    @Test fun vita3kRealConfigHasNoOverlayKeySoNothingChanges() {
        val real = fixture("vita3k_config.yml")
        assertSame(real, EmuControllerConfig.vita3kYaml(real))
        assertTrue(EmuControllerConfig.vita3kHasPadBinds(real))
    }

    @Test fun flycastFileNameUsesRealPadNotConsumerControl() {
        assertEquals("Android_GameSir-X5s.cfg", EmuControllerConfig.flycastFileName("GameSir-X5s"))
        assertEquals("Android_A-B- C.cfg", EmuControllerConfig.flycastFileName("A/B: C"))
    }

    @Test fun flycastMappingMatchesRealFileFormat() {
        val real = fixture("flycast_consumer_control.cfg")
        val ours = EmuControllerConfig.flycastMappingFor(GAMESIR_AXES)
        val sections = { t: String -> t.lines().filter { it.startsWith("[") } }
        assertEquals(sections(real), sections(ours))
        // Every button Flycast itself wrote is present (DC A = Android A = 96);
        // ours adds L2/R2 as buttons for pads without analog triggers.
        val values = { t: String -> section(t, "digital").map { it.substringAfter("= ") }.toSet() }
        assertTrue(values(ours).containsAll(values(real)))
        assertEquals(keys(section(real, "emulator")), keys(section(ours, "emulator")))
        assertEquals("GameBEX", IniText.get(ours, "emulator", "mapping_name"))
        val analog = section(ours, "analog").map { it.substringAfter("= ") }
        for (b in listOf(
            "0-:btn_analog_left", "1+:btn_analog_down", "11-:axis2_left", "14+:axis2_down",
            "23+:btn_trigger_left", "22+:btn_trigger_right",
            "15-:btn_dpad1_left", "15+:btn_dpad1_right", "16-:btn_dpad1_up", "16+:btn_dpad1_down",
        )) assertTrue(b, b in analog)
        // bindN indices are dense and unique per section.
        for (s in listOf("analog", "digital")) {
            val idx = keys(section(ours, s)).map { it.removePrefix("bind").toInt() }.sorted()
            assertEquals((0 until idx.size).toList(), idx)
        }
    }

    @Test fun flycastExistingMappingKept() {
        val real = fixture("flycast_consumer_control.cfg")
        assertSame(real, EmuControllerConfig.flycastMapping(real, GAMESIR_AXES))
        assertEquals(
            EmuControllerConfig.flycastMappingFor(GAMESIR_AXES),
            EmuControllerConfig.flycastMapping(null, GAMESIR_AXES),
        )
        assertEquals(
            EmuControllerConfig.flycastMappingFor(GAMESIR_AXES),
            EmuControllerConfig.flycastMapping("", GAMESIR_AXES),
        )
    }

    @Test fun flycastMappingFollowsThePadsAxes() {
        // DualShock-style: right stick RX/RY, analog triggers LTRIGGER/RTRIGGER, no hat.
        val ds = EmuControllerConfig.flycastMappingFor(setOf(0, 1, 12, 13, 17, 18))
        val analog = section(ds, "analog").map { it.substringAfter("= ") }
        assertTrue("12-:axis2_left" in analog && "13+:axis2_down" in analog)
        assertTrue("17+:btn_trigger_left" in analog && "18+:btn_trigger_right" in analog)
        assertTrue(analog.none { it.startsWith("15") || it.startsWith("11") || it.startsWith("23") })
        assertEquals("17,18", IniText.get(ds, "emulator", "triggers"))

        // Digital-only pad: no analog binds, buttons (incl. L2/R2) still mapped.
        val flat = EmuControllerConfig.flycastMappingFor(emptySet())
        assertTrue(section(flat, "analog").isEmpty())
        val digital = section(flat, "digital").map { it.substringAfter("= ") }
        assertTrue("104:btn_trigger_left" in digital && "96:btn_a" in digital)
    }

    private companion object {
        /** Axes the GameSir-X5s reports (sticks X/Y + Z/RZ, BRAKE/GAS, hat). */
        val GAMESIR_AXES = setOf(0, 1, 11, 14, 15, 16, 22, 23)
    }

    @Test fun netherRealProfileAlreadyAutoHidesUnchanged() {
        val real = fixture("nethersx2_gamebex.ini")
        assertSame(real, EmuControllerConfig.netherProfile(real, "unused"))
    }

    @Test fun netherAutoHideFalseFlipsOnlyThatLineNotAutoHideTime() {
        val off = fixture("nethersx2_gamebex.ini").replace("AutoHide = true", "AutoHide = false")
        val out = EmuControllerConfig.netherProfile(off, "unused")
        assertEquals(listOf("AutoHide = false" to "AutoHide = true"), diff(off, out))
        assertEquals("0", IniText.get(out, "TouchscreenController", "AutoHideTime"))
        assertTrue(!out.contains("Button96"))
    }

    @Test fun netherMissingProfileUsesTemplate() {
        val template = fixture("nethersx2_gamebex.ini")
        assertEquals(template, EmuControllerConfig.netherProfile(null, template))
    }

    @Test fun retroArchMergeKeepsOtherKeysAndIsIdempotent() {
        val cfg = "# comment\nvideo_smooth = \"false\"\ninput_overlay_hide_when_gamepad_connected = \"false\"\n"
        val out = EmuControllerConfig.retroArchCfg(cfg)
        assertEquals(
            "# comment\nvideo_smooth = \"false\"\ninput_overlay_hide_when_gamepad_connected = \"true\"\n" +
                "input_autodetect_enable = \"true\"\n",
            out,
        )
        assertSame(out, EmuControllerConfig.retroArchCfg(out))
    }

    @Test fun iniKeepsCrlfAndInsertsBeforeSectionGap() {
        val t = "[A]\r\nx = 1\r\n\r\n[B]\r\ny = 2\r\n"
        assertEquals("[A]\r\nx = 1\r\nz = 3\r\n\r\n[B]\r\ny = 2\r\n", IniText.set(t, "A", "z", "3"))
        assertEquals("[A]\r\nx = 9\r\n\r\n[B]\r\ny = 2\r\n", IniText.set(t, "A", "x", "9"))
        assertEquals("[A]\nk = v\n", IniText.set("", "A", "k", "v"))
    }

    private fun section(text: String, name: String): List<String> =
        text.lines().dropWhile { it != "[$name]" }.drop(1).takeWhile { !it.startsWith("[") && it.isNotBlank() }

    private fun keys(lines: List<String>): List<String> = lines.map { it.substringBefore(" =") }.sorted()
}
