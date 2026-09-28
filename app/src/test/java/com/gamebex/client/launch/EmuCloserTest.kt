package com.gamebex.client.launch

import com.gamebex.client.data.Emu
import com.gamebex.client.data.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmuCloserTest {
    private val own = "com.gamebex.client"

    @Test fun targetsExcludeLaunchTargetAndSelf() {
        val t = EmuCloser.targets(Emu.NETHER.pkg, own)
        assertFalse(Emu.NETHER.pkg in t)
        assertFalse(own in t)
        assertTrue(Emu.RETROARCH.pkg in t)
        assertEquals(Emu.entries.size - 1, t.size)
        // Unknown packages in the candidate list never get through.
        val mixed = EmuCloser.targets(Emu.RETROARCH.pkg, own, listOf(Emu.RETROARCH.pkg, Emu.DOLPHIN.pkg, "com.android.chrome", own))
        assertEquals(listOf(Emu.DOLPHIN.pkg), mixed)
    }

    @Test fun forceStopAllowlistOnlyKnownEmulators() {
        for (e in Emu.entries) assertEquals(e.pkg, ForceStopPolicy.allowed(e.pkg, own))
        assertNull(ForceStopPolicy.allowed(own, own))
        assertNull(ForceStopPolicy.allowed("com.gamebex.client.debug"))
        assertNull(ForceStopPolicy.allowed("com.android.systemui"))
        assertNull(ForceStopPolicy.allowed("moe.shizuku.privileged.api"))
        assertNull(ForceStopPolicy.allowed(null))
        assertNull(ForceStopPolicy.allowed(""))
        assertNull(ForceStopPolicy.allowed("${Emu.DOLPHIN.pkg}; reboot"))
        assertNull(ForceStopPolicy.allowed("${Emu.DOLPHIN.pkg} --user 0"))
        // Own package refused even if it were ever listed as an emulator.
        assertNull(ForceStopPolicy.allowed(Emu.DOLPHIN.pkg, Emu.DOLPHIN.pkg))
    }

    @Test fun closeOthersDefaultsOn() {
        assertTrue(LaunchPrefs.DEFAULT_CLOSE_OTHERS)
        assertTrue(LaunchPrefs.closeOthersOrDefault(null))
        assertFalse(LaunchPrefs.closeOthersOrDefault(false))
        assertTrue(LaunchPrefs.closeOthersOrDefault(true))
    }

    @Test fun launchTargetFollowsRouteAndOverride() {
        assertEquals(Emu.RETROARCH.pkg, EmuRouter.launchTarget(Route.RetroCore("snes9x"), null))
        assertEquals(Emu.RETROARCH.pkg, EmuRouter.launchTarget(Route.RetroCore("snes9x"), Emu.RETROARCH))
        assertEquals(Emu.DOLPHIN.pkg, EmuRouter.launchTarget(Route.RetroCore("dolphin"), Emu.DOLPHIN))
        assertEquals(Emu.NETHER.pkg, EmuRouter.launchTarget(Route.Standalone(Emu.NETHER), null))
        assertNull(EmuRouter.launchTarget(Route.GuidedInstall(Emu.VITA3K), null))
        assertNull(EmuRouter.launchTarget(Route.SideloadApp, null))
        assertNull(EmuRouter.launchTarget(Route.Blocked("x"), null))
    }
}
