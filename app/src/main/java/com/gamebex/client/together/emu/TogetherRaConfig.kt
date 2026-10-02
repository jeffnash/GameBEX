package com.gamebex.client.together.emu

import com.gamebex.client.launch.GraphicsTuner
import com.gamebex.client.launch.RetroArchTuner
import com.gamebex.client.together.Role
import com.gamebex.client.together.TogetherMode
import java.io.File
import java.io.IOException
import java.util.LinkedHashMap

/** Builds the isolated RetroArch configuration used by a together session. */
object TogetherRaConfig {
    fun cfgText(
        sharedRoot: String,
        role: Role,
        mode: TogetherMode,
        hostAddress: String,
        playerName: String,
    ): String {
        // Every together config boots fresh; overrides() also turns auto-save off.
        val base = GraphicsTuner.retroArchConfigText(sharedRoot, resume = false)
            .trimEnd('\n')
            .split('\n')
        val (merged, _) = RetroArchTuner.mergeConfig(
            base,
            overrides(sharedRoot, role, mode, hostAddress, playerName),
        )
        return merged.joinToString("\n") + "\n"
    }

    fun overrides(
        sharedRoot: String,
        role: Role,
        mode: TogetherMode,
        hostAddress: String,
        playerName: String,
    ): LinkedHashMap<String, String> = LinkedHashMap<String, String>().apply {
        if (mode == TogetherMode.RA_NETPLAY || mode == TogetherMode.GB_LINK) {
            put("global_core_options", "true")
            put("game_specific_options", "false")
            put("core_options_path", "$sharedRoot/GameBEX/together/core-options.cfg")
            // No save-state resume for any role or mode; battery saves keep their folders.
            put("savestate_auto_save", "false")
            put("savestate_auto_load", "false")
        }
        when (mode) {
            TogetherMode.RA_NETPLAY -> {
                put("netplay_nickname", playerName)
                put("netplay_ip_port", "55435")
                put("netplay_use_mitm_server", "false")
                put("netplay_public_announce", "false")
                put("netplay_nat_traversal", "false")
                put("netplay_start_as_spectator", "false")
                put("netplay_max_connections", "1")
                put("netplay_password", "")
                put("netplay_spectate_password", "")
                put("notification_show_netplay_extra", "true")
                // "1" is true for 1.22.2 and "Main Menu" for builds where it's a number.
                put("content_show_netplay", "1")
                put("auto_overrides_enable", "false")
                if (role == Role.HOST) {
                    put("input_netplay_host_toggle_btn", "103")
                    put("netplay_ip_address", "")
                } else {
                    put("input_netplay_host_toggle_btn", "nul")
                    put("netplay_ip_address", "$hostAddress|55435")
                    put("savefile_directory", "$sharedRoot/GameBEX/together/saves")
                    put("savestate_directory", "$sharedRoot/GameBEX/together/states")
                }
            }
            TogetherMode.GB_LINK -> {
                put("input_netplay_host_toggle_btn", "nul")
            }
            // STREAM writes its own session config (StreamRaConfig).
            TogetherMode.PSP_ADHOC, TogetherMode.STREAM -> Unit
        }
    }

    fun coreOptionsText(mode: TogetherMode, role: Role, hostAddress: String): String = when (mode) {
        TogetherMode.RA_NETPLAY ->
            "# Managed by GameBEX Play together. Empty on purpose: both phones run core defaults.\n"
        TogetherMode.GB_LINK -> buildString {
            append("gambatte_gb_link_mode = \"")
            append(if (role == Role.HOST) "Network Server" else "Network Client")
            append("\"\n")
            append("gambatte_gb_link_network_port = \"56400\"\n")
            if (role == Role.GUEST) {
                gbIpDigits(hostAddress).forEachIndexed { index, digit ->
                    append("gambatte_gb_link_network_server_ip_${index + 1} = \"")
                    append(digit)
                    append("\"\n")
                }
            }
            append("gambatte_show_gb_link_settings = \"enabled\"\n")
        }
        TogetherMode.PSP_ADHOC, TogetherMode.STREAM -> ""
    }

    fun gbIpDigits(ipv4: String): List<String> {
        val octets = ipv4.split('.')
        require(octets.size == 4) { "Invalid IPv4 address: $ipv4" }
        return octets.flatMap { raw ->
            val value = raw.toIntOrNull()
            require(raw.isNotEmpty() && raw.all { it in '0'..'9' } && value != null && value in 0..255) {
                "Invalid IPv4 address: $ipv4"
            }
            value.toString().padStart(3, '0').map(Char::toString)
        }
    }

    fun write(
        base: File,
        role: Role,
        mode: TogetherMode,
        hostAddress: String,
        playerName: String,
    ): File? = try {
        val togetherDir = GraphicsTuner.togetherDirUnder(base)
        val saves = File(togetherDir, "saves")
        val states = File(togetherDir, "states")
        val raDir = File(GraphicsTuner.sharedDirUnder(base), "ra")
        for (dir in listOf(togetherDir, saves, states, raDir)) {
            if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) {
                throw IOException("Couldn't create $dir")
            }
        }
        File(togetherDir, "core-options.cfg").writeText(coreOptionsText(mode, role, hostAddress))
        val cfg = GraphicsTuner.togetherConfigFileUnder(base, role)
        cfg.writeText(cfgText(base.absolutePath, role, mode, hostAddress, playerName))
        cfg
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

}
