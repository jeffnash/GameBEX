package com.gamebex.client.stream.p2

import android.view.InputDevice
import com.gamebex.client.data.Emu
import com.gamebex.client.data.local.GameEntity
import com.gamebex.client.launch.ControllerRegistry
import com.gamebex.client.launch.DirectFiles
import com.gamebex.client.launch.GraphicsTuner
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
        assertEquals("false", o["input_overlay_hide_when_gamepad_connected"])
        assertEquals(StreamCaps.PAD_NAME, o["input_player2_reserved_device"])
        assertEquals("2", o["input_player2_device_reservation_type"])
        assertEquals("97", o["input_player2_a_btn"])
        assertEquals("h0up", o["input_player2_up_btn"])
        assertEquals("+6", o["input_player2_l2_axis"])
        assertEquals("opensl", o["audio_driver"])
        assertEquals("32", o["audio_latency"])
    }

    @Test
    fun retroArchOverlayIsOffForARealHostPad() {
        val sources = InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK
        val pads = ControllerRegistry.pads(
            listOf(
                ControllerRegistry.DeviceInfo(20, StreamCaps.PAD_NAME, "p2", sources, false),
                ControllerRegistry.DeviceInfo(21, "GameSir-X5s", "x5", sources, false),
            ),
        )
        assertTrue(pads.isNotEmpty())
        val hasRealHostPad = pads.isNotEmpty()
        assertEquals("false", StreamRaConfig.overrides(hasRealHostPad = hasRealHostPad)["input_overlay_enable"])
        val text = StreamRaConfig.cfgText("/sdcard", hasRealHostPad = hasRealHostPad)
        assertTrue(text.contains("input_overlay_enable = \"false\""))
        assertTrue(text.contains("input_overlay_hide_when_gamepad_connected = \"false\""))
        assertEquals(1, text.lines().count { it.startsWith("input_overlay_enable ") })
    }

    @Test
    fun retroArchOverlayStaysOnWithOnlyTheStreamPadAndVirtualDevices() {
        val sources = InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK
        val pads = ControllerRegistry.pads(
            listOf(
                ControllerRegistry.DeviceInfo(20, StreamCaps.PAD_NAME, "p2", sources, false),
                ControllerRegistry.DeviceInfo(21, "Virtual controller", "virtual", sources, true),
            ),
        )
        assertTrue(pads.isEmpty())
        val hasRealHostPad = pads.isNotEmpty()
        assertEquals("true", StreamRaConfig.overrides(hasRealHostPad = hasRealHostPad)["input_overlay_enable"])
        val text = StreamRaConfig.cfgText("/sdcard", hasRealHostPad = hasRealHostPad)
        assertTrue(text.contains("input_overlay_enable = \"true\""))
        assertTrue(text.contains("input_overlay_hide_when_gamepad_connected = \"false\""))
        assertEquals(1, text.lines().count { it.startsWith("input_overlay_enable ") })
    }

    @Test
    fun retroArchOverlayDefaultsOnWithoutAnyPads() {
        val pads = ControllerRegistry.pads(emptyList())
        assertTrue(pads.isEmpty())
        assertEquals("true", StreamRaConfig.overrides()["input_overlay_enable"])
        val text = StreamRaConfig.cfgText("/sdcard", hasRealHostPad = pads.isNotEmpty())
        assertEquals(StreamRaConfig.cfgText("/sdcard"), text)
        assertTrue(text.contains("input_overlay_enable = \"true\""))
        assertTrue(text.contains("input_overlay_hide_when_gamepad_connected = \"false\""))
        assertEquals(1, text.lines().count { it.startsWith("input_overlay_enable ") })
    }

    @Test
    fun streamUsesEarlyPollingWithoutChangingSoloInputConfig() {
        assertEquals("0", StreamRaConfig.overrides()["input_poll_type_behavior"])
        assertTrue(StreamRaConfig.cfgText("/sdcard").contains("input_poll_type_behavior = \"0\""))
        assertFalse(GraphicsTuner.retroArchConfigText("/sdcard", resume = true).contains("input_poll_type_behavior"))
    }

    @Test
    fun retroArchDiagnosticsAreOptInAndKeepThePadReservation() {
        val normal = StreamRaConfig.overrides(diagnostics = false)
        assertNull(normal["log_verbosity"])
        assertNull(normal["log_to_file"])
        val diagnostic = StreamRaConfig.overrides(diagnostics = true)
        assertEquals("true", diagnostic["log_verbosity"])
        assertEquals("0", diagnostic["frontend_log_level"])
        assertEquals("true", diagnostic["log_to_file"])
        assertEquals("true", diagnostic["log_to_file_timestamp"])
        assertEquals(StreamCaps.PAD_NAME, diagnostic["input_player2_reserved_device"])
    }

    @Test
    fun rsoundRequiresExplicitListenerReadiness() {
        assertEquals("opensl", StreamRaConfig.overrides()["audio_driver"])
        assertNull(StreamRaConfig.overrides()["audio_device"])
        assertEquals("opensl", StreamRaConfig.overrides(rsoundReady = false)["audio_driver"])
        assertNull(StreamRaConfig.overrides(rsoundReady = false)["audio_device"])
        val ready = StreamRaConfig.overrides(rsoundReady = true)
        assertEquals("rsound", ready["audio_driver"])
        assertEquals("127.0.0.1", ready["audio_device"])
        assertEquals("32", ready["audio_latency"])
        assertTrue(StreamRaConfig.cfgText("/sdcard", rsoundReady = true).contains("audio_driver = \"rsound\""))
        assertFalse(StreamRaConfig.cfgText("/sdcard").contains("\"127.0.0.1\""))
    }

    @Test
    fun raExperimentSamplingRejectsInvalidValuesAndDoesNotReadInRelease() {
        assertEquals(RaExperimentOptions(), RaExperimentOptions.sample(false) { error("release getprop") })
        for (raw in listOf(null, "", "0", "1", "-1", "9", "17", "64", "8.0", " 16", "bad")) {
            val samples = mutableListOf<String>()
            val options = RaExperimentOptions.sample(true) { name -> samples += name; raw }
            assertEquals(RaExperimentOptions(noSmooth = raw == "1"), options)
            assertEquals(listOf("ra_audio_latency", "ra_swapchain", "ra_no_smooth"), samples)
        }
        assertEquals(RaExperimentOptions(), RaExperimentOptions.sample(true) { "32" })
        assertEquals(RaExperimentOptions(swapchain = 2), RaExperimentOptions.sample(true) { "2" })
    }

    @Test
    fun rsoundLatencyOverridesNeverChangeOpenSlAndSampledArmsKeepTheOverlay() {
        for (latency in listOf(8, 16, 32)) {
            val samples = mutableListOf<String>()
            val options = RaExperimentOptions.sample(true) { name ->
                samples += name
                when (name) {
                    "ra_audio_latency" -> latency.toString()
                    "ra_swapchain" -> "2"
                    "ra_no_smooth" -> "1"
                    else -> error(name)
                }
            }
            for (ready in listOf(false, true)) {
                for (hostPad in listOf(false, true)) {
                    val overrides = StreamRaConfig.overrides(ready, diagnostics = false, hasRealHostPad = hostPad, experiments = options)
                    assertEquals(if (ready) "rsound" else "opensl", overrides["audio_driver"])
                    assertEquals(if (ready) latency.toString() else "32", overrides["audio_latency"])
                    assertEquals((!hostPad).toString(), overrides["input_overlay_enable"])
                    assertEquals("false", overrides["input_overlay_hide_when_gamepad_connected"])
                    assertEquals(StreamCaps.PAD_NAME, overrides["input_player2_reserved_device"])
                    assertEquals("2", overrides["video_max_swapchain_images"])
                    assertEquals("false", overrides["video_smooth"])
                    val text = StreamRaConfig.cfgText("/sdcard", ready, hostPad, options)
                    for (key in listOf("audio_latency", "video_smooth", "video_max_swapchain_images", "input_overlay_enable")) {
                        assertEquals(1, text.lines().count { it.startsWith("$key = ") })
                        assertTrue(text.contains("$key = \"${overrides[key]}\""))
                    }
                }
            }
            assertEquals(listOf("ra_audio_latency", "ra_swapchain", "ra_no_smooth"), samples)
        }
        assertEquals("32", StreamRaConfig.overrides(true, diagnostics = false, experiments = RaExperimentOptions(audioLatency = 64))["audio_latency"])
        assertNull(StreamRaConfig.overrides(diagnostics = false, experiments = RaExperimentOptions(swapchain = 3))["video_max_swapchain_images"])
    }

    @Test
    fun unsetExperimentConfigMatchesTheOriginalBytes() {
        val baseline = linkedMapOf(
            "input_poll_type_behavior" to "0",
            "input_overlay_enable" to "true",
            "input_overlay_hide_when_gamepad_connected" to "false",
            "input_player2_reserved_device" to StreamCaps.PAD_NAME,
            "input_player2_device_reservation_type" to "2",
            "input_player2_a_btn" to "97",
            "input_player2_b_btn" to "96",
            "input_player2_x_btn" to "100",
            "input_player2_y_btn" to "99",
            "input_player2_l_btn" to "102",
            "input_player2_r_btn" to "103",
            "input_player2_l2_btn" to "104",
            "input_player2_r2_btn" to "105",
            "input_player2_l3_btn" to "106",
            "input_player2_r3_btn" to "107",
            "input_player2_start_btn" to "108",
            "input_player2_select_btn" to "109",
            "input_player2_up_btn" to "h0up",
            "input_player2_down_btn" to "h0down",
            "input_player2_left_btn" to "h0left",
            "input_player2_right_btn" to "h0right",
            "input_player2_l_x_plus_axis" to "+0",
            "input_player2_l_x_minus_axis" to "-0",
            "input_player2_l_y_plus_axis" to "+1",
            "input_player2_l_y_minus_axis" to "-1",
            "input_player2_r_x_plus_axis" to "+2",
            "input_player2_r_x_minus_axis" to "-2",
            "input_player2_r_y_plus_axis" to "+3",
            "input_player2_r_y_minus_axis" to "-3",
            "input_player2_l2_axis" to "+6",
            "input_player2_r2_axis" to "+7",
            "audio_driver" to "opensl",
            "audio_latency" to "32",
        )
        val unset = RaExperimentOptions.sample(true) { null }
        for (ready in listOf(false, true)) {
            for (hostPad in listOf(false, true)) {
                val expected = LinkedHashMap(baseline).apply {
                    put("input_overlay_enable", (!hostPad).toString())
                    if (ready) {
                        put("audio_driver", "rsound")
                        put("audio_device", "127.0.0.1")
                    }
                }
                assertEquals(expected.entries.toList(), StreamRaConfig.overrides(ready, false, hostPad, unset).entries.toList())
                val base = GraphicsTuner.retroArchConfigText("/sdcard", resume = true).trimEnd('\n').split('\n')
                val merged = com.gamebex.client.launch.RetroArchTuner.mergeConfig(base, expected).first.joinToString("\n") + "\n"
                assertEquals(merged, StreamRaConfig.cfgText("/sdcard", ready, hostPad, unset))
                assertEquals(merged, StreamRaConfig.cfgText("/sdcard", ready, hostPad, RaExperimentOptions.sample(false) { error("release getprop") }))
            }
        }
    }

    @Test
    fun playerTwoFreezesRaExperimentsUntilTheNextSessionAndRestoreClearsTheSnapshot() = runBlocking {
        var samples = 0
        var latency = 8
        var arms = true
        val p2 = StreamPlayer2Impl({ tmp }, dolphin(), hostPads = { emptyList() }, raExperiments = {
            samples++
            RaExperimentOptions(latency, if (arms) 2 else 0, arms)
        }) { true }
        val initial = p2.prepare(game("n64"), Emu.RETROARCH) as P2Prep.RetroArchConfig
        assertEquals(1, samples)
        assertTrue(initial.cfg.readText().contains("audio_latency = \"32\""))
        latency = 16
        arms = false
        repeat(2) {
            val ready = p2.prepare(game("n64"), Emu.RETROARCH, rsoundReady = true) as P2Prep.RetroArchConfig
            assertEquals(initial.cfg, ready.cfg)
            assertTrue(ready.cfg.readText().contains("audio_latency = \"8\""))
            assertTrue(ready.cfg.readText().contains("video_max_swapchain_images = \"2\""))
            assertTrue(ready.cfg.readText().contains("video_smooth = \"false\""))
        }
        assertEquals(1, samples)
        p2.prepare(game("n64"), Emu.RETROARCH)
        assertEquals(2, samples)
        p2.prepare(game("n64"), Emu.RETROARCH, rsoundReady = true)
        assertTrue(initial.cfg.readText().contains("audio_latency = \"16\""))
        assertFalse(initial.cfg.readText().contains("video_max_swapchain_images = \"2\""))
        assertEquals(2, samples)
        p2.restore()
        latency = 32
        p2.prepare(game("n64"), Emu.RETROARCH, rsoundReady = true)
        assertTrue(initial.cfg.readText().contains("audio_latency = \"32\""))
        assertEquals(3, samples)
        p2.prepare(game("ps2"), Emu.NETHER, rsoundReady = true)
        assertEquals(3, samples)
    }

    @Test
    fun rewritingReadinessUsesTheSameSessionFile() {
        val file = StreamRaConfig.write(tmp)!!
        assertTrue(file.readText().contains("audio_driver = \"opensl\""))
        assertFalse(file.readText().contains("\"127.0.0.1\""))
        assertEquals(file, StreamRaConfig.write(tmp, rsoundReady = true))
        assertTrue(file.readText().contains("audio_driver = \"rsound\""))
        assertTrue(file.readText().contains("audio_device = \"127.0.0.1\""))
        assertEquals(file, StreamRaConfig.write(tmp))
        val text = file.readText()
        assertTrue(text.contains("audio_driver = \"opensl\""))
        assertTrue(text.contains("audio_latency = \"32\""))
        assertFalse(text.contains("\"127.0.0.1\""))
        assertEquals(1, text.lines().count { it.startsWith("input_player2_reserved_device ") })
    }

    @Test
    fun playerTwoForwardsReadinessButDefaultsToOpenSl() = runBlocking {
        val p2 = StreamPlayer2Impl({ tmp }, dolphin(), hostPads = { emptyList() }) { true }
        val original = p2.prepare(game("n64"), Emu.RETROARCH) as P2Prep.RetroArchConfig
        assertTrue(original.cfg.readText().contains("audio_driver = \"opensl\""))
        val ready = p2.prepare(game("n64"), Emu.RETROARCH, rsoundReady = true) as P2Prep.RetroArchConfig
        assertEquals(original.cfg, ready.cfg)
        assertTrue(ready.cfg.readText().contains("audio_driver = \"rsound\""))
        val safeAgain = p2.prepare(game("n64"), Emu.RETROARCH, rsoundReady = false) as P2Prep.RetroArchConfig
        assertEquals(original.cfg, safeAgain.cfg)
        assertFalse(safeAgain.cfg.readText().contains("\"127.0.0.1\""))
    }

    @Test
    fun playerTwoSamplesHostPadsAndKeepsTheOverlayChoiceWhenReadinessChanges() = runBlocking {
        val sources = InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK
        val streamPad = ControllerRegistry.DeviceInfo(20, StreamCaps.PAD_NAME, "p2", sources, false)
        val realPad = ControllerRegistry.DeviceInfo(21, "GameSir-X5s", "x5", sources, false)
        var pads = ControllerRegistry.pads(listOf(streamPad, realPad))
        var snapshots = 0
        val p2 = StreamPlayer2Impl({ tmp }, dolphin(), hostPads = { snapshots++; pads }) { true }
        val original = p2.prepare(game("n64"), Emu.RETROARCH) as P2Prep.RetroArchConfig
        assertTrue(original.cfg.readText().contains("input_overlay_enable = \"false\""))
        val ready = p2.prepare(game("n64"), Emu.RETROARCH, rsoundReady = true) as P2Prep.RetroArchConfig
        assertEquals(original.cfg, ready.cfg)
        val text = ready.cfg.readText()
        assertTrue(text.contains("input_overlay_enable = \"false\""))
        assertTrue(text.contains("input_overlay_hide_when_gamepad_connected = \"false\""))
        assertTrue(text.contains("audio_driver = \"rsound\""))
        assertEquals(1, text.lines().count { it.startsWith("input_overlay_enable ") })
        pads = ControllerRegistry.pads(listOf(streamPad))
        val onlyStreamPad = p2.prepare(game("n64"), Emu.RETROARCH) as P2Prep.RetroArchConfig
        assertEquals(original.cfg, onlyStreamPad.cfg)
        assertTrue(onlyStreamPad.cfg.readText().contains("input_overlay_enable = \"true\""))
        pads = ControllerRegistry.pads(emptyList())
        val noPads = p2.prepare(game("n64"), Emu.RETROARCH) as P2Prep.RetroArchConfig
        assertTrue(noPads.cfg.readText().contains("input_overlay_enable = \"true\""))
        assertEquals(4, snapshots)
    }

    @Test
    fun readinessDoesNotTurnOtherEmulatorsIntoRetroArch() = runBlocking {
        val p2 = StreamPlayer2Impl({ tmp }, dolphin(), hostPads = { emptyList() }) { true }
        assertEquals(P2Prep.Guide(listOf(TogetherCopy.netherPlayer2)), p2.prepare(game("ps2"), Emu.NETHER, rsoundReady = true))
        assertEquals(P2Prep.Guide(listOf(TogetherCopy.streamPlayer2Other("ARMSX2"))), p2.prepare(game("ps2", Emu.ARMSX2.pkg), Emu.ARMSX2, rsoundReady = true))
        assertFalse(StreamRaConfig.fileUnder(tmp).exists())
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
        val p2 = StreamPlayer2Impl({ tmp }, dolphin(), hostPads = { emptyList() }) { key -> seen.add(key) }
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
