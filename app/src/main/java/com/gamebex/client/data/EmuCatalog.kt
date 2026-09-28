package com.gamebex.client.data

/** Emulator install sources, preferring F-Droid, then official direct downloads, then guided.
 *  Every direct entry pins URL, sha256, version and signer cert sha256. Only pinned releases
 *  install; newer upstream releases are only displayed. */
object EmuCatalog {

    sealed interface Origin {
        data class FDroid(val url: String) : Origin
        /** Upstream official site / GitHub release asset. */
        data class Direct(val url: String, val pageUrl: String) : Origin
        /** No direct APK channel: guide the user (Play listing + site). */
        data class Guided(val playUrl: String, val siteUrl: String) : Origin
    }

    /** How to discover upstream-newer releases (display-only, 24h cache). */
    sealed interface UpdateCheck {
        data class Github(val apiLatest: String) : UpdateCheck
        data class FDroidApi(val pkg: String) : UpdateCheck
        data object None : UpdateCheck
    }

    data class Entry(
        val emu: Emu,
        /** Human "plays X, Y" line for the Emulators tab. */
        val systems: String,
        val origin: Origin,
        /** Null for Guided entries (nothing pinned). */
        val pinnedSha256: String? = null,
        val pinnedVersionCode: Long = 0,
        val pinnedVersionName: String? = null,
        /** Signer #1 cert SHA-256 (lowercase hex, no colons). */
        val pinnedSignerSha256: String? = null,
        val updateCheck: UpdateCheck = UpdateCheck.None,
    )

