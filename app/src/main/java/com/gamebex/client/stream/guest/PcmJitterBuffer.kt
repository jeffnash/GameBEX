package com.gamebex.client.stream.guest

import com.gamebex.client.stream.StreamCaps
import java.util.ArrayDeque

/**
 * The guest's audio queue. The network thread pushes chunks of
 * [StreamCaps.AUDIO_CHUNK_FRAMES]; the player thread pulls one per chunk of playback.
 *
 * - Playback starts once [targetMs] is queued.
 * - An underrun plays silence and resumes with the next chunk that arrives; only a gap longer
 *   than [REPRIME_GAP_MS] waits for [targetMs] again. Each underrun raises the target by
 *   5 ms, up to [maxMs] less two chunks, except in the first [STARTUP_MS] of playback, when
 *   connecting and the first keyframe make the network burst anyway. [SHRINK_AFTER_MS]
 *   without one lowers it by one chunk, down to [minMs].
 * - Depth is steered back to the target: a burst or an underrun leaves extra audio
 *   queued, and that would otherwise stay as delay for the rest of the session.
 *   While more than the target plus [SLACK_MS] stays queued, one chunk is dropped every [DRAIN_EVERY_MS].
 * - More than [maxMs] queued is cut back to the target at once.
 *
 * Video is never delayed to match audio. Thread-safe.
 */
class PcmJitterBuffer(
    private val chunkMs: Int = StreamCaps.AUDIO_CHUNK_FRAMES * 1_000 / StreamCaps.AUDIO_RATE,
    startTargetMs: Int = StreamCaps.AUDIO_TARGET_MS,
    private val minMs: Int = StreamCaps.AUDIO_MIN_MS,
    private val maxMs: Int = StreamCaps.AUDIO_MAX_MS,
    startupMs: Int = STARTUP_MS,
    private val nowNs: () -> Long = System::nanoTime,
) {
    /** Pulls per policy period: the policy is in milliseconds, so it holds for any chunk size. */
    val shrinkAfterPulls: Int = (SHRINK_AFTER_MS / chunkMs).coerceAtLeast(1)
    private val startupPulls: Int = startupMs / chunkMs
    private val drainEveryPulls: Int = (DRAIN_EVERY_MS / chunkMs).coerceAtLeast(1)

    class Chunk(val seq: Int, val captureNs: Long, val pcm: ByteArray)

    private val queue = ArrayDeque<Chunk>()
    private var lastSeq = 0
    private var haveSeq = false
    private var priming = true
    private var missingSinceNs: Long? = null
    private var cleanPulls = 0
    private var playedPulls = 0
    private var highPulls = 0

    private val maxTargetMs: Int get() = maxMs - 2 * chunkMs

    private val initialTargetMs = startTargetMs.coerceIn(minMs, maxTargetMs)

    @Volatile var targetMs: Int = initialTargetMs
        private set
    @Volatile var underruns: Int = 0
        private set
    @Volatile var dropped: Int = 0
        private set

    val depthMs: Int
        @Synchronized get() = queue.size * chunkMs

    @Synchronized
    fun push(seq: Int, captureNs: Long, pcm: ByteArray) {
        if (haveSeq && seq - lastSeq <= 0) return
        haveSeq = true
        lastSeq = seq
        if (queue.isEmpty() && missingSinceNs?.let { nowNs() - it > REPRIME_GAP_MS * 1_000_000L } == true) priming = true
        queue.addLast(Chunk(seq, captureNs, pcm))
        if (queue.size * chunkMs > maxMs) {
            while (queue.size * chunkMs > targetMs) {
                queue.pollFirst()
                dropped += 1
            }
        }
    }

    /** The next chunk to play, or null: play silence for one chunk. */
    @Synchronized
    fun pull(): Chunk? {
        val now = nowNs()
        if (queue.isEmpty() && missingSinceNs?.let { now - it > REPRIME_GAP_MS * 1_000_000L } == true) priming = true
        if (priming) {
            if (queue.size * chunkMs < targetMs) return null
            priming = false
        }
        val next = queue.pollFirst()
        if (next == null) {
            if (missingSinceNs == null) {
                missingSinceNs = now
                underruns += 1
                cleanPulls = 0
                highPulls = 0
                if (playedPulls >= startupPulls) targetMs = minOf(maxTargetMs, targetMs + UNDERRUN_STEP_MS)
            }
            return null
        }
        missingSinceNs = null
        playedPulls += 1
        cleanPulls += 1
        if (cleanPulls >= shrinkAfterPulls) {
            cleanPulls = 0
            if (targetMs - chunkMs >= minMs) {
                targetMs -= chunkMs
                if (queue.size * chunkMs > targetMs) {
                    queue.pollFirst()
                    dropped += 1
                }
            }
        }
        if (queue.size * chunkMs > targetMs + SLACK_MS) {
            highPulls += 1
            if (highPulls >= drainEveryPulls) {
                highPulls = 0
                queue.pollFirst()
                dropped += 1
            }
        } else {
            highPulls = 0
        }
        return next
    }

    @Synchronized
    fun reset() {
        queue.clear()
        haveSeq = false
        priming = true
        missingSinceNs = null
        cleanPulls = 0
        playedPulls = 0
        highPulls = 0
        targetMs = initialTargetMs
    }

    companion object {
        const val REPRIME_GAP_MS: Int = 100
        private const val UNDERRUN_STEP_MS: Int = 5

        /** A 3 s period makes the target oscillate into an underrun every few seconds. */
        const val SHRINK_AFTER_MS: Int = 10_000

        const val STARTUP_MS: Int = 3_000

        const val SLACK_MS: Int = 10

        const val DRAIN_EVERY_MS: Int = 500
    }
}
