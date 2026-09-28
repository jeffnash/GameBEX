package com.gamebex.client.launch

import android.os.Environment
import java.io.File

/**
 * Cross-emulator graphics defaults for the 1600x720 handheld (Dimensity
 * MT6835, Mali-G57, 3.7 GB RAM). Android 13+ blocks cross-app
 * `/sdcard/Android/data` access even with MANAGE_EXTERNAL_STORAGE (verified
 * on-device), so programmatic tuning is limited to what each emulator
 * exposes: RetroArch honors a CONFIGFILE launch extra; everything else gets
 * an in-app guide (see graphicsGuideFor). All shared files live under
 * top-level /sdcard/GameBEX, which all-files apps can read.
 */
object GraphicsTuner {
    /**
     * Managed RetroArch config, rewritten on every launch. Fills screen
     * height (integer scaling is what caused bars on all four sides),
     * smooths the upscale, auto-hides the touch overlay when a controller
     * connects, and keeps rewind off (it costs CPU on this chip). Missing
     * keys fall back to RA defaults; RA never writes back into this file.
     * Per-core overrides (config/<Core>/<Core>.cfg, e.g. per-core touch
     * overlays) still load on top — verified via probe override.
     *
     * Never add video_threaded here: it segfaults Mupen64Plus-Next GLES3 at boot.
     *
     * [resume] false is the per-launch "Start fresh": only the auto-load
     * flips. Auto-save stays on, so quitting a fresh session bookmarks it
     * as the new resume point; numbered save slots are never touched.
     */
    fun retroArchConfigText(
        sharedRoot: String = "/storage/emulated/0",
        resume: Boolean = true,
    ): String = buildString {
        appendLine("# Managed by GameBEX - rewritten on every RetroArch launch.")
        // Shared dirs (BIOS/saves/states survive reinstalls) + pinned
        // input driver/autoconfig. Same values RetroArchTuner wants: this
        // file is passed as CONFIGFILE on every launch, which is the only
        // tune path that works — RA's own cfg is cross-app unreadable on
        // Android 13+ (verified on-device).
        for ((k, v) in RetroArchTuner.wantedDirs(sharedRoot)) {
            appendLine("$k = \"$v\"")
        }
        appendLine("video_scale_integer = \"false\"")
        appendLine("video_smooth = \"true\"")
        appendLine("video_vsync = \"true\"")
        appendLine("rewind_enable = \"false\"")
        appendLine("input_overlay_enable = \"true\"")
        appendLine("input_overlay_hide_when_gamepad_connected = \"true\"")
        // Any pad (not just ones with a bundled profile) gets auto-bound.
        appendLine("input_autodetect_enable = \"true\"")
        appendLine("auto_overrides_enable = \"true\"")
        appendLine("savestate_auto_save = \"true\"")
        appendLine("savestate_auto_load = \"$resume\"")
        // RA writes <state file>.png beside each savestate; the Home cards
        // use it as the game thumbnail.
        appendLine("savestate_thumbnail_enable = \"true\"")
        appendLine("autosave_interval = \"10\"")
        // Suspend shortcut: Select+Start clean-quits (auto-bookmark) back
        // to the frontend; Select+X opens the RA menu. Android keycodes
        // using Android's standard gamepad keycodes (select=109, start=108,
        // X=100), which are the same on every compliant pad.
        appendLine("input_enable_hotkey_btn = \"109\"")
        appendLine("input_exit_emulator_btn = \"108\"")
        appendLine("input_menu_toggle_btn = \"100\"")
        appendLine("config_save_on_exit = \"false\"")
    }

    @Suppress("DEPRECATION")
    private fun externalBase(): File = Environment.getExternalStorageDirectory()

    fun sharedDir(): File = sharedDirUnder(externalBase())

    fun retroArchConfigFile(): File = retroArchConfigFileUnder(externalBase())

    fun retroArchFreshConfigFile(): File = retroArchFreshConfigFileUnder(externalBase())

    /** Core .so staging for the RA sideload flow (doubles as a cache). */
    fun coreStageDir(): File = coreStageDirUnder(externalBase())

    /** Pure path builders (the only Android call is [externalBase]). */
    fun sharedDirUnder(base: File): File = File(base, "GameBEX")
    fun retroArchConfigFileUnder(base: File): File = File(sharedDirUnder(base), "ra/retroarch-frontend.cfg")
    fun retroArchFreshConfigFileUnder(base: File): File =
        File(sharedDirUnder(base), "ra/retroarch-frontend-fresh.cfg")
    fun coreStageDirUnder(base: File): File = File(sharedDirUnder(base), "cores")
    fun togetherDirUnder(base: File): File = File(sharedDirUnder(base), "together")

    fun togetherConfigFileUnder(base: File, role: com.gamebex.client.together.Role): File =
        File(sharedDirUnder(base), "ra/retroarch-together-${role.name.lowercase()}.cfg")

    fun togetherDir(): File = togetherDirUnder(externalBase())

    /**
     * Writes the managed RA config; null when storage is unreachable.
     * A fresh launch ([resume] false) writes a separate file, so the normal
     * config never needs restoring and a mid-launch failure can't leave
     * auto-load off for later Plays.
     */
    fun writeRetroArchConfig(resume: Boolean = true): File? = writeRetroArchConfigUnder(externalBase(), resume)

    /** [writeRetroArchConfig] against an explicit storage root (tests). */
    fun writeRetroArchConfigUnder(base: File, resume: Boolean = true): File? = runCatching {
        val f = if (resume) retroArchConfigFileUnder(base) else retroArchFreshConfigFileUnder(base)
        f.parentFile?.mkdirs()
        f.writeText(retroArchConfigText(base.absolutePath, resume))
        f.takeIf { it.isFile }
    }.getOrElse { null }
}
