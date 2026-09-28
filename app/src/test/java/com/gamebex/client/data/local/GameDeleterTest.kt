package com.gamebex.client.data.local

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Delete-to-free-space file selection: only the game's own files, always contained. */
class GameDeleterTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun root(): File = tmp.newFolder("roms")
    private fun write(f: File, text: String = "x"): File {
        f.parentFile.mkdirs()
        f.writeText(text)
        return f
    }

    @Test fun cartDeletesRomSidecarAndLeftoversOnly() {
        val root = root()
        val rom = write(File(root, "gba/emerald.gba"), "rom-bytes")
        val side = write(Sidecars.fileFor(rom))
        val bak = write(File(root, "gba/emerald.gba.bak"))
        val neighbor = write(File(root, "gba/ruby.gba"))
        val neighborSide = write(File(root, "gba/ruby.gba.gamebex.json"))

        val plan = GameDeleter.plan(root, "gba/emerald.gba")!!
        assertEquals(rom, plan.files.first())
        assertEquals(setOf(rom, side, bak), plan.files.toSet())
        assertTrue(GameDeleter.execute(plan))
        assertFalse(rom.exists())
        assertFalse(side.exists())
        assertTrue(neighbor.isFile)
        assertTrue(neighborSide.isFile)
        assertTrue(File(root, "gba").isDirectory)
    }

    @Test fun escapingPathsAreRefused() {
        val root = root()
        write(File(tmp.root, "outside.gba"))
        assertNull(GameDeleter.plan(root, "../outside.gba"))
        assertNull(GameDeleter.plan(root, "gba/../../outside.gba"))
        // ROM directly at the volume root: not inside a platform folder.
        write(File(root, "loose.gba"))
        assertNull(GameDeleter.plan(root, "loose.gba"))
        assertTrue(File(tmp.root, "outside.gba").isFile)
    }

    @Test fun symlinkedRomPointingOutsideIsRefused() {
        val root = root()
        val outside = write(File(tmp.root, "victim.bin"))
        File(root, "psx").mkdirs()
        val link = File(root, "psx/game.bin").toPath()
        runCatching { Files.createSymbolicLink(link, outside.toPath()) }.onFailure { return }
        assertNull(GameDeleter.plan(root, "psx/game.bin"))
        assertTrue(outside.isFile)
    }

    @Test fun prepOutputDeletesManifestOnlyAndKeepsForeignFiles() {
        val root = root()
        val rom = write(File(root, "psx/Game (USA).7z"))
        val out = File(root, "psx/Game (USA)")
        val cue = write(File(out, "Game (USA).cue"))
        val bin = write(File(out, "tracks/Game (USA).bin"))
        val m3u = write(File(out, "Game (USA).m3u"))
        val foreign = write(File(out, "my-notes.txt"))
        val marker = write(
            File(out, PrepPipeline.STAGE_MARKER),
            listOf("finger", "Game (USA).cue", "tracks/Game (USA).bin", "Game (USA).m3u", "../../escape.txt")
                .joinToString("\n"),
        )
        val escape = write(File(root, "escape.txt"))
        val stage = File(root, "psx/Game (USA).staging")
        write(File(stage, "partial.bin"))

        val plan = GameDeleter.plan(root, "psx/Game (USA).7z")!!
        assertEquals(setOf(rom, cue, bin, m3u, marker), plan.files.toSet())
        assertEquals(listOf(stage), plan.trees)
        assertTrue(GameDeleter.execute(plan))
        assertFalse(cue.exists() || bin.exists() || m3u.exists() || marker.exists())
        assertFalse(File(out, "tracks").exists()) // emptied subdir pruned
        assertFalse(stage.exists())
        assertTrue(foreign.isFile) // not prep-owned: survives, so outDir stays
        assertTrue(out.isDirectory)
        assertTrue(escape.isFile)
        assertTrue(File(root, "psx").isDirectory) // platform folder never pruned
    }

    @Test fun prepOutputFullyOwnedIsRemovedEntirely() {
        val root = root()
        write(File(root, "dreamcast/SA.zip"))
        val out = File(root, "dreamcast/SA")
        write(File(out, "SA.cue"))
        write(File(out, "SA.gdi"))
        write(File(out, PrepPipeline.STAGE_MARKER), "finger\nSA.cue\nSA.gdi")
        assertTrue(GameDeleter.execute(GameDeleter.plan(root, "dreamcast/SA.zip")!!))
        assertFalse(out.exists())
        assertTrue(File(root, "dreamcast").isDirectory)
    }

    @Test fun markerWithoutManifestDeletesNothingInOutput() {
        val root = root()
        write(File(root, "psx/Old.zip"))
        val out = File(root, "psx/Old")
        val cue = write(File(out, "Old.cue"))
        write(File(out, PrepPipeline.STAGE_MARKER), "finger-only")
        val plan = GameDeleter.plan(root, "psx/Old.zip")!!
        assertEquals(listOf(File(root, "psx/Old.zip")), plan.files)
        GameDeleter.execute(plan)
        assertTrue(cue.isFile)
    }

    @Test fun unmarkedSameNameFolderIsNeverTouched() {
        val root = root()
        write(File(root, "psx/Game.zip"))
        val userDir = File(root, "psx/Game")
        val userFile = write(File(userDir, "keep.bin"))
        GameDeleter.execute(GameDeleter.plan(root, "psx/Game.zip")!!)
        assertTrue(userFile.isFile)
    }

    @Test fun looseCueTakesItsTracksButNotOtherGames() {
        val root = root()
        val cue = write(
            File(root, "psx/Loose.cue"),
            "FILE \"Loose (Track 1).bin\" BINARY\n  TRACK 01 MODE2/2352\n    INDEX 01 00:00:00\n" +
                "FILE \"../../escape.bin\" BINARY\n  TRACK 02 AUDIO\n    INDEX 01 00:00:00\n",
        )
        val track = write(File(root, "psx/Loose (Track 1).bin"))
        val other = write(File(root, "psx/Other.bin"))
        val escape = write(File(tmp.root, "escape.bin"))
        val plan = GameDeleter.plan(root, "psx/Loose.cue")
        assertNotNull(plan)
        assertEquals(setOf(cue, track), plan!!.files.toSet())
        GameDeleter.execute(plan)
        assertTrue(other.isFile)
        assertTrue(escape.isFile)
    }

    @Test fun missingRomStillPlansCleanup() {
        val root = root()
        File(root, "gba").mkdirs()
        val side = write(File(root, "gba/gone.gba.gamebex.json"))
        val plan = GameDeleter.plan(root, "gba/gone.gba")!!
        assertEquals(listOf(side), plan.files)
        assertTrue(GameDeleter.execute(plan))
    }

    @Test fun sourceGameKeepsATombstoneForDownloadAgain() {
        val db = FakeLibraryDb()
        val id = runBlocking {
            db.games().upsert(
                GameEntity(
                    title = "Metal Gear Solid", folder = "psx", volumeId = "internal",
                    relPath = "psx/mgs.bin", fileName = "mgs.bin", sizeBytes = 10, mtimeMs = 1,
                    ext = "bin", source = "SOURCE", playCount = 4, favorite = true,
                    sourceRefJson = "{}",
                ),
            )
        }
        runBlocking { GameDeleter.finishRow(db, db.games().findById(id)!!, now = 123L) }
        val row = runBlocking { db.games().findById(id) }
        assertNotNull(row)
        assertFalse(row!!.available)
        assertEquals(123L, row.deletedAtMs)
        assertEquals(4, row.playCount) // history survives for the re-download
        assertTrue(row.favorite)
    }

    @Test fun importedGameLosesItsRow() {
        val db = FakeLibraryDb()
        val id = runBlocking {
            db.games().upsert(
                GameEntity(
                    title = "Ruby", folder = "gba", volumeId = "internal",
                    relPath = "gba/ruby.gba", fileName = "ruby.gba", sizeBytes = 1, mtimeMs = 1,
                    ext = "gba",
                ),
            )
        }
        runBlocking { GameDeleter.finishRow(db, db.games().findById(id)!!, now = 1L) }
        assertNull(runBlocking { db.games().findById(id) })
    }
}
