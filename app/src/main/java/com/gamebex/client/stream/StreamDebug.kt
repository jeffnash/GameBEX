package com.gamebex.client.stream

/**
 * Device-experiment switches, set from a computer with `adb shell setprop debug.gamebex.<name> <value>`
 * (only adb can set debug.* properties; they clear on reboot). Unset means normal behaviour.
 *
 * - decoder: MediaCodec decoder name the guest uses instead of its usual pick.
 * - tdls: "1" asks Wi-Fi for a direct TDLS link to the host while the viewer is open.
 * - ra_audio: "rsound" makes the RetroArch session send its sound to an RSound server on 127.0.0.1.
 */
object StreamDebug {
    fun prop(name: String): String? = runCatching {
        val process = ProcessBuilder("getprop", "debug.gamebex.$name").redirectErrorStream(true).start()
        process.inputStream.bufferedReader().use { it.readText() }.trim().also { process.waitFor() }
    }.getOrNull()?.takeIf { it.isNotEmpty() }
}
