package com.gamebex.client.net

import com.gamebex.client.data.local.ArtStore
import com.gamebex.client.data.local.GameEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ArtWorker pure policy: settle states, slot ownership, missing slots. */
class ArtWorkerTest {
    @Test fun tombstonedGamesNeverFetchArt() {
        val live = GameEntity(
            id = 1, title = "Tetris", folder = "gb", volumeId = "v",
            relPath = "gb/tetris.gb", fileName = "tetris.gb", sizeBytes = 1, mtimeMs = 0,
            ext = "gb",
        )
        assertTrue(ArtWorker.wantsArt(live))
        assertFalse(ArtWorker.wantsArt(live.copy(available = false, deletedAtMs = 1_000L)))
    }

    @Test fun settledCoversTerminalSuspendedAndGone() {
        assertTrue(ArtWorker.isSettledStatus(DownloadEngine.ST_DONE))
        assertTrue(ArtWorker.isSettledStatus(DownloadEngine.ST_FAILED))
        assertTrue(ArtWorker.isSettledStatus(DownloadEngine.ST_PAUSED))
        assertTrue(ArtWorker.isSettledStatus(DownloadEngine.ST_PARKED_SD))
        assertTrue(ArtWorker.isSettledStatus(DownloadEngine.ST_NEEDS_ASSIST))
        assertTrue(ArtWorker.isSettledStatus(DownloadEngine.ST_INGEST))
        assertTrue(ArtWorker.isSettledStatus(null)) // cancelled away
        assertFalse(ArtWorker.isSettledStatus(DownloadEngine.ST_ACTIVE))
        assertFalse(ArtWorker.isSettledStatus(DownloadEngine.ST_QUEUED))
    }

    @Test fun suspendedStatesAreRetryableNotTerminal() {
        assertTrue(ArtWorker.isSuspendedStatus(DownloadEngine.ST_PAUSED))
        assertTrue(ArtWorker.isSuspendedStatus(DownloadEngine.ST_PARKED_SD))
        assertTrue(ArtWorker.isSuspendedStatus(DownloadEngine.ST_NEEDS_ASSIST))
        assertFalse(ArtWorker.isSuspendedStatus(DownloadEngine.ST_DONE))
        assertFalse(ArtWorker.isSuspendedStatus(DownloadEngine.ST_FAILED))
        assertFalse(ArtWorker.isSuspendedStatus(DownloadEngine.ST_ACTIVE))
    }

    @Test fun userOwnedSlotsNeverFetch() {
        assertFalse(ArtWorker.shouldFetchSlot(null, false, true))
        assertFalse(ArtWorker.shouldFetchSlot("/art/1/box.jpg", true, true))
    }

    @Test fun populatedSlotsNeverFetch() {
        assertFalse(ArtWorker.shouldFetchSlot("/art/1/box.jpg", true, false))
    }

    @Test fun emptyOrDanglingSlotsFetch() {
        assertTrue(ArtWorker.shouldFetchSlot(null, false, false))
        assertTrue(ArtWorker.shouldFetchSlot("/art/1/box.jpg", false, false))
        assertTrue(ArtWorker.shouldFetchSlot(null, true, false)) // stray file, no row
    }

    @Test fun missingSlotsFlagsNullAndDanglingPaths() {
        val paths = mapOf(
            ArtStore.SLOT_BOX to "/art/1/box.jpg",
            ArtStore.SLOT_HERO to null,
            ArtStore.SLOT_LOGO to "/art/1/logo.jpg",
            ArtStore.SLOT_SHOT to "/art/1/shot.jpg",
        )
        val missing = ArtWorker.missingSlots(paths) { it == "/art/1/box.jpg" }
        assertEquals(setOf(ArtStore.SLOT_HERO, ArtStore.SLOT_LOGO, ArtStore.SLOT_SHOT), missing)
        assertEquals(setOf(ArtStore.SLOT_HERO), ArtWorker.missingSlots(paths) { true })
    }

    @Test fun coverWaitReturnsOnSettledOrGone() {
        assertEquals(ArtWorker.CoverWait.RETURN_SETTLED, ArtWorker.coverWaitStep(DownloadEngine.ST_DONE, 10, 5, 0))
        assertEquals(ArtWorker.CoverWait.RETURN_SETTLED, ArtWorker.coverWaitStep(DownloadEngine.ST_FAILED, 10, 5, 0))
        assertEquals(ArtWorker.CoverWait.RETURN_SETTLED, ArtWorker.coverWaitStep(DownloadEngine.ST_PAUSED, 10, 5, 0))
        assertEquals(ArtWorker.CoverWait.RETURN_SETTLED, ArtWorker.coverWaitStep(null, -1, -1, 999_999))
    }

    @Test fun coverWaitQueueTimeIsFree() {
        // Parked behind ROM transfers far past the stall budget: still wait.
        assertEquals(
            ArtWorker.CoverWait.RESET_CLOCK,
            ArtWorker.coverWaitStep(DownloadEngine.ST_QUEUED, 0, 0, 10 * ArtWorker.COVER_STALL_MS),
        )
    }

    @Test fun coverWaitProgressResetsClock() {
        assertEquals(
            ArtWorker.CoverWait.RESET_CLOCK,
            ArtWorker.coverWaitStep(DownloadEngine.ST_ACTIVE, 4096, 1024, 10 * ArtWorker.COVER_STALL_MS),
        )
    }

    @Test fun coverWaitAbortsOnlyFrozenActivePastBudget() {
        assertEquals(
            ArtWorker.CoverWait.KEEP_WAITING,
            ArtWorker.coverWaitStep(DownloadEngine.ST_ACTIVE, 1024, 1024, ArtWorker.COVER_STALL_MS - 1),
        )
        assertEquals(
            ArtWorker.CoverWait.ABORT_STALLED,
            ArtWorker.coverWaitStep(DownloadEngine.ST_ACTIVE, 1024, 1024, ArtWorker.COVER_STALL_MS),
        )
    }

    @Test fun autoSourceCoversHistoricalAndPerSourceLabels() {
        assertTrue(ArtWorker.isAutoSource("SS"))
        assertTrue(ArtWorker.isAutoSource("SOURCE:acme"))
        assertFalse(ArtWorker.isAutoSource("USER"))
        assertFalse(ArtWorker.isAutoSource("MIXED"))
        assertFalse(ArtWorker.isAutoSource("NONE"))
    }
}
