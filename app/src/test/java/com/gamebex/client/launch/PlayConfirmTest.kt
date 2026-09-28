package com.gamebex.client.launch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A play = a direct ROM launch whose session closed at the next resume. */
class PlayConfirmTest {
    private fun direct(at: Long = 1_000_000L) =
        PlayConfirm.Pending(42L, "com.retroarch.aarch64", at, true)

    @Test fun roundTrip() {
        val p = direct(1_700_000_000_000L)
        assertEquals(p, PlayConfirm.decode(PlayConfirm.encode(p)))
    }

    @Test fun launcherOnlyRoundTrip() {
        val p = PlayConfirm.Pending(7L, "org.scummvm.scummvm", 1_700_000_000_000L, false)
        assertEquals(p, PlayConfirm.decode(PlayConfirm.encode(p)))
    }

    @Test fun decodeRejectsJunk() {
        assertNull(PlayConfirm.decode(null))
        assertNull(PlayConfirm.decode(""))
        assertNull(PlayConfirm.decode("no-pipes-here"))
        assertNull(PlayConfirm.decode("42|com.retroarch.aarch64|1000000"))
        assertNull(PlayConfirm.decode("x|pkg|123"))
        assertNull(PlayConfirm.decode("1||123"))
        assertNull(PlayConfirm.decode("1|pkg|x"))
        assertNull(PlayConfirm.decode("1|pkg|123|1|extra"))
    }

    @Test fun directLaunchCountsAtAnyDuration() {
        // The contract only establishes launch + return, so any fresh return counts.
        assertTrue(PlayConfirm.shouldAttributePlay(direct(), 1_005_000L))
        assertTrue(PlayConfirm.shouldAttributePlay(direct(), 1_000_000L + 120_000L))
    }

    @Test fun launcherOnlyNeverAttributes() {
        val p = PlayConfirm.Pending(1L, "org.scummvm.scummvm", 1_000_000L, false)
        assertFalse(PlayConfirm.shouldAttributePlay(p, 1_005_000L))
        assertFalse(PlayConfirm.shouldAttributePlay(p, 1_000_000L + 3_600_000L))
    }

    @Test fun knownLauncherOnlyPkgsAreNotDirect() {
        assertFalse(PlayConfirm.isDirectBoot("org.scummvm.scummvm"))
        assertFalse(PlayConfirm.isDirectBoot("com.seleuco.mame4d2024"))
        assertTrue(PlayConfirm.isDirectBoot("com.retroarch.aarch64"))
        assertTrue(PlayConfirm.isDirectBoot("org.ppsspp.ppsspp"))
    }

    @Test fun stalePendingNeverCounts() {
        val p = direct()
        assertFalse(PlayConfirm.shouldAttributePlay(p, 1_000_000L + PlayConfirm.MAX_PENDING_AGE_MS + 1))
    }

    @Test fun futureStampNeverCounts() {
        assertFalse(PlayConfirm.shouldAttributePlay(direct(2_000_000L), 1_000_000L))
    }
}
