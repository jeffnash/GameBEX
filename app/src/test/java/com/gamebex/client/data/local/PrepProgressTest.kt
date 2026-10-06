package com.gamebex.client.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bus lifecycle, stall rule, and the user-facing progress line. */
class PrepProgressTest {
    private val t0 = 1_000_000L

    private fun snap(
        bytes: Long = 0,
        total: Long? = null,
        phase: PrepPhase = PrepPhase.EXTRACTING,
        updated: Long = t0,
        started: Long = t0,
    ) = PrepProgress(7L, phase, bytes, total, started, updated)

    @Test fun percentNeedsKnownTotal() {
        assertEquals(31, PrepProgressBus.percentOf(snap(bytes = 31, total = 100)))
        assertNull(PrepProgressBus.percentOf(snap(bytes = 31, total = null)))
        assertNull(PrepProgressBus.percentOf(snap(bytes = 31, total = 0)))
    }

    @Test fun stallRuleIsTwentySecondsQuiet() {
        assertFalse(PrepProgressBus.isStalled(snap(updated = t0), t0 + 19_999))
        assertTrue(PrepProgressBus.isStalled(snap(updated = t0), t0 + 20_000))
    }

    @Test fun displayLineShowsPhaseBytesAndPercent() {
        val mb = 1024L * 1024
        val p = snap(bytes = 312 * mb, total = 996 * mb)
        assertEquals(
            "Extracting archive… 312 of 996 MB · 31%",
            p.displayLine(t0 + 1_000),
        )
    }

    @Test fun displayLineWithoutTotalShowsBytesOnly() {
        val mb = 1024L * 1024
        val p = snap(bytes = 312 * mb, total = null)
        assertEquals("Extracting archive… 312 MB", p.displayLine(t0 + 1_000))
    }

    @Test fun displayLineStalledSaysStillWorking() {
        val mb = 1024L * 1024
        val p = snap(bytes = 312 * mb, total = 996 * mb, updated = t0)
        assertEquals(
            "Still working… 312 of 996 MB · 31%",
            p.displayLine(t0 + 25_000),
        )
    }

    @Test fun displayLineCopyingPhase() {
        val mb = 1024L * 1024
        val p = snap(bytes = 996 * mb, total = 996 * mb, phase = PrepPhase.COPYING)
        assertEquals("Copying files… 996 of 996 MB · 100%", p.displayLine(t0 + 1_000))
    }

    @Test fun formatBytesUnits() {
        assertEquals("0 B", PrepProgress.formatBytes(0))
        assertEquals("512 B", PrepProgress.formatBytes(512))
        assertEquals("2 KB", PrepProgress.formatBytes(2048))
        assertEquals("312 MB", PrepProgress.formatBytes(312L * 1024 * 1024))
        assertEquals("1.4 GB", PrepProgress.formatBytes((1.4 * 1024 * 1024 * 1024).toLong()))
    }

    @Test fun phaseLabels() {
        assertEquals("Extracting archive", PrepPhase.EXTRACTING.label)
        assertEquals("Copying files", PrepPhase.COPYING.label)
        assertEquals("Fixing disc image", PrepPhase.FIXING.label)
        assertEquals("Finishing", PrepPhase.FINISHING.label)
    }

    @Test fun busBeginProgressClear() {
        val id = 4242L
        try {
            PrepProgressBus.begin(id, PrepPhase.EXTRACTING, 100L, t0)
            // Inside the 200ms throttle: no update.
            PrepProgressBus.progress(id, PrepPhase.EXTRACTING, 10L, 100L, t0 + 50)
            assertEquals(0L, PrepProgressBus.forGame(id)!!.bytesWritten)
            PrepProgressBus.progress(id, PrepPhase.EXTRACTING, 31L, 100L, t0 + 250)
            assertEquals(31L, PrepProgressBus.forGame(id)!!.bytesWritten)
            // Never steps backwards.
            PrepProgressBus.progress(id, PrepPhase.EXTRACTING, 5L, 100L, t0 + 500)
            assertEquals(31L, PrepProgressBus.forGame(id)!!.bytesWritten)
        } finally {
            PrepProgressBus.clear(id)
        }
        assertNull(PrepProgressBus.forGame(id))
    }

    @Test fun downloadLinkResolvesThenClearsWithGame() {
        val dl = 777L
        val game = 778L
        try {
            PrepProgressBus.begin(game, PrepPhase.EXTRACTING, null, t0)
            PrepProgressBus.linkDownload(dl, game)
            assertEquals(game, PrepProgressBus.forDownload(dl)!!.gameId)
            PrepProgressBus.clear(game)
            assertNull(PrepProgressBus.forDownload(dl))
        } finally {
            PrepProgressBus.clear(game)
        }
    }
}
