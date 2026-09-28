package com.gamebex.client.launch

import org.junit.Assert.assertEquals
import org.junit.Test

/** Provider URI + MIME contract (standalones consume both). */
class RomProviderTest {
    @Test fun uriShapeMatchesMatcher() {
        assertEquals(
            "content://com.gamebex.client.roms/game/42",
            RomProvider.uriString(42),
        )
    }

    @Test fun mimeTable() {
        assertEquals("application/zip", RomProvider.mimeFor("rom.zip"))
        assertEquals("application/x-7z-compressed", RomProvider.mimeFor("ROM.7Z"))
        assertEquals("text/plain", RomProvider.mimeFor("disc.cue"))
        assertEquals("text/plain", RomProvider.mimeFor("set.m3u"))
        assertEquals("application/octet-stream", RomProvider.mimeFor("game.iso"))
        assertEquals("application/octet-stream", RomProvider.mimeFor("game.nds"))
        assertEquals("application/octet-stream", RomProvider.mimeFor("noext"))
        assertEquals("application/octet-stream", RomProvider.mimeFor("weird.xyz"))
    }
}
