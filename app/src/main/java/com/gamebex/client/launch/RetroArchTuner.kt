package com.gamebex.client.launch

import android.os.Environment
import android.util.Log
import java.io.File

/**
 * RA's own retroarch.cfg is unreadable cross-app on Android 13+ (File.exists()
 * is false from our process even though adb shell sees it), so tuning targets
 * the GameBEX-managed config passed as CONFIGFILE on every launch: shared dirs
 * (BIOS/saves/states survive reinstalls) plus a pinned input driver/autoconfig.
 */
object RetroArchTuner {
    private const val TAG = "GameBEXRetroTune"

    fun wantedDirs(sharedRoot: String): Map<String, String> {
        return mapOf(
            "system_directory" to "$sharedRoot/RetroArch/system",
            "savefile_directory" to "$sharedRoot/RetroArch/saves",
            "savestate_directory" to "$sharedRoot/RetroArch/states",
            "input_joypad_driver" to "android",
            "joypad_autoconfig_enable" to "true",
        )
    }

    data class TuneResult(val applied: Boolean, val reason: String)

    /**
     * Idempotent and safe while RA runs, since RA never writes back into our
     * file. The top-level dirs need all-files access; without it the config
     * still refreshes under /sdcard/GameBEX whenever shared storage is writable.
     */
    fun tune(): TuneResult {
        val root = sharedRoot()
        for (dir in listOf("system", "saves", "states")) {
            runCatching { File(root, "RetroArch/$dir").mkdirs() }
        }
        val cfg = GraphicsTuner.writeRetroArchConfig()
            ?: return TuneResult(false, "shared storage unreachable").also { Log.d(TAG, it.reason) }
        Log.d(TAG, "tuned ${cfg.absolutePath}")
        return TuneResult(true, "tuned")
    }

    /** Key of an active `key = "value"` line, else null (comments/blank/garbage). */
    fun parseKey(line: String): String? {
        val t = line.trim()
        if (t.isEmpty() || t.startsWith("#")) return null
        val m = Regex("""^([A-Za-z0-9_]+)\s*=""").find(t) ?: return null
        return m.groupValues[1]
    }

    /**
     * Replaces every active occurrence of each [wanted] key and appends missing
     * keys. The Boolean is whether any line changed.
     */
    fun mergeConfig(lines: List<String>, wanted: Map<String, String>): Pair<List<String>, Boolean> {
        val seen = mutableSetOf<String>()
        var changed = false
        val out = lines.map { line ->
            val key = parseKey(line)
            if (key != null && key in wanted) {
                seen.add(key)
                val want = "${key} = \"${wanted.getValue(key)}\""
                if (line.trim() != want) changed = true
                want
            } else {
                line
            }
        } + wanted.keys.filter { it !in seen }.map { key ->
            changed = true
            "${key} = \"${wanted.getValue(key)}\""
        }
        return out to changed
    }

    @Suppress("DEPRECATION")
    private fun sharedRoot(): String = Environment.getExternalStorageDirectory().absolutePath
}
