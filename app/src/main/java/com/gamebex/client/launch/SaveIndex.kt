package com.gamebex.client.launch

import com.gamebex.client.data.CoreNames
import com.gamebex.client.data.Emu
import com.gamebex.client.data.PlatformRegistry
import com.gamebex.client.data.Route
import com.gamebex.client.data.local.GameEntity
import com.gamebex.client.ui.home.model.SaveIndex
import com.gamebex.client.ui.home.model.SaveInfo
import java.io.File

/**
 * RetroArch savestate naming rules (Home save index). Pure: no IO,
 * no Android types. RetroArch writes states under
 * `<sharedRoot>/RetroArch/states/<core>/<rom base>.state[.auto|<slot>]`,
 * e.g. `dolphin-emu/Crazy Taxi (Europe) (En,Fr,De,Es).state.auto`, and
 * with `savestate_thumbnail_enable` a `<state file>.png` beside each one.
 */
object RetroArchStates {
    /** Names RetroArch accepts: `.state` (slot 0), `.state.auto`, `.state1..9`. */
    private val SUFFIXES = listOf(".state", ".state.auto") + (1..9).map { ".state$it" }

    /**
     * Route core ids whose RetroArch states folder deviates from the
     * core's display name. RetroArch names the
     * states folder after the core's `retro_system_info.library_name`
     * (paths.c), which is not the display name: values verified against
     * the Libretro buildbot cores GameBEX fetches and the state folders
     * on the device.
     */
    private val CORE_FOLDER_OVERRIDES = mapOf(
        "dolphin" to "dolphin-emu",
        "mupen64plus_next" to "Mupen64Plus-Next",
        "dosbox_pure" to "DOSBox-pure",
        
        "np2kai" to "Neko Project II kai",
        "px68k" to "PX68K",
        "same_cdi" to "SAME_CDI",
        "x1" to "x1",
    )

    /**
     * RetroArch states folder for a core id: the core's `library_name`
     * ("gambatte" → "Gambatte", "fbneo" → "FinalBurn Neo", …), which is
     * the display name unless the folder name deviates. Unknown cores
     * return null and match any folder.
     */
    fun coreFolderOf(core: String): String? =
        CORE_FOLDER_OVERRIDES[core] ?: CoreNames.NAMES[core]

    /** Every states folder name a known core owns (library_name values). */
    private val KNOWN_CORE_FOLDERS: Set<String> =
        (CoreNames.NAMES.values + CORE_FOLDER_OVERRIDES.values).toSet()

    /** Whether [folder] is owned by a known core: the missing-folder
     *  fallback never adopts saves from one. */
    fun isKnownCoreFolder(folder: String): Boolean = folder in KNOWN_CORE_FOLDERS

    /**
     * The states folder this game's saves live in: its route core (or the
     * per-game core override), null when the core's folder name is
     * unknown. Pure.
     */
    fun coreFolderFor(game: GameEntity): String? {
        val route = PlatformRegistry.byFolder[game.folder]?.route as? Route.RetroCore ?: return null
        return coreFolderOf(game.coreOverride ?: route.core)
    }

    /**
     * State base for a ROM's file name: the name without its extension,
     * so `Crazy Taxi (Europe) (En,Fr,De,Es).rvz` and the prepped
     * `...cue` both key on `Crazy Taxi (Europe) (En,Fr,De,Es)`.
     */
    fun baseName(fileName: String): String {
        val dot = fileName.lastIndexOf('.')
        return if (dot > 0) fileName.substring(0, dot) else fileName
    }

    /**
     * Base name when [name] is a state file, else null. `X.state.auto.png`
     * is the thumbnail, not a state, and `X.state10` is not a slot.
     */
    fun stateBaseOf(name: String): String? =
        SUFFIXES.firstNotNullOfOrNull { s ->
            name.removeSuffix(s).takeIf { it.length < name.length && it.isNotEmpty() }
        }

