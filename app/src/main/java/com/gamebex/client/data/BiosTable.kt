package com.gamebex.client.data

/**
 * Which firmware each system needs, where it goes, and how to verify it.
 * Keyed by library folder slug.
 *
 * MD5s are pinned only where canonical (mismatch = corrupt or foreign file).
 * Everything else verifies by name (+size where stable) and reports
 * PRESENT_UNVERIFIED. Pins also cross-check RetroBIOS downloads (BiosFetcher):
 * a pinned md5 that matches no upstream dump fails loudly.
 */
object BiosTable {
    /** One firmware file. sizeBytes/md5 null = don't verify that axis. */
    data class BiosFile(
        val name: String,
        val sizeBytes: Long? = null,
        val md5: String? = null,
        val note: String = "",
    )

    /**
     * A need: all [files] required, unless [anyOf] (one suffices, e.g.
     * regional PSX BIOS). [dirHint] is the placement guide shown to the
     * user; [required]=false means games still boot without it.
     */
    data class BiosNeed(
        val files: List<BiosFile>,
        val anyOf: Boolean = false,
        val required: Boolean = true,
        val dirHint: String = "RetroArch/system",
        val note: String = "",
    )

    /** Canonical RetroArch system dir, plus the per-app fallback. */
    const val RA_SYSTEM_PUBLIC = "RetroArch/system (internal storage)"
    const val RA_SYSTEM_PRIVATE = "Android/data/com.retroarch/files/system"

    /**
     * Shared staging for standalone-app firmware (relative to internal storage).
     * Standalone emulators can't read GameBEX-private storage and their own
     * folders aren't visible to GameBEX, but both sides can see this dir, so
     * staged files count toward readiness.
     */
    fun stagingRelPath(folder: String) = "GameBEX/bios/$folder"

