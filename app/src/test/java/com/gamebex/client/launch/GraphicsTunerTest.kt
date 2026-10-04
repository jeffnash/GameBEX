package com.gamebex.client.launch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Managed RetroArch config: the keys that fix bars/overlay/perf. */
class GraphicsTunerTest {
    @Test fun managedConfigCarriesAllKeys() {
        val cfg = GraphicsTuner.retroArchConfigText()
        for (key in listOf(
            "video_scale_integer = \"false\"",
            "video_smooth = \"true\"",
            "video_vsync = \"true\"",
            "video_threaded = \"false\"",
            "rewind_enable = \"false\"",
            "input_overlay_enable = \"true\"",
            "input_overlay_hide_when_gamepad_connected = \"true\"",
            "input_autodetect_enable = \"true\"",
            "auto_overrides_enable = \"true\"",
            "savestate_auto_save = \"true\"",
            "savestate_auto_load = \"true\"",
            "savestate_thumbnail_enable = \"true\"",
            "autosave_interval = \"10\"",
            "input_enable_hotkey_btn = \"109\"",
            "input_exit_emulator_btn = \"108\"",
            "input_menu_toggle_btn = \"100\"",
            "config_save_on_exit = \"false\"",
            "system_directory = \"/storage/emulated/0/RetroArch/system\"",
            "savefile_directory = \"/storage/emulated/0/RetroArch/saves\"",
            "savestate_directory = \"/storage/emulated/0/RetroArch/states\"",
            "input_joypad_driver = \"android\"",
            "joypad_autoconfig_enable = \"true\"",
        )) {
            assertTrue("missing $key", cfg.contains(key))
        }
        assertTrue("threaded video must not be enabled", !cfg.contains("video_threaded = \"true\""))
    }

    @Test fun freshConfigOnlyDisablesAutoLoad() {
        val normal = GraphicsTuner.retroArchConfigText()
        val fresh = GraphicsTuner.retroArchConfigText(resume = false)
        assertTrue(fresh.contains("savestate_auto_load = \"false\""))
        // Auto-save stays on: a fresh session still gets a resume point.
        assertTrue(fresh.contains("savestate_auto_save = \"true\""))
        // Nothing else differs from the normal managed config.
        assertEquals(
            normal.lines().filterNot { it.startsWith("savestate_auto_load") },
            fresh.lines().filterNot { it.startsWith("savestate_auto_load") },
        )
    }

    @Test fun freshConfigIsASeparateFile() {
        // Fresh never rewrites the normal file, so nothing needs restoring.
        val base = java.io.File("/storage/emulated/0")
        val normal = GraphicsTuner.retroArchConfigFileUnder(base)
        val fresh = GraphicsTuner.retroArchFreshConfigFileUnder(base)
        assertNotEquals(normal.absolutePath, fresh.absolutePath)
        assertEquals(normal.parentFile, fresh.parentFile)
    }

    @Test fun sharedPathsLiveUnderTopLevelGameBEX() {
        // App-specific dirs are unreadable cross-app; top-level is shared.
        val base = java.io.File("/storage/emulated/0")
        val cfg = GraphicsTuner.retroArchConfigFileUnder(base).absolutePath
        assertTrue(cfg, cfg.endsWith("GameBEX/ra/retroarch-frontend.cfg"))
        val cores = GraphicsTuner.coreStageDirUnder(base).absolutePath
        assertTrue(cores, cores.endsWith("GameBEX/cores"))
    }
}
