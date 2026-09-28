package com.gamebex.client.data

enum class Emu(val pkg: String, val label: String) {
    RETROARCH("com.retroarch.aarch64", "RetroArch"),
    PPSSPP("org.ppsspp.ppsspp", "PPSSPP"),
    MELONDS("me.magnum.melonds", "melonDS"),
    AZAHAR("org.azahar_emu.azahar", "Azahar"),
    DOLPHIN("org.dolphinemu.dolphinemu", "Dolphin"),
    NETHER("xyz.aethersx2.android", "NetherSX2"),
    ARMSX2("com.armsx2", "ARMSX2"),
    VITA3K("org.vita3k.emulator", "Vita3K"),
    SCUMMVM("org.scummvm.scummvm", "ScummVM"),
    MAME4DROID("com.seleuco.mame4d2024", "MAME4droid"),
    FLYCAST("com.flycast.emulator", "Flycast"),
    J2MELOADER("ru.playsoftware.j2meloader", "J2ME Loader"),
}

/** How a platform's games boot. */
sealed interface Route {
    data class Standalone(val emu: Emu) : Route
    /** RetroArch core .so base name (e.g. "gambatte" -> gambatte_libretro_android.so). */
    data class RetroCore(val core: String) : Route
    /** Install the emulator, then import inside it; no direct boot. */
    data class GuidedInstall(val emu: Emu) : Route
    /** The ROM files ARE installable Android apps (OUYA). */
    data object SideloadApp : Route
    /** No validated route: Play shows [reason], never a wrong emulator. */
    data class Blocked(val reason: String) : Route
}

data class Platform(
    val slug: String,
    val folder: String,
    val display: String,
    val extensions: Set<String>,
    val route: Route,
    /** Verified per-game alternates (P2 probes) offered by the detail screen. */
    val altEmus: List<Emu> = emptyList(),
)

/**
 * All 81 console slugs: folder, display name, accepted extensions
 * (from ES-DE's es_systems.xml, inspected) and launch route.
 * Extension sets always include zip/7z; preparation decides extraction later.
 */
object PlatformRegistry {
    /**
     * Hub-handled archive extensions beyond per-platform zip/7z, unioned
     * into DISC folders only (carts never boot these; arcade needs zips).
     */
    val HUB_ARCHIVE_EXTS = setOf(
        "rar", "tar", "tgz", "tbz", "txz", "gz", "bz2", "xz",
        "001", "z01", "r00",
    )

    /** Scanner gate: registry exts, plus hub archives on disc folders. */
    fun accepts(folder: String, ext: String): Boolean {
        val p = byFolder[folder] ?: return false
        if (ext in p.extensions) return true
        return folder in BiosTable.DISC_FOLDERS && ext in HUB_ARCHIVE_EXTS
    }

