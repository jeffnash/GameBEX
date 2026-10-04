package com.gamebex.client.stream.host

import org.junit.Assert.*
import org.junit.Test

class StreamAppCaptureTest {
    @Test fun staleSurfaceCannotResizeNewDisplay() {
        val oldService = Any()
        val currentService = Any()
        val epoch = java.util.concurrent.atomic.AtomicLong(2)
        assertFalse(CaptureIdentity.surfaceChange(
            { CaptureIdentity.ownsSession(epoch.get(), 1, currentService, oldService) }, Any(), true, 1280, 576,
            { _, _, _ -> error("stale resize") }, { _ -> error("stale retarget") },
            { error("stale store") }, { error("stale stop") }))
    }

    @Test fun codecResizeReusesVirtualDisplay() {
        val display = Any()
        val targets = mutableListOf<Any>()
        val sizes = mutableListOf<Pair<Int, Int>>()
        for ((w, h) in listOf(1280 to 576, 960 to 432)) {
            assertTrue(CaptureIdentity.surfaceChange({ true }, display, true, w, h,
                { actualDisplay, width, height ->
                    targets += actualDisplay
                    sizes += width to height
                }, { actualDisplay -> targets += actualDisplay }, {}, { error("stop") }))
        }
        assertEquals(listOf(1280 to 576, 960 to 432), sizes)
        assertEquals(4, targets.size)
        assertTrue(targets.all { it === display })
    }

    @Test fun repeatedSurfaceAndDimensionsAreNoOpButSameSurfaceResizeIsApplied() {
        val surface = Any()
        val display = Any()
        assertTrue(CaptureIdentity.surfaceChange({ true }, display, true, 1280, 576,
            { _, _, _ -> error("duplicate resize") }, { _ -> error("duplicate target") },
            { error("duplicate store") }, { error("stop") }, surface, surface, 1280, 576))
        var resizes = 0
        assertTrue(CaptureIdentity.surfaceChange({ true }, display, true, 960, 432,
            { actual, w, h -> assertSame(display, actual); assertEquals(960, w); assertEquals(432, h); resizes++ },
            {}, {}, { error("stop") }, surface, surface, 1280, 576))
        assertEquals(1, resizes)
    }

    @Test fun invalidDimensionsStopBeforeResizeOrRetarget() {
        var stops = 0
        for ((w, h) in listOf(0 to 576, 1280 to -1)) {
            assertFalse(CaptureIdentity.surfaceChange({ true }, Any(), true, w, h,
                { _, _, _ -> error("invalid resize") }, { _ -> error("invalid target") },
                { error("invalid store") }, { stops++ }))
        }
        assertEquals(2, stops)
    }

    @Test fun failedResizeStopsInsteadOfContinuingCapture() {
        var stops = 0
        assertFalse(CaptureIdentity.surfaceChange({ true }, Any(), true, 1280, 576,
            { _, _, _ -> throw IllegalStateException("display lost") }, { _ -> error("retarget after failure") },
            { error("store after failure") }, { stops++ }))
        assertEquals(1, stops)
    }

    @Test fun nullSurfaceDetachesWithoutResizing() {
        var detached = false
        assertTrue(CaptureIdentity.surfaceChange({ true }, Any(), false, 0, 0,
            { _, _, _ -> error("null resize") }, { detached = true }, {}, { error("stop") }))
        assertTrue(detached)
    }
}
