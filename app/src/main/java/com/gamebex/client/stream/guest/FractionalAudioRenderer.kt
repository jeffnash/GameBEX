package com.gamebex.client.stream.guest

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

class FractionalAudioRenderer(
    private val buffer: IndexedAudioBuffer,
    val control: AudioRegulator = AudioRegulator(),
    private val drift: AudioDrift = AudioDrift(),
    plc: Boolean = false,
    private val count: (String, Long) -> Unit = { _, _ -> },
) : AudioRenderSource {
    private val historySize = 4_096
    private val tags = LongArray(historySize) { Long.MIN_VALUE }
    private val history = ShortArray(historySize * 2)
    private val stamps = LongArray(historySize)
    private val seqs = IntArray(historySize)
    private val real = BooleanArray(historySize)
    private val scratch = ShortArray(1_024 * 2)
    private val present = BooleanArray(1_024)
    private val captures = LongArray(1_024)
    private val sequences = IntArray(1_024)
    private val concealed = ShortArray(2)
    private val concealment = AudioPlc(plc)
    private val kernels = Array(257) { phase ->
        val fraction = phase / 256.0
        val taps = DoubleArray(16) { i ->
            val x = i - 7 - fraction
            val sinc = if (abs(x) < 1e-12) .997 else sin(Math.PI * .997 * x) / (Math.PI * x)
            sinc * if (abs(x) < 8) .5 + .5 * cos(Math.PI * x / 8) else 0.0
        }
        val sum = taps.sum()
        DoubleArray(16) { taps[it] / sum }
    }
    private var active = 0L
    private var started = false
    private var everStarted = false
    private var cursor = 0L
    private var fraction = 0.0
    private var firstFrame = 0L
    private var fallbackL = 0
    private var fallbackR = 0
    private var pendingMissing = 0L
    private var expanded = 0.0
    private var compressed = 0.0
    @Volatile var totalReserveFrames = 0.0
        private set
    @Volatile var gapEpisodes = 0
        private set

    override fun epoch(): Long = buffer.snapshot().epoch

    @Synchronized fun resetOutput() {
        active = 0L
        synchronizeEpoch(buffer.snapshot().epoch)
    }

    private fun synchronizeEpoch(epoch: Long) {
        if (active == epoch) return
        active = epoch; started = false; everStarted = false
        tags.fill(Long.MIN_VALUE); real.fill(false)
        fraction = 0.0; pendingMissing = 0L; expanded = 0.0; compressed = 0.0
        fallbackL = 0; fallbackR = 0; concealment.reset(); control.reset(); drift.reset()
    }

    @Synchronized fun push(seq: Int, captureNs: Long, frames: Int, pcm: ByteArray, receivedNs: Long): Int {
        val added = buffer.push(seq, captureNs, frames, pcm)
        val snapshot = buffer.snapshot()
        synchronizeEpoch(snapshot.epoch)
        if (added > 0) {
            drift.observePacket(seq, frames, receivedNs)
            control.observe(snapshot.high - frames, receivedNs)
        }
        return added
    }

    private fun value(index: Long, channel: Int): Int {
        if (index < firstFrame) return if (channel == 0) fallbackL else fallbackR
        val slot = (index % historySize).toInt()
        return if (tags[slot] == index) history[slot * 2 + channel].toInt() else 0
    }

    private fun sourceIdentity(index: Long): Pair<Int?, Long> {
        if (index < firstFrame) return null to 0L
        val slot = (index % historySize).toInt()
        if (tags[slot] == index && real[slot]) return seqs[slot] to stamps[slot]
        return null to 0L
    }

    private fun silence(frames: Int, out: ByteArray): AudioRenderInfo {
        out.fill(0, 0, frames * 4)
        count(if (everStarted) "audio_reprime_output_frames" else "audio_startup_output_frames", frames.toLong())
        count("a_silence_frames", frames.toLong())
        return AudioRenderInfo(active, 0, 0.0, frames, true,
            spans = listOf(AudioRenderSpan(0, frames, null, 0L, 0)))
    }

    @Synchronized override fun render(frames: Int, queuedOutputFrames: Int, nowNs: Long, out: ByteArray): AudioRenderInfo {
        require(frames in 1..512 && queuedOutputFrames >= 0 && out.size >= frames * 4)
        val snapshot = buffer.snapshot()
        synchronizeEpoch(snapshot.epoch)
        if (!snapshot.initialized) return silence(frames, out)
        if (!started) {
            val need = maxOf(0, control.targetFrames - queuedOutputFrames) + 8
            if (snapshot.spanFrames < need) return silence(frames, out)
            cursor = snapshot.next; firstFrame = cursor; fraction = 0.0
            started = true; everStarted = true
        }
        totalReserveFrames = (snapshot.high - cursor).toDouble() - fraction + queuedOutputFrames
        val ratio = control.step(nowNs, totalReserveFrames, pendingMissing, if (drift.valid) drift.ppm else null)
        pendingMissing = 0L
        val begin = cursor
        val beginFraction = fraction
        val lastNeeded = cursor + floor(fraction + (frames - 1) * ratio).toLong() + 8
        val need = (lastNeeded - snapshot.next + 1).coerceAtLeast(0L).toInt()
        if (need > 1_024) {
            count("audio_render_bound_failure", 1L); started = false
            return silence(frames, out)
        }
        val read = buffer.take(active, need, scratch, present, captures, sequences) ?: return silence(frames, out)
        for (i in 0 until need) {
            val index = read.first + i
            concealment.sample(present[i], scratch[i * 2].toInt(), scratch[i * 2 + 1].toInt(), concealed)
            if (concealment.gapStarted) { gapEpisodes++; count("audio_gap_started", 1L) }
            if (!present[i]) { pendingMissing++; count("audio_plc_frames", if (concealed[0] != 0.toShort() || concealed[1] != 0.toShort()) 1L else 0L) }
            if (index == firstFrame) { fallbackL = concealed[0].toInt(); fallbackR = concealed[1].toInt() }
            val slot = (index % historySize).toInt()
            tags[slot] = index; history[slot * 2] = concealed[0]; history[slot * 2 + 1] = concealed[1]
            stamps[slot] = captures[i]; seqs[slot] = sequences[i]; real[slot] = present[i]
        }
        val spans = ArrayList<AudioRenderSpan>()
        var spanStart = 0
        var spanIndex = cursor
        var spanFraction = fraction
        var spanSeq: Int? = null
        var spanStamp = 0L
        var silenceFrames = 0L
        for (i in 0 until frames) {
            val weights = kernels[(fraction * 256).roundToInt().coerceIn(0, 256)]
            var id = sourceIdentity(cursor)
            if (id.first == null) {
                for (tap in 0..15) {
                    val candidate = sourceIdentity(cursor + tap - 7)
                    if (abs(weights[tap]) > .0001 && candidate.first != null) { id = candidate; break }
                }
            }
            if (i == 0) { spanSeq = id.first; spanStamp = id.second }
            else if (id.first != spanSeq || id.second != spanStamp) {
                spans.add(AudioRenderSpan(spanStart, i - spanStart, spanSeq, spanStamp, spanIndex,
                    spanFraction, (i - spanStart) * ratio))
                spanStart = i; spanIndex = cursor; spanFraction = fraction
                spanSeq = id.first; spanStamp = id.second
            }
            var allZero = true
            for (channel in 0..1) {
                var sample = 0.0
                for (tap in 0..15) sample += value(cursor + tap - 7, channel) * weights[tap]
                val raw = sample.roundToInt()
                val value = raw.coerceIn(-32768, 32767)
                if (raw != value) count("audio_clipped_samples", 1L)
                if (value != 0) allZero = false
                val offset = i * 4 + channel * 2
                out[offset] = value.toByte(); out[offset + 1] = (value shr 8).toByte()
            }
            if (id.first == null && allZero) silenceFrames++
            fraction += ratio
            val step = floor(fraction).toLong()
            cursor += step; fraction -= step
        }
        spans.add(AudioRenderSpan(spanStart, frames - spanStart, spanSeq, spanStamp, spanIndex,
            spanFraction, (frames - spanStart) * ratio))
        count("a_silence_frames", silenceFrames)
        val consumed = cursor - begin + fraction - beginFraction
        if (consumed < frames) expanded += frames - consumed else compressed += consumed - frames
        val e = expanded.toLong()
        if (e > 0) { count("audio_expanded_equivalent_frames", e); expanded -= e }
        val c = compressed.toLong()
        if (c > 0) { count("audio_compressed_equivalent_frames", c); compressed -= c }
        if (concealment.missingAge >= 4_800) {
            started = false; concealment.reset(); tags.fill(Long.MIN_VALUE)
            count("audio_reprime_started", 1L)
        }
        return AudioRenderInfo(active, begin, beginFraction, frames, false, consumed, spans)
    }
}
