package com.gamebex.client.together.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamResumeGuardTest {
    private val token = "00112233445566778899aabbccddeeff"
    private val owner = StreamResumeGuard.Bound("room", 1, token, "device", "10.42.1.21", "bridge-arrival")

    @Test
    fun onlyExactAuthenticatedIdentityMatches() {
        assertTrue(StreamResumeGuard.matches(owner, "room", 1, token, "device", "10.42.1.21"))
        for (candidate in listOf("", token.dropLast(1), token + "0", token.uppercase(), "f".repeat(32))) {
            assertFalse(StreamResumeGuard.matches(owner, "room", 1, candidate, "device", "10.42.1.21"))
        }
        assertFalse(StreamResumeGuard.matches(owner, "other", 1, token, "device", "10.42.1.21"))
        for (round in listOf(Int.MIN_VALUE, -1, 0, 2, Int.MAX_VALUE)) {
            assertFalse(StreamResumeGuard.matches(owner, "room", round, token, "device", "10.42.1.21"))
        }
        assertFalse(StreamResumeGuard.matches(owner, "room", 1, token, "other", "10.42.1.21"))
        assertFalse(StreamResumeGuard.matches(owner, "room", 1, token, "device", "10.42.1.22"))
    }

    @Test
    fun attemptMustBeCanonicalUuidAndRetryDelayIsBounded() {
        assertTrue(StreamResumeGuard.validAttempt("12345678-abcd-4000-8000-000000000001"))
        for (attempt in listOf("", "1-1-1-1-1", "12345678-ABCD-4000-8000-000000000001", "invalid")) {
            assertFalse(StreamResumeGuard.validAttempt(attempt))
        }
        assertEquals(listOf(250L, 500L, 1000L, 2000L, 2000L), (0..4).map(StreamResumeGuard::delayMs))
        assertFalse(TogetherControlOptions().controlResume)
        assertEquals(0, TogetherControlOptions().linkCutEveryS)
    }
}
