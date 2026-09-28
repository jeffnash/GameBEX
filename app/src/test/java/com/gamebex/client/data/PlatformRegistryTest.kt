package com.gamebex.client.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** Scanner gate: registry exts plus hub archives on disc folders only. */
class PlatformRegistryTest {
    @Test fun registryExtsAccepted() {
        assertEquals(true, PlatformRegistry.accepts("dreamcast", "iso"))
        assertEquals(true, PlatformRegistry.accepts("dreamcast", "zip"))
        assertEquals(true, PlatformRegistry.accepts("gb", "gb"))
        assertEquals(true, PlatformRegistry.accepts("mame", "zip"))
    }

    @Test fun hubArchivesAcceptedOnDiscFolders() {
        for (ext in listOf("rar", "tar", "tgz", "gz", "bz2", "xz", "001")) {
            assertEquals(true, PlatformRegistry.accepts("dreamcast", ext))
            assertEquals(true, PlatformRegistry.accepts("psx", ext))
        }
    }

    @Test fun hubArchivesRejectedOffDisc() {
        for (ext in listOf("rar", "tar", "tgz", "gz", "bz2", "xz", "001")) {
            assertEquals(false, PlatformRegistry.accepts("gb", ext))
            assertEquals(false, PlatformRegistry.accepts("mame", ext))
        }
    }

    @Test fun unknownRejected() {
        assertEquals(false, PlatformRegistry.accepts("dreamcast", "exe"))
        assertEquals(false, PlatformRegistry.accepts("nope", "zip"))
    }

    /** lrps2 SIGSEGVs on-device, so PS2 rides the standalone. */
    @Test fun ps2StaysStandaloneWhileLrps2Crashes() {
        assertEquals(Route.Standalone(Emu.NETHER), PlatformRegistry.byFolder["ps2"]?.route)
    }

    /** Disc/32-bit systems ride cores, standalones as alternates. */
    @Test fun migratedRoutesRideCores() {
        assertEquals(Route.RetroCore("flycast"), PlatformRegistry.byFolder["dreamcast"]?.route)
        assertEquals(Route.RetroCore("dolphin"), PlatformRegistry.byFolder["gc"]?.route)
        assertEquals(Route.RetroCore("melonds"), PlatformRegistry.byFolder["nds"]?.route)
        assertEquals(Route.RetroCore("citra"), PlatformRegistry.byFolder["n3ds"]?.route)
        assertEquals(Route.RetroCore("ppsspp"), PlatformRegistry.byFolder["psp"]?.route)
    }
}
