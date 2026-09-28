package com.gamebex.client.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Raw RetroArch core ids never reach the UI. */
class CoreNamesTest {
    @Test fun everyRegistryCoreHasADisplayName() {
        val cores = PlatformRegistry.all.mapNotNull { (it.route as? Route.RetroCore)?.core }.toSet()
        val missing = cores - CoreNames.NAMES.keys
        assertTrue("cores without a display name: $missing", missing.isEmpty())
        for (c in cores) {
            val name = CoreNames.display(c)
            assertTrue("$c -> $name", !name.contains('_'))
        }
    }

    @Test fun knownNames() {
        assertEquals("Beetle PSX", CoreNames.display("mednafen_psx"))
        assertEquals("Mupen64Plus-Next", CoreNames.display("mupen64plus_next_gles3"))
        assertEquals("Snes9x", CoreNames.display("snes9x"))
        assertEquals("mGBA", CoreNames.display("mgba"))
        assertEquals("FinalBurn Neo", CoreNames.display("fbneo"))
    }

    @Test fun unknownIdIsPrettified() {
        assertEquals("Some New Core", CoreNames.display("some_new_core"))
        assertEquals("", CoreNames.display(""))
    }
}
