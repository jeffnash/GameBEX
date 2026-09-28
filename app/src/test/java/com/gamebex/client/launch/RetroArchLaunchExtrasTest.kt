package com.gamebex.client.launch

import org.junit.Assert.assertEquals
import org.junit.Test

/** GameBEX launches RetroArch with the extras RetroArch's own launcher passes. */
class RetroArchLaunchExtrasTest {
    @Test fun extrasMatchRetroArchsLauncher() {
        val paths = EmuRouter.RaPaths(
            dataDir = "/data/user/0/com.retroarch.aarch64",
            apk = "/data/app/ra/base.apk",
            sdcard = "/storage/emulated/0",
            ime = "com.google.android.inputmethod.latin/.LatinIME",
        )
        assertEquals(
            mapOf(
                "ROM" to "/storage/1D20-0000/GameBEX/snes/Game.zip",
                "LIBRETRO" to "/data/data/com.retroarch.aarch64/cores/snes9x_libretro_android.so",
                "CONFIGFILE" to "/storage/emulated/0/GameBEX/ra/retroarch-frontend.cfg",
                "IME" to "com.google.android.inputmethod.latin/.LatinIME",
                "DATADIR" to "/data/user/0/com.retroarch.aarch64",
                "APK" to "/data/app/ra/base.apk",
                "SDCARD" to "/storage/emulated/0",
                "EXTERNAL" to "/storage/emulated/0/Android/data/com.retroarch.aarch64/files",
            ),
            EmuRouter.retroArchExtras(
                "/storage/1D20-0000/GameBEX/snes/Game.zip",
                "/data/data/com.retroarch.aarch64/cores/snes9x_libretro_android.so",
                "/storage/emulated/0/GameBEX/ra/retroarch-frontend.cfg",
                paths,
            ),
        )
    }

    @Test fun unknownPathsAreLeftOut() {
        assertEquals(
            mapOf("ROM" to "/rom.zip", "LIBRETRO" to "/core.so"),
            EmuRouter.retroArchExtras("/rom.zip", "/core.so", null, EmuRouter.RaPaths(null, null, null, null)),
        )
    }
}
