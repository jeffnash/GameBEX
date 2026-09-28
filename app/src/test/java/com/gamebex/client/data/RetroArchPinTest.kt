package com.gamebex.client.data

import com.gamebex.client.together.TogetherCaps
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pinned RetroArch must be a build with the netplay client fix. */
class RetroArchPinTest {
    @Test fun pinnedRetroArchIsBuiltAfterTheNetplayFix() {
        val pin = EmuCatalog.entries.single { it.emu == Emu.RETROARCH }
        // RetroArch's versionCode is its build time in Unix seconds.
        assertTrue(pin.pinnedVersionCode >= TogetherCaps.RA_NETPLAY_MIN_VERSION_CODE)
        assertTrue(pin.pinnedSha256.orEmpty().matches(Regex("[0-9a-f]{64}")))
    }
}
