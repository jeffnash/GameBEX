package com.gamebex.client.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** User game folders: dedupe, overlap, containment, alias-aware scan gate. */
class GameFoldersTest {
    @get:Rule val tmp = TemporaryFolder()

    private val sd = "/storage/1234-ABCD/GameBEX"
    private val internal = "/storage/emulated/0/Download/GameBEX"
    private val builtIns = listOf(sd, internal)

    @Test fun normalizeCollapsesDotsAndSlashes() {
        assertEquals("/storage/x/ROMs", GameFolders.normalize("/storage/x//ROMs/"))
        assertEquals("/storage/ROMs", GameFolders.normalize("/storage/x/../ROMs"))
        assertNull(GameFolders.normalize("relative/path"))
        assertNull(GameFolders.normalize("/.."))
    }

    @Test fun isWithinIsSegmentAware() {
        assertTrue(GameFolders.isWithin("/a/ROMs", "/a/ROMs/snes"))
        assertTrue(GameFolders.isWithin("/a/ROMs", "/a/ROMs"))
        assertFalse(GameFolders.isWithin("/a/ROMs", "/a/ROMs2"))
        assertFalse(GameFolders.isWithin("/a/ROMs/snes", "/a/ROMs"))
    }

    @Test fun overlapOnlyInsideScannedSystemSubtree() {
        assertTrue(GameFolders.overlaps("/sd/ROMs", "/sd/ROMs"))
        assertTrue(GameFolders.overlaps("/sd/ROMs", "/sd/ROMs/snes"))
        assertTrue(GameFolders.overlaps("/sd/ROMs/megadrive/hacks", "/sd/ROMs"))
        // /sd never walks into /sd/ROMs: "ROMs" is not a system folder.
        assertFalse(GameFolders.overlaps("/sd", "/sd/ROMs"))
        assertFalse(GameFolders.overlaps("/sd/ROMs", "/sd/Games"))
    }

    @Test fun dedupeDropsDuplicatesNestedAndBuiltIns() {
        val custom = listOf(
            "/storage/1234-ABCD/ROMs",
            "/storage/1234-ABCD/ROMs/", // same after normalize
            "/storage/1234-ABCD/ROMs/snes", // inside a scanned subtree
            sd, // the built-in root itself
            "$sd/snes", // inside the built-in root
            "$internal/.gamebex_ext", // app-owned prep root
            "/storage/1234-ABCD", // parent; "ROMs" isn't a system dir: kept
            "relative", // invalid
        )
        assertEquals(
            listOf("/storage/1234-ABCD/ROMs", "/storage/1234-ABCD"),
            GameFolders.dedupe(builtIns, custom),
        )
    }

    @Test fun rejectReasonsNameTheConflict() {
        assertNotNull(GameFolders.rejectReason(sd, builtIns, emptyList()))
        assertNotNull(GameFolders.rejectReason("$sd/psx", builtIns, emptyList()))
        assertNotNull(GameFolders.rejectReason("/x/ROMs/psx", builtIns, listOf("/x/ROMs")))
        assertNull(GameFolders.rejectReason("/x/ROMs", builtIns, emptyList()))
        // A parent whose system subtree holds the built-in root overlaps it.
        assertNotNull(GameFolders.rejectReason("/storage", listOf("/storage/snes/GameBEX"), emptyList()))
    }

    @Test fun prepDirStaysInsidePrepRoot() {
        val prepRoot = File(tmp.root, "GameBEX/.gamebex_ext")
        val vol = GameFolders.volumeIdFor("/storage/1234-ABCD/ROMs")
        val d = GameFolders.prepDirFor(prepRoot, vol, "psx/Game (USA)/Game.zip")
        assertTrue(d.path.startsWith(prepRoot.path + File.separator))
        assertTrue(d.path.endsWith("psx" + File.separator + "Game (USA)"))
        val evil = GameFolders.prepDirFor(prepRoot, vol, "../../../etc/x.zip")
        assertTrue(evil.canonicalPath.startsWith(prepRoot.canonicalPath + File.separator))
        // Never inside the user's own tree.
        assertFalse(d.path.startsWith("/storage/1234-ABCD/ROMs"))
    }

    @Test fun volumeKeyIsStableAndPathFree() {
        val k = GameFolders.volumeKey("dir:/storage/1234-ABCD/ROMs")
        assertEquals(k, GameFolders.volumeKey("dir:/storage/1234-ABCD/ROMs"))
        assertEquals(16, k.length)
        assertFalse(k.contains('/'))
    }

    @Test fun containmentRejectsEscapesAndSiblings() {
        val root = File(tmp.root, "ROMs").apply { mkdirs() }
        File(root, "snes").mkdirs()
        assertTrue(RomStore.isInside(root, File(root, "snes/a.sfc")))
        assertFalse(RomStore.isInside(root, File(root, "../outside.sfc")))
        assertFalse(RomStore.isInside(root, File(tmp.root, "ROMs2/a.sfc")))
        assertFalse(RomStore.isInside(root, root))
    }

    @Test fun userVolumeIdsAreRecognized() {
        assertTrue(RomStore.isUserVolume(GameFolders.volumeIdFor("/x/ROMs")))
        assertFalse(RomStore.isUserVolume("internal"))
        assertFalse(RomStore.isUserVolume("sd:1234-ABCD"))
    }

    @Test fun scanGateUsesAliasesOnlyForUserRoots() {
        assertEquals("genesis", LibraryScanner.platformFor("megadrive", aliasFolders = true))
        assertNull(LibraryScanner.platformFor("megadrive", aliasFolders = false))
        assertEquals("snes", LibraryScanner.platformFor("snes", aliasFolders = false))
        assertNull(LibraryScanner.platformFor("media", aliasFolders = true))
    }
}
