package com.gamebex.client.net

import com.gamebex.client.data.local.DownloadRecord
import com.gamebex.client.data.local.FakeLibraryDb
import com.gamebex.client.data.local.GameEntity
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Play when ready: arming, the foreground gate, download→game mapping. */
class PlayWhenReadyTest {
    @Before
    fun setUp() {
        PlayWhenReady.foreground = true
    }

    @After
    fun tearDown() {
        PlayWhenReady.armed.value.toList().forEach { PlayWhenReady.toggle(it) }
        PlayWhenReady.foreground = false
    }

    private fun game(id: Long, relPath: String) = GameEntity(
        id = id, title = "Game $id", folder = "gb", volumeId = "internal",
        relPath = relPath, fileName = relPath.substringAfter('/'), sizeBytes = 1, mtimeMs = 1,
        ext = "gb",
    )

    private fun record(
        id: Long,
        relPath: String,
        status: String = DownloadEngine.ST_DONE,
    ) = DownloadRecord(
        id = id, pageUrl = "https://acme.com/roms/tetris.html", title = "Tetris",
        destRelPath = relPath, volumeId = "internal", bytes = 0, status = status,
    )

    @Test
    fun toggleArmsThenDisarms() {
        PlayWhenReady.toggle(7L)
        assertEquals(setOf(7L), PlayWhenReady.armed.value)
        PlayWhenReady.toggle(7L)
        assertTrue(PlayWhenReady.armed.value.isEmpty())
    }

    @Test
    fun backgroundReadyDisarmsButDoesNotLaunch(): Unit = runBlocking {
        PlayWhenReady.toggle(7L)
        PlayWhenReady.foreground = false
        // READY consumes the arm even without a launch: a leftover arm
        // must never auto-launch on a later READY.
        assertNull(PlayWhenReady.claim(42L) { 42L })
        assertTrue(PlayWhenReady.armed.value.isEmpty())
    }

    @Test
    fun leftoverArmNeverAutoLaunchesOnALaterReady(): Unit = runBlocking {
        PlayWhenReady.toggle(7L)
        PlayWhenReady.foreground = false
        assertNull(PlayWhenReady.claim(42L) { 42L }) // background READY: notification only
        PlayWhenReady.foreground = true
        assertNull(PlayWhenReady.claim(42L) { 42L }) // later READY: no stale auto-launch
        assertTrue(PlayWhenReady.armed.value.isEmpty())
    }

    @Test
    fun concurrentClaimsLaunchOnce(): Unit = runTest {
        PlayWhenReady.toggle(7L)
        val claims = List(2) {
            async {
                PlayWhenReady.claim(42L) {
                    yield() // force interleaving at the resolve step
                    if (it == 7L) 42L else null
                }
            }
        }
        val results = claims.map { it.await() }
        assertEquals(1, results.count { it != null })
        assertEquals(7L, results.firstOrNull { it != null })
        assertTrue(PlayWhenReady.armed.value.isEmpty())
    }

    @Test
    fun dropSettledDisarmsFailedAndCancelledDownloads(): Unit = runBlocking {
        val db = FakeLibraryDb(
            downloadsList = mutableListOf(
                record(id = 7, relPath = "gb/tetris.gb", status = DownloadEngine.ST_FAILED),
                record(id = 9, relPath = "gb/tetris.gb"),
            ),
        )
        PlayWhenReady.toggle(7L) // failed download
        PlayWhenReady.toggle(8L) // cancelled: no record left
        PlayWhenReady.toggle(9L) // landed, waiting for its READY
        PlayWhenReady.dropSettled(db)
        assertEquals(setOf(9L), PlayWhenReady.armed.value)
    }

    @Test
    fun claimMapsAndConsumesTheArm(): Unit = runBlocking {
        PlayWhenReady.toggle(7L)
        assertEquals(7L, PlayWhenReady.claim(42L) { if (it == 7L) 42L else null })
        assertTrue(PlayWhenReady.armed.value.isEmpty())
    }

    @Test
    fun claimSkipsDownloadsOfOtherGames(): Unit = runBlocking {
        PlayWhenReady.toggle(7L)
        PlayWhenReady.toggle(8L)
        assertEquals(8L, PlayWhenReady.claim(42L) { if (it == 8L) 42L else 1L })
        assertEquals(setOf(7L), PlayWhenReady.armed.value)
    }

    @Test
    fun claimWithNothingArmedReturnsNull(): Unit = runBlocking {
        assertNull(PlayWhenReady.claim(42L) { 42L })
    }

    @Test
    fun gameForDownloadMapsTheRecordPathToTheGame(): Unit = runBlocking {
        val db = FakeLibraryDb(
            gamesList = mutableListOf(game(id = 42, relPath = "gb/tetris.gb")),
            downloadsList = mutableListOf(record(id = 7, relPath = "gb/tetris.gb")),
        )
        assertEquals(42L, PlayWhenReady.gameForDownload(db, 7L))
        assertNull(PlayWhenReady.gameForDownload(db, 8L)) // no such record
    }

    @Test
    fun gameForDownloadIgnoresARecordForAnotherFile(): Unit = runBlocking {
        val db = FakeLibraryDb(
            gamesList = mutableListOf(game(id = 42, relPath = "gb/tetris.gb")),
            downloadsList = mutableListOf(record(id = 7, relPath = "gb/other.gb")),
        )
        assertNull(PlayWhenReady.gameForDownload(db, 7L))
    }
}
