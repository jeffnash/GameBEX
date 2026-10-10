package com.gamebex.client.stream.shell

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import com.gamebex.client.stream.StreamDebug
import android.os.Bundle
import android.util.Log
import android.view.Surface
import com.gamebex.client.stream.StreamCaps
import com.gamebex.client.stream.StreamConfig
import com.gamebex.client.stream.StreamError
import com.gamebex.client.stream.VideoLevel
import com.gamebex.client.stream.proto.AnnexB
import java.util.concurrent.atomic.AtomicInteger

/**
 * Display capture into a MediaCodec H.264 encoder. The
 * encoder's input surface is the target of a display mirror or, with app
 * capture, of the app's single-app MediaProjection, so frames never touch the
 * CPU; one drain thread hands each access unit to the sink.
 *
 * Control calls ([requestKeyframe], [setBitrate], [changeLevel]) come from
 * MediaServer's reader and ticker threads, never from the drain thread.
 */
class AvcScreenSource(
    private val encoders: () -> List<AvcEncoders.EncoderInfo> = AvcEncoders::list,
    private val codecDiagnostics: Boolean = StreamDebug.flag("codec_diag"),
    private val config: StreamConfig? = null,
    private val options: AvcEncoders.Options = AvcEncoders.Options(
        fps = shellInt("encoder_fps", config?.encoderFps ?: 0, 0..50).takeIf { it == 50 } ?: 0,
        intraRefresh = StreamDebug.flag("encoder_intra_refresh"),
        keyframesOnRequest = shellFlag("keyframes_on_request", config?.keyframesOnRequest ?: false),
    ),
    private val cropOptions: StreamCropOptions = StreamCropOptions.resolve(config),
) : VideoSource {
    private val lock = Any()
    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    /** App capture: the app renders the game's window into our surface, so no display mirror. */
    private var appTarget: CaptureTarget? = null
    private var drain: Thread? = null
    private var cropPass: StreamCropPass? = null
    /** The rect the live crop pass cuts, or null when uncropped. Guarded by [lock]. */
    private var activeCropRect: CropRect? = null
    /** Current crop viewport: the config's portrait-time value until [updateCropViewport] re-pins it. */
    @Volatile private var cropViewportNow: String = cropOptions.viewport
    private var generation = 0
    private var restarts = 0
    private val seq = AtomicInteger()
    private val invalidPts = AtomicInteger()
    @Volatile private var running = false
    @Volatile private var sink: VideoSink? = null
    private val catalog by lazy { encoders() }
    private var vendorDiscovered = false
    private var codecEpoch = 0L
    @Volatile private var intraRefreshDisabled = false
    val eligibleLevels: List<VideoLevel> get() = StreamCaps.LEVELS.filter { AvcEncoders.rank(catalog, it, options).isNotEmpty() }
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
            intraRefreshDisabled = false
            try {
                openLocked(level, bitrate)
            } catch (f: StreamFailure) {
                running = false
                throw f
            }
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
        val reopenGeneration: Int
        synchronized(lock) {
            if (!running) return
            old = closeLocked()
            reopenGeneration = generation
        }
        old?.join(JOIN_MS)
        synchronized(lock) {
            if (!running || generation != reopenGeneration || codec != null) return
            try {
                openLocked(level, bitrate)
            } catch (f: StreamFailure) {
                running = false
                sink?.onFailure("level ${level.index}: ${f.message}")
            }
        }
    }

    override fun captureInto(target: CaptureTarget) {
        synchronized(lock) { appTarget = target }
    }

    /**
     * Re-pins the crop to the landscape [viewport] once the game rotates
     * the display: the config's portrait-time viewport centers the crop
     * like legacy, off the emulator window. Same-size shifts swap the GL
     * pass and re-point capture (the encoder is untouched); a resize
     * (legacy level-size capture → native display capture) re-opens the
     * encoder at the same level via [changeLevel] (new config + keyframe,
     * the guest's routine restart path); anything else — idle,
     * uncropped, unparseable, EGL failure — keeps the live pass. Later
     * level changes reuse the re-pinned viewport.
     */
    override fun updateCropViewport(viewport: String) {
        var reopen = false
        synchronized(lock) {
            if (!running) return
            if (parseViewport(viewport) == null) {
                Log.w(TAG, "video crop keeps viewport (unparseable update)")
                return
            }
            cropViewportNow = viewport
            val pass = cropPass ?: return
            val current = activeCropRect ?: return
            val lv = level
            val updated = cropOptions.copy(viewport = viewport)
            val (capW, capH) = streamCropCaptureSize(lv, updated)
            val rect = runCatching {
                streamCropRect(capW, capH, cropOptions.aspectNum, cropOptions.aspectDen, viewport)
            }.getOrNull() ?: return
            if (rect == current) {
                Log.i(TAG, "video crop already pinned rect=${rect.left},${rect.top} ${rect.width}x${rect.height}")
                return
            }
            if (streamCropEncodeSize(rect) != streamCropEncodeSize(current)) {
                reopen = true
                return@synchronized
            }
            val target = appTarget ?: return
            val input = surface ?: return
            val replacement = runCatching { StreamCropPass(capW, capH, rect, input) }.getOrNull()
            if (replacement == null) {
                Log.w(TAG, "video crop keeps rect (EGL unavailable for the update)")
                return
            }
            runCatching { pass.release() }
            cropPass = replacement
            activeCropRect = rect
            Log.i(TAG, "video crop re-pinned src=${capW}x${capH} rect=${rect.left},${rect.top} ${rect.width}x${rect.height} viewport=$viewport")
            runCatching { target.onSurface(replacement.inputSurface, capW, capH) }
        }
        if (reopen) {
            Log.i(TAG, "video crop re-opening the encoder for the landscape viewport")
            changeLevel(level, bitrate)
        }
    }

    /** Configures the first encoder that accepts [level], then starts capture into it. */
    private fun openLocked(level: VideoLevel, bitrate: Int, allowCrop: Boolean = true) {
        val target = appTarget ?: throw StreamFailure(StreamError.DISPLAY_CAPTURE, "explicit screen sharing consent required")
        val activeOptions = if (intraRefreshDisabled) options.copy(intraRefresh = false) else options
        val effectiveCrop = if (cropViewportNow == cropOptions.viewport) cropOptions else cropOptions.copy(viewport = cropViewportNow)
        val (encodeLevel, cropRect) = if (allowCrop) streamCropEncodeLevel(level, effectiveCrop) else level to null
        val candidates = AvcEncoders.rank(catalog, encodeLevel, activeOptions)
        if (candidates.isEmpty()) throw StreamFailure(StreamError.ENCODER, "no H.264 encoder for ${encodeLevel.width}x${encodeLevel.height}")
        var last: Throwable? = null
        for (info in candidates) {
            if (activeOptions.intraRefresh && !info.intraRefresh) {
                Log.i(TAG, "ENCODER_EXPERIMENT codec=${info.name} intraRefreshFallback=unsupported periodicIdrS=${AvcEncoders.periodicIdrS(activeOptions)}")
            }
            for (configuration in AvcEncoders.configurationAttempts(encodeLevel, info, activeOptions)) {
                val c = try {
                    MediaCodec.createByCodecName(info.name)
                } catch (t: Throwable) {
                    last = t
                    break
                }
                val input: Surface = try {
                    c.configure(AvcEncoders.format(encodeLevel, bitrate, info, configuration), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
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
                    Log.w(TAG, "encoder ${info.name} strict=${configuration.strict} fps=${configuration.fps} intraRefreshPeriod=${configuration.intraRefreshPeriod} refused ${encodeLevel.width}x${encodeLevel.height}", t)
                    runCatching { c.release() }
                    continue
                }
                codec = c
                surface = input
                encoderName = info.name
                captureApi = API_APP
                this.bitrate = bitrate
                generation += 1
                val gen = generation
                if (codecDiagnostics && !vendorDiscovered) {
                    vendorDiscovered = true
                    if (Build.VERSION.SDK_INT >= 31) runCatching {
                        c.supportedVendorParameters.sorted().forEach { name ->
                            Log.i(TAG, "CODEC_VENDOR codec=${c.name} parameter=$name type=${c.getParameterDescriptor(name)?.type}")
                        }
                    }.onFailure { Log.i(TAG, "CODEC_VENDOR_UNAVAILABLE codec=${c.name} reason=${it.javaClass.simpleName}") }
                    Log.i(TAG, "CODEC_CAPS ${catalog.joinToString { "${it.name}:${it.supportedLevelIds}" }}")
                    runCatching { Log.i(TAG, "CODEC_FORMAT configured=${c.inputFormat}") }
                }
                if (configuration.intraRefreshFallback) intraRefreshDisabled = true
                if (options.fps != 0 || options.intraRefresh) {
                    Log.i(TAG, "ENCODER_EXPERIMENT codec=${info.name} requestedFps=${level.fps} configuredFps=${configuration.fps} intraRefreshPeriod=${configuration.intraRefreshPeriod} intraRefreshFallback=${configuration.intraRefreshFallback} periodicIdrS=${configuration.iFrameIntervalS}")
                }
                drain = Thread({ drainLoop(c, gen, encodeLevel, configuration) }, "gbx-video-drain").apply {
                    isDaemon = true
                    priority = Thread.MAX_PRIORITY
                    start()
                }
                Log.i(TAG, "video: ${info.name} ${encodeLevel.width}x${encodeLevel.height}@${configuration.fps} requestedFps=${encodeLevel.fps} ${bitrate}bps strict=${configuration.strict} software=${!info.hardware} via $captureApi")
                // Native path: the capture runs at the display size and the
                // GL pass crops display-px 1:1 into the encoder (no
                // 1604→1600 resampling smear); legacy captures at the level
                // size, uncropped streams unchanged.
                val (capW, capH) = if (cropRect != null) streamCropCaptureSize(level, effectiveCrop)
                else level.width to level.height
                val captureSurface = if (cropRect != null) {
                    try {
                        val pass = StreamCropPass(capW, capH, cropRect, input)
                        cropPass = pass
                        activeCropRect = cropRect
                        Log.i(TAG, "video crop src=${capW}x${capH} rect=${cropRect.left},${cropRect.top} ${cropRect.width}x${cropRect.height} encode=${encodeLevel.width}x${encodeLevel.height}")
                        pass.inputSurface
                    } catch (t: Throwable) {
                        Log.w(TAG, "video crop disabled, EGL unavailable: ${t.javaClass.simpleName}: ${t.message}")
                        closeLocked()
                        return openLocked(level, bitrate, allowCrop = false)
                    }
                } else input
                try {
                    target.onSurface(captureSurface, capW, capH)
                } catch (t: Throwable) {
                    closeLocked()
                    throw StreamFailure(StreamError.DISPLAY_CAPTURE, "capture surface failed", t)
                }
                return
            }
        }
        throw StreamFailure(StreamError.ENCODER, "no encoder accepted ${level.width}x${level.height}: $last", last)
    }

    /** Stops capture and the encoder; returns the drain thread to join outside the lock. */
    private fun closeLocked(): Thread? {
        generation += 1
        if (surface != null) runCatching { appTarget?.onSurface(null, 0, 0) }
        runCatching { cropPass?.release() }
        cropPass = null
        activeCropRect = null
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        runCatching { surface?.release() }
        codec = null
        surface = null
        return drain.also { drain = null }
    }

    private fun drainLoop(c: MediaCodec, gen: Int, requested: VideoLevel, configuration: AvcEncoders.Configuration) {
        val info = MediaCodec.BufferInfo()
        var configSent = false
        var actual = requested
        var epoch = 0L
        fun publish(csd: ByteArray) {
            synchronized(lock) {
                if (!current(c, gen)) return
                codecEpoch++
                epoch = codecEpoch
                level = actual
                sink?.onFormat(EncodedFormat(actual, bitrate, csd, epoch))
                configSent = true
            }
        }
        try {
            while (synchronized(lock) { current(c, gen) }) {
                val index = c.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
                if (!synchronized(lock) { current(c, gen) }) return
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
                        if (!synchronized(lock) { current(c, gen) }) return
                        if (data != null) {
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                if (!configSent) publish(data)
                            } else {
                                val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                                if (key && !configSent) AnnexB.parameterSets(data)?.let { publish(it) }
                                if (configSent) synchronized(lock) {
                                    if (current(c, gen)) deliver(data, info, dequeuedNs, epoch)
                                }
                            }
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val format = c.outputFormat
                        val refreshPeriod = runCatching {
                            if (format.containsKey(MediaFormat.KEY_INTRA_REFRESH_PERIOD))
                                format.getInteger(MediaFormat.KEY_INTRA_REFRESH_PERIOD) else null
                        }.getOrNull()
                        if (!AvcEncoders.intraRefreshConfirmed(configuration, refreshPeriod)) {
                            synchronized(lock) {
                                if (!current(c, gen)) return
                                intraRefreshDisabled = true
                            }
                            Log.w(TAG, "ENCODER_EXPERIMENT codec=${c.name} intraRefreshFallback=output_unconfirmed reportedPeriod=$refreshPeriod periodicIdrS=${configuration.iFrameIntervalS}")
                            throw IllegalStateException("intra refresh not confirmed by encoder output")
                        }
                        val width = format.getInteger(MediaFormat.KEY_WIDTH)
                        val height = format.getInteger(MediaFormat.KEY_HEIGHT)
                        val fps = runCatching {
                            if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) format.getInteger(MediaFormat.KEY_FRAME_RATE) else null
                        }.getOrNull()
                        actual = actualLevelFor(requested, width, height, fps ?: requested.fps)
                        Log.i(TAG, "encoder output ${actual.width}x${actual.height}@${actual.fps} rank=${actual.index} configuredFps=${configuration.fps} reportedFps=$fps intraRefreshPeriod=$refreshPeriod")
                        val csd = listOf("csd-0", "csd-1").mapNotNull { key ->
                            format.getByteBuffer(key)?.duplicate()?.let { b -> ByteArray(b.remaining()).also { b.get(it) } }
                        }.fold(ByteArray(0)) { bytes, next -> bytes + next }
                        configSent = false
                        if (csd.isNotEmpty()) publish(csd)
                    }
                }
            }
        } catch (e: IllegalStateException) {
            if (synchronized(lock) { current(c, gen) }) recover(c, gen, requested, e)
        }
    }

    private fun deliver(data: ByteArray, info: MediaCodec.BufferInfo, dequeuedNs: Long, epoch: Long) {
        val out = sink ?: return
        val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
        val capture = captureNsForPts(info.presentationTimeUs, dequeuedNs)
        if (capture == 0L) {
            val count = invalidPts.incrementAndGet()
            if (count == 1 || count % PTS_LOG_INTERVAL == 0) {
                Log.w(TAG, "encoder PTS outside ${MAX_PTS_AGE_NS}ns: ptsUs=${info.presentationTimeUs} dequeuedNs=$dequeuedNs invalidPts=$count")
            }
        }
        out.onFrame(EncodedFrame(seq.incrementAndGet(), key, capture, dequeuedNs, data, epoch))
    }

    /** One restart per failure, at most [MAX_RESTARTS] per stream; then the stream ends. */
    private fun recover(failed: MediaCodec, failedGen: Int, requested: VideoLevel, e: IllegalStateException) {
        Log.w(TAG, "encoder failed", e)
        Thread({
            val old: Thread?
            val reopenGeneration: Int
            synchronized(lock) {
                if (!current(failed, failedGen)) return@Thread
                old = closeLocked()
                reopenGeneration = generation
            }
            old?.join(JOIN_MS)
            synchronized(lock) {
                if (!running || generation != reopenGeneration || codec != null) return@Thread
                restarts += 1
                val failure = if (restarts > MAX_RESTARTS) "encoder failed ${restarts} times" else try {
                    openLocked(requested, bitrate)
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

    private fun current(c: MediaCodec, gen: Int): Boolean = running && identityMatches(codec, generation, c, gen)

    companion object {
        /** [captureApi] while the app's single-app projection feeds the encoder. */
        const val API_APP: String = "app-projection"
        private const val TAG = "GameBEXStream"
        private const val DEQUEUE_TIMEOUT_US = 100_000L
        private const val JOIN_MS = 1_000L
        private const val MAX_RESTARTS = 2
        private const val MAX_PTS_AGE_NS = 1_000_000_000L
        private const val PTS_LOG_INTERVAL = 120

        internal fun identityMatches(current: Any?, generation: Int, candidate: Any, candidateGeneration: Int): Boolean =
            current === candidate && generation == candidateGeneration

        internal fun actualLevelFor(requested: VideoLevel, width: Int, height: Int, fps: Int): VideoLevel {
            if (width <= 0 || height <= 0) throw IllegalStateException("encoder reported invalid dimensions")
            if (width == requested.width && height == requested.height) return requested
            val sizes = StreamCaps.LEVELS.filter { it.width == width && it.height == height }
            val actual = sizes.firstOrNull { it.index == requested.index }
                ?: sizes.firstOrNull { it.fps == requested.fps }
                ?: sizes.firstOrNull()
                ?: throw IllegalStateException("encoder reported unsupported format ${width}x${height}@$fps")
            return requested.copy(index = actual.index, width = width, height = height)
        }

        internal fun captureNsForPts(ptsUs: Long, dequeuedNs: Long): Long {
            if (ptsUs <= 0L || ptsUs > Long.MAX_VALUE / 1_000L) return 0L
            val ptsNs = ptsUs * 1_000L
            return if (ptsNs <= dequeuedNs && dequeuedNs - ptsNs <= MAX_PTS_AGE_NS) ptsNs else 0L
        }
    }
}
