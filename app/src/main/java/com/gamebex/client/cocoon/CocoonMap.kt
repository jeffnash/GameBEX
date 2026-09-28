package com.gamebex.client.cocoon

/**
 * Cocoon Shell folder names (from https://cocoon-shell.com/wiki/compatibility/)
 * and the Play-store emulator Cocoon launches for each platform.
 */
object CocoonMap {

    data class Platform(
        /** Cocoon platform folder, e.g. "gba". */
        val folder: String,
        /** Play-store emulator Cocoon launches for this platform. */
        val playerPackage: String,
        val playerName: String,
    )

    const val ROM_ROOT = "GameBEX"

    /** Console slug -> Cocoon platform (aliases + player map). */
    val byConsoleSlug: Map<String, Platform> = mapOf(
        "game-boy" to Platform("gb", "com.swordfish.lemuroid", "Lemuroid"),
        "game-boy-color" to Platform("gbc", "com.swordfish.lemuroid", "Lemuroid"),
        "game-boy-advance" to Platform("gba", "com.swordfish.lemuroid", "Lemuroid"),
        "nes" to Platform("nes", "com.swordfish.lemuroid", "Lemuroid"),
        "super-nintendo" to Platform("snes", "com.swordfish.lemuroid", "Lemuroid"),
        "nintendo-64" to Platform("n64", "com.swordfish.lemuroid", "Lemuroid"),
        "gamecube" to Platform("gc", "org.dolphinemu.dolphinemu", "Dolphin"),
        "nintendo-wii" to Platform("wii", "org.dolphinemu.dolphinemu", "Dolphin"),
        "wii-u" to Platform("wiiu", "com.swordfish.lemuroid", "Lemuroid"),
        "nintendo-ds" to Platform("nds", "me.magnum.melonds", "melonDS"),
        "nintendo-3ds" to Platform("n3ds", "io.github.lime3ds.android", "Azahar"),
        "nintendo-pokemon-mini" to Platform("pokemini", "com.swordfish.lemuroid", "Lemuroid"),
        "nintendo-virtual-boy" to Platform("virtualboy", "com.swordfish.lemuroid", "Lemuroid"),
        "playstation" to Platform("psx", "com.github.stenzek.duckstation", "DuckStation"),
        "playstation-portable" to Platform("psp", "org.ppsspp.ppsspp", "PPSSPP"),
        "playstation-2" to Platform("ps2", "xyz.aethersx2.android", "NetherSX2"),
        "ps3" to Platform("ps3", "com.swordfish.lemuroid", "Lemuroid"),
        "playstation-4" to Platform("psx", "com.github.stenzek.duckstation", "DuckStation"),
        "ps-vita" to Platform("psvita", "org.vita3k.emulator", "Vita3K"),
        "sega-genesis" to Platform("genesis", "com.swordfish.lemuroid", "Lemuroid"),
        "sega-master-system" to Platform("mastersystem", "com.swordfish.lemuroid", "Lemuroid"),
        "sega-game-gear" to Platform("gamegear", "com.swordfish.lemuroid", "Lemuroid"),
        "sega-sg-1000" to Platform("sg-1000", "com.swordfish.lemuroid", "Lemuroid"),
        "32x" to Platform("sega32x", "com.retroarch.aarch64", "RetroArch Plus"),
        "sega-cd" to Platform("segacd", "com.retroarch.aarch64", "RetroArch Plus"),
        "sega-saturn" to Platform("saturn", "com.retroarch.aarch64", "RetroArch Plus"),
        "dreamcast" to Platform("dreamcast", "com.retroarch.aarch64", "RetroArch Plus"),
        "sega-pico" to Platform("pico", "com.retroarch.aarch64", "RetroArch Plus"),
        "sega-naomi" to Platform("naomi", "com.retroarch.aarch64", "RetroArch Plus"),
        "lindbergh" to Platform("naomi", "com.retroarch.aarch64", "RetroArch Plus"),
        "sega-ringedge" to Platform("naomi", "com.retroarch.aarch64", "RetroArch Plus"),
        "sega-ringedge-2" to Platform("naomi", "com.retroarch.aarch64", "RetroArch Plus"),
        "xbox" to Platform("xbox", "com.retroarch.aarch64", "RetroArch Plus"),
        "xbox-360" to Platform("xbox360", "com.retroarch.aarch64", "RetroArch Plus"),
        "amiga" to Platform("amiga", "com.retroarch.aarch64", "RetroArch Plus"),
        "microsoft-msx" to Platform("msx", "com.retroarch.aarch64", "RetroArch Plus"),
        "microsoft-msx2" to Platform("msx", "com.retroarch.aarch64", "RetroArch Plus"),
        "nec-pc-9801" to Platform("pc98", "com.retroarch.aarch64", "RetroArch Plus"),
        "nec-pc-fx" to Platform("pcfx", "com.retroarch.aarch64", "RetroArch Plus"),
        "turbografx" to Platform("tg16", "com.swordfish.lemuroid", "Lemuroid"),
        "turbografx-cd" to Platform("tg-cd", "com.retroarch.aarch64", "RetroArch Plus"),
        "sharp-x1" to Platform("x1", "com.retroarch.aarch64", "RetroArch Plus"),
        "sharp-x68000" to Platform("x68000", "com.retroarch.aarch64", "RetroArch Plus"),
        "atari-2600" to Platform("atari2600", "com.swordfish.lemuroid", "Lemuroid"),
        "atari-7800" to Platform("atari7800", "com.swordfish.lemuroid", "Lemuroid"),
        "atari-jaguar" to Platform("atarijaguar", "com.retroarch.aarch64", "RetroArch Plus"),
        "mame" to Platform("mame", "com.swordfish.lemuroid", "Lemuroid"),
        "neo-geo" to Platform("neogeo", "com.swordfish.lemuroid", "Lemuroid"),
        "neo-geo-aes" to Platform("neogeo", "com.swordfish.lemuroid", "Lemuroid"),
        "neo-geo-cd" to Platform("neogeocd", "com.swordfish.lemuroid", "Lemuroid"),
        "snk-neo-geo-pocket-color" to Platform("ngpc", "com.swordfish.lemuroid", "Lemuroid"),
        "nokia-n-gage" to Platform("ngage", "com.retroarch.aarch64", "RetroArch Plus"),
        "3do" to Platform("3do", "com.retroarch.aarch64", "RetroArch Plus"),
        "philips-cd-i" to Platform("cdimono1", "com.retroarch.aarch64", "RetroArch Plus"),
        "fujitsu-fm-towns-marty" to Platform("fmtowns", "com.retroarch.aarch64", "RetroArch Plus"),
        "scummvm" to Platform("scummvm", "org.scummvm.scummvm", "ScummVM"),
        "openbor" to Platform("ports", "com.retroarch.aarch64", "RetroArch Plus"),
        "mugen" to Platform("ports", "com.retroarch.aarch64", "RetroArch Plus"),
        "teknoparrot" to Platform("ports", "com.retroarch.aarch64", "RetroArch Plus"),
        "gamepark-gp32" to Platform("ports", "com.retroarch.aarch64", "RetroArch Plus"),
        "ouya" to Platform("ports", "com.retroarch.aarch64", "RetroArch Plus"),
        "j2me" to Platform("j2me", "com.retroarch.aarch64", "RetroArch Plus"),
        "windows" to Platform("windows", "com.retroarch.aarch64", "RetroArch Plus"),
        "windows-3x" to Platform("windows", "com.retroarch.aarch64", "RetroArch Plus"),
    )
}