    /** Whether [name] is a state file for [base]. */
    fun isState(name: String, base: String): Boolean = stateBaseOf(name) == base

    /**
     * RetroArch state rules apply only to games that boot through a
     * RetroArch core (same routing as [EmuRouter]): standalone emulators
     * keep their saves elsewhere. [emulatorOverride] is the entity's.
     */
    fun runsOnRetroArch(folder: String, emulatorOverride: String?): Boolean {
        if (PlatformRegistry.byFolder[folder]?.route !is Route.RetroCore) return false
        return emulatorOverride == null || emulatorOverride == Emu.RETROARCH.pkg
    }
}

/**
 * Save thumbnails and counts from RetroArch states. Scans
 * [statesRoot] one core folder deep — exactly where RA writes — and keys
 * state files by (core folder, ROM base name), so "Tetris (World)" on GB
 * never shows the NES Tetris saves. A game whose core's
 * folder is unknown — or not found on this device — falls back to
 * folders no known core owns. Blocking file IO: call
 * off the main thread. [invalidate] drops the listing cache (Home
 * re-entry, after an emulator exits).
 */
class RetroArchSaveIndex(private val statesRoot: File) : SaveIndex {
    /** Core folder ("" = states root) → ROM base → state files. */
    private var cache: Map<String, Map<String, List<File>>>? = null

    override fun lookup(game: GameEntity): SaveInfo? {
        if (!RetroArchStates.runsOnRetroArch(game.folder, game.emulatorOverride)) return null
        val base = RetroArchStates.baseName(launchFileName(game))
        val coreFolder = RetroArchStates.coreFolderFor(game)
        val states = index().let { byFolder ->
            if (coreFolder != null && byFolder.containsKey(coreFolder)) {
                byFolder[coreFolder]?.get(base).orEmpty()
            } else {
                // Unknown core, or the known core's folder is not on this
                // device (folder-name drift): search only folders no known
                // core owns, so saves never cross between known cores — a
                // missing "Gambatte" folder can never adopt Mesen's saves.
                // Empty own folders already took the strict branch above.
                byFolder.filterKeys { !RetroArchStates.isKnownCoreFolder(it) }
                    .values.mapNotNull { it[base] }.flatten()
            }
        }
        val newest = states.maxByOrNull { it.lastModified() } ?: return null
        val newestMs = newest.lastModified()
        val thumb = File(newest.absolutePath + ".png").takeIf { it.isFile }
        return SaveInfo(newestMs = newestMs, count = states.size, thumbnail = thumb)
    }

    override fun invalidate() {
        synchronized(this) { cache = null }
    }

    /** Prepped boot target when set (cue/m3u), else the ROM file. */
    private fun launchFileName(game: GameEntity): String {
        val rel = game.launchRelPath ?: return game.fileName
        return rel.replace('\\', '/').substringAfterLast('/').ifBlank { game.fileName }
    }

    private fun index(): Map<String, Map<String, List<File>>> = synchronized(this) {
        cache ?: buildIndex().also { cache = it }
    }

    /** One listing per folder: root plus each core folder, never deeper.
     *  Empty core folders still register their key: existence — not a
     *  non-empty listing — is what keys saves strictly to a core. */
    private fun buildIndex(): Map<String, Map<String, List<File>>> {
        val out = HashMap<String, HashMap<String, MutableList<File>>>()
        fun add(folder: String, file: File) {
            if (!file.isFile) return
            val base = RetroArchStates.stateBaseOf(file.name) ?: return
            out.getOrPut(folder) { HashMap() }.getOrPut(base) { ArrayList() }.add(file)
        }
        val entries = statesRoot.listFiles() ?: return emptyMap()
        for (e in entries) {
            if (e.isDirectory) {
                out.getOrPut(e.name) { HashMap() }
                e.listFiles()?.forEach { add(e.name, it) }
            } else {
                add("", e)
            }
        }
        return out
    }
}