    val entries: List<Entry> = listOf(
        // A nightly: stable 1.22.2's netplay client crashes on the first savestate (fixed upstream
        // in 5fa3573d88). The buildbot drops dated nightlies after about two weeks; see LocalApks.
        Entry(
            Emu.RETROARCH, "GB/GBC/GBA/SNES/NES/arcade + 40 cores",
            Origin.Direct(
                "https://buildbot.libretro.com/nightly/android/2026-09-27-RetroArch_aarch64.apk",
                "https://www.retroarch.com/",
            ),
            pinnedSha256 = "64472da5bdfcacd541f58d0f539909bc51e514fa264ce6cbd55fb6cce5b00d03",
            pinnedVersionCode = 1790521311,
            pinnedVersionName = "1.22.2_GIT",
            pinnedSignerSha256 = "d7a5eab30902c9b2775af6fb4eac2d294a64634fb47dd1382bc3ac5bf669982e",
        ),
        Entry(
            Emu.PPSSPP, "PlayStation Portable",
            Origin.FDroid("https://f-droid.org/repo/org.ppsspp.ppsspp_119030000.apk"),
            pinnedSha256 = "44e1ecb9268702fb65adcee14cffb6cdc3607e87abcf409004c9bdb6b2543f6c",
            pinnedVersionCode = 119030000,
            pinnedVersionName = "1.19.3",
            pinnedSignerSha256 = "19ed8afe0520d512bd1d9a780131d486edb7e384614b5e43af9ad8c688b2e100",
            updateCheck = UpdateCheck.FDroidApi("org.ppsspp.ppsspp"),
        ),
        Entry(
            Emu.MELONDS, "Nintendo DS",
            Origin.Direct(
                "https://github.com/rafaelvcaetano/melonDS-android/releases/download/2.0.1/app-gitHub-prod-release.apk",
                "https://github.com/rafaelvcaetano/melonDS-android/releases",
            ),
            pinnedSha256 = "646eb4564c71b0817ace6474e92c169da16630d58826c43e0fe648896311ec94",
            pinnedVersionCode = 40,
            pinnedVersionName = "2.0.1 GH",
            pinnedSignerSha256 = "0493bb8749289f0304cc868489dd68b930c19ccc4dce2da876bfe6b9bdbabebc",
            updateCheck = UpdateCheck.Github("https://api.github.com/repos/rafaelvcaetano/melonDS-android/releases/latest"),
        ),
        Entry(
            Emu.AZAHAR, "Nintendo 3DS",
            Origin.Direct(
                "https://github.com/azahar-emu/azahar/releases/download/2126.1.1/azahar-android-vanilla-2126.1.1.apk",
                "https://github.com/azahar-emu/azahar/releases",
            ),
            pinnedSha256 = "2b1cebdb94c3da644d71c59e534acd91b5bd90352386acfc72f9837f32a273f8",
            pinnedVersionCode = 33755511,
            pinnedVersionName = "2126.1.1-vanilla",
            pinnedSignerSha256 = "5ba2563e0d13fae2aba067d1c230cd1c863a93005c10d648ce5b331792aa0af1",
            updateCheck = UpdateCheck.Github("https://api.github.com/repos/azahar-emu/azahar/releases/latest"),
        ),
        Entry(
            Emu.DOLPHIN, "GameCube / Wii",
            Origin.Guided(
                "https://play.google.com/store/apps/details?id=org.dolphinemu.dolphinemu",
                "https://dolphin-emu.org/download/",
            ),
        ),
        Entry(
            Emu.NETHER, "PlayStation 2",
            Origin.Direct(
                "https://github.com/Trixarian/NetherSX2-classic/releases/download/2.2n/NetherSX2-v2.2n-3668.apk",
                "https://github.com/Trixarian/NetherSX2-classic/releases",
            ),
            pinnedSha256 = "8b502b31ce797d5a162bc6b9300b5f02351b0b6b290b8dc1390b8dfb826673ab",
            pinnedVersionCode = 15269,
            pinnedVersionName = "v2.2n-3668",
            pinnedSignerSha256 = "9bf3772a94603282fb13c64ea8771d66817382f6aca9923e8a178e7219340296",
            updateCheck = UpdateCheck.Github("https://api.github.com/repos/Trixarian/NetherSX2-classic/releases/latest"),
        ),
        Entry(
            // Key rotation (v3.1): Android 13+ verifies the armsx_release
            // key pinned here; 11–12 would see the old debug key, so the
            // pin refuses rather than trusting it there.
            Emu.ARMSX2, "PlayStation 2 (alternative, newer PCSX2 core)",
            Origin.Direct(
                "https://github.com/ARMSX2/ARMSX2/releases/download/2.7/ARMSX2-2.7-a11-armv8.2-sdk30.apk",
                "https://github.com/ARMSX2/ARMSX2/releases",
            ),
            pinnedSha256 = "89087b150b58e5920bbe4b18145ef82790edaa19db306ac8ce1dd0b33fc9ff0a",
            pinnedVersionCode = 1640,
            pinnedVersionName = "2.7",
            pinnedSignerSha256 = "df91c9ce39d3f53c9bab5f2c1e0820e761800c93f1805305b87e87486d244d5a",
            updateCheck = UpdateCheck.Github("https://api.github.com/repos/ARMSX2/ARMSX2/releases/latest"),
        ),
        Entry(
            Emu.VITA3K, "PS Vita (import inside it)",
            Origin.Direct(
                "https://github.com/Vita3K/Vita3K/releases/download/continuous/android-latest.apk",
                "https://github.com/Vita3K/Vita3K/releases",
            ),
            pinnedSha256 = "2ba3f92e4cfb4c9c6a5a398a35c604abf50f457e3793ba541e61adeb89030a33",
            pinnedVersionCode = 21,
            pinnedVersionName = "0.2.1",
            pinnedSignerSha256 = "eadeff93b5fb0317051111f77d56abbaa0e6716cc847969926aa36dac4beb202",
            // "continuous" is a moving tag: an upstream rebuild fails the hash check
            // instead of installing silently.
        ),
        Entry(
            Emu.SCUMMVM, "Point-and-click adventures",
            Origin.Direct(
                "https://downloads.scummvm.org/frs/scummvm/2026.3.0/scummvm-2026.3.0-android-arm64-v8a.apk",
                "https://www.scummvm.org/downloads/",
            ),
            pinnedSha256 = "26e25a59496eb94a4dc76f282bb3234d6d36f7f393c3e699e0a62e1a5c3e412b",
            pinnedVersionCode = 26030002,
            pinnedVersionName = "2026.3.0",
            pinnedSignerSha256 = "c59c0677e9835b720812c08ccec8d4c65c4b4718ffc9109a8d9d058d7223f995",
        ),
        Entry(
            Emu.MAME4DROID, "Arcade alternate (pick game inside)",
            Origin.Direct(
                "https://github.com/seleuco/MAME4droid-Current/releases/download/v1.41/MAME4droid.2026-1.41-release.apk",
                "https://github.com/seleuco/MAME4droid-Current/releases",
            ),
            pinnedSha256 = "f1d744c583ed1c30bbbb1d14f15eed00d3ac701248dbff1a08aa76a1b99b5549",
            pinnedVersionCode = 143,
            pinnedVersionName = "1.41",
            pinnedSignerSha256 = "218b28a42dba410d29adfd2233041032f521fbfb44b3d075dce406b135ab3077",
            updateCheck = UpdateCheck.Github("https://api.github.com/repos/seleuco/MAME4droid-Current/releases/latest"),
        ),
        Entry(
            Emu.FLYCAST, "Dreamcast / NAOMI",
            Origin.Direct(
                "https://github.com/flyinghead/flycast/releases/download/v2.7/flycast-2.7.apk",
                "https://github.com/flyinghead/flycast/releases",
            ),
            pinnedSha256 = "1ac54d77b8283f6d58d077396d0916ec12c15b7e4ada9d957836a64590756c00",
            pinnedVersionCode = 3302,
            pinnedVersionName = "v2.7-1-g44e4c7b50",
            pinnedSignerSha256 = "0774794c54c7e463004754e2caba432a2057d917e64c5e24460606a7471b1395",
            updateCheck = UpdateCheck.Github("https://api.github.com/repos/flyinghead/flycast/releases/latest"),
        ),
        Entry(
            Emu.J2MELOADER, "J2ME (.jar) games",
            Origin.FDroid("https://f-droid.org/repo/ru.playsoftware.j2meloader_101.apk"),
            pinnedSha256 = "723133c8a3737e397b3257a9d05f95e7a6fb98db22c8e421138183c60eeb310c",
            pinnedVersionCode = 101,
            pinnedVersionName = "1.8.2",
            pinnedSignerSha256 = "e50809b534faee8d6d8a4eff20a298a5e51347a39d1d46413dc8b66e764661b4",
            updateCheck = UpdateCheck.FDroidApi("ru.playsoftware.j2meloader"),
        ),
    )

    val byPkg: Map<String, Entry> = entries.associateBy { it.emu.pkg }
}
