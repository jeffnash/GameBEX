package com.gamebex.client.launch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** RetroArch cfg merge: replace, append, preserve. */
class RetroArchTunerTest {
    @Test fun parseKey() {
        assertEquals("system_directory", RetroArchTuner.parseKey("system_directory = \"/x\""))
        assertEquals("a", RetroArchTuner.parseKey("a=\"b\""))
        assertEquals("a_1", RetroArchTuner.parseKey("  a_1  =  \"b\"  "))
        assertNull(RetroArchTuner.parseKey("# system_directory = \"/x\""))
        assertNull(RetroArchTuner.parseKey(""))
        assertNull(RetroArchTuner.parseKey("   "))
        assertNull(RetroArchTuner.parseKey("not a setting"))
    }

    @Test fun mergeReplacesAndPreserves() {
        val before = listOf(
            "# comment",
            "",
            "system_directory = \"/wrong\"",
            "menu_driver = \"glui\"",
        )
        val (after, changed) = RetroArchTuner.mergeConfig(before, mapOf("system_directory" to "/right"))
        assertEquals(true, changed)
        assertEquals(
            listOf("# comment", "", "system_directory = \"/right\"", "menu_driver = \"glui\""),
            after,
        )
    }

    @Test fun mergeAppendsMissing() {
        val (after, changed) = RetroArchTuner.mergeConfig(
            listOf("menu_driver = \"glui\""),
            mapOf("system_directory" to "/s", "input_joypad_driver" to "android"),
        )
        assertEquals(true, changed)
        assertEquals(
            listOf(
                "menu_driver = \"glui\"",
                "system_directory = \"/s\"",
                "input_joypad_driver = \"android\"",
            ),
            after,
        )
    }

    @Test fun mergeNoChangeWhenCorrect() {
        val before = listOf("system_directory = \"/s\"")
        val (after, changed) = RetroArchTuner.mergeConfig(before, mapOf("system_directory" to "/s"))
        assertEquals(false, changed)
        assertEquals(before, after)
    }

    @Test fun mergeFixesDuplicates() {
        val before = listOf(
            "system_directory = \"/a\"",
            "system_directory = \"/b\"",
        )
        val (after, changed) = RetroArchTuner.mergeConfig(before, mapOf("system_directory" to "/s"))
        assertEquals(true, changed)
        assertEquals(
            listOf("system_directory = \"/s\"", "system_directory = \"/s\""),
            after,
        )
    }

    @Test fun wantedDirsShape() {
        val wanted = RetroArchTuner.wantedDirs("/storage/emulated/0")
        assertEquals(5, wanted.size)
        assertEquals("android", wanted["input_joypad_driver"])
        assertEquals("true", wanted["joypad_autoconfig_enable"])
        assertEquals(true, wanted["system_directory"]!!.endsWith("/RetroArch/system"))
    }
}
