package com.gamebex.client.together

/** Emulator behaviour confirmed on real devices; change a value only after re-testing on hardware. */
object TogetherCaps {
    /** RA_NETPLAY core allowlist; a core that fails its on-device netplay test is removed. */
    val RA_NETPLAY_CORES: Set<String> =
        setOf("snes9x", "genesis_plus_gx", "fbneo", "mesen", "mednafen_pce")

    /** CoreSideloadActivity installs a core when the ROM extra is absent. */
    const val SIDELOAD_WITHOUT_ROM: Boolean = true

    /** A running RetroArch ignores a new launch's CONFIGFILE, so RA modes need Shizuku to stop it first. */
    const val RA_RESTART_NEEDS_SHIZUKU: Boolean = true

    /** The host room hotkey (Android keycode; 103 = R1), held with SELECT (109). */
    const val RA_HOST_TOGGLE_BTN: Int = 103

    /** Seconds between the host's GB launch and the guest's. */
    const val GB_HOST_HEADSTART_S: Int = 8

    /**
     * RetroArch builds before this (versionCode is the build time in Unix seconds, here 2026-07-28 UTC)
     * crash as the netplay client on the first savestate; upstream fix 5fa3573d88 landed 2026-07-27.
     */
    const val RA_NETPLAY_MIN_VERSION_CODE: Long = 1785196800L

    /** How long the guest watches for the host's RetroArch room. */
    const val LIVE_WATCH_S: Int = 180

    /**
     * Native modes verified on two phones, by PlatformRegistry folder, best first; they rank above STREAM.
     * A folder is added only after its device test passes (e.g. "psx" to listOf(TogetherMode.RA_NETPLAY),
     * with the core in [RA_NETPLAY_CORES]); until then Stream is that folder's mode.
     */
    val VERIFIED_NATIVE: Map<String, List<TogetherMode>> = emptyMap()
}
