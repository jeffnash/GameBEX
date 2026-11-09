package com.gamebex.client.stream.guest

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import com.gamebex.client.BuildConfig
import com.gamebex.client.stream.StreamCaps
import com.gamebex.client.stream.StreamDebug
import com.gamebex.client.stream.proto.AnnexB
import com.gamebex.client.stream.proto.MediaFrame
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

class VideoDecoder(
    private val surface: Surface,
    private val stats: LatencyStats,
    private val clock: ClockSync,
    private val onVideoReady: () -> Unit = {},
    private val latestOutput: Boolean = StreamDebug.flag("latest_output"),
    private val codecDiag: Boolean = StreamDebug.flag("codec_diag"),
    private val decoderOverride: String? = if (BuildConfig.DEBUG) StreamDebug.prop("decoder") else null,
    private val vendorDiscovery: AtomicBoolean = AtomicBoolean(false),
    private val formatKeys: Map<String, Int> = decoderFormatKeys(),
    private val onNeedKeyframe: () -> Unit,
) {
    private data class Released(val frame: DecoderFrames.Submitted, val releasedNs: Long, val generation: Long)

    private val thread = HandlerThread("gbx-video-decode", android.os.Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    private val handler = Handler(thread.looper)
    private val lock = Any()
    private var codec: MediaCodec? = null
    private var activeRequest: DecoderRequest? = null
    private var readyRequest: DecoderRequest? = null
    private var generation = 0L
    private val frames = DecoderFrames { reason, count ->
        stats.count(reason, count.toLong())
        stats.frameDropped(count)
    }
    private val freeInputs = ArrayDeque<Int>()
    private val released = LinkedHashMap<Long, Released>()
    private var returningOutputs = false
    private val decoderInfos = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter {
            !it.isEncoder && it.supportedTypes.any { type -> type.equals(MIME, ignoreCase = true) }
        }
    }.getOrDefault(emptyList())
    private val readyOutputs = ReadyOutputs<MediaCodec>(
        latestOutput,
        post = { action -> handler.post { action() }; Unit },
        isCurrent = { output -> synchronized(lock) { isCurrent(output.codec, output.generation) && frames.contains(output.pts) } },
        finish = ::finishOutput,
    )
    @Volatile private var closed = false
    private val configureRetry = DecoderConfigureRetry(
        post = { action, delayMs -> handler.postDelayed(action, delayMs); Unit },
        cancel = { action -> handler.removeCallbacks(action) },
        attempt = ::recreate,
        onNeedKeyframe = onNeedKeyframe,
        lock = lock,
    )

    @Volatile var decoderName: String? = null
        private set

    val pendingCount: Int get() = synchronized(lock) { frames.pendingCount }
    val hasRenderedCurrentConfig: Boolean get() = synchronized(lock) { !closed && readyRequest != null && readyRequest === frames.config }

    fun configure(csdIn: ByteArray, w: Int, h: Int, codecEpoch: Long = 0) {
        require(w > 0 && h > 0 && codecEpoch >= 0)
        val fixed = if (StreamCaps.SPS_FIXUP) SpsFixup.fixCsd(csdIn) else csdIn
        synchronized(lock) {
            if (closed) return
            val level = StreamCaps.LEVELS.getOrNull(stats.level)?.takeIf { it.width == w && it.height == h }
            val request = frames.configure(fixed, w, h, codecEpoch, level?.fps ?: 60)
            configureRetry.configure(request)
        }
    }

    fun offer(frame: MediaFrame.Video, receivedNs: Long, codecEpoch: Long = 0) {
        var keyRequest: DecoderRequest? = null
        val accepted = synchronized(lock) {
            if (closed) {
                stats.count("dec_closed")
                stats.frameDropped()
                return
            }
            frames.offer(if (frame.key && StreamCaps.SPS_FIXUP) withFixedSps(frame) else frame, receivedNs, codecEpoch).also {
                if (it == DecoderFrames.Offer.NEED_KEY) keyRequest = frames.config
            }
        }
        if (accepted == DecoderFrames.Offer.NEED_KEY) configureRetry.requestKeyframe(keyRequest)
        if (accepted == DecoderFrames.Offer.ACCEPTED) handler.post { feed() }
    }

    fun tick(nowNs: Long) {
        stats.tick(nowNs)
    }

    private fun withFixedSps(frame: MediaFrame.Video): MediaFrame.Video {
        if (AnnexB.split(frame.data).none { it.type == AnnexB.NAL_SPS }) return frame
        return runCatching {
            MediaFrame.Video(frame.key, frame.seq, frame.captureNs, frame.encodedNs, frame.sendNs, SpsFixup.fixCsd(frame.data))
        }.getOrDefault(frame)
    }

    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            configureRetry.close()
            frames.reset("dec_close")
            if (released.isNotEmpty()) stats.count("dec_framework_callback_missing", released.size.toLong())
            released.clear()
        }
        handler.post {
            releaseCodec("dec_close")
            thread.quitSafely()
        }
    }

    private fun recreate(request: DecoderRequest): Boolean {
        if (synchronized(lock) { closed || frames.config !== request }) return false
        releaseCodec("dec_recreate_abandon")
        if (synchronized(lock) { closed || frames.config !== request }) return false
        val fps = request.fps
        val candidates = candidates(request.width, request.height, fps)
        if (candidates.isEmpty()) {
            stats.count("dec_no_candidate")
            Log.w(TAG, "no AVC decoder candidate ${request.width}x${request.height}@$fps")
        }
        for (candidate in candidates) {
            for (attempt in 0..2) {
                if (synchronized(lock) { closed || frames.config !== request }) return false
                val decoder = try {
                    MediaCodec.createByCodecName(candidate.name)
                } catch (e: Exception) {
                    Log.w(TAG, "no decoder ${candidate.name}", e)
                    break
                }
                val ownedGeneration = synchronized(lock) { ++generation }
                try {
                    decoder.setCallback(callbackFor(ownedGeneration, request), handler)
                    val requestedFormat = format(request.csd, request.width, request.height, attempt)
                    if (codecDiag) Log.i(TAG, "CODEC_FORMAT stage=requested codec=${candidate.name} format=$requestedFormat")
                    decoder.configure(requestedFormat, surface, null, 0)
                    decoder.setOnFrameRenderedListener({ c, ptsUs, _ -> onRendered(c, ownedGeneration, request, ptsUs) }, handler)
                    val installed = synchronized(lock) {
                        if (closed || frames.config !== request) false else {
                            codec = decoder
                            activeRequest = request
                            freeInputs.clear()
                            true
                        }
                    }
                    if (!installed) {
                        runCatching { decoder.release() }
                        return false
                    }
                    decoder.start()
                    decoderName = decoder.name
                    Log.i(TAG, "decoder ${decoder.name} ${request.width}x${request.height}@$fps attempt $attempt hardware=${candidate.hardware} joint=${candidate.eligible} fallback=${!candidate.eligible || !candidate.hardware}")
                    if (!candidate.eligible) stats.count("dec_capability_fallback")
                    if (!candidate.hardware) stats.count("dec_software_fallback")
                    discoverVendor(decoder)
                    return synchronized(lock) {
                        if (closed || !ownsDecoder(decoder, codec, request, frames.config)) false else {
                            frames.requireKey()
                            true
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "decoder ${candidate.name} refused attempt $attempt", e)
                    synchronized(lock) {
                        if (codec === decoder) {
                            codec = null
                            activeRequest = null
                            freeInputs.clear()
                        }
                    }
                    runCatching { decoder.release() }
                }
            }
        }
        stats.count("dec_configure_failed")
        synchronized(lock) {
            if (!closed && frames.config === request) frames.reset("dec_configure_failed")
        }
        return false
    }

    private fun releaseCodec(reason: String) {
        returningOutputs = true
        readyOutputs.clear()
        returningOutputs = false
        val c = synchronized(lock) {
            freeInputs.clear()
            frames.abandonSubmitted(reason)
            if (released.isNotEmpty()) stats.count("dec_framework_callback_missing", released.size.toLong())
            released.clear()
            activeRequest = null
            readyRequest = null
            codec.also { codec = null }
        } ?: return
        runCatching { c.stop() }
        runCatching { c.release() }
    }

    private fun feed() {
        while (true) {
            var keyRequest: DecoderRequest? = null
            val failed = synchronized(lock) {
                val c = codec ?: return
                val request = activeRequest ?: return
                if (closed || frames.config !== request || freeInputs.isEmpty() || frames.pendingCount == 0) return
                val index = freeInputs.removeFirst()
                val input = frames.submit() ?: return
                try {
                    val buffer = c.getInputBuffer(index)
                    if (buffer == null || input.frame.data.size > buffer.capacity()) {
                        frames.terminal(input.pts, if (buffer == null) "dec_input_null" else "dec_input_oversize")
                        frames.requireKey()
                        keyRequest = request
                        c.queueInputBuffer(index, 0, 0, input.pts, 0)
                    } else {
                        buffer.clear()
                        buffer.put(input.frame.data)
                        c.queueInputBuffer(index, 0, input.frame.data.size, input.pts, 0)
                    }
                    null
                } catch (e: Exception) {
                    frames.terminal(input.pts, "dec_input_fail")
                    frames.requireKey()
                    keyRequest = request
                    Log.w(TAG, "queueInputBuffer failed", e)
                    c to request
                }
            }
            if (keyRequest != null) configureRetry.requestKeyframe(keyRequest)
            if (failed != null) {
                val (c, request) = failed
                handler.post {
                    val restart = synchronized(lock) {
                        if (!ownsDecoder(c, codec, request, frames.config) || closed) false else {
                            frames.reset("dec_recreate_abandon")
                            true
                        }
                    }
                    if (restart) configureRetry.restart(request)
                }
                return
            }
        }
    }

    private fun isCurrent(c: MediaCodec, capturedGeneration: Long): Boolean =
        !closed && c === codec && capturedGeneration == generation && activeRequest === frames.config

    private fun callbackFor(capturedGeneration: Long, request: DecoderRequest) = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            synchronized(lock) {
                if (!isCurrent(codec, capturedGeneration) || frames.config !== request) return
                freeInputs.addLast(index)
            }
            feed()
        }

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            if (!synchronized(lock) { isCurrent(codec, capturedGeneration) && frames.config === request }) return
            readyOutputs.offer(ReadyOutput(codec, capturedGeneration, index, info.presentationTimeUs, info.size, info.flags, System.nanoTime()))
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            if (!synchronized(lock) { isCurrent(codec, capturedGeneration) && frames.config === request }) return
            Log.w(TAG, "decoder error", e)
            handler.post {
                val restart = synchronized(lock) {
                    if (!isCurrent(codec, capturedGeneration) || frames.config !== request) false else {
                        frames.reset("dec_recreate_abandon")
                        true
                    }
                }
                if (restart) configureRetry.restart(request)
            }
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            if (!synchronized(lock) { isCurrent(codec, capturedGeneration) && frames.config === request }) return
            Log.i(TAG, "decoder output format $format")
        }
    }

    private fun finishOutput(output: ReadyOutput<MediaCodec>, render: Boolean) {
        synchronized(lock) {
            val live = output.codec === codec && output.generation == generation
            if (!live || (!returningOutputs && !isCurrent(output.codec, output.generation))) return
            val now = System.nanoTime()
            val visible = render && output.picture && frames.contains(output.pts) && !returningOutputs
            try {
                if (visible) output.codec.releaseOutputBuffer(output.index, now)
                else output.codec.releaseOutputBuffer(output.index, false)
            } catch (e: Exception) {
                frames.terminal(output.pts, "dec_release_fail")
                return
            }
            val input = frames.terminal(output.pts, if (visible) null else "dec_decoded_superseded") ?: return
            if (!visible) return
            stats.count("dec_rendered")
            stats.add(LatencyStats.Metric.CALLBACK_RELEASE, now - output.callbackNs)
            released[output.pts] = Released(input, now, output.generation)
            if (released.size > MAX_TIMES) {
                released.remove(released.keys.first())
                stats.count("dec_framework_callback_missing")
            }
            readyRequest = frames.config
            recordOutputStats(stats, clock, input.frame.captureNs, input.frame.sendNs, input.receivedNs, now)
        }
        onVideoReady()
    }

    private fun onRendered(c: MediaCodec, capturedGeneration: Long, request: DecoderRequest, ptsUs: Long) {
        synchronized(lock) {
            if (!isCurrent(c, capturedGeneration) || frames.config !== request) return
            val output = released.remove(ptsUs) ?: return
            if (output.generation == capturedGeneration) stats.count("dec_framework_progress")
        }
    }

    private fun format(csd: ByteArray, w: Int, h: Int, attempt: Int): MediaFormat =
        MediaFormat.createVideoFormat(MIME, w, h).apply {
            val nals = AnnexB.split(csd)
            val sps = nals.firstOrNull { it.type == AnnexB.NAL_SPS }
            val pps = nals.firstOrNull { it.type == AnnexB.NAL_PPS }
            if (sps != null && pps != null) {
                setByteBuffer("csd-0", ByteBuffer.wrap(AnnexB.join(listOf(csd.copyOfRange(sps.start, sps.end)))))
                setByteBuffer("csd-1", ByteBuffer.wrap(AnnexB.join(listOf(csd.copyOfRange(pps.start, pps.end)))))
            } else {
                setByteBuffer("csd-0", ByteBuffer.wrap(csd))
            }
            if (attempt <= 1) {
                if (Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                setInteger(MediaFormat.KEY_PRIORITY, 0)
            }
            if (attempt == 0) setInteger(KEY_VDEC_LOW_LATENCY, 1)
            formatKeys.forEach { (key, value) -> setInteger(key, value) }
        }

    private fun candidates(w: Int, h: Int, fps: Int): List<DecoderCandidate> {
        val listed = decoderInfos.map { info ->
            val hardware = if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated else {
                !info.name.lowercase().let { it.startsWith("omx.google.") || it.startsWith("c2.android.") || it.contains(".sw.") }
            }
            val supported = runCatching {
                val caps = info.getCapabilitiesForType(MIME).videoCapabilities ?: return@runCatching false
                supportsDecoderSize(w, h, fps, caps.widthAlignment, caps.heightAlignment) {
                    caps.areSizeAndRateSupported(w, h, fps.toDouble())
                }
            }.getOrDefault(false)
            if (codecDiag) Log.i(TAG, "CODEC_CAPS name=${info.name} encoder=false hardware=$hardware size=${w}x$h@$fps joint=$supported")
            DecoderCandidate(info.name, hardware, supported)
        }
        return rankDecoderCandidates(listed, decoderOverride)
    }

    private fun discoverVendor(c: MediaCodec) {
        if (!codecDiag || !vendorDiscovery.compareAndSet(false, true)) return
        if (Build.VERSION.SDK_INT >= 31) runCatching {
            c.supportedVendorParameters.sorted().forEach { name ->
                Log.i(TAG, "CODEC_VENDOR codec=${c.name} parameter=$name type=${c.getParameterDescriptor(name)?.type}")
            }
        }.onFailure { Log.i(TAG, "CODEC_VENDOR_UNAVAILABLE codec=${c.name} reason=${it.javaClass.simpleName}") }
        runCatching { Log.i(TAG, "CODEC_FORMAT stage=configured codec=${c.name} input=${c.inputFormat} output=${c.outputFormat}") }
    }

    companion object {
        const val MIME: String = MediaFormat.MIMETYPE_VIDEO_AVC
        const val KEY_VDEC_LOW_LATENCY: String = "vdec-lowlatency"
        private const val TAG = "GameBEXStream"
        private const val MAX_TIMES = 120

        internal fun recordOutputStats(stats: LatencyStats, clock: ClockSync, captureNs: Long, sendNs: Long, receivedNs: Long, releasedNs: Long) {
            stats.frameRendered(releasedNs)
            stats.add(LatencyStats.Metric.DECODE, releasedNs - receivedNs)
            if (captureNs != 0L) clock.estimateHost(captureNs, releasedNs)?.let {
                stats.addClockSample(LatencyStats.Metric.VIDEO, releasedNs - it.guestNs, it.errorBoundNs)
            }
            clock.estimateHost(sendNs, releasedNs)?.let {
                stats.addClockSample(LatencyStats.Metric.NETWORK, receivedNs - it.guestNs, it.errorBoundNs)
            }
        }

        fun available(): Boolean = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .any { !it.isEncoder && it.supportedTypes.any { t -> t.equals(MIME, ignoreCase = true) } }
    }
}

