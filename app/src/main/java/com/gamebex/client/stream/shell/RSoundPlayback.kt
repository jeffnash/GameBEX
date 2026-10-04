package com.gamebex.client.stream.shell

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

fun interface RSoundPlaybackFactory {
    fun create(): RSoundPlayer
}

data class RSoundPlaybackStats(
    val playedFrames: Long,
    val headFrames: Long,
    val timestampFrames: Long?,
    val timestampNs: Long?,
    val underruns: Int,
)

interface RSoundPlayer : AutoCloseable {
    val ready: Boolean
    val bufferBytes: Int
    fun start()
    fun write(bytes: ByteArray, offset: Int, count: Int): Int
    fun stats(): RSoundPlaybackStats
    override fun close()
}

internal fun rsoundWriteFully(
    player: RSoundPlayer,
    bytes: ByteArray,
    onWritten: (Int) -> Unit = {},
    active: () -> Boolean,
): Boolean {
    var offset = 0
    var emptyWrites = 0
    while (offset < bytes.size && active()) {
        val wanted = minOf(bytes.size - offset, RSoundProtocol.CHUNK_BYTES)
        val count = player.write(bytes, offset, wanted)
        if (count < 0 || count > wanted) throw IOException("RSound AudioTrack write failed: $count")
        if (count == 0) {
            if (++emptyWrites >= 3) throw IOException("RSound AudioTrack write stalled")
            Thread.yield()
        } else {
            emptyWrites = 0
            onWritten(count)
            offset += count
        }
    }
    return offset == bytes.size && active()
}

internal data class RSoundBufferSizes(val capacityBytes: Int, val requestedFrames: Int)

internal fun rsoundChooseBufferSizes(
    minimumBytes: Int,
    desiredFrames: Int = RSoundPlayback.DEFAULT_BUFFER_FRAMES,
): RSoundBufferSizes {
    check(minimumBytes in 1..RSoundPlayback.MAX_ALLOCATION_BYTES) {
        "RSound unsupported AudioTrack allocation: $minimumBytes"
    }
    rsoundCheckEffectiveBufferFrames(desiredFrames)
    return RSoundBufferSizes(
        capacityBytes = maxOf(minimumBytes, desiredFrames * RSoundProtocol.FRAME_BYTES),
        requestedFrames = desiredFrames,
    )
}

internal fun rsoundCheckEffectiveBufferFrames(frames: Int) {
    check(frames in 1..(RSoundPlayback.MAX_BUFFER_BYTES / RSoundProtocol.FRAME_BYTES)) {
        "RSound AudioTrack buffer exceeds latency cap: $frames frames"
    }
}

class RSoundPlayback : RSoundPlayer {
    private val closed = AtomicBoolean(false)
    private val metricsLock = Any()
    private var lastHead = 0L
    private var headWrap = 0L
    private val track: AudioTrack

    override val ready: Boolean
        get() = !closed.get() && track.state == AudioTrack.STATE_INITIALIZED

    override val bufferBytes: Int
        get() = track.bufferSizeInFrames * RSoundProtocol.FRAME_BYTES

    init {
        check(ShellContext.get() != null) { "RSound shell audio identity unavailable" }
        val minimum = AudioTrack.getMinBufferSize(
            RSoundProtocol.RATE,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val buffers = rsoundChooseBufferSizes(minimum)
        val candidate = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build(),
            )
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(RSoundProtocol.RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setBufferSizeInBytes(buffers.capacityBytes)
            .build()
        try {
            check(candidate.state == AudioTrack.STATE_INITIALIZED) { "RSound AudioTrack did not initialize" }
            val frames = candidate.setBufferSizeInFrames(buffers.requestedFrames)
            rsoundCheckEffectiveBufferFrames(frames)
            rsoundCheckEffectiveBufferFrames(candidate.bufferSizeInFrames)
            candidate.play()
            check(candidate.playState == AudioTrack.PLAYSTATE_PLAYING) { "RSound AudioTrack did not start" }
            candidate.pause()
            candidate.flush()
        } catch (t: Throwable) {
            runCatching { candidate.release() }
            throw t
        }
        track = candidate
    }

    override fun start() {
        check(ready) { "RSound AudioTrack is closed" }
        track.play()
        check(track.playState == AudioTrack.PLAYSTATE_PLAYING) { "RSound AudioTrack did not start" }
    }

    override fun write(bytes: ByteArray, offset: Int, count: Int): Int {
        if (closed.get()) return AudioTrack.ERROR_INVALID_OPERATION
        return track.write(bytes, offset, minOf(count, RSoundProtocol.CHUNK_BYTES), AudioTrack.WRITE_BLOCKING)
    }

    override fun stats(): RSoundPlaybackStats = synchronized(metricsLock) {
        val rawHead = track.playbackHeadPosition.toLong() and 0xffff_ffffL
        if (rawHead < lastHead) headWrap += 1L shl 32
        lastHead = rawHead
        val head = headWrap + rawHead
        val timestamp = AudioTimestamp()
        val timestampValid = track.getTimestamp(timestamp)
        RSoundPlaybackStats(
            playedFrames = if (timestampValid) timestamp.framePosition else head,
            headFrames = head,
            timestampFrames = if (timestampValid) timestamp.framePosition else null,
            timestampNs = if (timestampValid) timestamp.nanoTime else null,
            underruns = track.underrunCount,
        )
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { track.pause() }
        runCatching { track.flush() }
        runCatching { track.stop() }
        runCatching { track.release() }
    }

    companion object {
        internal const val DEFAULT_BUFFER_FRAMES = 768
        internal const val MAX_ALLOCATION_BYTES = RSoundProtocol.RATE * RSoundProtocol.FRAME_BYTES
        const val MAX_BUFFER_BYTES = RSoundProtocol.RATE * RSoundProtocol.FRAME_BYTES / 25
    }
}
