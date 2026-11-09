package com.gamebex.client.stream.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureTraceTest {
    @Test fun keyframeSpacingUsesCaptureAndOutputTimeIndependently() {
        val logs = mutableListOf<String>()
        val trace = CaptureTrace(now = { 1L }, log = { logs += it })
        trace.onOutput(1_000_000L, 1_020_000_000L, 100, keyframe = true)
        trace.onOutput(2_000_000L, 2_020_000_000L, 20)
        trace.onOutput(11_000_000L, 11_030_000_000L, 120, keyframe = true)
        assertEquals(2, logs.size)
        assertTrue(logs.last().contains("spacingPtsS=10.0"))
        assertTrue(logs.last().contains("spacingOutputS=10.01"))
        assertTrue(logs.last().contains("bytes=120"))
    }
}
