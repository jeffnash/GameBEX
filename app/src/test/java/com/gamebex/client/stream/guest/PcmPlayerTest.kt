package com.gamebex.client.stream.guest

import org.junit.Assert.*
import org.junit.Test

class PcmPlayerTest {
    private class Source : AudioRenderSource {
        var generation = 1L
        var renders = 0
        val queued = mutableListOf<Int>()
        override fun epoch(): Long = generation
        override fun render(frames: Int, queuedOutputFrames: Int, nowNs: Long, out: ByteArray): AudioRenderInfo {
            renders++
            queued += queuedOutputFrames
            repeat(frames * 4) { out[it] = (it + generation).toByte() }
            return AudioRenderInfo(generation, 100, .25, frames, false, frames * 1.0002,
                listOf(AudioRenderSpan(0, frames, 17, 1_000L, 100, .25, frames * 1.0002)))
        }
    }
    private class Output : PcmOutputPort {
        override var bufferFrames = 768
        override var underruns = 0
        var position = 0
        var stamp: PcmTimestamp? = null
        var released = false
        var flushes = 0
        var grows = 0
        var accepted = 0
        var writer: (ByteArray, Int, Int, Boolean) -> Int = { _, _, count, _ -> count }
        val requests = mutableListOf<Pair<Int, Int>>()
        val modes = mutableListOf<Boolean>()
        override fun head(): Int = position
        override fun timestamp(): PcmTimestamp? = stamp
        override fun write(bytes: ByteArray, offset: Int, remaining: Int, nonBlocking: Boolean): Int {
            assertFalse(released)
            requests += offset to remaining; modes += nonBlocking
            val n = writer(bytes, offset, remaining, nonBlocking)
            if (n > 0) accepted += n
            return n
        }
        override fun flush() { flushes++; position = 0; accepted = 0 }
        override fun grow() { grows++; bufferFrames += 256 }
        override fun release() { assertFalse(released); released = true }
    }

    @Test fun experimentsDefaultOffAndDebugReadOnce() {
        var reads = 0
        assertEquals(AudioPlaybackOptions(), AudioPlaybackOptions.sample(false) { reads++; "1" })
        assertEquals(0, reads)
        val seen = mutableListOf<String>()
        val options = AudioPlaybackOptions.sample(true) { name ->
            seen += name
            when (name) { "audio_lead_frames" -> "512"; "audio_output_frames" -> "64"; else -> "1" }
        }
        assertEquals(5, seen.size)
        assertEquals(5, seen.toSet().size)
        assertEquals(AudioPlaybackOptions(512, true, true, true, 64), options)
        assertEquals(AudioPlaybackOptions(), AudioPlaybackOptions.sample(true) { "invalid" })
    }

    @Test fun rendersOncePerBlockAndPartialZeroWritesRetainSuffix() {
        val port = Output()
        val source = Source()
        val returns = listOf(2, 0, 510)
        val expected = ByteArray(512) { (it + 1).toByte() }
        var running = true
        var now = 1_000_000L
        var parks = 0
        val played = mutableListOf<StreamPcmPlayed>()
        port.writer = { bytes, offset, remaining, nonblocking ->
            assertTrue(nonblocking)
            assertArrayEquals(expected, bytes)
            assertEquals(bytes.size - offset, remaining)
            returns[port.requests.lastIndex]
        }
        runPcmOutput(port, source, AudioPlaybackOptions(jit = true), true,
            keepRunning = { running }, nowNs = { now }, park = { now += it; parks++ },
            onPcmPlayed = { played += it }, onOutputMapping = { info, first, _, valid ->
                assertEquals(128.0256, info.sourceFrames, .00001)
                assertEquals(0L, first); assertFalse(valid); running = false
            })
        assertEquals(1, source.renders)
        assertEquals(listOf(0 to 512, 2 to 510, 2 to 510), port.requests)
        assertEquals(1, parks)
        assertEquals(17, played.single().seq)
        assertEquals(0L, played.single().firstWrittenFrame)
        assertArrayEquals(expected, played.single().pcm)
        assertTrue(port.released)
    }

    @Test fun rendersOnlyAtDemandThresholdNotWhileWaitingBehindFullTrack() {
        val port = Output()
        val source = Source()
        var running = true
        var parks = 0
        var now = 1_000_000L
        runPcmOutput(port, source, AudioPlaybackOptions(512, jit = true), true,
            keepRunning = { running }, nowNs = { now }, park = {
                assertEquals(4, source.renders)
                parks++; now += it; port.position += 128
            }, onOutputMapping = { _, _, _, _ -> if (source.renders == 5) running = false })
        assertEquals(listOf(0, 128, 256, 384, 384), source.queued)
        assertEquals(1, parks)
        assertTrue(port.released)
    }

