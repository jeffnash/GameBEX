package com.gamebex.client.stream.guest

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.util.Log
import com.gamebex.client.stream.StreamCaps

/**
 * Plays the host's audio on the guest: a low-latency AudioTrack fed
 * from [buffer] by one thread, silence when the buffer is empty. Each played
 * chunk reports its host capture time and the guest time it should reach the
 * speaker, for the audio latency figure.
 */
class PcmPlayer(
    private val buffer: PcmJitterBuffer,
    private val onPlayed: (captureNs: Long, outputNs: Long) -> Unit = { _, _ -> },
) {
    private var track: AudioTrack? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile var trackUnderruns: Int = 0
        private set

 /** Guest-side mute for the one-emulator gate, where the guest's sound would be captured again. */
    @Volatile var muted: Boolean = false
        set(value) {
            field = value
            runCatching { track?.setVolume(if (value) 0f else 1f) }
        }

    fun start(): Boolean {
        if (running) return true
        val chunkBytes = StreamCaps.AUDIO_CHUNK_FRAMES * StreamCaps.AUDIO_CHANNELS * 2
        val min = AudioTrack.getMinBufferSize(StreamCaps.AUDIO_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val t = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(StreamCaps.AUDIO_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(min, 2 * chunkBytes))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
        } catch (e: Exception) {
            Log.w(TAG, "no audio track", e)
            return false
        }
        if (t.state != AudioTrack.STATE_INITIALIZED) {
            t.release()
            return false
        }
        track = t
 // The loop below writes silence whenever the jitter buffer is empty, so the track stays full and
 // its whole buffer becomes output latency .
 // The track is a FAST one on these phones (256-frame mixer bursts), so start at three bursts (16 ms)
 // and add a burst whenever it runs dry.
        runCatching { t.bufferSizeInFrames = START_FRAMES.coerceAtMost(t.bufferCapacityInFrames) }
        Log.i(TAG, "audio track frames=${t.bufferSizeInFrames} capacity=${t.bufferCapacityInFrames} mode=${t.performanceMode} min=$min")
        if (muted) t.setVolume(0f)
        t.play()
        running = true
        thread = Thread({ loop(t, chunkBytes) }, "gbx-audio-play").apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY
            start()
        }
        return true
    }

    fun stop() {
        running = false
        thread?.join(JOIN_MS)
        thread = null
        track?.let {
            runCatching { it.pause() }
            runCatching { it.flush() }
            runCatching { it.release() }
        }
        track = null
    }

    private fun loop(t: AudioTrack, chunkBytes: Int) {
        runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO) }
        val silence = ByteArray(chunkBytes)
        val ts = AudioTimestamp()
        var writtenBytes = 0L
        var underrunsSeen = t.underrunCount
        trackUnderruns = underrunsSeen
        while (running) {
            val chunk = buffer.pull()
            val data = chunk?.pcm ?: silence
            val first = writtenBytes / (StreamCaps.AUDIO_CHANNELS * 2)
            val n = writePcmFully(
                size = data.size,
                keepWriting = { running },
                write = { offset, remaining -> t.write(data, offset, remaining, AudioTrack.WRITE_BLOCKING) },
                onWritten = { writtenBytes += it },
            )
            if (n != data.size) {
                if (running) Log.w(TAG, "audio write incomplete $n/${data.size}")
                return
            }
            if (chunk != null) onPlayed(chunk.captureNs, playoutNs(t, ts, first))
            val u = t.underrunCount
            trackUnderruns = u
            if (u > underrunsSeen) {
                underrunsSeen = u
                val grown = (t.bufferSizeInFrames + BURST_FRAMES).coerceAtMost(minOf(MAX_FRAMES, t.bufferCapacityInFrames))
                if (grown > t.bufferSizeInFrames) {
                    runCatching { t.bufferSizeInFrames = grown }
                    Log.i(TAG, "audio track ran dry: buffer now ${t.bufferSizeInFrames} frames")
                }
            }
        }
    }

 /**
 * When the first frame of a chunk written at [firstFrame] reaches the speaker: from the track's
 * presentation timestamp when it has one (it includes the mixer and HAL), else from the frames still queued.
 */
    private fun playoutNs(t: AudioTrack, ts: AudioTimestamp, firstFrame: Long): Long {
        if (t.getTimestamp(ts)) return ts.nanoTime + (firstFrame - ts.framePosition) * 1_000_000_000L / StreamCaps.AUDIO_RATE
        val queued = (firstFrame - t.playbackHeadPosition.toLong()).coerceAtLeast(0)
        return System.nanoTime() + queued * 1_000_000_000L / StreamCaps.AUDIO_RATE
    }

    private companion object {
        const val TAG = "GameBEXStream"
        const val JOIN_MS = 500L
        const val BURST_FRAMES = 256
        const val START_FRAMES = 3 * BURST_FRAMES
        const val MAX_FRAMES = 1_920
    }
}

internal fun writePcmFully(
    size: Int,
    keepWriting: () -> Boolean,
    write: (offset: Int, remaining: Int) -> Int,
    onWritten: (bytes: Int) -> Unit,
): Int {
    var offset = 0
    while (offset < size && keepWriting()) {
        val n = write(offset, size - offset)
        if (n <= 0) return n
        if (n > size - offset) return -1
        offset += n
        onWritten(n)
    }
    return offset
}