internal fun decoderFormatKeys(
    debug: Boolean = BuildConfig.DEBUG,
    prop: () -> String? = { StreamDebug.prop("vdec_keys") },
): Map<String, Int> = if (debug) com.gamebex.client.stream.shell.AvcEncoders.vendorKeys(prop()) else emptyMap()

internal class DecoderRequest(csd: ByteArray, val width: Int, val height: Int, val codecEpoch: Long, val fps: Int = 60) {
    val csd = csd.copyOf()
}

internal class DecoderConfigureRetry(
    private val post: (Runnable, Long) -> Unit,
    private val cancel: (Runnable) -> Unit,
    private val attempt: (DecoderRequest) -> Boolean,
    private val onNeedKeyframe: () -> Unit,
    private val lock: Any = Any(),
) {
    private var request: DecoderRequest? = null
    private var generation = 0L
    private var retries = 0
    private var configured = false
    private var closed = false
    private var scheduled: Runnable? = null

    fun configure(request: DecoderRequest) {
        synchronized(lock) {
            if (closed) return
            invalidate()
            this.request = request
            retries = 0
            schedule(0)
        }
    }

    fun restart(request: DecoderRequest) {
        synchronized(lock) {
            if (closed || this.request !== request || !configured) return
            invalidate()
            retries = 0
            schedule(0)
        }
    }

    fun requestKeyframe(request: DecoderRequest?) {
        synchronized(lock) {
            if (!closed && configured && request != null && this.request === request) onNeedKeyframe()
        }
    }

    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            invalidate()
            request = null
        }
    }

    private fun invalidate() {
        generation++
        configured = false
        scheduled?.let(cancel)
        scheduled = null
    }

    private fun schedule(delayMs: Long) {
        val capturedGeneration = generation
        lateinit var action: Runnable
        action = Runnable {
            val current = synchronized(lock) {
                if (closed || capturedGeneration != generation || scheduled !== action) null else {
                    scheduled = null
                    request
                }
            } ?: return@Runnable
            val success = attempt(current)
            val notify = synchronized(lock) {
                if (closed || capturedGeneration != generation || request !== current) false else if (success) {
                    configured = true
                    true
                } else {
                    RETRY_DELAYS_MS.getOrNull(retries)?.let { delay ->
                        retries++
                        schedule(delay)
                    }
                    false
                }
            }
            if (notify) requestKeyframe(current)
        }
        scheduled = action
        post(action, delayMs)
    }

    private companion object {
        val RETRY_DELAYS_MS = longArrayOf(100, 300, 1000)
    }
}

