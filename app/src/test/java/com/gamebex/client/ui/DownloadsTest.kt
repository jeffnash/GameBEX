package com.gamebex.client.ui

import com.gamebex.client.data.local.DownloadRecord
import com.gamebex.client.net.DownloadEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Downloads screen policy: art separation, labels, history+live merge. */
class DownloadsTest {
    private fun rec(
        id: Long,
        pageUrl: String = "https://acme.example/g",
        destDirAbs: String? = null,
        transferKind: String = "INTERNAL",
        sourceId: String? = null,
    ) = DownloadRecord(
        id = id, pageUrl = pageUrl, title = "Game $id",
        destRelPath = "g.zip", volumeId = "internal", bytes = 10, status = DownloadEngine.ST_DONE,
        fileName = "g.zip", destDirAbs = destDirAbs,
        transferKind = transferKind, sourceId = sourceId,
    )

    private fun snap(
        id: Long,
        pageUrl: String = "https://acme.example/g",
        transferKind: String = "INTERNAL",
        sourceId: String? = null,
    ) = DownloadEngine.DlSnapshot(
        id = id, title = "Game $id", fileName = "g.zip",
        status = DownloadEngine.ST_ACTIVE, read = 5, total = 10,
        error = null, pageUrl = pageUrl,
        sourceId = sourceId, transferKind = transferKind,
    )

    @Test fun artTransfersDetected() {
        assertTrue(isArtTransfer("ss://123/box", null))
        assertTrue(isArtTransfer("https://x/y.jpg", "/data/data/x/files/art/7"))
        assertFalse(isArtTransfer("https://catalog.example/game", null))
    }

    @Test fun stateLabelsCoverAllStatuses() {
        assertEquals("Queued", downloadStateLabel(DownloadEngine.ST_QUEUED))
        assertEquals("Downloading", downloadStateLabel(DownloadEngine.ST_ACTIVE))
        assertEquals("Paused", downloadStateLabel(DownloadEngine.ST_PAUSED))
        assertEquals("SD ejected — parked", downloadStateLabel(DownloadEngine.ST_PARKED_SD))
        assertEquals("Needs verification", downloadStateLabel(DownloadEngine.ST_NEEDS_ASSIST))
        assertEquals("Source unavailable", downloadStateLabel(DownloadEngine.ST_SOURCE_UNAVAILABLE))
        assertEquals("Failed", downloadStateLabel(DownloadEngine.ST_FAILED))
        assertEquals("Finishing…", downloadStateLabel(DownloadEngine.ST_INGEST))
        assertEquals("Done", downloadStateLabel(DownloadEngine.ST_DONE))
    }

    @Test fun mergeOverlaysLiveProgressById() {
        val rows = mergeDownloadRows(listOf(rec(1)), mapOf(1L to snap(1)))
        assertEquals(1, rows.size)
        assertEquals(DownloadEngine.ST_ACTIVE, rows[0].status)
        assertEquals(5, rows[0].read)
        assertTrue(rows[0].live)
    }

    @Test fun mergeDropsArtFromBothSides() {
        val rows = mergeDownloadRows(
            listOf(rec(1, "ss://1/box", "/art/1"), rec(2)),
            mapOf(1L to snap(1, "ss://1/box"), 3L to snap(3, "ss://3/box")),
        )
        assertEquals(listOf(2L), rows.map { it.id })
    }

    @Test fun mergeDropsArtByTransferKind() {
        val rows = mergeDownloadRows(
            listOf(rec(1, transferKind = "ART"), rec(2)),
            mapOf(3L to snap(3, transferKind = "ART")),
        )
        assertEquals(listOf(2L), rows.map { it.id })
    }

    @Test fun mergeResolvesSourceLabels() {
        val rows = mergeDownloadRows(
            listOf(rec(1, sourceId = "s1"), rec(2)),
            mapOf(1L to snap(1, sourceId = "s1")),
            sourceNames = mapOf("s1" to "Acme"),
        )
        assertEquals("Acme", rows.single { it.id == 1L }.sourceLabel)
        assertEquals(null, rows.single { it.id == 2L }.sourceLabel)
    }

    @Test fun mergePrependsLiveJobsMissingFromHistory() {
        val rows = mergeDownloadRows(listOf(rec(2)), mapOf(9L to snap(9)))
        assertEquals(listOf(9L, 2L), rows.map { it.id })
        assertTrue(rows[0].live)
        assertFalse(rows[1].live)
    }
}
