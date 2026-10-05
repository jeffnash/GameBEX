package com.gamebex.client.stream.guest

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.util.Log
import com.gamebex.client.stream.StreamCaps
import com.gamebex.client.BuildConfig
import com.gamebex.client.stream.StreamDebug
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport

class PcmPlayer(
    private val buffer: PcmJitterBuffer,
    private val onPcmPlayed: ((StreamPcmPlayed) -> Unit)? = null,
    private val options: AudioPlaybackOptions = AudioPlaybackOptions.sample(BuildConfig.DEBUG, StreamDebug::prop),
    private val source: AudioRenderSource? = null,
    private val drift: AudioDrift = AudioDrift(),
    private val count: (String, Long) -> Unit = { _, _ -> },
    private val onAudioFailure: (String) -> Unit = {},
    private val onOutputMapping: (AudioRenderInfo, Long, Long, Boolean) -> Unit = { _, _, _, _ -> },
    private val onPlayed: (captureNs: Long, outputNs: Long) -> Unit = { _, _ -> },
) {
    private val lifecycle: AudioOutputLifecycle<AndroidPcmOutput> = AudioOutputLifecycle(
        factory = { createAndroidPcmOutput(options) },
        write = { output, keepRunning ->
            runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO) }
            count("audio_native_underruns", 0L)
            drift.reset()
            val priorUnderruns = trackUnderruns
            var observedUnderruns = 0
            try {
                runPcmOutput(
                    output, source ?: JitterAudioRenderSource(buffer), options, source != null,
                    keepRunning = keepRunning, flush = { flushPending.getAndSet(false) },
                    drift = drift, count = count, muted = { muted }, onPcmPlayed = onPcmPlayed,
                    onPlayed = onPlayed, onOutputMapping = onOutputMapping,
                    onMetrics = { queued, underruns ->
                        queuedOutputFrames = queued
                        trackUnderruns = priorUnderruns + underruns
                        val delta = underruns - observedUnderruns
                        observedUnderruns = underruns
                        if (delta > 0) count("audio_native_underruns", delta.toLong())
                    },
                    releaseOnExit = false,
                )
            } catch (error: Exception) {
                if (keepRunning()) {
                    count("audio_output_failure", 1L)
                    Log.w(TAG, "guest audio output failed", error)
                }
                throw error
            }
        },
        interruptOutput = !options.jit,
        onStopTimeout = { count("audio_writer_stop_timeout", 1L) },
    )
    private val flushPending = AtomicBoolean(false)
    @Volatile var trackUnderruns: Int = 0
        private set
    @Volatile var queuedOutputFrames: Int = 0
        private set

    @Volatile var muted: Boolean = false
        set(value) {
            field = value
            lifecycle.setMuted(value)
        }

    val isActive: Boolean get() = lifecycle.isActive
    fun requestFlush() { flushPending.set(true) }
    fun start(onFailure: (String) -> Unit = onAudioFailure): Boolean = lifecycle.start(onFailure)
    fun stop() = lifecycle.stop()
    fun close() = lifecycle.close()

    private companion object { const val TAG = "GameBEXStream" }
}

internal data class PcmTimestamp(val frame: Long, val nanoTime: Long)

internal interface PcmOutputPort {
    val bufferFrames: Int
    val underruns: Int
    fun head(): Int
    fun timestamp(): PcmTimestamp?
    fun write(bytes: ByteArray, offset: Int, remaining: Int, nonBlocking: Boolean): Int
    fun flush()
    fun grow()
    fun release()
}

