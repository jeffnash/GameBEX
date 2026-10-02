package com.gamebex.client.stream.p2

import android.view.InputDevice
import com.gamebex.client.data.Emu
import com.gamebex.client.data.local.GameEntity
import com.gamebex.client.launch.ControllerRegistry
import com.gamebex.client.launch.DirectFiles
import com.gamebex.client.stream.P2Prep
import com.gamebex.client.stream.StreamCaps
import com.gamebex.client.stream.StreamEmus
import com.gamebex.client.together.TogetherCopy
import com.gamebex.client.together.emu.RestoreStore
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Player-2 binding per emulator. */
class StreamP2Test {
    private val tmp: File = Files.createTempDirectory("gbx-p2").toFile()
    private val configDir = File(tmp, "Android/data/org.dolphinemu.dolphinemu/files/Config").apply { mkdirs() }
    private val stopped = mutableListOf<String>()

    @After
    fun tearDown() {
        tmp.deleteRecursively()
    }

    private fun game(folder: String, emuOverride: String? = null, core: String? = null) = GameEntity(
        title = "T", folder = folder, volumeId = "v", relPath = "t.bin", fileName = "t.bin",
        sizeBytes = 1, mtimeMs = 0, ext = "bin", emulatorOverride = emuOverride, coreOverride = core,
    )

    private fun dolphin() = DolphinP2Config(
        store = RestoreStore(File(tmp, "restore")),
        fs = { DirectFiles },
        stopper = { { pkg: String -> stopped += pkg; true } },
        configDir = configDir.path,
        settleMs = 0L,
    )

    @Test
    fun emulatorsResolveLikePlay() {
        assertEquals(Emu.RETROARCH, StreamEmus.emuFor(game("n64")))
        assertEquals("mupen64plus_next_gles3", StreamEmus.raCore(game("n64")))
        assertEquals(Emu.DOLPHIN, StreamEmus.emuFor(game("gc", Emu.DOLPHIN.pkg)))
        assertNull(StreamEmus.raCore(game("gc", Emu.DOLPHIN.pkg)))
        assertEquals("dolphin", StreamEmus.raCore(game("wii")))
        assertEquals(Emu.NETHER, StreamEmus.emuFor(game("ps2")))
        assertEquals(Emu.ARMSX2, StreamEmus.emuFor(game("ps2", Emu.ARMSX2.pkg)))
        assertEquals("pcsx_rearmed", StreamEmus.raCore(game("psx", core = "pcsx_rearmed")))
        assertNull(StreamEmus.emuFor(game("not-a-system")))
    }

    @Test
    fun retroArchReservesPlayerTwoByName() {
        val o = StreamRaConfig.overrides()
        assertEquals("GameBEX Player 2", o["input_player2_reserved_device"])
        assertEquals("2", o["input_player2_device_reservation_type"])
        assertEquals("97", o["input_player2_a_btn"])
        assertEquals("h0up", o["input_player2_up_btn"])
        assertEquals("+6", o["input_player2_l2_axis"])
        assertEquals("opensl", o["audio_driver"])
        assertEquals("32", o["audio_latency"])
    }

    @Test
    fun theStreamConfigKeepsTheManagedConfig() {
        val text = StreamRaConfig.cfgText("/sdcard")
        assertTrue(text.contains("input_enable_hotkey_btn = \"109\""))
        assertTrue(text.contains("savestate_auto_load = \"true\""))
        assertEquals(1, text.lines().count { it.startsWith("input_player2_reserved_device ") })
        assertTrue(text.endsWith("\n"))
    }

    @Test
    fun theStreamConfigIsItsOwnFile() {
        val file = StreamRaConfig.write(tmp)!!
        assertEquals(File(tmp, "GameBEX/ra/retroarch-stream.cfg"), file)
        assertTrue(file.readText().contains("input_player2_device_reservation_type = \"2\""))
    }