    val all: List<Platform> = listOf(
        Platform("32x", "sega32x", "Sega 32X", setOf("32x", "68k", "7z", "bin", "chd", "cue", "gen", "iso", "m3u", "md", "smd", "sms", "zip"), Route.RetroCore("picodrive")),
        Platform("3do", "3do", "3DO", setOf("7z", "bin", "chd", "cue", "iso", "zip"), Route.RetroCore("opera")),
        Platform("acorn-archimedes", "archimedes", "Acorn Archimedes", setOf("1dd", "360", "7z", "adf", "adl", "adm", "ads", "apd", "bbc", "chd", "cqi", "cqm", "d77", "d88", "dfi", "dsd", "dsk", "hfe", "ima", "imd", "img", "ipf", "jfd", "mfi", "mfm", "msa", "ssd", "st", "td0", "ufi", "zip"), Route.Blocked("MAME software-list route, post-v1")),
        Platform("amiga", "amiga", "Amiga", setOf("7z", "adf", "adz", "ccd", "chd", "cue", "dms", "fdi", "hdf", "hdz", "ipf", "iso", "lha", "m3u", "mds", "nrg", "rp9", "uae", "zip"), Route.RetroCore("puae")),
        Platform("apple-ii", "apple2", "Apple II", setOf("7z", "do", "dsk", "nib", "po", "zip"), Route.RetroCore("applewin")),
        Platform("apple-iigs", "apple2gs", "Apple IIGS", setOf("2mg", "7z", "zip"), Route.Blocked("MAME software-list route, post-v1")),
        Platform("atari-2600", "atari2600", "Atari 2600", setOf("7z", "a26", "bin", "zip"), Route.RetroCore("stella")),
        Platform("atari-7800", "atari7800", "Atari 7800", setOf("7z", "a78", "bin", "zip"), Route.RetroCore("prosystem")),
        Platform("atari-jaguar", "atarijaguar", "Atari Jaguar", setOf("7z", "abs", "bin", "cdi", "cof", "cue", "j64", "jag", "prg", "rom", "zip"), Route.RetroCore("virtualjaguar")),
        Platform("dragon", "dragon", "Dragon 32/64", setOf("7z", "dsk", "zip"), Route.Blocked("XRoar route unverified")),
        Platform("dreamcast", "dreamcast", "Dreamcast", setOf("7z", "cdi", "chd", "cue", "dat", "elf", "gdi", "iso", "lst", "m3u", "zip"), Route.RetroCore("flycast"), altEmus = listOf(Emu.FLYCAST)),
        Platform("fujitsu-fm-towns-marty", "fmtowns", "FM Towns Marty", setOf("7z", "cdr", "chd", "cue", "gdi", "iso", "zip"), Route.Blocked("MAME software-list route, post-v1")),
        Platform("game-boy", "gb", "Game Boy", setOf("7z", "bs", "cgb", "dmg", "gb", "gbc", "gbx", "sfc", "sgb", "smc", "zip"), Route.RetroCore("gambatte")),
        Platform("game-boy-advance", "gba", "Game Boy Advance", setOf("7z", "agb", "bin", "cgb", "dmg", "gb", "gba", "gbc", "gbx", "sgb", "zip"), Route.RetroCore("mgba")),
        Platform("game-boy-color", "gbc", "Game Boy Color", setOf("7z", "bs", "cgb", "dmg", "gb", "gbc", "gbx", "sfc", "sgb", "smc", "zip"), Route.RetroCore("gambatte")),
        Platform("gamecube", "gc", "GameCube", setOf("7z", "ciso", "dff", "dol", "elf", "gcm", "gcz", "iso", "json", "m3u", "rvz", "tgc", "wad", "wbfs", "wia", "zip"), Route.RetroCore("dolphin"), altEmus = listOf(Emu.DOLPHIN)),
        Platform("gamepark-gp32", "gp32", "GamePark GP32", setOf("7z", "bin", "fxe", "smd", "zip"), Route.Blocked("GeePee32 route unverified")),
        Platform("j2me", "j2me", "J2ME", setOf("7z", "jar", "zip"), Route.Standalone(Emu.J2MELOADER)),
        Platform("lindbergh", "lindbergh", "Sega Lindbergh", setOf("7z", "zip"), Route.Blocked("no Android route (PC-based arcade)")),
        Platform("mame", "mame", "MAME Arcade", setOf("7z", "cmd", "neo", "zip"), Route.RetroCore("fbneo"), altEmus = listOf(Emu.MAME4DROID)),
        Platform("mattel-hyperscan", "hyperscan", "Mattel HyperScan", setOf("7z", "zip"), Route.Blocked("no Android route")),
        Platform("microsoft-msx", "msx", "MSX", setOf("7z", "cas", "col", "di1", "di2", "dmk", "dsk", "fd1", "fd2", "m3u", "mx1", "mx2", "ogv", "ri", "rom", "sc", "sg", "wav", "xsa", "zip"), Route.RetroCore("bluemsx")),
        Platform("microsoft-msx2", "msx", "MSX2", setOf("7z", "cas", "col", "di1", "di2", "dmk", "dsk", "fd1", "fd2", "m3u", "mx1", "mx2", "ogv", "ri", "rom", "sc", "sg", "wav", "xsa", "zip"), Route.RetroCore("bluemsx")),
        Platform("ms-dos", "msdos", "MS-DOS", setOf("7z", "bat", "com", "conf", "cue", "dosz", "exe", "img", "iso", "zip"), Route.RetroCore("dosbox_pure")),
        Platform("mugen", "mugen", "MUGEN", setOf("7z", "zip"), Route.Blocked("no validated Android engine")),
        Platform("nec-pc-9801", "pc98", "PC-98", setOf("2hd", "7z", "88d", "98d", "cmd", "d88", "d98", "dup", "fdd", "fdi", "hdd", "hdi", "hdm", "hdn", "m3u", "nhd", "tfd", "thd", "xdf", "zip"), Route.RetroCore("np2kai")),
        Platform("nec-pc-fx", "pcfx", "PC-FX", setOf("7z", "ccd", "chd", "cue", "m3u", "toc", "zip"), Route.RetroCore("mednafen_pcfx")),
        Platform("neo-geo", "neogeo", "Neo Geo", setOf("7z", "neo", "zip"), Route.RetroCore("fbneo")),
        Platform("neo-geo-aes", "neogeo", "Neo Geo AES", setOf("7z", "neo", "zip"), Route.RetroCore("fbneo")),
        Platform("neo-geo-cd", "neogeocd", "Neo Geo CD", setOf("7z", "chd", "cue", "zip"), Route.RetroCore("neocd")),
        Platform("nes", "nes", "NES", setOf("3dsen", "7z", "fds", "nes", "unf", "unif", "zip"), Route.RetroCore("mesen")),
        Platform("nintendo-3ds", "n3ds", "Nintendo 3DS", setOf("3ds", "3dsx", "7z", "app", "axf", "cci", "cxi", "elf", "z3dsx", "zcci", "zcxi", "zip"), Route.RetroCore("citra"), altEmus = listOf(Emu.AZAHAR)),
        Platform("nintendo-64", "n64", "Nintendo 64", setOf("7z", "app", "bin", "d64", "n64", "ndd", "u1", "v64", "z64", "zip"), Route.RetroCore("mupen64plus_next_gles3")),
        Platform("nintendo-ds", "nds", "Nintendo DS", setOf("7z", "app", "bin", "dsi", "ids", "nds", "zip"), Route.RetroCore("melonds"), altEmus = listOf(Emu.MELONDS)),
        Platform("nintendo-pokemon-mini", "pokemini", "Pokemon Mini", setOf("7z", "min", "zip"), Route.RetroCore("pokemini")),
        Platform("nintendo-satellaview", "satellaview", "Satellaview", setOf("7z", "bml", "bs", "fig", "sfc", "smc", "st", "swc", "zip"), Route.RetroCore("snes9x")),
        Platform("nintendo-virtual-boy", "virtualboy", "Virtual Boy", setOf("7z", "bin", "vb", "vboy", "zip"), Route.RetroCore("mednafen_vb")),
        Platform("nintendo-wii", "wii", "Wii", setOf("7z", "ciso", "dff", "dol", "elf", "gcm", "gcz", "iso", "json", "m3u", "rvz", "tgc", "wad", "wbfs", "wia", "zip"), Route.RetroCore("dolphin"), altEmus = listOf(Emu.DOLPHIN)),
        Platform("nokia-n-gage", "ngage", "N-Gage", setOf("7z", "zip"), Route.Blocked("no validated Android route")),
        Platform("nuon", "nuon", "Nuon", setOf("7z", "iso", "zip"), Route.Blocked("no Android route")),
        Platform("openbor", "openbor", "OpenBOR", setOf("7z", "openbor", "zip"), Route.Blocked("Android port unverified")),
        Platform("other", "other", "Other", setOf("7z", "zip"), Route.Blocked("unclassified content")),
        Platform("ouya", "ouya", "OUYA", setOf("apk"), Route.SideloadApp),
        Platform("philips-cd-i", "cdimono1", "CD-i", setOf("7z", "chd", "cue", "iso", "zip"), Route.RetroCore("same_cdi")),
        Platform("pico-8", "pico8", "PICO-8", setOf("7z", "p8", "png", "zip"), Route.Blocked("fake08 route unverified")),
        Platform("playstation", "psx", "PlayStation", setOf("7z", "bin", "cbn", "ccd", "chd", "cue", "ecm", "exe", "img", "iso", "m3u", "mdf", "mds", "minipsf", "pbp", "psexe", "psf", "toc", "z", "zip", "znx"), Route.RetroCore("mednafen_psx")),
        // PS2 stays standalone: the lrps2 core segfaults on the tested device, with no
        // content and with a valid ISO + BIOS. NetherSX2 opens for a manual pick (no direct-boot API).
        Platform("playstation-2", "ps2", "PlayStation 2", setOf("7z", "bin", "chd", "ciso", "cso", "dump", "elf", "gz", "img", "iso", "isz", "m3u", "mdf", "ngr", "nrg", "zip"), Route.Standalone(Emu.NETHER), altEmus = listOf(Emu.ARMSX2)),
        Platform("playstation-4", "ps4", "PlayStation 4", setOf("7z", "ps4", "zip"), Route.Blocked("no validated Android emulator (never PSX emu)")),
        Platform("playstation-portable", "psp", "PSP", setOf("7z", "chd", "cso", "elf", "iso", "pbp", "prx", "zip"), Route.RetroCore("ppsspp"), altEmus = listOf(Emu.PPSSPP)),
        Platform("ps-vita", "psvita", "PS Vita", setOf("7z", "psvita", "zip"), Route.GuidedInstall(Emu.VITA3K)),
        Platform("ps3", "ps3", "PlayStation 3", setOf("7z", "iso", "ps3", "zip"), Route.Blocked("no validated Android emulator")),
        Platform("scummvm", "scummvm", "ScummVM", setOf("7z", "scummvm", "svm", "zip"), Route.Standalone(Emu.SCUMMVM)),
        Platform("sega-cd", "segacd", "Sega CD", setOf("68k", "7z", "bin", "bms", "chd", "cue", "gen", "gg", "iso", "m3u", "md", "mdx", "sg", "sgd", "smd", "sms", "zip"), Route.RetroCore("genesis_plus_gx")),
        Platform("sega-game-gear", "gamegear", "Game Gear", setOf("68k", "7z", "bin", "bms", "chd", "col", "cue", "gen", "gg", "iso", "m3u", "md", "mdx", "rom", "sg", "sgd", "smd", "sms", "zip"), Route.RetroCore("genesis_plus_gx")),
        Platform("sega-genesis", "genesis", "Genesis", setOf("32x", "68k", "7z", "bin", "bms", "chd", "cue", "gen", "gg", "iso", "m3u", "md", "mdx", "sg", "sgd", "smd", "sms", "zip"), Route.RetroCore("genesis_plus_gx")),
        Platform("sega-master-system", "mastersystem", "Master System", setOf("68k", "7z", "bin", "bms", "chd", "col", "cue", "gen", "gg", "iso", "m3u", "md", "mdx", "rom", "sg", "sgd", "smd", "sms", "zip"), Route.RetroCore("genesis_plus_gx")),
        Platform("sega-naomi", "naomi", "NAOMI", setOf("7z", "bin", "dat", "elf", "lst", "zip"), Route.RetroCore("flycast"), altEmus = listOf(Emu.FLYCAST)),
        Platform("sega-pico", "pico", "Sega Pico", setOf("7z", "bin", "md", "zip"), Route.RetroCore("picodrive")),
        Platform("sega-ringedge", "ringedge", "RingEdge", setOf("7z", "zip"), Route.Blocked("no Android route (PC-based arcade)")),
        Platform("sega-ringedge-2", "ringedge2", "RingEdge 2", setOf("7z", "zip"), Route.Blocked("no Android route (PC-based arcade)")),
        Platform("sega-saturn", "saturn", "Saturn", setOf("7z", "bin", "ccd", "chd", "cue", "iso", "m3u", "mds", "toc", "zip"), Route.RetroCore("mednafen_saturn")),
        Platform("sega-sg-1000", "sg-1000", "SG-1000", setOf("68k", "7z", "bin", "bms", "chd", "cue", "gen", "gg", "iso", "m3u", "md", "mdx", "ri", "rom", "sg", "sgd", "smd", "sms", "zip"), Route.RetroCore("genesis_plus_gx")),
        Platform("sharp-x1", "x1", "Sharp X1", setOf("2d", "2hd", "7z", "88d", "cmd", "d88", "dup", "dx1", "hdm", "tap", "tfd", "xdf", "zip"), Route.RetroCore("x1")),
        Platform("sharp-x68000", "x68000", "X68000", setOf("2hd", "7z", "88d", "cmd", "d88", "dim", "dup", "hdf", "hdm", "img", "m3u", "xdf", "zip"), Route.RetroCore("px68k")),
        Platform("snk-neo-geo-pocket-color", "ngpc", "Neo Geo Pocket", setOf("7z", "ngc", "ngp", "ngpc", "npc", "zip"), Route.RetroCore("mednafen_ngp")),
        Platform("super-nintendo", "snes", "SNES", setOf("7z", "bin", "bml", "bs", "bsx", "dx2", "fig", "gd3", "gd7", "mgd", "sfc", "smc", "st", "swc", "zip"), Route.RetroCore("snes9x")),
        Platform("switch", "switch", "Switch", setOf("7z", "nca", "nro", "nso", "nsp", "xci", "zip"), Route.Blocked("Eden route unverified, post-v1")),
        Platform("taito-type-x", "taito-type-x", "Taito Type X", setOf("7z", "zip"), Route.Blocked("no Android route (PC-based arcade)")),
        Platform("teknoparrot", "teknoparrot", "TeknoParrot", setOf("7z", "zip"), Route.Blocked("PC-only, no Android route")),
        Platform("turbografx", "tg16", "TurboGrafx-16", setOf("7z", "ccd", "chd", "cue", "img", "iso", "m3u", "pce", "rom", "sgx", "toc", "zip"), Route.RetroCore("mednafen_pce")),
        Platform("turbografx-cd", "tg-cd", "TurboGrafx-CD", setOf("7z", "ccd", "chd", "cue", "img", "iso", "m3u", "pce", "sgx", "toc", "zip"), Route.RetroCore("mednafen_pce")),
        Platform("uncategorized", "uncategorized", "Uncategorized", setOf("7z", "zip"), Route.Blocked("unclassified content")),
        Platform("wii-u", "wiiu", "Wii U", setOf("7z", "elf", "rpx", "tmd", "wua", "wud", "wuhb", "wux", "zip"), Route.Blocked("no validated Android emulator")),
        Platform("windows", "windows", "Windows", setOf("7z", "exe", "zip"), Route.Blocked("Winlator route post-v1")),
        Platform("windows-3x", "windows3x", "Windows 3.x", setOf("7z", "bat", "dosz", "zip"), Route.RetroCore("dosbox_pure")),
        Platform("windows-9x", "win9x", "Windows 9x", setOf("7z", "iso", "zip"), Route.Blocked("Winlator route post-v1")),
        Platform("wonderswan", "wonderswan", "WonderSwan", setOf("7z", "pc2", "ws", "zip"), Route.RetroCore("mednafen_wswan")),
        Platform("wonderswan-color", "wonderswancolor", "WonderSwan Color", setOf("7z", "pc2", "ws", "wsc", "zip"), Route.RetroCore("mednafen_wswan")),
        Platform("xbox", "xbox", "Xbox", setOf("7z", "iso", "xiso", "zip"), Route.Blocked("no validated Android emulator")),
        Platform("xbox-360", "xbox360", "Xbox 360", setOf("", "7z", "iso", "xex", "zar", "zip"), Route.Blocked("no validated Android emulator")),
        Platform("xbox-one", "xboxone", "Xbox One", setOf("iso", "xex"), Route.Blocked("no validated Android emulator")),
    )

    val bySlug: Map<String, Platform> = all.associateBy { it.slug }
    val byFolder: Map<String, Platform> = all.associateBy { it.folder }
    val folders: Set<String> = all.map { it.folder }.toSortedSet()

}
