package com.gamebex.client.launch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** RetroArch core filename -> base name parsing. */
class RetroCoreProbeTest {
    @Test fun androidSuffixParses() {
        assertEquals("gambatte", RetroCoreProbe.coreBaseName("gambatte_libretro_android.so"))
    }

    @Test fun plainSuffixParses() {
        assertEquals("fbneo", RetroCoreProbe.coreBaseName("fbneo_libretro.so"))
    }

    @Test fun junkReturnsNull() {
        assertNull(RetroCoreProbe.coreBaseName("retroarch.cfg"))
        assertNull(RetroCoreProbe.coreBaseName("cores.txt"))
        assertNull(RetroCoreProbe.coreBaseName(""))
    }

    @Test fun baseNameLowercases() {
        assertEquals("mgba", RetroCoreProbe.coreBaseName("MGBA_libretro_android.so"))
    }
}