internal class DecoderFrames(
    private val maxPending: Int = 8,
    private val maxTimes: Int = 120,
    private val drop: (String, Int) -> Unit,
) {
    enum class Offer { ACCEPTED, DROPPED, NEED_KEY }
    data class Envelope(val frame: MediaFrame.Video, val receivedNs: Long, val codecEpoch: Long)
    data class Submitted(val pts: Long, val frame: MediaFrame.Video, val receivedNs: Long, val codecEpoch: Long)
    var config: DecoderRequest? = null
        private set
    private val pending = ArrayDeque<Envelope>()
    private val times = LinkedHashMap<Long, Submitted>()
    private var nextPtsUs = 1L
    private var waitingForKey = true
    val pendingCount: Int get() = pending.size

    init {
        require(maxPending > 0 && maxTimes > 0)
    }

    fun configure(csd: ByteArray, width: Int, height: Int, codecEpoch: Long, fps: Int = 60): DecoderRequest {
        reset("dec_config_reset")
        return DecoderRequest(csd, width, height, codecEpoch, fps).also { config = it }
    }

    fun offer(frame: MediaFrame.Video, receivedNs: Long, codecEpoch: Long): Offer {
        if (config == null || codecEpoch != config?.codecEpoch) {
            drop("dec_epoch_stale", 1)
            return Offer.DROPPED
        }
        if (waitingForKey && !frame.key) {
            drop("dec_await_key", 1)
            return Offer.DROPPED
        }
        if (pending.size >= maxPending) {
            discardPending("dec_overflow")
            waitingForKey = !frame.key
            if (!frame.key) {
                drop("dec_await_key", 1)
                return Offer.NEED_KEY
            }
        }
        waitingForKey = false
        pending.addLast(Envelope(frame, receivedNs, codecEpoch))
        return Offer.ACCEPTED
    }

    fun submit(): Submitted? {
        val envelope = pending.pollFirst() ?: return null
        check(nextPtsUs > 0)
        val input = Submitted(nextPtsUs++, envelope.frame, envelope.receivedNs, envelope.codecEpoch)
        times[input.pts] = input
        if (times.size > maxTimes) terminal(times.keys.first(), "dec_time_evict")
        return input
    }

    fun contains(pts: Long): Boolean = times.containsKey(pts)

    fun terminal(pts: Long, reason: String?): Submitted? = times.remove(pts)?.also {
        if (reason != null) drop(reason, 1)
    }

    fun requireKey() {
        waitingForKey = true
        discardPending("dec_await_key")
    }

    fun abandonSubmitted(reason: String) {
        if (times.isNotEmpty()) drop(reason, times.size)
        times.clear()
    }

    fun reset(reason: String) {
        discardPending(reason)
        abandonSubmitted(reason)
        waitingForKey = true
    }

    private fun discardPending(reason: String) {
        if (pending.isNotEmpty()) drop(reason, pending.size)
        pending.clear()
    }
}

internal data class DecoderCandidate(val name: String, val hardware: Boolean, val eligible: Boolean)

internal fun supportsDecoderSize(width: Int, height: Int, fps: Int, widthAlignment: Int, heightAlignment: Int, joint: () -> Boolean): Boolean =
    width > 0 && height > 0 && fps > 0 && widthAlignment > 0 && heightAlignment > 0 &&
        width % widthAlignment == 0 && height % heightAlignment == 0 && joint()

internal fun rankDecoderCandidates(candidates: List<DecoderCandidate>, override: String?): List<DecoderCandidate> {
    val unique = candidates.distinctBy { it.name }
    if (override != null) return listOf(unique.firstOrNull { it.name == override } ?: DecoderCandidate(override, false, false))
    return unique.sortedWith(compareByDescending<DecoderCandidate> { it.eligible }.thenByDescending { it.hardware })
}

internal fun ownsDecoder(callbackCodec: Any, liveCodec: Any?, request: DecoderRequest, current: DecoderRequest?): Boolean =
    callbackCodec === liveCodec && request === current
