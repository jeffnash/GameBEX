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

    fun overrides(): LinkedHashMap<String, String> = LinkedHashMap<String, String>().apply {
 // input_max_users stays as in the managed config (8): forcing 2 makes RetroArch connect the core's
 // ports 3-4 as RETRO_DEVICE_NONE, and the Dolphin core crashes a few seconds into the game .
        put("input_player2_reserved_device", StreamCaps.PAD_NAME)
        put("input_player2_device_reservation_type", "2")
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
 // StreamDebug "ra_audio": RetroArch's RSound driver sends PCM to 127.0.0.1:12345,
 // skipping AudioFlinger and the remote-submix pipe.
        if (StreamDebug.prop("ra_audio") == "rsound") {
            put("audio_driver", "rsound")
            put("audio_device", "127.0.0.1")
        }
    }

 /** The whole file: the managed config (with save-state resume, as a normal Play) plus [overrides]. */
    fun cfgText(sharedRoot: String): String {
        val base = GraphicsTuner.retroArchConfigText(sharedRoot, resume = true).trimEnd('\n').split('\n')
        val (merged, _) = RetroArchTuner.mergeConfig(base, overrides())
        return merged.joinToString("\n") + "\n"
    }

    fun fileUnder(base: File): File = File(GraphicsTuner.sharedDirUnder(base), "ra/$FILE_NAME")

 /** Writes the config under [base] (external storage); null when it can't. */
    fun write(base: File): File? = try {
        val file = fileUnder(base)
        val dir = file.parentFile ?: throw IOException("no parent for $file")
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) throw IOException("Couldn't create $dir")
        file.writeText(cfgText(base.absolutePath))
        file
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }
}
