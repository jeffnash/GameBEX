package com.gamebex.client.ui

import com.gamebex.client.data.local.DownloadRecord
import com.gamebex.client.net.DownloadEngine
import com.gamebex.client.ui.console.ControllerGlyph
import com.gamebex.client.ui.console.ControllerHint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Console Downloads policy: sections, state-aware actions/hints, dismissals. */
class ConsoleDownloadsTest {
    private fun row(id: Long, status: String, read: Long = 0, total: Long = 0) = DownloadRow(
        id = id, title = "Game $id", status = status, read = read, total = total,
        error = null, live = false,
    )

    private val A = ControllerGlyph.A
    private val X = ControllerGlyph.X
    private val B = ControllerGlyph.B

    @Test fun sectionsSplitActiveFromCompleted() {
        val s = sectionDownloadRows(
            listOf(
                row(1, DownloadEngine.ST_DONE),
                row(2, DownloadEngine.ST_QUEUED),
                row(3, DownloadEngine.ST_ACTIVE),
                row(4, DownloadEngine.ST_FAILED),
                row(5, DownloadEngine.ST_DONE),
            ),
        )
        assertEquals(listOf(3L, 4L, 2L), s.active.map { it.id })
        assertEquals(listOf(1L, 5L), s.completed.map { it.id })
        assertEquals(listOf(3L, 4L, 2L, 1L, 5L), s.all.map { it.id })
        assertEquals(1, s.runningCount)
        assertEquals(1, s.queuedCount)
        assertEquals(1, s.attentionCount)
        assertFalse(s.isEmpty)
    }

    @Test fun activeOrderIsStableAcrossUpdatedAtShuffles() {
        val a = listOf(row(7, DownloadEngine.ST_PAUSED), row(3, DownloadEngine.ST_PAUSED))
        assertEquals(
            sectionDownloadRows(a).active.map { it.id },
            sectionDownloadRows(a.reversed()).active.map { it.id },
        )
    }

    @Test fun dismissedCompletedRowsAreHiddenButActiveNeverIs() {
        val s = sectionDownloadRows(
            listOf(row(1, DownloadEngine.ST_DONE), row(2, DownloadEngine.ST_ACTIVE)),
            dismissed = setOf(1L, 2L),
        )
        assertEquals(listOf(2L), s.active.map { it.id })
        assertTrue(s.completed.isEmpty())
        assertTrue(sectionDownloadRows(emptyList()).isEmpty)
    }

    @Test fun hintsFollowSpecPerState() {
        fun hints(status: String) = downloadHints(row(1, status), empty = false)
        assertEquals(
            listOf(ControllerHint(A, "Details"), ControllerHint(X, "Cancel"), ControllerHint(B, "Back")),
            hints(DownloadEngine.ST_ACTIVE),
        )
        assertEquals(
            listOf(ControllerHint(A, "Resume"), ControllerHint(X, "Cancel"), ControllerHint(B, "Back")),
            hints(DownloadEngine.ST_PAUSED),
        )
        assertEquals(
            listOf(ControllerHint(A, "Retry"), ControllerHint(X, "Remove"), ControllerHint(B, "Back")),
            hints(DownloadEngine.ST_FAILED),
        )
        assertEquals(
            listOf(ControllerHint(A, "Open"), ControllerHint(X, "Remove"), ControllerHint(B, "Back")),
            hints(DownloadEngine.ST_DONE),
        )
        assertEquals(
            listOf(ControllerHint(A, "Browse games"), ControllerHint(B, "Back")),
            downloadHints(null, empty = true),
        )
        assertEquals(
            listOf(ControllerHint(A, "Pause"), ControllerHint(B, "Back")),
            downloadHints(row(1, DownloadEngine.ST_ACTIVE), empty = false, paneAction = DownloadAction.Pause),
        )
    }

    @Test fun completedRowsNeverOfferCancel() {
        assertEquals(DownloadAction.Remove, downloadSecondaryAction(DownloadEngine.ST_DONE))
        assertFalse(DownloadAction.Cancel in downloadJobActions(DownloadEngine.ST_DONE))
        assertEquals(listOf(DownloadAction.Open, DownloadAction.Remove), downloadJobActions(DownloadEngine.ST_DONE))
        assertNull(downloadPrimaryAction(null))
    }

    @Test fun paneActionsKeepPauseAndVerifyReachable() {
        assertEquals(
            listOf(DownloadAction.ClearCompleted, DownloadAction.Pause, DownloadAction.Cancel),
            downloadPaneActions(DownloadEngine.ST_ACTIVE, canClearCompleted = true),
        )
        assertEquals(
            listOf(DownloadAction.Verify, DownloadAction.Cancel),
            downloadPaneActions(DownloadEngine.ST_NEEDS_ASSIST, canClearCompleted = false),
        )
        assertEquals(listOf(DownloadAction.Cancel), downloadJobActions(DownloadEngine.ST_INGEST))
        assertEquals(listOf(DownloadAction.ClearCompleted), downloadPaneActions(null, true))
    }

    @Test fun percentOnlyWhenDeterminate() {
        assertNull(downloadPercent(10, 0))
        assertEquals(0, downloadPercent(0, 100))
        assertEquals(42, downloadPercent(42, 100))
        assertEquals(100, downloadPercent(150, 100))
    }

    @Test fun metaDistinguishesDuplicates() {
        val a = row(1, DownloadEngine.ST_DONE).copy(fileName = "Game (USA).zip", sourceLabel = "Acme")
        val b = row(2, DownloadEngine.ST_DONE).copy(fileName = "Game (Europe).zip")
        assertEquals("SNES · Game (USA).zip · from Acme", downloadRowMeta(a, platform = "SNES"))
        assertTrue(downloadRowMeta(a, "SNES") != downloadRowMeta(b, "SNES"))
        assertEquals("", downloadRowMeta(row(3, DownloadEngine.ST_DONE), platform = null))
    }

    @Test fun mergeCarriesFileNamePlatformAndTime() {
        val rec = DownloadRecord(
            id = 9, pageUrl = "https://acme.example/g", title = "G", destRelPath = "snes/g.zip",
            volumeId = "internal", bytes = 10, status = DownloadEngine.ST_DONE,
            fileName = "g.zip", platformFolder = "snes", updatedAtMs = 1234,
        )
        val r = mergeDownloadRows(listOf(rec), emptyMap()).single()
        assertEquals("g.zip", r.fileName)
        assertEquals("snes", r.platformFolder)
        assertEquals(1234L, r.updatedAtMs)
    }

    @Test fun dismissalsPruneOnlyOnceHistoryIsKnown() {
        assertEquals(setOf(1L, 2L, 3L), pruneDismissed(setOf(1L, 2L), listOf(3L), existing = emptySet()))
        assertEquals(setOf(2L, 3L), pruneDismissed(setOf(1L, 2L), listOf(3L), existing = setOf(2L)))
        val d = DownloadDismissals(null)
        d.dismiss(listOf(5L), existing = setOf(5L, 6L))
        d.dismiss(listOf(6L), existing = setOf(5L, 6L))
        assertEquals(setOf(5L, 6L), d.ids.value)
    }

    @Test fun volumeLabel() {
        assertEquals("SD card", downloadVolumeLabel("/storage/1A2B-3C4D/GameBEX"))
        assertEquals("Internal storage", downloadVolumeLabel("/storage/emulated/0/Download/GameBEX"))
    }
}
