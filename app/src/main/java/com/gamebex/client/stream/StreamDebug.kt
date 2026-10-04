package com.gamebex.client.stream

/**
 * Device-experiment switches, set from a computer with `adb shell setprop debug.gamebex.<name> <value>`
 * (only adb can set debug.* properties; they clear on reboot). Unset means normal behaviour.
 *
 * - decoder: MediaCodec decoder name the guest uses instead of its usual pick.
 * - tdls: "1" asks Wi-Fi for a direct TDLS link to the host while the viewer is open.
 * - ra_audio: "opensl" selects loopback for comparisons; otherwise readiness selects RSound.
 * - ra_log: "1" enables verbose RetroArch session logs in its timestamped log file.
 * - ra_poll: "0", "1" or "2" selects early, normal or late input polling for diagnosis.
 * - overlay_boxes: "1" logs every drawn overlay/emu control box on the guest.
 * - no_pad: "1" runs a padless session (no virtual gamepad; standalone emulators keep their touch
 *   overlay visible; guest input is dropped).
 */
object StreamDebug {
    fun flag(name: String, default: Boolean = false): Boolean {
        if (!com.gamebex.client.BuildConfig.DEBUG) return default
        return when (prop(name)) {
            "1" -> true
            "0" -> false
            else -> default
        }
    }

    fun int(name: String, default: Int, range: IntRange): Int {
        if (!com.gamebex.client.BuildConfig.DEBUG) return default
        return prop(name)?.toIntOrNull()?.takeIf { it in range } ?: default
    }

    fun prop(name: String): String? = runCatching {
        val process = ProcessBuilder("getprop", "debug.gamebex.$name").redirectErrorStream(true).start()
        process.inputStream.bufferedReader().use { it.readText() }.trim().also { process.waitFor() }
    }.getOrNull()?.takeIf { it.isNotEmpty() }
}
