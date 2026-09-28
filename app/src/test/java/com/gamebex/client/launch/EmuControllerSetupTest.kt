package com.gamebex.client.launch

import com.gamebex.client.data.Emu
import com.gamebex.client.launch.EmuControllerSetup.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** File-level setup against a fake /sdcard: statuses, backup-once, atomic, idempotent. */
class EmuControllerSetupTest {
    @get:Rule val tmp = TemporaryFolder()

    private val own = "com.gamebex.client"
    private val pad = ControllerRegistry.Pad(12, "GameSir-X5s", "pad")

    private fun fixture(name: String): String =
        requireNotNull(javaClass.classLoader?.getResource("emucfg/$name")) { name }.readText()

    private fun base(vararg pkgs: String): File {
        val b = tmp.newFolder("sdcard")
        File(b, "Android/data/$own").mkdirs()
        for (p in pkgs) File(b, "Android/data/$p/files").mkdirs()
        return b
    }

    private fun run(emu: Emu, b: File, installed: Boolean = true, pads: List<ControllerRegistry.Pad> = listOf(pad)) =
        EmuControllerSetup.applyUnder(emu, installed, b, own, pads) { fixture("nethersx2_gamebex.ini") }

    @Test fun notInstalledSkipped() {
        assertEquals(Status.NOT_INSTALLED, run(Emu.DOLPHIN, base(), installed = false).status)
    }

    @Test fun hiddenAndroidDataReportedAsBlockedNotOpenOnce() {
        assertEquals(Status.BLOCKED, run(Emu.DOLPHIN, base()).status)
        val b2 = File(tmp.root, "sd2").apply { File(this, "Android/data/other.app/files").mkdirs() }
        assertEquals(Status.OPEN_ONCE, run(Emu.FLYCAST, b2).status)
        assertTrue(EmuControllerSetup.dataDirHidden(null, own))
        assertTrue(EmuControllerSetup.dataDirHidden(listOf(own, ".nomedia"), own))
        assertFalse(EmuControllerSetup.dataDirHidden(listOf(own, "org.ppsspp.ppsspp"), own))
    }

    @Test fun dolphinBackupOnceAndIdempotent() {
        val b = base(Emu.DOLPHIN.pkg)
        val ini = File(b, "Android/data/${Emu.DOLPHIN.pkg}/files/Config/Dolphin.ini")
        ini.parentFile!!.mkdirs()
        val original = fixture("dolphin_Dolphin.ini").replace("ShowInputOverlay = False", "ShowInputOverlay = True")
        ini.writeText(original)
        assertEquals(Status.ONE_TIME, run(Emu.DOLPHIN, b).status)
        val backup = File(ini.path + EmuControllerSetup.BACKUP_SUFFIX)
        assertEquals(original, backup.readText())
        val first = ini.readText()
        assertTrue(first.contains("ShowInputOverlay = False"))
        // User flips it back; we fix it again but the backup keeps the true original.
        ini.writeText(first.replace("= False\nOverlayGC", "= True\nOverlayGC"))
        run(Emu.DOLPHIN, b)
        assertEquals(original, backup.readText())
        val stamp = ini.lastModified()
        run(Emu.DOLPHIN, b)
        assertEquals(first, ini.readText())
        assertEquals(stamp, ini.lastModified())
        assertTrue(ini.parentFile!!.listFiles()!!.none { it.name.endsWith("gamebex-tmp") })
    }

    @Test fun dolphinNeverOpenedMeansOpenOnce() {
        assertEquals(Status.OPEN_ONCE, run(Emu.DOLPHIN, base(Emu.DOLPHIN.pkg)).status)
    }

    @Test fun flycastWritesRealPadMappingAndLeavesConsumerControlAlone() {
        val b = base(Emu.FLYCAST.pkg)
        val dir = File(b, "Android/data/${Emu.FLYCAST.pkg}/files/mappings").apply { mkdirs() }
        val cc = File(dir, "Android_GameSir-X5s Consumer Control.cfg").apply { writeText(fixture("flycast_consumer_control.cfg")) }
        assertEquals(Status.CONFIGURED, run(Emu.FLYCAST, b).status)
        assertEquals(EmuControllerConfig.flycastMappingFor(emptySet()), File(dir, "Android_GameSir-X5s.cfg").readText())
        assertEquals(fixture("flycast_consumer_control.cfg"), cc.readText())
        assertEquals(Status.ALREADY, run(Emu.FLYCAST, b).status)
        assertEquals(Status.NO_PAD, run(Emu.FLYCAST, b, pads = emptyList()).status)
        assertFalse(File(dir, "Android_GameSir-X5s.cfg" + EmuControllerSetup.BACKUP_SUFFIX).exists())
    }

    @Test fun netherWritesTemplateThenOnlyFixesAutoHide() {
        val b = base(Emu.NETHER.pkg)
        val f = File(b, "Android/data/${Emu.NETHER.pkg}/files/inputprofiles/gamebex.ini")
        val r = run(Emu.NETHER, b)
        assertEquals(Status.ONE_TIME, r.status)
        assertEquals(fixture("nethersx2_gamebex.ini"), f.readText())
        f.writeText(f.readText().replace("AutoHide = true", "AutoHide = false"))
        run(Emu.NETHER, b)
        assertEquals(fixture("nethersx2_gamebex.ini"), f.readText())
        assertTrue(File(f.path + EmuControllerSetup.BACKUP_SUFFIX).readText().contains("AutoHide = false"))
    }

    @Test fun vita3kNeverWritten() {
        val b = base(Emu.VITA3K.pkg)
        val yml = File(b, "Android/data/${Emu.VITA3K.pkg}/files/config.yml").apply { writeText(fixture("vita3k_config.yml")) }
        assertEquals(Status.ALREADY, run(Emu.VITA3K, b).status)
        assertEquals(fixture("vita3k_config.yml"), yml.readText())
        assertFalse(File(yml.path + EmuControllerSetup.BACKUP_SUFFIX).exists())
    }

    @Test fun retroArchManagedConfigPlusOwnCfgMerge() {
        val b = base()
        val own = File(b, "RetroArch/retroarch.cfg").apply {
            parentFile!!.mkdirs()
            writeText("menu_driver = \"ozone\"\ninput_autodetect_enable = \"false\"\n")
        }
        assertEquals(Status.CONFIGURED, run(Emu.RETROARCH, b).status)
        assertTrue(GraphicsTuner.retroArchConfigFileUnder(b).readText().contains("input_autodetect_enable = \"true\""))
        assertEquals(
            "menu_driver = \"ozone\"\ninput_autodetect_enable = \"true\"\n" +
                "input_overlay_hide_when_gamepad_connected = \"true\"\n",
            own.readText(),
        )
        assertTrue(File(own.path + EmuControllerSetup.BACKUP_SUFFIX).isFile)
    }
}