    @Test fun sourceEpochChangeDiscardsPendingAndFlushesOnWriter() {
        val port = Output()
        val source = Source()
        var running = true
        val counts = mutableMapOf<String, Long>()
        port.writer = { bytes, _, remaining, _ ->
            if (port.requests.size == 1) { source.generation++; 2 }
            else { assertEquals(2.toByte(), bytes[0]); remaining }
        }
        runPcmOutput(port, source, AudioPlaybackOptions(jit = true), true,
            keepRunning = { running }, nowNs = { 1_000_000L },
            count = { name, value -> counts[name] = (counts[name] ?: 0L) + value },
            onOutputMapping = { info, _, _, _ -> assertEquals(2L, info.epoch); running = false })
        assertEquals(2, source.renders)
        assertEquals(1, port.flushes)
        assertEquals(128L, counts["audio_abandoned_output_frames"])
        assertEquals(listOf(0 to 512, 0 to 512), port.requests)
        assertTrue(port.released)
    }

    @Test fun timestampValidityAndOutputSpanMappingPreserveEvidence() {
        for (valid in listOf(false, true)) {
            val port = Output()
            val source = Source()
            var running = true
            var evidence: StreamPcmPlayed? = null
            port.stamp = PcmTimestamp(0, if (valid) 1_000_000L else 0L)
            runPcmOutput(port, source, AudioPlaybackOptions(jit = true), true,
                keepRunning = { running }, nowNs = { 1_000_000L }, onPcmPlayed = { evidence = it },
                onOutputMapping = { _, _, _, _ -> running = false })
            assertEquals(valid, evidence!!.timestampValid)
            assertEquals(17, evidence!!.seq)
            assertEquals(0L, evidence!!.firstWrittenByte)
            assertEquals(1_000_000L, evidence!!.outputNs)
        }
    }

    @Test fun zeroProgressParksFailsAndReleasesRatherThanSpins() {
        val port = Output()
        val source = Source()
        var now = 1_000_000L
        var parks = 0
        port.writer = { _, _, _, _ -> 0 }
        try {
            runPcmOutput(port, source, AudioPlaybackOptions(jit = true), true,
                keepRunning = { true }, nowNs = { now }, park = { now += 100_000_000L; parks++ })
            fail("missing output failure")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("progress"))
        }
        assertEquals(1, source.renders)
        assertEquals(5, parks)
        assertTrue(port.released)
    }

    @Test fun fullNativeQueueWithoutPlayoutFailsRatherThanWaitsForever() {
        val port = Output()
        val source = Source()
        var now = 1_000_000L
        var parks = 0
        try {
            runPcmOutput(port, source, AudioPlaybackOptions(512, jit = true), true,
                keepRunning = { true }, nowNs = { now }, park = { now += 100_000_000L; parks++ })
            fail("missing stalled-playout failure")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("stalled"))
        }
        assertEquals(4, source.renders)
        assertEquals(5, parks)
        assertTrue(port.released)
    }

    @Test fun negativeAndOversizedWritesFailExplicitlyAndReleaseOnce() {
        for (result in listOf(-6, 513)) {
            val port = Output()
            port.writer = { _, _, _, _ -> result }
            try {
                runPcmOutput(port, Source(), AudioPlaybackOptions(jit = true), true, keepRunning = { true })
                fail("missing write failure")
            } catch (expected: IllegalStateException) { assertTrue(port.released) }
        }
    }

    @Test fun defaultControlStillUsesBlocking240FramesAndGrowsOnNativeUnderrun() {
        val port = Output()
        val source = Source()
        var running = true
        port.writer = { _, _, remaining, blocking -> assertFalse(blocking); port.underruns++; remaining }
        runPcmOutput(port, source, AudioPlaybackOptions(), false, keepRunning = { running },
            nowNs = { 200_000_000L }, onOutputMapping = { _, _, _, _ -> running = false })
        assertEquals(listOf(0 to 960), port.requests)
        assertEquals(1, port.grows)
        assertTrue(port.released)
    }

    @Test fun lifecycleOwnedOutputIsNotReleasedByRenderLoop() {
        val port = Output()
        var running = true
        runPcmOutput(port, Source(), AudioPlaybackOptions(), false,
            keepRunning = { running }, onOutputMapping = { _, _, _, _ -> running = false },
            releaseOnExit = false)
        assertFalse(port.released)
        port.release()
        assertTrue(port.released)
    }

    @Test fun shutdownAbandonsOnlyUnwrittenBytesAndReleasesAfterLastWrite() {
        val port = Output()
        var running = true
        val counts = mutableMapOf<String, Long>()
        port.writer = { _, _, _, _ -> assertFalse(port.released); running = false; 2 }
        runPcmOutput(port, Source(), AudioPlaybackOptions(jit = true), true,
            keepRunning = { running }, count = { name, value -> counts[name] = (counts[name] ?: 0L) + value })
        assertEquals(128L, counts["audio_abandoned_output_frames"])
        assertEquals(1, port.requests.size)
        assertTrue(port.released)
    }
}
