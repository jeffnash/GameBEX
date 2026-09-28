package com.gamebex.client.launch

import com.gamebex.client.data.Emu
import com.gamebex.client.launch.EmuControllerSetup.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Dolphin writer against a real on-device Dolphin.ini. */
class PerfTunerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val own = "com.gamebex.client"

    private fun fixture(name: String): String =
        requireNotNull(javaClass.classLoader?.getResource("emucfg/$name")) { name }.readText()

    private fun base(vararg pkgs: String, name: String = "sdcard"): File {
        val b = tmp.newFolder(name)
        File(b, "Android/data/$own").mkdirs()
        for (p in pkgs) File(b, "Android/data/$p/files").mkdirs()
        return b
    }

    private fun config(b: File) = File(b, "Android/data/${Emu.DOLPHIN.pkg}/files/Config").apply { mkdirs() }

    @Test fun dolphinIniAddsCoreSectionKeepingEverythingElse() {
        val original = fixture("dolphin_Dolphin.ini")
        val out = PerfTuner.dolphinIni(original)
        assertTrue(out.startsWith(original))
        assertEquals("Vulkan", IniText.get(out, "Core", "GFXBackend"))
        assertEquals("False", IniText.get(out, "Android", "ShowInputOverlay"))
        assertEquals(out, PerfTuner.dolphinIni(out))
        // Existing backend is replaced in place.
        val ogl = "[Core]\nGFXBackend = OGL\nCPUThread = True\n"
        assertEquals("[Core]\nGFXBackend = Vulkan\nCPUThread = True\n", PerfTuner.dolphinIni(ogl))
    }

    @Test fun gfxResolutionOnlyOnLowTier() {
        assertEquals("[Settings]\nInternalResolution = 1\n", PerfTuner.dolphinGfxIni(null, DeviceTier.LOW))
        val gfx = "[Settings]\nInternalResolution = 3\nShowFPS = True\n[Enhancements]\nMaxAnisotropy = 0\n"
        assertEquals(gfx.replace("= 3", "= 1"), PerfTuner.dolphinGfxIni(gfx, DeviceTier.LOW))
        assertEquals(gfx, PerfTuner.dolphinGfxIni(gfx, DeviceTier.MID))
        assertNull(PerfTuner.dolphinGfxIni(null, DeviceTier.HIGH))
    }

    @Test fun applyWritesBothFilesWithBackupOnceAndIsIdempotent() {
        val b = base(Emu.DOLPHIN.pkg)
        val cfg = config(b)
        val ini = File(cfg, "Dolphin.ini").apply { writeText(fixture("dolphin_Dolphin.ini")) }
        val gfx = File(cfg, "GFX.ini")
        assertEquals(emptySet<String>(), PerfTuner.inspectUnder(Emu.DOLPHIN, DeviceTier.LOW, b, own))

        val r = PerfTuner.applyUnder(Emu.DOLPHIN, DeviceTier.LOW, true, b, own)
        assertEquals(Status.CONFIGURED, r.status)
        assertEquals("Vulkan", IniText.get(ini.readText(), "Core", "GFXBackend"))
        assertEquals("1", IniText.get(gfx.readText(), "Settings", "InternalResolution"))
        assertEquals(fixture("dolphin_Dolphin.ini"), File(ini.path + EmuControllerSetup.BACKUP_SUFFIX).readText())
        // New file: nothing to back up.
        assertTrue(!File(gfx.path + EmuControllerSetup.BACKUP_SUFFIX).exists())
        assertEquals(
            setOf(PerfRecommendations.DOLPHIN_BACKEND, PerfRecommendations.DOLPHIN_RES),
            PerfTuner.inspectUnder(Emu.DOLPHIN, DeviceTier.LOW, b, own),
        )

        val stamp = ini.lastModified()
        assertEquals(Status.ALREADY, PerfTuner.applyUnder(Emu.DOLPHIN, DeviceTier.LOW, true, b, own).status)
        assertEquals(stamp, ini.lastModified())
        assertTrue(cfg.listFiles()!!.none { it.name.endsWith("gamebex-tmp") })
    }

    @Test fun midTierLeavesResolutionAlone() {
        val b = base(Emu.DOLPHIN.pkg)
        val cfg = config(b)
        File(cfg, "Dolphin.ini").writeText(fixture("dolphin_Dolphin.ini"))
        assertEquals(Status.CONFIGURED, PerfTuner.applyUnder(Emu.DOLPHIN, DeviceTier.MID, true, b, own).status)
        assertTrue(!File(cfg, "GFX.ini").exists())
    }

    @Test fun statusesWhenNotReachable() {
        val hidden = base()
        assertEquals(Status.NOT_INSTALLED, PerfTuner.applyUnder(Emu.DOLPHIN, DeviceTier.LOW, false, hidden, own).status)
        // Android/data shows only us → hidden; no Shizuku → BLOCKED.
        assertEquals(Status.BLOCKED, PerfTuner.applyUnder(Emu.DOLPHIN, DeviceTier.LOW, true, hidden, own).status)
        assertEquals(
            Status.SHIZUKU_START,
            PerfTuner.applyUnder(Emu.DOLPHIN, DeviceTier.LOW, true, File(tmp.root, "sdcard"), own, privileged = { Privileged.NotRunning }).status,
        )
        assertNull(PerfTuner.inspectUnder(Emu.DOLPHIN, DeviceTier.LOW, File(tmp.root, "sdcard"), own))
        // Dir visible but Dolphin never opened.
        val b2 = File(tmp.root, "sd2").apply { File(this, "Android/data/${Emu.DOLPHIN.pkg}/files").mkdirs() }
        assertEquals(Status.OPEN_ONCE, PerfTuner.applyUnder(Emu.DOLPHIN, DeviceTier.LOW, true, b2, own).status)
        // Non-writable emulators say so instead of pretending.
        assertEquals(Status.ONE_TIME, PerfTuner.applyUnder(Emu.NETHER, DeviceTier.LOW, true, b2, own).status)
    }

    @Test fun shizukuBackendUsedWhenHidden() {
        val b = base()
        val real = File(tmp.root, "real").apply { mkdirs() }
        // Fake shell backend: maps the hidden path onto a real temp dir.
        val files = File(b, "Android/data/${Emu.DOLPHIN.pkg}/files").path
        val shadow = File(real, "files").apply { File(this, "Config").mkdirs() }
        File(shadow, "Config/Dolphin.ini").writeText(fixture("dolphin_Dolphin.ini"))
        val fake = object : EmuFiles {
            fun m(p: String) = p.replace(files, shadow.path)
            override fun isDirectory(path: String) = DirectFiles.isDirectory(m(path))
            override fun isFile(path: String) = DirectFiles.isFile(m(path))
            override fun exists(path: String) = DirectFiles.exists(m(path))
            override fun readText(path: String) = DirectFiles.readText(m(path))
            override fun writeTextAtomic(path: String, text: String) = DirectFiles.writeTextAtomic(m(path), text)
            override fun mkdirs(path: String) = DirectFiles.mkdirs(m(path))
            override fun copy(path: String, dest: String) = DirectFiles.copy(m(path), m(dest))
        }
        val r = PerfTuner.applyUnder(Emu.DOLPHIN, DeviceTier.LOW, true, b, own, privileged = { Privileged.Ready(fake) })
        assertEquals(Status.CONFIGURED, r.status)
        assertEquals("Vulkan", IniText.get(File(shadow, "Config/Dolphin.ini").readText(), "Core", "GFXBackend"))
        assertEquals("1", IniText.get(File(shadow, "Config/GFX.ini").readText(), "Settings", "InternalResolution"))
    }
}
