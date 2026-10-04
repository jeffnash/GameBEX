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

    @Test fun sourceSwitchClearsFilterHistoryAndDuplicateDoesNotRestartSound() {
        val (buffer, renderer) = filled()
        val out = ByteArray(512)
        val first = renderer.render(128, 512, 1_000_000L, out)
        assertEquals(0, renderer.push(11, 1_011L, 240, pcm { _, _ -> 30_000 }, 60_000_000L))
        val second = renderer.render(128, 512, 4_000_000L, out)
        assertEquals(first.epoch, second.epoch)
        assertTrue(second.firstSourceFrame > first.firstSourceFrame)
        buffer.reset()
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
        assertTrue(buffer.snapshot().missing >= 4_800L)
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
