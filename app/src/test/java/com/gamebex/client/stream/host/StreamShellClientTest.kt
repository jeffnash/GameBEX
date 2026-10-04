package com.gamebex.client.stream.host

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class StreamShellClientTest {
    @Test fun cancelBeforeConsentCannotMirror() = runTest {
        var stops = 0
        CaptureIdentity.attach({ false }, { true }, { stops++ })
        assertEquals(1, stops)
    }

    @Test fun timeoutStops() = runTest {
        val consent = StreamConsentLifecycle<Boolean>()
        val pending = consent.open()
        var stops = 0
        CaptureIdentity.attach({ consent.await(pending, 1) == true }, { true }, { stops++ })
        assertEquals(1, stops)
    }

    @Test fun failureStops() = runTest {
        var stops = 0
        CaptureIdentity.attach({ throw IllegalStateException("projection failed") }, { true }, { stops++ })
        assertEquals(1, stops)
    }

    @Test fun oldAttachResultCannotStopNewShellSession() = runTest {
        val oldService = Any()
        val newService = Any()
        CaptureIdentity.attach({ false },
            { CaptureIdentity.ownsSession(2, 1, newService, oldService) }, { error("stopped new session") })
    }

    @Test fun cancellationStopsCurrentSessionAndIsRethrown() = runTest {
        var stops = 0
        val cancelled = CancellationException("leave")
        try {
            CaptureIdentity.attach({ throw cancelled }, { true }, { stops++ })
            fail("cancellation swallowed")
        } catch (caught: CancellationException) {
            assertSame(cancelled, caught)
        }
        assertEquals(1, stops)
    }

    @Test fun sharedCaptureDoesNotStop() = runTest {
        CaptureIdentity.attach({ true }, { true }, { error("shared capture stopped") })
    }
}