    @Test
    fun dolphinPortTwoIsMappedAndRestoredExactly() = runBlocking {
        val ini = File(configDir, "Dolphin.ini").apply { writeText("[Core]\nSIDevice0 = 6\nSIDevice1 = 0\n[Android]\nShowInputOverlay = False\n") }
        val pads = File(configDir, "GCPadNew.ini").apply { writeText("[GCPad1]\nDevice = Android/0/GameSir-X5s\n") }
        val iniBefore = ini.readText()
        val padsBefore = pads.readText()
        val d = dolphin()
        assertEquals(DolphinP2Config.Result.Applied, d.apply())
        assertTrue(ini.readText().contains("SIDevice1 = 6"))
        assertTrue(pads.readText().contains("[GCPad2]"))
        assertTrue(pads.readText().contains("Device = Android/0/GameBEX Player 2"))
        assertTrue(pads.readText().contains("Buttons/Start = `Start`"))
        val remote = File(configDir, "WiimoteNew.ini").readText()
        assertTrue(remote.contains("Source = 1"))
        assertTrue(remote.contains("Extension = Nunchuk"))
        assertEquals(listOf(Emu.DOLPHIN.pkg), stopped)
        assertTrue(d.restorePending())
        assertEquals(iniBefore, ini.readText())
        assertEquals(padsBefore, pads.readText())
        assertFalse(File(configDir, "WiimoteNew.ini").readText().contains("Wiimote2"))
        assertTrue(d.restorePending())
    }

    @Test
    fun dolphinNeverOpenedAsksToOpenItOnce() = runBlocking {
        assertEquals(DolphinP2Config.Result.Failed(TogetherCopy.openOnce("Dolphin")), dolphin().apply())
    }

    @Test
    fun dolphinWithoutShizukuSaysSo() = runBlocking {
        val d = DolphinP2Config(RestoreStore(File(tmp, "restore")), { null }, { null }, configDir.path, 0L)
        assertEquals(DolphinP2Config.Result.Failed(TogetherCopy.startShizuku), d.apply())
    }

    @Test
    fun eachEmulatorGetsItsPlayerTwoPreparation() = runBlocking {
        File(configDir, "Dolphin.ini").writeText("[Core]\n")
        val seen = mutableSetOf<String>()
        val p2 = StreamPlayer2Impl({ tmp }, dolphin()) { key -> seen.add(key) }
        val ra = p2.prepare(game("n64"), Emu.RETROARCH)
        assertTrue(ra is P2Prep.RetroArchConfig)
        assertEquals(P2Prep.Written, p2.prepare(game("gc", Emu.DOLPHIN.pkg), Emu.DOLPHIN))
        assertEquals(P2Prep.Guide(listOf(TogetherCopy.netherPlayer2)), p2.prepare(game("ps2"), Emu.NETHER))
        assertEquals(if (StreamCaps.NETHER_MAPPING_PERSISTS) P2Prep.Written else P2Prep.Guide(listOf(TogetherCopy.netherPlayer2)), p2.prepare(game("ps2"), Emu.NETHER))
        assertEquals(P2Prep.Guide(listOf(TogetherCopy.streamPlayer2Other("ARMSX2"))), p2.prepare(game("ps2", Emu.ARMSX2.pkg), Emu.ARMSX2))
        assertTrue(p2.restore())
        assertFalse(File(configDir, "Dolphin.ini").readText().contains("SIDevice1"))
    }

    @Test
    fun playerOneSetupNeverPicksTheStreamPad() {
        val sources = InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK
        val pads = ControllerRegistry.pads(
            listOf(
                ControllerRegistry.DeviceInfo(20, StreamCaps.PAD_NAME, "p2", sources, false),
                ControllerRegistry.DeviceInfo(21, "GameSir-X5s", "x5", sources, false),
            ),
        )
        assertEquals(listOf("GameSir-X5s"), pads.map { it.name })
    }
}
