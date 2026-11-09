package com.gamebex.client.stream.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamShellFlagsTest {
    @Test fun releaseBuildsAlwaysUseTheConfigValue() {
        assertEquals(240, shellInt("encoder_rate_hint", 240, -1..960, debug = false) { "0" })
        assertEquals(-1, shellInt("encoder_rate_hint", -1, -1..960, debug = true) { "9999" })
        assertEquals(0, shellInt("encoder_rate_hint", 240, -1..960, debug = true) { "0" })
        assertTrue(shellFlag("latency_cc", true, debug = false) { "0" })
        assertFalse(shellFlag("latency_cc", false, debug = false) { "1" })
        assertEquals(40, shellInt("queue_deadline_ms", 40, 0..40, debug = false) { "0" })
        assertEquals("b", shellString("k", "b", setOf("a", "b"), debug = false) { "a" })
    }

    @Test fun debugPropBeatsConfigAndInvalidFallsThrough() {
        assertTrue(shellFlag("latency_cc", false, debug = true) { "1" })
        assertFalse(shellFlag("latency_cc", true, debug = true) { "0" })
        assertTrue(shellFlag("latency_cc", true, debug = true) { "yes" })
        assertFalse(shellFlag("latency_cc", false, debug = true) { null })
        assertEquals(20, shellInt("queue_deadline_ms", 40, 0..40, debug = true) { "20" })
        assertEquals(40, shellInt("queue_deadline_ms", 40, 0..40, debug = true) { "400" })
        assertEquals(40, shellInt("queue_deadline_ms", 40, 0..40, debug = true) { "x" })
        assertEquals("a", shellString("k", "b", setOf("a", "b"), debug = true) { "a" })
        assertEquals("b", shellString("k", "b", setOf("a", "b"), debug = true) { "z" })
        assertEquals("b", shellString("k", "b", setOf("a", "b"), debug = true) { null })
    }
}
