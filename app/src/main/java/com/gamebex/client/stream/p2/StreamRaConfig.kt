package com.gamebex.client.stream.p2

import com.gamebex.client.launch.GraphicsTuner
import com.gamebex.client.launch.RetroArchTuner
import com.gamebex.client.stream.StreamCaps
import com.gamebex.client.stream.StreamDebug
import java.io.File
import java.io.IOException
import java.util.LinkedHashMap

/**
 * The RetroArch session config for a Stream host: GameBEX's managed
 * config plus player 2 reserved for "GameBEX Player 2" by name
 * (input_player2_device_reservation_type 2 = reserved; RetroArch
 * tasks/task_autodetect.c matches a reserved name against the device name),
 * with explicit player-2 binds in the Android joypad driver's terms: buttons
 * are Android keycodes, the hat is h0, sticks are axes 0–3 and the triggers
 * axes 6/7 (AXIS_LTRIGGER/RTRIGGER, which Generic.kl gives the pad's
 * Brake/Accelerator). Written as a separate file passed as CONFIGFILE, so
 * RetroArch's own retroarch.cfg is never touched.
 */
object StreamRaConfig {
    const val FILE_NAME: String = "retroarch-stream.cfg"
    /** First RetroArch versionCode whose overlay auto-hide follows player 1's pad. */
    const val RETROARCH_V3_VERSION_CODE: Long = 1791174559L

    fun overrides(
        rsoundReady: Boolean = false,
        diagnostics: Boolean = StreamDebug.prop("ra_log") == "1",
        hasRealHostPad: Boolean = false,
        experiments: RaExperimentOptions = RaExperimentOptions.sample(),
        retroArchVersionCode: Long = 0,
        /** Seeded per-core overlay cfg ([OverlaySeed]); the host shows it and shares it with the guest. */
        overlayCfgPath: String? = null,
        /**
         * PLACEHOLDER unbinds user 1, so the placeholder's registering SELECT
         * blip stays silent even with stale host-pad binds. The player-2
         * reservation stays in every mode: buttons follow slots alone, but the
         * reservation still carries hat/analog.
         */
        p2SlotMode: P2SlotMode = P2SlotMode.OFF,
    ): LinkedHashMap<String, String> = LinkedHashMap<String, String>().apply {
        if (diagnostics) {
            put("log_verbosity", "true")
            put("frontend_log_level", "0")
            put("log_to_file", "true")
            put("log_to_file_timestamp", "true")
        }
        put("input_poll_type_behavior", experiments.poll.takeIf { it in 0..2 }?.toString() ?: "0")
        // input_max_users stays as in the managed config (8): forcing 2 makes RetroArch connect the core's
        // ports 3-4 as RETRO_DEVICE_NONE, and the Dolphin core crashes a few seconds into the game.
        // On v3+ with the flag on, RetroArch itself hides the overlay for
        // player 1's pad (and ignores "GameBEX Player 2"); otherwise the
        // overlay is decided once at launch.
        val v3Autohide = retroArchVersionCode >= RETROARCH_V3_VERSION_CODE && experiments.overlayAutohideV3
        put("input_overlay_enable", (v3Autohide || !hasRealHostPad).toString())
        put("input_overlay_hide_when_gamepad_connected", v3Autohide.toString())
        if (!overlayCfgPath.isNullOrBlank()) put("input_overlay", overlayCfgPath)
        put("input_player2_reserved_device", StreamCaps.PAD_NAME)
        put("input_player2_device_reservation_type", "2")
        if (p2SlotMode == P2SlotMode.PLACEHOLDER) {
            // Touch host: player 1 plays the overlay, so unbound user 1
            // keeps the placeholder's registering SELECT blip silent.
            put("input_player1_a_btn", "nul")
            put("input_player1_b_btn", "nul")
            put("input_player1_x_btn", "nul")
            put("input_player1_y_btn", "nul")
            put("input_player1_l_btn", "nul")
            put("input_player1_r_btn", "nul")
            put("input_player1_l2_btn", "nul")
            put("input_player1_r2_btn", "nul")
            put("input_player1_l3_btn", "nul")
            put("input_player1_r3_btn", "nul")
            put("input_player1_start_btn", "nul")
            put("input_player1_select_btn", "nul")
            put("input_player1_up_btn", "nul")
            put("input_player1_down_btn", "nul")
            put("input_player1_left_btn", "nul")
            put("input_player1_right_btn", "nul")
        }
        put("input_player2_a_btn", "97")
        put("input_player2_b_btn", "96")
        put("input_player2_x_btn", "100")
        put("input_player2_y_btn", "99")
        put("input_player2_l_btn", "102")
        put("input_player2_r_btn", "103")
        put("input_player2_l2_btn", "104")
        put("input_player2_r2_btn", "105")
        put("input_player2_l3_btn", "106")
        put("input_player2_r3_btn", "107")
        put("input_player2_start_btn", "108")
        put("input_player2_select_btn", "109")
        put("input_player2_up_btn", "h0up")
        put("input_player2_down_btn", "h0down")
        put("input_player2_left_btn", "h0left")
        put("input_player2_right_btn", "h0right")
        put("input_player2_l_x_plus_axis", "+0")
        put("input_player2_l_x_minus_axis", "-0")
        put("input_player2_l_y_plus_axis", "+1")
        put("input_player2_l_y_minus_axis", "-1")
        put("input_player2_r_x_plus_axis", "+2")
        put("input_player2_r_x_minus_axis", "-2")
        put("input_player2_r_y_plus_axis", "+3")
        put("input_player2_r_y_minus_axis", "-3")
        put("input_player2_l2_axis", "+6")
        put("input_player2_r2_axis", "+7")
        // OpenSL ES output goes through AudioFlinger's mixer, which playback capture can see.
        put("audio_driver", "opensl")
        put("audio_latency", "32")
        if (rsoundReady) {
            put("audio_driver", "rsound")
            put("audio_device", "127.0.0.1")
            put("audio_latency", experiments.audioLatency.takeIf { it == 8 || it == 16 || it == 32 }?.toString() ?: "32")
        }
        if (experiments.swapchain == 2) put("video_max_swapchain_images", "2")
        if (experiments.noSmooth) put("video_smooth", "false")
    }

