package com.gamebex.client.net

import com.gamebex.client.data.local.DownloadRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Engine pure logic: range parsing and writer identity. */
class DownloadEngineTest {
    @Test fun contentRangeParses() {
        assertEquals(
            DownloadEngine.Companion.ContentRange(100, 199, 1000),
            DownloadEngine.parseContentRange("bytes 100-199/1000"),
        )
    }

    @Test fun contentRangeUnknownTotal() {
        assertEquals(
            DownloadEngine.Companion.ContentRange(0, 99, null),
            DownloadEngine.parseContentRange("bytes 0-99/*"),
        )
    }

    @Test fun contentRangeRejects() {
        assertNull(DownloadEngine.parseContentRange(null))
        assertNull(DownloadEngine.parseContentRange("bytes */1000"))
        assertNull(DownloadEngine.parseContentRange("garbage"))
        assertNull(DownloadEngine.parseContentRange("bytes 100-99/1000x"))
    }

    @Test fun destKeySameFile() {
        assertEquals(
            DownloadEngine.destKeyFor("internal", "gb/game.zip", null),
            DownloadEngine.destKeyFor("internal", "gb/game.zip", null),
        )
    }

    @Test fun destKeyDiffersByVolume() {
        assertNotEquals(
            DownloadEngine.destKeyFor("internal", "gb/game.zip", null),
            DownloadEngine.destKeyFor("sd:1D20-0000", "gb/game.zip", null),
        )
    }

    @Test fun destKeySdCaseBlind() {
        assertEquals(
            DownloadEngine.destKeyFor("sd:1D20-0000", "gb/Game.ZIP", null),
            DownloadEngine.destKeyFor("sd:1d20-0000", "GB/game.zip", null),
        )
    }

    @Test fun destKeyInternalCaseSharp() {
        assertNotEquals(
            DownloadEngine.destKeyFor("internal", "gb/Game.zip", null),
            DownloadEngine.destKeyFor("internal", "gb/game.zip", null),
        )
    }

    @Test fun destKeyRawDirsDistinct() {
        assertNotEquals(
            DownloadEngine.destKeyFor("internal", "box.jpg", "/art/12"),
            DownloadEngine.destKeyFor("internal", "box.jpg", "/art/34"),
        )
    }

    @Test fun sanitizeSlashesBecomeUnderscores() {
        assertEquals("a_b_c.zip", DownloadEngine.sanitizeTargetFileName("a/b\\c.zip"))
    }

    @Test fun sanitizeRejectsEmptyDotAndControls() {
        for (raw in listOf("", "   ", ".", "..", "...", "a\u0000b", "a\nb", "a\u007fb")) {
            try {
                DownloadEngine.sanitizeTargetFileName(raw)
                fail("accepted: $raw")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    private fun sourceRec(
        sourceId: String? = "s1",
        itemKey: String? = "item",
        variantKey: String? = "v",
        volumeId: String = "internal",
        destRelPath: String = "gb/game.zip",
    ) = DownloadRecord(
        pageUrl = "https://x", title = "t", destRelPath = destRelPath,
        volumeId = volumeId, bytes = 0, status = DownloadEngine.ST_QUEUED,
        sourceId = sourceId, sourceItemKey = itemKey, sourceVariantKey = variantKey,
    )

    @Test fun sameIdentityMatchesDoubleTap() {
        val rec = sourceRec()
        val key = DownloadEngine.destKeyFor("internal", "gb/game.zip", null)
        assertTrue(DownloadEngine.sameSourceIdentity(rec, "s1", "item", "v", key))
    }

    @Test fun differentSourceOrVariantOrDestIsNotSameJob() {
        val key = DownloadEngine.destKeyFor("internal", "gb/game.zip", null)
        // Same remote item from another source: a different job.
        assertFalse(DownloadEngine.sameSourceIdentity(sourceRec(sourceId = "s2"), "s1", "item", "v", key))
        assertFalse(DownloadEngine.sameSourceIdentity(sourceRec(itemKey = "other"), "s1", "item", "v", key))
        assertFalse(DownloadEngine.sameSourceIdentity(sourceRec(variantKey = "v2"), "s1", "item", "v", key))
        assertFalse(DownloadEngine.sameSourceIdentity(sourceRec(destRelPath = "gb/other.zip"), "s1", "item", "v", key))
        // Rows without provenance never match a source identity.
        assertFalse(DownloadEngine.sameSourceIdentity(sourceRec(sourceId = null), "s1", "item", "v", key))
    }
}