private fun createAndroidPcmOutput(options: AudioPlaybackOptions): AndroidPcmOutput {
    val chunkBytes = StreamCaps.AUDIO_CHUNK_FRAMES * 4
    val min = AudioTrack.getMinBufferSize(StreamCaps.AUDIO_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
    val track = AudioTrack.Builder()
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
    return AndroidPcmOutput(track, options)
}

private class AndroidPcmOutput(private val track: AudioTrack, private val options: AudioPlaybackOptions) : PcmOutputPort, AudioOutputHandle {
    private val stamp = AudioTimestamp()
    override fun play() {
        check(track.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack initialization failed" }
        val nativeFrames = if (options.jit) 768 else options.leadFrames
        runCatching { track.bufferSizeInFrames = nativeFrames.coerceAtMost(track.bufferCapacityInFrames) }
        track.play()
    }
    override fun stop() { track.stop() }
    override fun setMuted(muted: Boolean) { track.setVolume(if (muted) 0f else 1f) }
    override val bufferFrames: Int get() = track.bufferSizeInFrames
    override val underruns: Int get() = track.underrunCount
    override fun head(): Int = track.playbackHeadPosition
    override fun timestamp(): PcmTimestamp? = if (track.getTimestamp(stamp)) PcmTimestamp(stamp.framePosition, stamp.nanoTime) else null
    override fun write(bytes: ByteArray, offset: Int, remaining: Int, nonBlocking: Boolean): Int =
        track.write(bytes, offset, remaining, if (nonBlocking) AudioTrack.WRITE_NON_BLOCKING else AudioTrack.WRITE_BLOCKING)
    override fun flush() { track.pause(); track.flush(); track.play() }
    override fun grow() {
        val frames = (bufferFrames + 256).coerceAtMost(minOf(1_920, track.bufferCapacityInFrames))
        if (frames > bufferFrames) track.bufferSizeInFrames = frames
    }
    override fun release() {
        runCatching { track.pause() }
        runCatching { track.flush() }
        track.release()
    }
}

internal fun runPcmOutput(
    port: PcmOutputPort,
    source: AudioRenderSource,
    options: AudioPlaybackOptions,
    indexed: Boolean,
    keepRunning: () -> Boolean,
    flush: () -> Boolean = { false },
    nowNs: () -> Long = System::nanoTime,
    park: (Long) -> Unit = { LockSupport.parkNanos(it) },
    drift: AudioDrift = AudioDrift(),
    count: (String, Long) -> Unit = { _, _ -> },
    muted: () -> Boolean = { false },
    onPcmPlayed: ((StreamPcmPlayed) -> Unit)? = null,
    onPlayed: (Long, Long) -> Unit = { _, _ -> },
    onOutputMapping: (AudioRenderInfo, Long, Long, Boolean) -> Unit = { _, _, _, _ -> },
    onMetrics: (Int, Int) -> Unit = { _, _ -> },
    releaseOnExit: Boolean = true,
) {
    val quantum = if (indexed) options.outputFrames else StreamCaps.AUDIO_CHUNK_FRAMES
    val bytes = ByteArray(quantum * 4)
    val head = PlaybackPosition()
    var epoch = source.epoch()
    var writtenBytes = 0L
    var pending: PendingPcm? = null
    var badHeads = 0
    var flushedNs = nowNs()
    var progressNs = flushedNs
    var lastMetric = 0L
    var underruns = port.underruns
    var renderNs = flushedNs
    var firstWrite = false
    try {
        while (keepRunning()) {
            val now = nowNs()
            if (flush() || source.epoch() != epoch) {
                pending?.let { count("audio_abandoned_output_frames", (it.remaining + 3L) / 4) }
                val played = head.update(port.head())
                if (played != null && played <= writtenBytes / 4) count("audio_flushed_queued_frames", writtenBytes / 4 - played)
                else count("audio_flush_position_unknown", 1L)
                port.flush(); head.reset(); writtenBytes = 0L; pending = null
                epoch = source.epoch(); drift.reset(); flushedNs = now; progressNs = now; badHeads = 0
            }
            val played = head.update(port.head())
            if (played == null || played > writtenBytes / 4) {
                count("audio_invalid_head", 1L)
                if (++badHeads >= 3) throw IllegalStateException("invalid AudioTrack playback head")
                park(500_000L); continue
            }
            badHeads = 0
            val queued = (writtenBytes / 4 - played).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
            onMetrics(queued, port.underruns)
            if (pending == null) {
                val lead = minOf(options.leadFrames, port.bufferFrames).coerceAtLeast(quantum)
                if (options.jit && queued > lead - quantum) {
                    if (now - progressNs >= 500_000_000L) throw IllegalStateException("AudioTrack playout stalled")
                    park(500_000L); continue
                }
                val info = source.render(quantum, queued, now, bytes)
                if (info.epoch != source.epoch()) continue
                pending = PendingPcm(bytes, info, writtenBytes)
                progressNs = now; renderNs = now; firstWrite = true
            }
            val block = pending ?: continue
            if (block.info.epoch != source.epoch()) continue
            val n = port.write(block.bytes, block.offset, block.remaining, options.jit)
            if (n < 0 || n > block.remaining) throw IllegalStateException("AudioTrack write=$n")
            if (n == 0) {
                if (now - progressNs >= 500_000_000L) throw IllegalStateException("AudioTrack made no write progress")
                park(500_000L); continue
            }
            block.wrote(n); writtenBytes += n
            val after = nowNs()
            progressNs = after
            if (firstWrite) {
                count("audio_render_to_first_write_ns", after - renderNs)
                count("audio_render_to_first_write_spans", 1L)
                firstWrite = false
            }
            if (block.complete) {
                val currentHead = head.update(port.head())?.takeIf { it <= writtenBytes / 4 }
                val stamp = port.timestamp()?.takeIf {
                    it.nanoTime >= flushedNs && it.nanoTime <= after + 1_000_000L &&
                        after - it.nanoTime <= 500_000_000L && it.frame in 0..writtenBytes / 4
                }
                val position = stamp?.frame ?: currentHead ?: played
                val anchorNs = stamp?.nanoTime ?: after
                drift.observePlayout(position, anchorNs)
                val outputNs = anchorNs + audioFrameNs(block.outputFirstFrame - position)
                val spans = block.info.spans.ifEmpty { listOf(AudioRenderSpan(0, quantum, null, 0L, block.info.firstSourceFrame)) }
                for (span in spans) {
                    val spanOutput = outputNs + audioFrameNs(span.outputOffset.toLong())
                    onPcmPlayed?.let { callback ->
                        runCatching {
                            callback(StreamPcmPlayed(
                                pcm = block.bytes.copyOfRange(span.outputOffset * 4, (span.outputOffset + span.outputFrames) * 4),
                                bytes = span.outputFrames * 4, seq = span.seq, captureNs = span.captureNs,
                                outputNs = spanOutput, timestampValid = stamp != null,
                                timestampNs = anchorNs, timestampFramePosition = position,
                                firstWrittenFrame = block.outputFirstFrame + span.outputOffset,
                                firstWrittenByte = block.outputFirstByte + span.outputOffset * 4L,
                                observedNs = after, muted = muted(),
                            ))
                        }
                    }
                    if (span.seq != null && span.captureNs > 0L && (!indexed || stamp != null)) onPlayed(span.captureNs, spanOutput)
                }
                onOutputMapping(block.info, block.outputFirstFrame, outputNs, stamp != null)
                pending = null
            }
            if (now - lastMetric >= 100_000_000L) {
                lastMetric = now
                val current = port.underruns
                onMetrics(queued, current)
                if (!options.jit && current > underruns) runCatching { port.grow() }
                underruns = current
            }
        }
    } finally {
        pending?.let { count("audio_abandoned_output_frames", (it.remaining + 3L) / 4) }
        val played = runCatching { head.update(port.head()) }.getOrNull()
        if (played != null && played <= writtenBytes / 4) count("audio_flushed_queued_frames", writtenBytes / 4 - played)
        else count("audio_flush_position_unknown", 1L)
        if (releaseOnExit) port.release()
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
