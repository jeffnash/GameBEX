package com.gamebex.client.stream.guest

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class FractionalAudioRendererTest {
    private fun pcm(frames: Int = 240, sample: (Int, Int) -> Int): ByteArray = ByteArray(frames * 4).also { bytes ->
        repeat(frames) { frame -> repeat(2) { channel ->
            val value = sample(frame, channel)
            val offset = frame * 4 + channel * 2
            bytes[offset] = value.toByte(); bytes[offset + 1] = (value shr 8).toByte()
        } }
    }
    private fun sample(bytes: ByteArray, frame: Int, channel: Int): Int {
        val offset = frame * 4 + channel * 2
        return ((bytes[offset].toInt() and 255) or (bytes[offset + 1].toInt() shl 8)).toShort().toInt()
    }
    private fun filled(missing: Int = -1, plc: Boolean = false): Pair<IndexedAudioBuffer, FractionalAudioRenderer> {
        val buffer = IndexedAudioBuffer()
        val renderer = FractionalAudioRenderer(buffer, plc = plc)
        repeat(12) { packet -> if (packet != missing) renderer.push(packet, 1_000L + packet, 240,
            pcm { frame, channel -> (packet * 240 + frame) * if (channel == 0) 1 else -1 }, packet * 5_000_000L) }
        return buffer to renderer
    }

    @Test fun missingIntervalNotCompressedAndRealPcmKeepsSequence() {
        val (buffer, renderer) = filled(missing = 4)
        val out = ByteArray(128 * 4)
        val spans = mutableListOf<AudioRenderSpan>()
        repeat(12) { spans += renderer.render(128, 512, it * 2_666_666L, out).spans }
        assertEquals(240L, buffer.snapshot().missing)
        assertTrue(spans.any { it.seq == 3 })
        assertTrue(spans.any { it.seq == 5 })
        assertTrue(spans.any { it.seq == null })
        assertEquals(1, renderer.gapEpisodes)
        assertEquals(0L, buffer.snapshot().rebasedFrames)
    }

    @Test fun latePacketBeforeDeadlineFillsAndAfterDeadlineCannotReplay() {
        val (buffer, renderer) = filled(missing = 4)
        repeat(4) { renderer.render(128, 512, it * 2_666_666L, ByteArray(512)) }
        assertEquals(240, renderer.push(4, 1_004L, 240, pcm { _, _ -> 123 }, 60_000_000L))
        repeat(8) { renderer.render(128, 512, 70_000_000L + it * 2_666_666L, ByteArray(512)) }
        assertEquals(0L, buffer.snapshot().missing)
        assertEquals(0, renderer.push(4, 1_004L, 240, pcm { _, _ -> 123 }, 100_000_000L))
        assertEquals(240L, buffer.snapshot().late)
    }

    @Test fun mappingConservesFractionalSourceConsumptionAndDoesNotDeleteChunks() {
        val buffer = IndexedAudioBuffer()
        val renderer = FractionalAudioRenderer(buffer)
        repeat(12) { renderer.push(it, 1_000L + it, 240, pcm { frame, _ -> frame }, it * 5_000_000L) }
        val infos = (0..7).map { renderer.render(128, 512, it * 2_666_666L, ByteArray(512)) }
        for (i in 0 until infos.lastIndex) {
            val info = infos[i]
            val next = infos[i + 1]
            assertEquals(info.firstSourceFrame + info.fraction + info.sourceFrames,
                next.firstSourceFrame + next.fraction, .000001)
            assertEquals(info.sourceFrames, info.spans.sumOf { it.sourceFrames }, .000001)
        }
        assertEquals(0L, buffer.snapshot().evicted)
        assertEquals(0L, buffer.snapshot().missing)
        assertTrue(renderer.control.ratio in .997..1.003)
        assertTrue(renderer.totalReserveFrames > buffer.snapshot().spanFrames)
    }

    @Test fun lowReserveExpansionActuallyConsumesFewerSourceFrames() {
        val buffer = IndexedAudioBuffer()
        val renderer = FractionalAudioRenderer(buffer)
        repeat(9) { renderer.push(it, 1L + it, 240, pcm { _, _ -> 1000 }, it * 5_000_000L) }
        val info = renderer.render(128, 0, 50_000_000L, ByteArray(512))
        assertFalse(info.priming)
        repeat(3) { renderer.render(128, 0, 60_000_000L + it * 3_000_000L, ByteArray(512)) }
        val expanded = renderer.render(128, 0, 70_000_000L, ByteArray(512))
        assertTrue(expanded.sourceFrames < 128)
        assertEquals(0L, buffer.snapshot().evicted)
    }

    @Test fun highWaterBoundsFractionalMappingAndLateArrivalForBothRatioDirections() {
        for (queued in listOf(0, 8_192)) {
            val buffer = IndexedAudioBuffer()
            val counts = mutableMapOf<String, Long>()
            val renderer = FractionalAudioRenderer(buffer,
                count = { name, value -> counts[name] = (counts[name] ?: 0L) + value })
            repeat(9) { packet -> renderer.push(packet, 1_000L + packet, 240,
                pcm { _, channel -> if (channel == 0) 4_000 else -4_000 }, packet * 5_000_000L) }
            val high = buffer.snapshot().high
            val epoch = buffer.snapshot().epoch
            val out = ByteArray(512)
            var proven = 0.0
            var next = 0L
            var fractional = false
            var directionObserved = false
            var clampedTail = false
            repeat(24) { block ->
                val info = renderer.render(128, queued, 50_000_000L + block * 2_666_666L, out)
                assertFalse(info.priming)
                assertEquals(epoch, info.epoch)
                assertEquals(proven, info.firstSourceFrame + info.fraction, .000001)
                assertTrue(info.sourceFrames >= 0.0)
                assertTrue(info.sourceFrames <= minOf(128 * renderer.control.ratio, high - proven) + .000001)
                var offset = 0
                var spanProven = proven
                for (span in info.spans) {
                    assertEquals(offset, span.outputOffset)
                    assertTrue(span.outputFrames > 0)
                    assertEquals(spanProven, span.firstSourceFrame + span.fraction, .000001)
                    assertTrue(span.sourceFrames >= 0.0)
                    assertTrue(spanProven + span.sourceFrames <= high + .000001)
                    val step = span.sourceFrames / span.outputFrames
                    if (step > 0.0 && step < renderer.control.ratio - .000001) {
                        assertEquals(1, span.outputFrames)
                        clampedTail = true
                    }
                    if (span.sourceFrames == 0.0) {
                        assertNull(span.seq)
                        assertEquals(0L, span.captureNs)
                        assertEquals(high.toDouble(), spanProven, .000001)
                    }
                    offset += span.outputFrames
                    spanProven += span.sourceFrames
                }
                assertEquals(info.outputFrames, offset)
                assertEquals(info.sourceFrames, info.spans.sumOf { it.sourceFrames }, .000001)
                proven += info.sourceFrames
                assertEquals(proven, spanProven, .000001)
                val snapshot = buffer.snapshot()
                assertTrue(snapshot.next >= next)
                assertTrue(snapshot.next <= snapshot.high)
                assertTrue(proven <= snapshot.high + .000001)
                next = snapshot.next
                fractional = fractional || info.fraction > 0.0
                directionObserved = directionObserved ||
                    if (queued == 0) renderer.control.ratio < 1.0 else renderer.control.ratio > 1.0
            }
            assertTrue(fractional)
            assertTrue(directionObserved)
            assertTrue(clampedTail)
            assertEquals(high.toDouble(), proven, .000001)
            assertEquals(high, buffer.snapshot().next)
            assertEquals(0L, buffer.snapshot().missing)
            assertEquals(0L, buffer.snapshot().late)
            assertEquals(0L, counts["audio_plc_frames"] ?: 0L)
            assertTrue(out.all { it == 0.toByte() })
            assertEquals(240, renderer.push(9, 1_009L, 240,
                pcm { _, channel -> if (channel == 0) -9_000 else 9_000 }, 130_000_000L))
            val resumed = renderer.render(128, queued, 131_000_000L, out)
            assertFalse(resumed.priming)
            assertEquals(epoch, resumed.epoch)
            assertEquals(high, resumed.firstSourceFrame)
            assertEquals(0.0, resumed.fraction, .000001)
            assertTrue(resumed.sourceFrames > 0.0)
            assertEquals(resumed.sourceFrames, resumed.spans.sumOf { it.sourceFrames }, .000001)
            assertTrue(resumed.spans.any { it.seq == 9 && it.captureNs == 1_009L })
            assertTrue(abs(sample(out, 32, 0) + 9_000) <= 2)
            assertTrue(abs(sample(out, 32, 1) - 9_000) <= 2)
            assertTrue(buffer.snapshot().next <= buffer.snapshot().high)
            assertEquals(0L, buffer.snapshot().late)
            assertEquals(0L, buffer.snapshot().missing)
            assertEquals(0, renderer.push(8, 1_008L, 240, pcm { _, _ -> 1 }, 132_000_000L))
            assertEquals(240L, buffer.snapshot().late)
            assertEquals(0L, buffer.snapshot().rebasedFrames)
        }
    }

    @Test fun startupHolesKeepIndicesAllowLateFillAndDoNotStallAtMissingFrames() {
        val buffer = IndexedAudioBuffer()
        val renderer = FractionalAudioRenderer(buffer)
        renderer.push(100, 1L, 240, pcm { _, _ -> 1_000 }, 0L)
        renderer.push(108, 9L, 240, pcm { _, _ -> 8_000 }, 40_000_000L)
        assertEquals(240, renderer.push(104, 5L, 240, pcm { _, _ -> 4_000 }, 45_000_000L))
        val spans = mutableListOf<AudioRenderSpan>()
        var proven = 0.0
        var next = 0L
        val out = ByteArray(512)
        repeat(24) { block ->
            val info = renderer.render(128, 0, 50_000_000L + block * 2_666_666L, out)
            assertFalse(info.priming)
            assertEquals(proven, info.firstSourceFrame + info.fraction, .000001)
            assertEquals(info.sourceFrames, info.spans.sumOf { it.sourceFrames }, .000001)
            proven += info.sourceFrames
            spans += info.spans
            val snapshot = buffer.snapshot()
            assertTrue(snapshot.next >= next)
            assertTrue(snapshot.next <= snapshot.high)
            assertTrue(proven <= snapshot.high + .000001)
            next = snapshot.next
        }
        assertEquals(2_160.0, proven, .000001)
        assertTrue(spans.any { it.seq == 100 })
        assertTrue(spans.any { it.seq == 104 && it.firstSourceFrame in 953L..1_207L })
        assertTrue(spans.any { it.seq == 108 && it.firstSourceFrame >= 1_913L })
        assertTrue(spans.any { it.seq == null && it.sourceFrames > 0.0 })
        assertEquals(1_440L, buffer.snapshot().missing)
        assertEquals(7L, buffer.snapshot().gapPackets)
        assertEquals(0L, buffer.snapshot().late)
        assertEquals(0L, buffer.snapshot().buffered)
        assertEquals(0L, buffer.snapshot().evicted)
        assertEquals(0L, buffer.snapshot().rebasedFrames)
        assertEquals(240, renderer.push(109, 10L, 240, pcm { _, _ -> 9_000 }, 130_000_000L))
        val resumed = renderer.render(128, 0, 131_000_000L, out)
        assertFalse(resumed.priming)
        assertTrue(resumed.sourceFrames > 0.0)
        assertTrue(resumed.spans.any { it.seq == 109 })
        assertEquals(1_440L, buffer.snapshot().missing)
        assertEquals(0L, buffer.snapshot().late)
    }

    @Test fun longIndexedHoleReprimePreservesPrefetchedSourceMappingAndReachesLaterPcm() {
        val buffer = IndexedAudioBuffer()
        val counts = mutableMapOf<String, Long>()
        val renderer = FractionalAudioRenderer(buffer,
            count = { name, value -> counts[name] = (counts[name] ?: 0L) + value })
        repeat(9) { packet -> renderer.push(packet, 1_000L + packet, 240,
            pcm { _, _ -> 2_000 }, packet * 5_000_000L) }
        renderer.push(40, 1_040L, 240, pcm { _, _ -> 6_000 }, 200_000_000L)
        val high = buffer.snapshot().high
        val epoch = buffer.snapshot().epoch
        val out = ByteArray(512)
        var proven = 0.0
        var sawLaterPcm = false
        repeat(90) { block ->
            val info = renderer.render(128, 512, 210_000_000L + block * 2_666_666L, out)
            assertFalse(info.priming)
            assertEquals(epoch, info.epoch)
            assertEquals(proven, info.firstSourceFrame + info.fraction, .000001)
            assertTrue(info.sourceFrames >= 0.0)
            var spanProven = proven
            for (span in info.spans) {
                assertEquals(spanProven, span.firstSourceFrame + span.fraction, .000001)
                spanProven += span.sourceFrames
            }
            proven += info.sourceFrames
            assertEquals(proven, spanProven, .000001)
            assertTrue(proven <= high + .000001)
            assertTrue(buffer.snapshot().next <= buffer.snapshot().high)
            sawLaterPcm = sawLaterPcm || info.spans.any { it.seq == 40 && it.captureNs == 1_040L }
        }
        assertTrue((counts["audio_reprime_started"] ?: 0L) > 0L)
        assertTrue(sawLaterPcm)
        assertEquals(high.toDouble(), proven, .000001)
        assertEquals(high, buffer.snapshot().next)
        assertEquals(7_440L, buffer.snapshot().missing)
        assertEquals(31L, buffer.snapshot().gapPackets)
        assertEquals(0L, buffer.snapshot().late)
        assertEquals(0L, buffer.snapshot().evicted)
        assertEquals(0L, buffer.snapshot().rebasedFrames)
    }

    @Test fun noDataAndInsufficientStartupReserveHaveZeroSourceConsumption() {
        val buffer = IndexedAudioBuffer()
        val renderer = FractionalAudioRenderer(buffer)
        for (frames in listOf(1, 128, 512)) {
            val out = ByteArray(frames * 4) { 127 }
            val info = renderer.render(frames, 0, frames.toLong(), out)
            assertTrue(info.priming)
            assertEquals(frames, info.outputFrames)
            assertEquals(0L, info.firstSourceFrame)
            assertEquals(0.0, info.sourceFrames, .000001)
            assertEquals(0.0, info.spans.sumOf { it.sourceFrames }, .000001)
            assertTrue(info.spans.all { it.seq == null && it.captureNs == 0L })
            assertTrue(out.all { it == 0.toByte() })
        }
        renderer.push(999, 1L, 240, pcm { _, _ -> 12_000 }, 1_000L)
        val waiting = renderer.render(512, 0, 2_000L, ByteArray(2_048))
        assertTrue(waiting.priming)
        assertEquals(0.0, waiting.sourceFrames, .000001)
        assertEquals(0.0, waiting.spans.sumOf { it.sourceFrames }, .000001)
        assertEquals(0L, buffer.snapshot().next)
        assertEquals(240L, buffer.snapshot().high)
        assertEquals(240L, buffer.snapshot().buffered)
        assertEquals(0L, buffer.snapshot().missing)
        assertEquals(0, renderer.gapEpisodes)
    }

    @Test fun renderDemandBoundariesDoNotPrefetchDuringAnExhaustedGap() {
        val buffer = IndexedAudioBuffer()
        val renderer = FractionalAudioRenderer(buffer)
        renderer.push(0, 1L, 240, pcm { _, _ -> 2_000 }, 0L)
        val out = ByteArray(512 * 4)
        val first = renderer.render(512, 2_048, 1_000_000L, out)
        assertFalse(first.priming)
        assertEquals(240.0, first.sourceFrames, .000001)
        assertEquals(240.0, first.spans.sumOf { it.sourceFrames }, .000001)
        assertEquals(240L, buffer.snapshot().next)
        assertEquals(0L, buffer.snapshot().missing)
        assertTrue(first.spans.any { it.seq == null && it.sourceFrames == 0.0 })
        val exhausted = buffer.snapshot()
        for (frames in listOf(1, 128, 512)) {
            val gap = renderer.render(frames, 2_048, 2_000_000L + frames, out)
            assertFalse(gap.priming)
            assertEquals(240L, gap.firstSourceFrame)
            assertEquals(0.0, gap.sourceFrames, .000001)
            assertEquals(0.0, gap.spans.sumOf { it.sourceFrames }, .000001)
            assertTrue(gap.spans.all { it.seq == null })
            assertTrue(out.take(frames * 4).all { it == 0.toByte() })
            assertEquals(exhausted, buffer.snapshot())
        }
        assertEquals(240, renderer.push(1, 2L, 240, pcm { _, _ -> 3_000 }, 4_000_000L))
        val resumed = renderer.render(1, 2_048, 5_000_000L, out)
        assertFalse(resumed.priming)
        assertEquals(240L, resumed.firstSourceFrame)
        assertTrue(resumed.sourceFrames > 0.0)
        assertEquals(resumed.sourceFrames, resumed.spans.sumOf { it.sourceFrames }, .000001)
        assertTrue(resumed.spans.any { it.seq == 1 && it.captureNs == 2L })
        assertTrue(buffer.snapshot().next <= buffer.snapshot().high)
        assertEquals(0L, buffer.snapshot().late)
    }

    @Test fun sourceSwitchClearsFilterHistoryAndDuplicateDoesNotRestartSound() {
        val (buffer, renderer) = filled()
        val out = ByteArray(512)
        val first = renderer.render(128, 512, 1_000_000L, out)
        assertEquals(0, renderer.push(11, 1_011L, 240, pcm { _, _ -> 30_000 }, 60_000_000L))
        val second = renderer.render(128, 512, 4_000_000L, out)
        assertEquals(first.epoch, second.epoch)
        assertTrue(second.firstSourceFrame > first.firstSourceFrame)
        buffer.reset()
        val disconnected = renderer.render(128, 512, 65_000_000L, out)
        assertTrue(disconnected.priming)
        assertTrue(disconnected.epoch > first.epoch)
        assertEquals(0L, disconnected.firstSourceFrame)
        assertEquals(0.0, disconnected.spans.sumOf { it.sourceFrames }, .000001)
        assertTrue(out.all { it == 0.toByte() })
        repeat(12) { renderer.push(it, 1_000L + it, 240, pcm { _, _ -> 0 }, 70_000_000L + it * 5_000_000L) }
        val after = renderer.render(128, 512, 140_000_000L, out)
        assertTrue(after.epoch > first.epoch)
        assertTrue(out.all { it == 0.toByte() })
    }

    @Test fun hundredMsOutageReprimesExplicitlyAndCountsConcealment() {
        val buffer = IndexedAudioBuffer()
        val counts = mutableMapOf<String, Long>()
        val renderer = FractionalAudioRenderer(buffer, plc = true,
            count = { name, value -> counts[name] = (counts[name] ?: 0L) + value })
        repeat(12) { renderer.push(it, 1L, 240, pcm { _, _ -> 20_000 }, it * 5_000_000L) }
        val out = ByteArray(512)
        repeat(80) { renderer.render(128, 512, it * 2_666_666L, out) }
        assertEquals(1L, counts["audio_reprime_started"])
        assertTrue((counts["audio_reprime_output_frames"] ?: 0L) > 0L)
        assertTrue((counts["audio_plc_frames"] ?: 0L) > 0L)
        assertEquals(0L, buffer.snapshot().missing)
        assertEquals(buffer.snapshot().high, buffer.snapshot().next)
        assertEquals(0L, buffer.snapshot().late)
        val priming = renderer.render(128, 512, 220_000_000L, out)
        assertTrue(priming.priming)
        assertEquals(buffer.snapshot().high, priming.firstSourceFrame)
        assertEquals(0.0, priming.spans.sumOf { it.sourceFrames }, .000001)
        val epoch = priming.epoch
        repeat(12) { packet -> renderer.push(12 + packet, 2L, 240,
            pcm { _, _ -> 10_000 }, 230_000_000L + packet * 5_000_000L) }
        val resumed = renderer.render(128, 512, 300_000_000L, out)
        assertFalse(resumed.priming)
        assertEquals(epoch, resumed.epoch)
        assertTrue(resumed.spans.any { it.seq == 12 && it.captureNs == 2L })
        assertTrue(resumed.sourceFrames > 0.0)
        assertEquals(0L, buffer.snapshot().late)
        assertEquals(0L, buffer.snapshot().missing)
    }

    @Test fun tenSecondTonesStayWithinAmplitudeBudgetWithoutChannelSwap() {
        for (hz in listOf(1_000, 10_000, 18_000)) for (skew in listOf(-200.0, 200.0)) {
            val buffer = IndexedAudioBuffer()
            val drift = AudioDrift()
            val renderer = FractionalAudioRenderer(buffer, drift = drift)
            var packet = 0
            fun push() {
                val first = packet * 240
                renderer.push(packet, 1L + packet, 240,
                    pcm { frame, channel -> if (channel == 1) 0 else
                        (sin(2 * Math.PI * hz * (first + frame) / 48_000) * 10_000).toInt() },
                    (packet * 5_000_000L / (1 + skew / 1e6)).toLong())
                packet++
            }
            repeat(12) { push() }
            var squares = 0.0
            var measured = 0
            var now = 1_000_000_000L
            val out = ByteArray(512)
            repeat(3_750) { block ->
                while (buffer.snapshot().spanFrames < 1_920) push()
                val info = renderer.render(128, 512, now, out)
                drift.observePlayout(block * 128L, now)
                now += 2_666_667L
                assertTrue(info.sourceFrames.isFinite())
                assertTrue(renderer.control.ratio in .997..1.003)
                if (block > 100) repeat(128) { frame ->
                    val left = sample(out, frame, 0)
                    squares += left.toDouble() * left; measured++
                    assertEquals(0, sample(out, frame, 1))
                }
            }
            val gainDb = 20 * log10(sqrt(squares / measured) / (10_000 / sqrt(2.0)))
            assertTrue("hz=$hz skew=$skew gain=$gainDb", abs(gainDb) <= if (hz == 18_000) 1.0 else .5)
            assertEquals(0L, buffer.snapshot().missing)
            assertEquals(0L, buffer.snapshot().evicted)
        }
    }

    @Test fun impulseSaturatesWithoutIntegerWrapOrNan() {
        val buffer = IndexedAudioBuffer()
        val renderer = FractionalAudioRenderer(buffer)
        repeat(12) { packet -> renderer.push(packet, 1L, 240,
            pcm { frame, channel -> if (packet == 1 && frame == 0) if (channel == 0) 32767 else -32768 else 0 },
            packet * 5_000_000L) }
        val out = ByteArray(512)
        var energy = 0L
        repeat(8) {
            val info = renderer.render(128, 512, it * 2_666_666L, out)
            assertTrue(info.sourceFrames.isFinite())
            repeat(128) { frame -> energy += abs(sample(out, frame, 0).toLong()) }
        }
        assertTrue(energy > 10_000L)
        assertEquals(0L, buffer.snapshot().missing)
    }
}
