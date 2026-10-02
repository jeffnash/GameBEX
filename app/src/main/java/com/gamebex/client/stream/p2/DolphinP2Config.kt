package com.gamebex.client.stream.p2

import android.content.Context
import com.gamebex.client.data.Emu
import com.gamebex.client.launch.EmuFiles
import com.gamebex.client.launch.IniText
import com.gamebex.client.launch.Privileged
import com.gamebex.client.launch.ShizukuFiles
import com.gamebex.client.stream.StreamCaps
import com.gamebex.client.together.TogetherCopy
import com.gamebex.client.together.emu.RestoreFile
import com.gamebex.client.together.emu.RestoreKey
import com.gamebex.client.together.emu.RestoreRecord
import com.gamebex.client.together.emu.RestoreStore
import java.io.File
import java.io.IOException

/**
 * Standalone Dolphin's player 2 for a Stream host: GameCube port 2
 * becomes a standard controller (Dolphin.ini [Core] SIDevice1 = 6,
 * SIDEVICE_GC_CONTROLLER) mapped to "GameBEX Player 2" in GCPadNew.ini
 * [GCPad2], and Wii Remote 2 becomes an emulated remote with a Nunchuk in
 * WiimoteNew.ini [Wiimote2]. Names follow Dolphin's Android input backend
 * (Source/Core/InputCommon/ControllerInterface/Android/Android.cpp):
 * keys are "Button A", "Button B", "Button X", "Button Y", "Button L1", "Button R1",
 * "Button L2", "Button R2", "Button L3", "Button R3", "Start" and "Select"; axes are
 * "Axis <id><sign>".
 * Every original value goes to [RestoreStore] before anything is written, and
 * [restorePending] puts them back (End, and the next launch after a crash),
 * the way PpssppNetConfig does for PPSSPP.
 */
