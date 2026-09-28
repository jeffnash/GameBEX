package com.gamebex.client.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ES-DE / RetroArch folder names map onto registry folders. */
class PlatformAliasesTest {
    @Test fun commonEsDeNamesResolve() {
        val cases = mapOf(
            "genesis" to "genesis", "megadrive" to "genesis", "md" to "genesis",
            "psx" to "psx", "ps1" to "psx",
            "n64" to "n64",
            "gb" to "gb", "gbc" to "gbc", "gba" to "gba",
            "nes" to "nes", "famicom" to "nes",
            "snes" to "snes", "sfc" to "snes",
            "dreamcast" to "dreamcast", "dc" to "dreamcast",
            "pcengine" to "tg16", "arcade" to "mame", "gamecube" to "gc",
            "sg1000" to "sg-1000", "3ds" to "n3ds",
        )
        for ((dir, want) in cases) assertEquals(dir, want, PlatformAliases.resolve(dir))
    }

    @Test fun caseAndSeparatorsIgnored() {
        assertEquals("genesis", PlatformAliases.resolve("MegaDrive"))
        assertEquals("genesis", PlatformAliases.resolve("Mega Drive"))
        assertEquals("snes", PlatformAliases.resolve("SNES"))
        assertEquals("sg-1000", PlatformAliases.resolve("SG-1000"))
        assertEquals("psx", PlatformAliases.resolve("PS1"))
    }

    @Test fun unknownAndHiddenRejected() {
        assertNull(PlatformAliases.resolve("media"))
        assertNull(PlatformAliases.resolve("downloaded_media"))
        assertNull(PlatformAliases.resolve("ROMs"))
        assertNull(PlatformAliases.resolve(".gamebex_ext"))
        assertNull(PlatformAliases.resolve(""))
    }

    @Test fun everyAliasTargetsRealFolder() {
        for ((alias, folder) in PlatformAliases.table) {
            assertTrue("$alias -> $folder", PlatformRegistry.byFolder.containsKey(folder))
            assertEquals("alias key must be pre-normalized", PlatformAliases.normalize(alias), alias)
        }
    }

    @Test fun registryFoldersAlwaysResolveToThemselves() {
        for (f in PlatformRegistry.folders) assertEquals(f, PlatformAliases.resolve(f))
    }

    @Test fun aliasesNeverShadowARegistryFolder() {
        val normalizedFolders = PlatformRegistry.folders.associateBy { PlatformAliases.normalize(it) }
        for ((alias, target) in PlatformAliases.table) {
            val clash = normalizedFolders[alias]
            assertTrue("$alias shadows $clash", clash == null || clash == target)
        }
    }
}