    /** The whole file: the managed config (with save-state resume, as a normal Play) plus [overrides]. */
    fun cfgText(
        sharedRoot: String,
        rsoundReady: Boolean = false,
        hasRealHostPad: Boolean = false,
        experiments: RaExperimentOptions = RaExperimentOptions.sample(),
        retroArchVersionCode: Long = 0,
        overlayCfgPath: String? = null,
        p2SlotMode: P2SlotMode = P2SlotMode.OFF,
    ): String {
        val base = GraphicsTuner.retroArchConfigText(sharedRoot, resume = true).trimEnd('\n').split('\n')
        val (merged, _) = RetroArchTuner.mergeConfig(base, overrides(rsoundReady, hasRealHostPad = hasRealHostPad, experiments = experiments, retroArchVersionCode = retroArchVersionCode, overlayCfgPath = overlayCfgPath, p2SlotMode = p2SlotMode))
        return merged.joinToString("\n") + "\n"
    }

    fun fileUnder(base: File): File = File(GraphicsTuner.sharedDirUnder(base), "ra/$FILE_NAME")

    /** Writes the config under [base] (external storage); null when it can't. */
    fun write(
        base: File,
        rsoundReady: Boolean = false,
        hasRealHostPad: Boolean = false,
        experiments: RaExperimentOptions = RaExperimentOptions.sample(),
        retroArchVersionCode: Long = 0,
        overlayCfgPath: String? = null,
        p2SlotMode: P2SlotMode = P2SlotMode.OFF,
    ): File? = try {
        val file = fileUnder(base)
        val dir = file.parentFile ?: throw IOException("no parent for $file")
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) throw IOException("Couldn't create $dir")
        file.writeText(cfgText(base.absolutePath, rsoundReady, hasRealHostPad, experiments, retroArchVersionCode, overlayCfgPath, p2SlotMode))
        file
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }
}

data class RaExperimentOptions(
    val audioLatency: Int = 32,
    val swapchain: Int = 0,
    val noSmooth: Boolean = false,
    val poll: Int = 0,
    val overlayAutohideV3: Boolean = false,
) {
    companion object {
        fun sample(
            debug: Boolean = com.gamebex.client.BuildConfig.DEBUG,
            prop: (String) -> String? = StreamDebug::prop,
            flags: (String) -> String? = { null },
        ): RaExperimentOptions {
            val p: (String) -> String? = if (debug) prop else ({ _: String -> null })
            fun pick(name: String) = p(name) ?: flags(name)
            val latency = when (pick("ra_audio_latency")) {
                "8" -> 8
                "16" -> 16
                else -> 32
            }
            val swapchain = if (pick("ra_swapchain") == "2") 2 else 0
            val noSmooth = pick("ra_no_smooth") == "1"
            val poll = pick("ra_poll")?.takeIf { it in setOf("0", "1", "2") }?.toInt() ?: 0
            val overlayAutohideV3 = pick("overlay_autohide_v3") == "1"
            return RaExperimentOptions(latency, swapchain, noSmooth, poll, overlayAutohideV3)
        }
    }
}
