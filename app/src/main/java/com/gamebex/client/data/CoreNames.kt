package com.gamebex.client.data

/**
 * User-facing names for RetroArch core ids (the .so base names in
 * [Route.RetroCore]). Raw ids stay in logs, URLs and file names only. Pure.
 */
object CoreNames {
    val NAMES: Map<String, String> = mapOf(
        "applewin" to "AppleWin",
        "bluemsx" to "blueMSX",
        "citra" to "Citra",
        "azahar" to "Azahar",
        "dolphin" to "Dolphin",
        "dosbox_pure" to "DOSBox Pure",
        "fbneo" to "FinalBurn Neo",
        "flycast" to "Flycast",
        "gambatte" to "Gambatte",
        "genesis_plus_gx" to "Genesis Plus GX",
        "mednafen_ngp" to "Beetle NeoPop",
        "mednafen_pce" to "Beetle PCE",
        "mednafen_pce_fast" to "Beetle PCE Fast",
        "mednafen_pcfx" to "Beetle PC-FX",
        "mednafen_psx" to "Beetle PSX",
        "mednafen_psx_hw" to "Beetle PSX HW",
        "mednafen_saturn" to "Beetle Saturn",
        "mednafen_vb" to "Beetle VB",
        "mednafen_wswan" to "Beetle WonderSwan",
        "melonds" to "melonDS",
        "mesen" to "Mesen",
        "mgba" to "mGBA",
        "mupen64plus_next_gles3" to "Mupen64Plus-Next",
        "mupen64plus_next" to "Mupen64Plus-Next",
        "neocd" to "NeoCD",
        "np2kai" to "Neko Project II Kai",
        "opera" to "Opera",
        "picodrive" to "PicoDrive",
        "pokemini" to "PokeMini",
        "ppsspp" to "PPSSPP",
        "prosystem" to "ProSystem",
        "puae" to "PUAE",
        "px68k" to "PX68k",
        "same_cdi" to "SAME CDi",
        "snes9x" to "Snes9x",
        "stella" to "Stella",
        "swanstation" to "SwanStation",
        "virtualjaguar" to "Virtual Jaguar",
        "x1" to "X Millennium",
    )

    /** Display name for [core]; unknown ids read as prettified words ("my_core" → "My Core"). */
    fun display(core: String): String = NAMES[core] ?: core
        .split('_', '-', ' ').filter { it.isNotBlank() }
        .joinToString(" ") { w -> w.replaceFirstChar { it.uppercaseChar() } }
        .ifBlank { core }
}
