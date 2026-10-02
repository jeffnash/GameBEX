package com.gamebex.client.stream.shell

import android.media.MediaCodec
import android.os.Bundle
import android.util.Log
import android.view.Surface
import com.gamebex.client.stream.StreamCaps
import com.gamebex.client.stream.StreamError
import com.gamebex.client.stream.VideoLevel
import com.gamebex.client.stream.proto.AnnexB
import java.util.concurrent.atomic.AtomicInteger

/**
 * Display capture into a MediaCodec H.264 encoder. The
 * encoder's input surface is the mirror's target, so frames never touch the
 * CPU; one drain thread hands each access unit to the sink.
 *
 * Control calls ([requestKeyframe], [setBitrate], [changeLevel]) come from
 * MediaServer's reader and ticker threads, never from the drain thread.
 */
class AvcScreenSource(
    private val encoders: () -> List<AvcEncoders.EncoderInfo> = AvcEncoders::list,
) : VideoSource {
    private val lock = Any()
    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private var mirror: ScreenMirror? = null
    private var drain: Thread? = null
    private var generation = 0
    private var restarts = 0
    private val seq = AtomicInteger()
    private val invalidPts = AtomicInteger()
    @Volatile private var running = false
    @Volatile private var sink: VideoSink? = null
    @Volatile private var configSent = false
    @Volatile private var bitrate = 0

    /** The encoder and capture API in use, for logs and the probe. */
    @Volatile var encoderName: String? = null
        private set
    @Volatile var captureApi: String? = null
        private set

    @Volatile override var level: VideoLevel = StreamCaps.LEVELS[0]
        private set

    override fun start(level: VideoLevel, bitrate: Int, sink: VideoSink) {
        synchronized(lock) {
            this.sink = sink
            running = true
            restarts = 0
            openLocked(level, bitrate)
        }
    }

    override fun stop() {
        val thread: Thread?
        synchronized(lock) {
            running = false
            thread = closeLocked()
        }
        thread?.join(JOIN_MS)
    }

    override fun requestKeyframe() {
        val c = synchronized(lock) { codec } ?: return
        runCatching { c.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
            .onFailure { Log.w(TAG, "keyframe request failed", it) }
    }

    override fun setBitrate(bps: Int) {
        val c = synchronized(lock) {
            bitrate = bps
            codec
        } ?: return
        runCatching { c.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bps) }) }
            .onFailure { Log.w(TAG, "bitrate change failed", it) }
    }

    override fun changeLevel(level: VideoLevel, bitrate: Int) {
        val old: Thread?
        synchronized(lock) {
            if (!running) return
            old = closeLocked()
        }
        old?.join(JOIN_MS)
        synchronized(lock) {
            if (!running) return
            try {
                openLocked(level, bitrate)
            } catch (f: StreamFailure) {
                running = false
                sink?.onFailure("level ${level.index}: ${f.message}")
            }
        }
    }

    /** Configures the first encoder that accepts [level], then starts capture into it. */
    private fun openLocked(level: VideoLevel, bitrate: Int) {
        val candidates = AvcEncoders.rank(encoders(), level)
        if (candidates.isEmpty()) throw StreamFailure(StreamError.ENCODER, "no H.264 encoder for ${level.width}x${level.height}")
        var last: Throwable? = null
        for (info in candidates) {
            for (strict in listOf(true, false)) {
                val c = try {
                    MediaCodec.createByCodecName(info.name)
                } catch (t: Throwable) {
                    last = t
                    break
                }
                val input: Surface = try {
                    c.configure(AvcEncoders.format(level, bitrate, info, strict), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    val s = c.createInputSurface()
                    try {
                        c.start()
                    } catch (t: Throwable) {
                        runCatching { s.release() }
                        throw t
                    }
                    s
                } catch (t: Throwable) {
                    last = t
                    Log.w(TAG, "encoder ${info.name} strict=$strict refused ${level.width}x${level.height}", t)
                    runCatching { c.release() }
                    continue
                }
                val m = try {
                    ScreenMirror.open(level.width, level.height, input)
                } catch (f: StreamFailure) {
                    runCatching { c.stop() }
                    runCatching { c.release() }
                    runCatching { input.release() }
                    throw f
                }
                codec = c
                surface = input
                mirror = m
                encoderName = info.name
                captureApi = m.api
                this.level = level
                this.bitrate = bitrate
                configSent = false
                generation += 1
                val gen = generation
                drain = Thread({ drainLoop(c, gen) }, "gbx-video-drain").apply {
                    isDaemon = true
                    priority = Thread.MAX_PRIORITY
                    start()
                }
                Log.i(TAG, "video: ${info.name} ${level.width}x${level.height}@${level.fps} ${bitrate}bps strict=$strict via ${m.api}")
                return
            }
        }
        throw StreamFailure(StreamError.ENCODER, "no encoder accepted ${level.width}x${level.height}: $last", last)
    }

    /** Stops capture and the encoder; returns the drain thread to join outside the lock. */
    private fun closeLocked(): Thread? {
        generation += 1
        runCatching { mirror?.close() }
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        runCatching { surface?.release() }
        mirror = null
        codec = null
        surface = null
        return drain.also { drain = null }
    }

    private fun drainLoop(c: MediaCodec, gen: Int) {
        val info = MediaCodec.BufferInfo()
        try {
            while (running && gen == currentGeneration()) {
                val index = c.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
                when {
                    index >= 0 -> {
                        val dequeuedNs = System.nanoTime()
                        val data = try {
                            val buffer = c.getOutputBuffer(index)
                            if (buffer != null && info.size > 0) {
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                ByteArray(info.size).also { buffer.get(it) }
                            } else null
                        } finally {
                            c.releaseOutputBuffer(index, false)
                        }
                        if (data != null) deliver(data, info, dequeuedNs)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Log.i(TAG, "encoder output format ${c.outputFormat}")
                }
            }
        } catch (e: IllegalStateException) {
            if (running && gen == currentGeneration()) recover(e)
        }
    }

    private fun deliver(data: ByteArray, info: MediaCodec.BufferInfo, dequeuedNs: Long) {
        val out = sink ?: return
        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
            configSent = true
            out.onConfig(data)
            return
        }
        val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
        if (key && !configSent) {
            // Some encoders put SPS/PPS only inside the first IDR; the guest needs them as config.
            AnnexB.parameterSets(data)?.let {
                configSent = true
                out.onConfig(it)
            }
        }
        val capture = captureNsForPts(info.presentationTimeUs, dequeuedNs)
        if (capture == 0L) {
            val count = invalidPts.incrementAndGet()
            if (count == 1 || count % PTS_LOG_INTERVAL == 0) {
                Log.w(TAG, "encoder PTS outside ${MAX_PTS_AGE_NS}ns: ptsUs=${info.presentationTimeUs} dequeuedNs=$dequeuedNs invalidPts=$count")
            }
        }
        out.onFrame(EncodedFrame(seq.incrementAndGet(), key, capture, dequeuedNs, data))
    }

    /** One restart per failure, at most [MAX_RESTARTS] per stream; then the stream ends. */
    private fun recover(e: IllegalStateException) {
        Log.w(TAG, "encoder failed", e)
        Thread({
            val old: Thread?
            synchronized(lock) {
                if (!running) return@Thread
                old = closeLocked()
            }
            old?.join(JOIN_MS)
            synchronized(lock) {
                if (!running) return@Thread
                restarts += 1
                val failure = if (restarts > MAX_RESTARTS) "encoder failed ${restarts} times" else try {
                    openLocked(level, bitrate)
                    null
                } catch (f: StreamFailure) {
                    f.message ?: "restart failed"
                }
                if (failure != null) {
                    running = false
                    sink?.onFailure(failure)
                }
            }
        }, "gbx-video-restart").apply { isDaemon = true }.start()
    }

    private fun currentGeneration(): Int = synchronized(lock) { generation }

    companion object {
        private const val TAG = "GameBEXStream"
        private const val DEQUEUE_TIMEOUT_US = 100_000L
        private const val JOIN_MS = 1_000L
        private const val MAX_RESTARTS = 2
        private const val MAX_PTS_AGE_NS = 1_000_000_000L
        private const val PTS_LOG_INTERVAL = 120

        internal fun captureNsForPts(ptsUs: Long, dequeuedNs: Long): Long {
            if (ptsUs <= 0L || ptsUs > Long.MAX_VALUE / 1_000L) return 0L
            val ptsNs = ptsUs * 1_000L
            return if (ptsNs <= dequeuedNs && dequeuedNs - ptsNs <= MAX_PTS_AGE_NS) ptsNs else 0L
        }
    }
}
