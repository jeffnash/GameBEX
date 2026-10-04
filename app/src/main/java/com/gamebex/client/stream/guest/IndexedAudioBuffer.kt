package com.gamebex.client.stream.guest

class IndexedAudioBuffer(
    private val capacity: Int = 16_384,
    private val onCounter: (String, Long) -> Unit = { _, _ -> },
) {
    data class Snapshot(
        val epoch: Long, val initialized: Boolean, val next: Long, val high: Long,
        val buffered: Long, val unique: Long, val duplicate: Long, val late: Long,
        val missing: Long, val evicted: Long, val gapPackets: Long, val rebasedFrames: Long,
        val packetFrames: Int,
    ) {
        val spanFrames: Int get() = (high - next).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
    }

    data class Read(val first: Long, val frames: Int)
    private val tags = LongArray(capacity) { Long.MIN_VALUE }
    private val samples = ShortArray(capacity * 2)
    private val sequences = IntArray(capacity)
    private val stamps = LongArray(capacity)
    private var epoch = 1L
    private var initialized = false
    private var newestSeq = 0
    private var newestFirst = 0L
    private var packetFrames = 0
    private var next = 0L
    private var high = 0L
    private var buffered = 0L
    private var unique = 0L
    private var duplicate = 0L
    private var late = 0L
    private var missing = 0L
    private var evicted = 0L
    private var gapPackets = 0L
    private var rebasedFrames = 0L

    init { require(capacity >= 1_024) }

    @Synchronized fun reset() {
        val discarded = buffered
        evicted += discarded
        tags.fill(Long.MIN_VALUE)
        buffered = 0L; initialized = false; next = 0L; high = 0L; packetFrames = 0
        newestFirst = 0L; epoch++
        emitCounter("a_evicted_frames", discarded)
    }

    @Synchronized fun push(seq: Int, captureNs: Long, frames: Int, pcm: ByteArray): Int {
        require(frames in 1..512 && pcm.size == frames * 4)
        if (initialized && frames != packetFrames) reset()
        if (!initialized) {
            newestSeq = seq; newestFirst = 0L; packetFrames = frames
            initialized = true
        }
        val delta = seq - newestSeq
        val first = newestFirst + delta.toLong() * packetFrames
        if (delta > 0) {
            val gaps = (delta - 1).toLong()
            gapPackets += gaps
            newestSeq = seq; newestFirst = first
            emitCounter("a_gap_pkts", gaps)
        }
        if (first + frames > next + capacity) {
            val discarded = buffered
            val rebased = (first - next).coerceAtLeast(0L)
            evicted += discarded
            rebasedFrames += rebased
            tags.fill(Long.MIN_VALUE); buffered = 0L
            next = first; high = first; epoch++
            emitCounter("a_evicted_frames", discarded)
            emitCounter("audio_explicit_rebase_frames", rebased)
        }
        val previousLate = late
        val previousDuplicate = duplicate
        var added = 0
        for (i in 0 until frames) {
            val index = first + i
            if (index < next) { late++; continue }
            val slot = (index % capacity).toInt()
            if (tags[slot] == index) { duplicate++; continue }
            val offset = i * 4
            samples[slot * 2] = ((pcm[offset].toInt() and 255) or (pcm[offset + 1].toInt() shl 8)).toShort()
            samples[slot * 2 + 1] = ((pcm[offset + 2].toInt() and 255) or (pcm[offset + 3].toInt() shl 8)).toShort()
            sequences[slot] = seq; stamps[slot] = captureNs
            tags[slot] = index; added++; unique++; buffered++
            high = maxOf(high, index + 1)
        }
        emitCounter("a_late_frames", late - previousLate)
        emitCounter("a_duplicate_frames", duplicate - previousDuplicate)
        emitCounter("a_source_unique_frames", added.toLong())
        return added
    }

    @Synchronized fun snapshot(): Snapshot = Snapshot(epoch, initialized, next, high, buffered,
        unique, duplicate, late, missing, evicted, gapPackets, rebasedFrames, packetFrames)

    @Synchronized fun take(expectedEpoch: Long, count: Int, out: ShortArray,
        present: BooleanArray, captureNs: LongArray, seq: IntArray): Read? {
        require(count in 0..capacity && out.size >= count * 2 && present.size >= count &&
            captureNs.size >= count && seq.size >= count)
        if (epoch != expectedEpoch || !initialized) return null
        val first = next
        val frames = minOf(count.toLong(), (high - next).coerceAtLeast(0L)).toInt()
        val previousMissing = missing
        for (i in 0 until frames) {
            val index = first + i
            val slot = (index % capacity).toInt()
            val ok = tags[slot] == index
            present[i] = ok
            out[i * 2] = if (ok) samples[slot * 2] else 0
            out[i * 2 + 1] = if (ok) samples[slot * 2 + 1] else 0
            captureNs[i] = if (ok) stamps[slot] else 0L
            seq[i] = if (ok) sequences[slot] else 0
            if (ok) { tags[slot] = Long.MIN_VALUE; buffered-- } else missing++
        }
        next += frames
        emitCounter("a_missing_frames", missing - previousMissing)
        return Read(first, frames)
    }

    private fun emitCounter(name: String, delta: Long) {
        if (delta > 0L) onCounter(name, delta)
    }
}
