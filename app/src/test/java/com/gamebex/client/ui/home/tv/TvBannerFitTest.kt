package com.gamebex.client.ui.home.tv

import com.gamebex.client.ui.console.ConsoleGeometry
import com.gamebex.client.ui.home.model.CardState
import com.gamebex.client.ui.home.model.HomeCopy
import com.gamebex.client.ui.home.model.SizeInfo
import com.gamebex.client.ui.home.model.SystemTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TV banner fitting: both banner lines are pinned to the mockup size
 * (never scaled by the app's text-size preference), the state line is
 * always ONE line — key and detail step down together to a 15 px
 * floor — and when the copy still does not fit, only the detail
 * ellipsizes and the key stays whole.
 */
class TvBannerFitTest {

    /** Silkscreen-like advance: 0.81em per character plus tracking. */
    private val measure = { text: String, sizeSp: Float, tracking: Float ->
        text.length * sizeSp * (0.81f + tracking)
    }

    /** The banner content box: 646 px screen minus 22 px padding each side. */
    private val availPx = 602f

    private fun fit(key: String, detail: String?, avail: Float = availPx) = fitStateLine(
        key = key,
        detail = detail,
        sizes = listOf(19f, 18f, 17f, 16f, 15f),
        trackings = listOf(.04f, .04f, .04f, .04f, .04f),
        availPx = avail,
        glyphPx = 24f,
        gapPx = 10f,
        splitPx = 12f,
        measure = measure,
    )

    private fun keyNeed(f: StateLineFit, key: String): Float =
        24f + 10f + measure(key, f.sizeSp, f.tracking)

    private fun oneLineNeed(f: StateLineFit, key: String, detail: String?): Float =
        keyNeed(f, key) +
            (if (detail == null) 0f else 12f + measure(detail, f.sizeSp, f.tracking))

    @Test fun bannerLineTypeIsPinnedToTheMockupNotTheTextPreference() {
        // g.t(21) inflates with DisplayPrefs (default Large 1.25);
        // mockupType pins BOTH banner lines to the mockup (state 19/15 px,
        // facts 21 px).
        for (scale in floatArrayOf(0.5f, 1f, 1.33f)) {
            for (textScale in floatArrayOf(1f, 1.25f, 1.4f)) {
                val g = ConsoleGeometry(scale, textScale)
                assertEquals(19f * scale, g.mockupType(19).value, 0.001f)
                assertEquals(21f * scale, g.mockupType(21).value, 0.001f)
                assertEquals(15f * scale, g.mockupType(15).value, 0.001f)
                if (textScale > 1f) {
                    assertTrue(g.mockupType(21).value < g.t(21).value)
                }
            }
        }
    }

    @Test fun reportedSiteLineFitsWholeAtMockupSize() {
        // "…NOT ON YOU|" clipped mid-word.
        val f = fit("ON ROMS-I-OWN.COM", "NOT ON YOUR PHONE")
        assertFalse(f.detailEllipsized)
        assertEquals(19f, f.sizeSp, 0.001f)
        assertTrue(oneLineNeed(f, "ON ROMS-I-OWN.COM", "NOT ON YOUR PHONE") <= availPx)
    }

    @Test fun reportedDownloadLineFitsWholeAtMockupSize() {
        // "…PLAYS WHEN REA" truncated the same way.
        val f = fit("DOWNLOADING 64%", "PLAYS WHEN READY")
        assertFalse(f.detailEllipsized)
        assertEquals(19f, f.sizeSp, 0.001f)
        assertTrue(oneLineNeed(f, "DOWNLOADING 64%", "PLAYS WHEN READY") <= availPx)
    }

    @Test fun shortLineStaysAtTheMockupNineteen() {
        val f = fit("RESUME", "SAVED 12 MIN AGO")
        assertFalse(f.detailEllipsized)
        assertEquals(19f, f.sizeSp, 0.001f)
        assertEquals(0.04f, f.tracking, 0.001f)
    }

    @Test fun stateLineTypeCandidatesNeverStepBelowTheFifteenPxFloor() {
        val (sizes, _) = stateLineCandidates(ConsoleGeometry(1f, 1.25f))
        assertTrue("candidates $sizes must not go below 15 px", sizes.min() >= 15f)
        assertEquals(19f, sizes.max(), 0.001f)
    }

    @Test fun overlongStateLineIsEllipsizedAtTheFloorNeverWrapped() {
        val f = fit(
            key = "ON SOME-REALLY-LONG-ROM-SITE-EXAMPLE.COM",
            detail = "NOT ON YOUR PHONE AND THEN SOME MORE DETAIL",
        )
        assertEquals("cut at the 15 px floor, not smaller", 15f, f.sizeSp, 0.001f)
        assertTrue("the detail is what gets cut", f.detailEllipsized)
    }

    @Test fun theKeyIsNeverEllipsizedAtAnyLength() {
        // The key is budgeted whole first; any cut is detail-side only.
        for (len in 1..60) {
            val key = "K".repeat(len)
            val detail = "D".repeat(len)
            val f = fit(key, detail)
            assertTrue("floor $len", f.sizeSp >= 15f - 0.001f)
            assertEquals("key gets its whole width $len", keyNeed(f, key), f.keyNeedPx, 0.001f)
            if (measure(detail, f.sizeSp, f.tracking) > f.detailAvailPx + 0.001f) {
                assertTrue("cut is detail-only $len", f.detailEllipsized)
            }
        }
    }

    @Test fun keyOnlyLinesKeepFullWidthAndNoDetailBudget() {
        // "NEEDS THE SATURN BIOS" is a whole key.
        val f = fit("NEEDS THE SATURN BIOS", null)
        assertEquals(19f, f.sizeSp, 0.001f)
        assertFalse(f.detailEllipsized)
        assertEquals(0f, f.detailAvailPx, 0.001f)
        assertEquals(keyNeed(f, "NEEDS THE SATURN BIOS"), f.keyNeedPx, 0.001f)
    }

    @Test fun everyProductionStateLineFitsAtOrAboveTheFloorWithItsKeyWhole() {
        // Real HomeCopy copy through the real candidate ladder: the key
        // is budgeted whole first, the floor holds, and any cut is
        // detail-side only.
        val now = 1_700_000_000_000L
        val system = SystemTag("saturn", "SAT", "Saturn", "RetroArch · Beetle Saturn")
        val states = listOf(
            "saved" to CardState.Saved(now - 12 * 60_000L, 3),
            "played" to CardState.Played(now - 26 * 3_600_000L),
            "added" to CardState.Added(now),
            "needs-bios" to CardState.NeedsBios(listOf("sega_101.bin")),
            "needs-emulator" to CardState.NeedsEmulator("Dolphin"),
            "needs-fix" to CardState.NeedsFix("Point it at your ROMs folder."),
            "preparing" to CardState.Preparing(),
            "downloading" to CardState.Downloading(64L, 100L, 180L, paused = false, armed = false),
            "downloading-armed" to
                CardState.Downloading(64L, 100L, 180L, paused = false, armed = true),
            "paused" to CardState.Downloading(64L, 100L, null, paused = true, armed = false),
            "queued" to CardState.Queued(armed = true),
            "download-problem" to CardState.DownloadProblem("The download stopped.", verify = false),
            "offphone" to CardState.OffPhone(SizeInfo(1_200_000_000L, "1.2 GB"), 2),
            "onsite" to CardState.OnSite(SizeInfo(1_400_000_000L, "1.4 GB")),
            "no-room" to CardState.NoRoom(1_400_000_000L, 600_000_000L),
            "waiting" to CardState.WaitingForConnection,
            "unsupported" to CardState.Unsupported("This phone has no route for it yet."),
            "unavailable" to CardState.Unavailable("Needs the SD card."),
            "none" to CardState.None,
        )
        val (sizes, trackings) = stateLineCandidates(ConsoleGeometry(1f, 1.25f))
        for ((name, state) in states) {
            val key = HomeCopy.tvStateKey(state, "roms-i-own.com", now, system)
            val detail = HomeCopy.tvStateDetail(state, "roms-i-own.com", now)
            val f = fitStateLine(
                key = key,
                detail = detail,
                sizes = sizes,
                trackings = trackings,
                availPx = availPx,
                glyphPx = 24f,
                gapPx = 10f,
                splitPx = 12f,
                measure = measure,
            )
            assertTrue("$name floor", f.sizeSp >= 15f - 0.001f)
            assertEquals(
                "$name key whole",
                keyNeed(f, key),
                f.keyNeedPx,
                0.001f,
            )
            val need = detail?.let { measure(it, f.sizeSp, f.tracking) } ?: 0f
            assertEquals(
                "$name cut is detail-only",
                detail != null && need > f.detailAvailPx,
                f.detailEllipsized,
            )
        }
    }

    @Test fun longSiteNameKeyIsNeverCutOrShrunkBelowTheFloor() {
        // HomeCopy's OnSite key "ON {site}" has no length bound. A very long
        // site name overflows the banner at the 15 px floor — the key still
        // renders whole (never ellipsized, wrapped or shrunk) and the detail
        // is what gets cut, down to nothing when the key alone overflows.
        val now = 1_700_000_000_000L
        val site = "some-extremely-long-rom-site-name-example.com"
        val key = HomeCopy.tvStateKey(CardState.OnSite(null), site, now)
        val detail = HomeCopy.tvStateDetail(CardState.OnSite(null), site, now)
        val f = fitStateLine(
            key = key,
            detail = detail,
            sizes = listOf(19f, 18f, 17f, 16f, 15f),
            trackings = listOf(.04f, .04f, .04f, .04f, .04f),
            availPx = availPx,
            glyphPx = 24f,
            gapPx = 10f,
            splitPx = 12f,
            measure = measure,
        )
        assertEquals("floor, not smaller", 15f, f.sizeSp, 0.001f)
        assertEquals("key keeps its full width", keyNeed(f, key), f.keyNeedPx, 0.001f)
        assertTrue("the key overflows the banner at the floor", f.keyNeedPx > availPx)
        assertEquals("the detail gets only what is left", 0f, f.detailAvailPx, 0.001f)
        assertTrue("the cut is detail-side", f.detailEllipsized)
    }
}
