package com.gamebex.client.stream.host

import org.junit.Assert.*
import org.junit.Test

class CaptureIdentityTest {
    @Test fun oldProjectionStopCannotStopNewProjection() {
        val old = Any()
        val current = Any()
        var active: Any? = current
        var stops = 0
        assertFalse(CaptureIdentity.stopProjection(active, old) { active = null; stops++ })
        assertSame(current, active)
        assertEquals(0, stops)
        assertTrue(CaptureIdentity.stopProjection(active, current) { active = null; stops++ })
        assertNull(active)
        assertEquals(1, stops)
        assertFalse(CaptureIdentity.stopProjection(active, old) { error("retired callback") })
    }

    @Test fun sessionRequiresBothEpochAndExactService() {
        val service = Any()
        assertTrue(CaptureIdentity.ownsSession(3, 3, service, service))
        assertFalse(CaptureIdentity.ownsSession(4, 3, service, service))
        assertFalse(CaptureIdentity.ownsSession(3, 3, Any(), service))
    }

    @Test fun android13UsesStandardWholeScreenConsent() {
        assertTrue(CaptureIdentity.supported(33))
        assertFalse(CaptureIdentity.supported(32))
        var prompts = 0
        assertEquals("whole-screen", CaptureIdentity.consentIntent(33,
            userChoice = { error("Android 13 has no user-choice config") },
            wholeScreen = { prompts++; "whole-screen" }))
        assertEquals(1, prompts)
        assertEquals("user-choice", CaptureIdentity.consentIntent(34,
            userChoice = { "user-choice" }, wholeScreen = { error("wrong prompt") }))
    }
}