class DolphinP2Config(
    private val store: RestoreStore,
    private val fs: () -> EmuFiles?,
    private val stopper: () -> ((String) -> Boolean)?,
    private val configDir: String = CONFIG_DIR,
    private val settleMs: Long = 500L,
) {
    data class Key(val file: String, val section: String, val key: String, val value: String)

    sealed interface Result {
        data object Applied : Result
        data class Failed(val text: String) : Result
    }

    suspend fun apply(): Result {
        if (!restorePending()) return Result.Failed(TogetherCopy.streamStopped)
        val files = fs() ?: return Result.Failed(TogetherCopy.startShizuku)
        val stop = stopper() ?: return Result.Failed(TogetherCopy.startShizuku)
        stop(Emu.DOLPHIN.pkg)
        kotlinx.coroutines.delay(settleMs)
        if (!files.isFile("$configDir/Dolphin.ini")) return Result.Failed(TogetherCopy.openOnce(Emu.DOLPHIN.label))
        val byFile = wanted().groupBy { it.file }
        return try {
            val records = byFile.map { (name, keys) ->
                val path = "$configDir/$name"
                val text = files.readText(path).orEmpty()
                RestoreFile(
                    path = path,
                    keys = keys.map { RestoreKey(it.section, it.key, IniText.get(text, it.section, it.key)) },
                    createdSections = keys.map { it.section }.distinct().filter { section -> text.lineSequence().none { it.trim() == "[$section]" } },
                )
            }
            store.save(RECORD, RestoreRecord(records))
            for ((name, keys) in byFile) {
                val path = "$configDir/$name"
                val text = files.readText(path).orEmpty()
                val edited = keys.fold(text) { acc, k -> IniText.set(acc, k.section, k.key, k.value) }
                if (!files.writeTextAtomic(path, edited)) throw IOException("write failed: $path")
            }
            Result.Applied
        } catch (_: Exception) {
            restorePending()
            Result.Failed(TogetherCopy.streamStopped)
        }
    }

    /** Puts back what [apply] changed. True when nothing is pending afterwards. */
    suspend fun restorePending(): Boolean {
        val record = store.load(RECORD) ?: return true
        val files = fs() ?: return false
        stopper()?.invoke(Emu.DOLPHIN.pkg)
        kotlinx.coroutines.delay(settleMs)
        return try {
            for (file in record.files) {
                val text = files.readText(file.path) ?: continue
                var edited = text
                for (key in file.keys) {
                    edited = if (key.value == null) IniText.remove(edited, key.section, key.key)
                    else IniText.set(edited, key.section, key.key, key.value)
                }
                for (section in file.createdSections) edited = IniText.removeEmptySection(edited, section)
                if (!files.writeTextAtomic(file.path, edited)) return false
            }
            store.clear(RECORD)
            true
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        const val CONFIG_DIR: String = "/storage/emulated/0/Android/data/org.dolphinemu.dolphinemu/files/Config"
        private const val RECORD = "dolphin-p2"
        /** Dolphin SerialInterface SIDEVICE_GC_CONTROLLER (Core/HW/SI/SI_Device.h). */
        const val SIDEVICE_GC_CONTROLLER: String = "6"
        /** WiimoteSource::Emulated (Core/HW/Wiimote.h). */
        const val WIIMOTE_EMULATED: String = "1"

        fun device(index: Int = StreamCaps.DOLPHIN_DEVICE_INDEX): String = "Android/$index/${StreamCaps.PAD_NAME}"

        private fun q(input: String) = "`$input`"

        /** Every key [apply] writes, in write order. */
        fun wanted(index: Int = StreamCaps.DOLPHIN_DEVICE_INDEX): List<Key> {
            val gc = "GCPadNew.ini"
            val wii = "WiimoteNew.ini"
            val pad = "GCPad2"
            val remote = "Wiimote2"
            return listOf(
                Key("Dolphin.ini", "Core", "SIDevice1", SIDEVICE_GC_CONTROLLER),
                Key(gc, pad, "Device", device(index)),
                Key(gc, pad, "Buttons/A", q("Button A")),
                Key(gc, pad, "Buttons/B", q("Button X")),
                Key(gc, pad, "Buttons/X", q("Button B")),
                Key(gc, pad, "Buttons/Y", q("Button Y")),
                Key(gc, pad, "Buttons/Z", q("Button R1")),
                Key(gc, pad, "Buttons/Start", q("Start")),
                Key(gc, pad, "Main Stick/Up", q("Axis 1-")),
                Key(gc, pad, "Main Stick/Down", q("Axis 1+")),
                Key(gc, pad, "Main Stick/Left", q("Axis 0-")),
                Key(gc, pad, "Main Stick/Right", q("Axis 0+")),
                Key(gc, pad, "C-Stick/Up", q("Axis 14-")),
                Key(gc, pad, "C-Stick/Down", q("Axis 14+")),
                Key(gc, pad, "C-Stick/Left", q("Axis 11-")),
                Key(gc, pad, "C-Stick/Right", q("Axis 11+")),
                Key(gc, pad, "Triggers/L", q("Button L2")),
                Key(gc, pad, "Triggers/R", q("Button R2")),
                Key(gc, pad, "Triggers/L-Analog", q("Axis 17+")),
                Key(gc, pad, "Triggers/R-Analog", q("Axis 18+")),
                Key(gc, pad, "D-Pad/Up", q("Axis 16-")),
                Key(gc, pad, "D-Pad/Down", q("Axis 16+")),
                Key(gc, pad, "D-Pad/Left", q("Axis 15-")),
                Key(gc, pad, "D-Pad/Right", q("Axis 15+")),
                Key(wii, remote, "Source", WIIMOTE_EMULATED),
                Key(wii, remote, "Device", device(index)),
                Key(wii, remote, "Buttons/A", q("Button A")),
                Key(wii, remote, "Buttons/B", q("Button B")),
                Key(wii, remote, "Buttons/1", q("Button X")),
                Key(wii, remote, "Buttons/2", q("Button Y")),
                Key(wii, remote, "Buttons/-", q("Select")),
                Key(wii, remote, "Buttons/+", q("Start")),
                Key(wii, remote, "D-Pad/Up", q("Axis 16-")),
                Key(wii, remote, "D-Pad/Down", q("Axis 16+")),
                Key(wii, remote, "D-Pad/Left", q("Axis 15-")),
                Key(wii, remote, "D-Pad/Right", q("Axis 15+")),
                Key(wii, remote, "Extension", "Nunchuk"),
                Key(wii, remote, "Nunchuk/Buttons/C", q("Button L1")),
                Key(wii, remote, "Nunchuk/Buttons/Z", q("Button L2")),
                Key(wii, remote, "Nunchuk/Stick/Up", q("Axis 1-")),
                Key(wii, remote, "Nunchuk/Stick/Down", q("Axis 1+")),
                Key(wii, remote, "Nunchuk/Stick/Left", q("Axis 0-")),
                Key(wii, remote, "Nunchuk/Stick/Right", q("Axis 0+")),
            )
        }

        fun forApp(ctx: Context): DolphinP2Config {
            val app = ctx.applicationContext
            return DolphinP2Config(
                store = RestoreStore(File(app.filesDir, "together")),
                fs = { (runCatching { ShizukuFiles.privileged(app) }.getOrNull() as? Privileged.Ready)?.files },
                stopper = { ShizukuFiles.forceStopper(app) },
            )
        }
    }
}