    val TABLE: Map<String, List<BiosNeed>> = mapOf(
        "gba" to listOf(
            BiosNeed(
                files = listOf(
                    BiosFile("gba_bios.bin", 16384, "a860e8c0b6d573d191e4ec7db1b1e4f6"),
                ),
                required = false,
                note = "mGBA boots without it; some cores need it",
            ),
        ),
        "gb" to listOf(
            BiosNeed(
                files = listOf(
                    // Gambatte filename (route default); SameBoy calls it dmg_boot.bin.
                    BiosFile("gb_bios.bin", 256, "32fbbd84168d3482956eb3c5051637f5"),
                ),
                required = false,
                note = "boot animation only",
            ),
        ),
        "gbc" to listOf(
            BiosNeed(
                files = listOf(
                    // Gambatte filename (route default); SameBoy calls it cgb_boot.bin.
                    BiosFile("gbc_bios.bin", 2304, "dbfce9db9deaa2567f6a84fde55f9680"),
                ),
                required = false,
                note = "boot animation only",
            ),
        ),
        "psx" to listOf(
            BiosNeed(
                files = listOf(
                    BiosFile("scph5500.bin", 524288, "8dd7d5296a650fac7319bce665a6a53c", "JP"),
                    BiosFile("scph5501.bin", 524288, "490f666e1afb15b7362b406ed1cea246", "US"),
                    BiosFile("scph5502.bin", 524288, "32736f17079d0b2b7024407c39bd3050", "EU"),
                ),
                anyOf = true,
                note = "one regional BIOS; DuckStation can also use OpenBIOS (HLE)",
            ),
        ),
        "segacd" to listOf(
            BiosNeed(
                files = listOf(
                    // md5s match RetroArch's revision-pinned declarations.
                    BiosFile("bios_CD_U.bin", 131072, "2efd74e3232ff260e371b99f84024f7f", "US"),
                    BiosFile("bios_CD_J.bin", 131072, "278a9397d192149e84e820ac621a8edd", "JP"),
                    BiosFile("bios_CD_E.bin", 131072, "e66fa1dc5820d254611fdcdba0662372", "EU"),
                ),
                anyOf = true,
                note = "match the BIOS region to the disc region",
            ),
        ),
        "saturn" to listOf(
            BiosNeed(
                files = listOf(
                    // Fixed mask ROMs; md5s per RetroArch declarations
                    // (sega_101 is the JP dump, mpr-17933 US/EU).
                    BiosFile("sega_101.bin", 524288, "85ec9ca47d8f6807718151cbcca8b964", "JP"),
                    BiosFile("mpr-17933.bin", 524288, "3240872c70984b6cbfda1586cab68dbe", "US/EU"),
                ),
                anyOf = true,
                note = "some cores also accept saturn_bios.bin",
            ),
        ),
        "dreamcast" to listOf(
            BiosNeed(
                files = listOf(
                    BiosFile("dc_boot.bin", 2097152, "e10c53c2f8b90bab96ead2d368858623"),
                ),
                dirHint = "RetroArch/system/dc",
            ),
            BiosNeed(
                files = listOf(
                    BiosFile("dc_flash.bin", 131072, note = "writable settings flash; content varies"),
                ),
                dirHint = "RetroArch/system/dc",
                note = "goes next to dc_boot.bin",
            ),
        ),
        "neogeo" to listOf(
            BiosNeed(
                files = listOf(
                    BiosFile("neogeo.zip", note = "from a romset matching your core version"),
                ),
                dirHint = "the neogeo ROM folder (not system/)",
            ),
        ),
        "neogeocd" to listOf(
            BiosNeed(
                files = listOf(
                    BiosFile("neocdz.zip", note = "NeoGeo CD BIOS zip"),
                ),
            ),
        ),
        "tg-cd" to listOf(
            BiosNeed(
                files = listOf(
                    BiosFile("syscard3.pce", 262144, "38179df8f4ac870017db21ebcbf53114", "Super System Card 3"),
                ),
                note = "CD games only; HuCard games don't need it",
            ),
        ),
        "nds" to listOf(
            BiosNeed(
                files = listOf(
                    // bios7/9 are fixed mask ROMs; firmware stays unpinned.
                    BiosFile("bios7.bin", 16384, "df692a80a5b1bc90728bc3dfc76cd948"),
                    BiosFile("bios9.bin", 4096, "a392174eb3e572fed6447e956bde4b25"),
                    BiosFile("firmware.bin", note = "dump varies; NDS firmware"),
                ),
                dirHint = "RetroArch/system",
                note = "melonDS core reads the system root",
            ),
        ),
        "ps2" to listOf(
            // NetherSX2 route (lrps2 segfaults on-device): downloads stage
            // to GameBEX/bios/ps2 and the user points the app at it.
            // NetherSX2 ships its own game DB (no GameIndex.yaml need).
            BiosNeed(
                files = listOf(
                    BiosFile("scph39001.bin", note = "or any scph3xxxx/scph7xxxx dump"),
                    BiosFile("scph77001.bin", note = "or any scph3xxxx/scph7xxxx dump"),
                ),
                anyOf = true,
                dirHint = "NetherSX2 bios folder (pick in the app)",
                note = "NetherSX2 reads any valid dump (content-checked)",
            ),
        ),
        "switch" to listOf(
            BiosNeed(
                files = listOf(
                    BiosFile("prod.keys", note = "dump from your Switch"),
                ),
                dirHint = "emulator keys folder (pick in the app)",
            ),
        ),
        "wiiu" to listOf(
            BiosNeed(
                files = listOf(
                    BiosFile("otp.bin", 1024),
                    BiosFile("seeprom.bin", 512),
                ),
                dirHint = "Cemu mlc01 folder",
                note = "dump both from your Wii U",
            ),
        ),
        "gc" to listOf(
            BiosNeed(
                files = listOf(
                    BiosFile("IPL.bin", note = "GameCube IPL; pretty boot only"),
                ),
                required = false,
                dirHint = "Dolphin GC folder (pick in the app)",
            ),
        ),
    )

    /** Folders whose games launch from cue/m3u/gdi/iso rather than the raw file. */
    val DISC_FOLDERS = setOf(
        "psx", "segacd", "saturn", "dreamcast", "neogeocd", "tg-cd",
        "3do", "atarijaguar", "gc", "wii", "wiiu", "ps2", "psp", "switch",
    )

    /** Folders that must stay zipped with exact shortnames. */
    val ARCADE_FOLDERS = setOf("mame", "neogeo", "naomi")
}
