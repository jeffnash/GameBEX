package com.gamebex.client.data

/**
 * Folder-name aliases for user game folders (ES-DE / RetroArch / Batocera
 * layouts): maps a per-system directory name onto a [PlatformRegistry]
 * folder. Registry folders always resolve to themselves; lookups ignore
 * case, spaces, dashes, and underscores ("Mega Drive" == "megadrive").
 *
 * Only user-added roots use aliases; GameBEX's own tree keeps exact
 * registry folder names.
 */
object PlatformAliases {
    /** Alias -> registry folder. Keys are written pre-normalized. */
    private val ALIASES: Map<String, String> = mapOf(
        "megadrive" to "genesis", "md" to "genesis", "megadrivejp" to "genesis",
        "genesisplus" to "genesis", "segagenesis" to "genesis", "segamegadrive" to "genesis",
        "sms" to "mastersystem", "markiii" to "mastersystem", "segamastersystem" to "mastersystem",
        "gg" to "gamegear", "segagamegear" to "gamegear",
        "32x" to "sega32x", "sega32xjp" to "sega32x", "sega32xna" to "sega32x",
        "megacd" to "segacd", "megacdjp" to "segacd", "scd" to "segacd",
        "saturnjp" to "saturn", "segasaturn" to "saturn",
        "dc" to "dreamcast", "segadreamcast" to "dreamcast",
        "sg1000" to "sg-1000",
        "ps1" to "psx", "psone" to "psx", "playstation" to "psx", "playstation1" to "psx",
        "playstation2" to "ps2",
        "playstationportable" to "psp",
        "vita" to "psvita",
        "famicom" to "nes", "fc" to "nes", "fds" to "nes",
        "sfc" to "snes", "superfamicom" to "snes", "snesna" to "snes", "supernintendo" to "snes",
        "nintendo64" to "n64",
        "gameboy" to "gb",
        "gameboycolor" to "gbc",
        "gameboyadvance" to "gba",
        "ds" to "nds", "nintendods" to "nds",
        "3ds" to "n3ds", "nintendo3ds" to "n3ds",
        "gamecube" to "gc", "ngc" to "gc",
        "vb" to "virtualboy",
        "pcengine" to "tg16", "pce" to "tg16", "turbografx" to "tg16", "turbografx16" to "tg16",
        "pcenginecd" to "tg-cd", "pcecd" to "tg-cd", "turbografxcd" to "tg-cd",
        "ngp" to "ngpc", "neogeopocket" to "ngpc", "neogeopocketcolor" to "ngpc",
        "neogeocdjp" to "neogeocd",
        "arcade" to "mame", "fbneo" to "mame", "fba" to "mame", "mame2003" to "mame",
        "mame2003plus" to "mame", "mame2010" to "mame",
        "jaguar" to "atarijaguar",
        "a2600" to "atari2600", "a7800" to "atari7800",
        "wsc" to "wonderswancolor", "ws" to "wonderswan",
        "msx1" to "msx", "msx2" to "msx",
        "dos" to "msdos", "pcdos" to "msdos",
        "cdi" to "cdimono1",
    )

    fun normalize(name: String): String =
        name.trim().lowercase().filter { it.isLetterOrDigit() }

    private val byNormalized: Map<String, String> by lazy {
        val out = HashMap<String, String>()
        // Aliases first so registry folders (exact semantic names) win.
        out.putAll(ALIASES)
        PlatformRegistry.folders.forEach { out[normalize(it)] = it }
        out
    }

    /** Registry folder for a directory name, or null when unrecognized. */
    fun resolve(dirName: String): String? {
        if (dirName.startsWith(".")) return null
        val exact = dirName.trim().lowercase()
        if (PlatformRegistry.byFolder.containsKey(exact)) return exact
        return byNormalized[normalize(dirName)]
    }

    /** Visible for tests: raw alias table (normalized keys). */
    internal val table: Map<String, String> get() = ALIASES
}
