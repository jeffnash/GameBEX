package com.gamebex.client.together.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostEngineFlagsTest {
    private fun cfg(vararg pairs: Pair<String, String>) =
        streamConfigForFlags("tok", "10.0.0.2", true, mapOf(*pairs)::get)

    @Test fun unsetFlagsMapToSafeDefaults() {
        val c = cfg()
        assertEquals("tok", c.token)
        assertEquals("10.0.0.2", c.guestAddress)
        assertTrue(c.rsound)
        assertEquals(1, c.level)
        assertEquals(0, c.queueDeadlineMs)
        assertFalse(c.latencyCc)
        assertFalse(c.inputCs6)
        assertEquals(0, c.encoderFps)
        // Keyframes-on-request defaults ON.
        assertTrue(c.keyframesOnRequest)
        assertFalse(c.crop)
    }

    @Test fun keyframesOffIsStickyWhenSet() {
        assertFalse(cfg("keyframes_on_request" to "0").keyframesOnRequest)
    }

    @Test fun cropAspectDefaultsFourThreeAndPassesThrough() {
        assertEquals("4:3", cfg().cropAspect)
        assertEquals(
            "3:2",
            streamConfigForFlags("tok", "10.0.0.2", true, mapOf<String, String>()::get, cropAspect = "3:2").cropAspect,
        )
    }

    @Test fun p2SlotModeDefaultsOffAndPassesThrough() {
        assertEquals("off", cfg().p2SlotMode)
        assertEquals(
            "placeholder",
            streamConfigForFlags("tok", "10.0.0.2", true, mapOf<String, String>()::get, p2SlotMode = "placeholder").p2SlotMode,
        )
    }

    @Test fun everyHostFlagMapsIntoTheConfig() {
        val c = cfg(
            "start_level" to "2",
            "queue_deadline_ms" to "40",
            "latency_cc" to "1",
            "input_cs6" to "1",
            "encoder_fps" to "50",
            "keyframes_on_request" to "1",
            "stream_crop" to "1",
        )
        assertEquals(2, c.level)
        assertEquals(40, c.queueDeadlineMs)
        assertTrue(c.latencyCc)
        assertTrue(c.inputCs6)
        assertEquals(50, c.encoderFps)
        assertTrue(c.keyframesOnRequest)
        assertTrue(c.crop)
    }

    @Test fun outOfRangeAndGarbageValuesFallBackToDefaults() {
        val c = cfg(
            "start_level" to "9",
            "queue_deadline_ms" to "400",
            "latency_cc" to "yes",
            "input_cs6" to "2",
            "encoder_fps" to "60",
            "keyframes_on_request" to "true",
            "stream_crop" to "",
        )
        assertEquals(1, c.level)
        assertEquals(0, c.queueDeadlineMs)
        assertFalse(c.latencyCc)
        assertFalse(c.inputCs6)
        assertEquals(0, c.encoderFps)
        assertFalse(c.keyframesOnRequest)
        assertFalse(c.crop)
    }

    @Test fun autoStartLevelMeansTheDefaultEncoderSize() {
        assertEquals(1, cfg("start_level" to "-1").level)
        assertEquals(0, cfg("start_level" to "0").level)
        assertEquals(3, cfg("start_level" to "3").level)
    }

    @Test fun debugPropOverrideWinsOverTheStoredStartLevel() {
        val flags = mapOf("start_level" to "2")::get
        assertEquals(0, streamConfigForFlags("tok", "10.0.0.2", true, flags, startLevelOverride = 0).level)
        assertEquals(3, streamConfigForFlags("tok", "10.0.0.2", true, flags, startLevelOverride = 3).level)
        // Out of range: falls through to the stored flag, then the default.
        assertEquals(2, streamConfigForFlags("tok", "10.0.0.2", true, flags, startLevelOverride = 9).level)
        assertEquals(1, streamConfigForFlags("tok", "10.0.0.2", true, emptyMap<String, String>()::get, startLevelOverride = 9).level)
    }
}
