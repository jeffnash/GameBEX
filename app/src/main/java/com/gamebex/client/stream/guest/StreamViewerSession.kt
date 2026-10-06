package com.gamebex.client.stream.guest

import android.util.Log
import android.view.Surface
import com.gamebex.client.BuildConfig
import com.gamebex.client.launch.GraphicsTuner
import com.gamebex.client.stream.StreamCaps
import com.gamebex.client.stream.StreamDebug
import com.gamebex.client.stream.StreamEndpoint
import com.gamebex.client.stream.proto.MediaFrame
import com.gamebex.client.stream.proto.PadState
import com.gamebex.client.together.TogetherCopy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

data class ViewerUi(
    val status: ViewerStatus,
    val hostName: String,
    val width: Int,
    val height: Int,
    val showStats: Boolean = false,
    val stats: List<String> = emptyList(),
    val weakWifi: Boolean = false,
    /** The Play together link to the host dropped; the stream may still heal or stop soon. */
    val linkLost: Boolean = false,
    /** Set when [status] is ENDED and there is something to say. */
    val message: String? = null,
    val waitingForVideo: Boolean = false,
)

enum class ViewerStatus { CONNECTING, WATCHING, RECONNECTING, ENDED }

/**
 * One guest viewing session: media client, decoder, audio, input and stats,
 * wired together. Outlives the Activity's Surface (a new one is attached after
 * the screen turns back on) but not the stream.
 */
class StreamViewerSession(
    private val endpoint: StreamEndpoint,
    private val loopback: Boolean = false,
    private val onLeave: () -> Unit = {},
    /** Watching-side Stream flags: raw in-app value or null when unset. */
    private val guestFlag: (String) -> String? = { null },
    /** Precomputed stats line for this phone's non-default flags, if any. */
    private val activeFlagsLine: String? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val runLabel = if (BuildConfig.DEBUG) StreamDebug.prop("run_label").orEmpty() else ""
    val stats = LatencyStats(
        measureWarmupSeconds = StreamDebug.int("measure_warmup_s", 10, 0..600),
        measureSeconds = StreamDebug.int("measure_seconds", 120, 1..3600),
        runLabel = runLabel,
        runEnabled = runLabel.isNotBlank(),
        deferredRunStart = true,
    )
    private val clock = ClockSync()
    private val attachId = com.gamebex.client.stream.StreamToken.attachId()
    private val jitter = PcmJitterBuffer(onCounter = { name, delta -> stats.count(name, delta) })
    private val audioOptions = AudioPlaybackOptions.sample(BuildConfig.DEBUG, StreamDebug::prop, flags = guestFlag)
    private val audioDrift = AudioDrift()
    private val indexedAudio = IndexedAudioBuffer(onCounter = { name, delta -> stats.count(name, delta) })
    private val audioRenderer = if (audioOptions.regulator) FractionalAudioRenderer(
        indexedAudio, drift = audioDrift, plc = audioOptions.plc,
        count = { name, delta -> stats.count(name, delta) },
    ) else null
    private val audioRecovery = AudioOutputRecovery()
    private var lastAudioMapping: AudioRenderInfo? = null
    private var lastAudioOutputFrame = 0L
    private var lastAudioOutputNs = 0L
    private var lastAudioTimestampValid = false
    private var audioRenderWriteSamples = 0L
    private var audioRenderWriteMaxNs = 0L
    private val audioRenderWriteBuckets = LongArray(12)
    private val mapper = PadMapper()
    private val touchPadMapper = PadMapper()
    private val inputLock = Any()
    private val lock = Any()
    private var decoder: VideoDecoder? = null
    private var videoConfig: DecoderRequest? = null
    private val latestOutput = (((if (BuildConfig.DEBUG) StreamDebug.prop("latest_output") else null)
        ?: guestFlag("latest_output")) == "1")
    private val codecDiag = StreamDebug.flag("codec_diag")
    private val decoderOverride = if (BuildConfig.DEBUG) StreamDebug.prop("decoder") else null
    private val vendorDiscovery = java.util.concurrent.atomic.AtomicBoolean(false)
    private var width = endpoint.width
    private var height = endpoint.height
    private var ticker: Job? = null
    @Volatile private var ended = false
    private val weakWifiPolicy = WeakWifiPolicy()

    private val _ui = MutableStateFlow(ViewerUi(ViewerStatus.CONNECTING, endpoint.hostName, endpoint.width, endpoint.height))
    val ui: StateFlow<ViewerUi> = _ui.asStateFlow()

    private val avEvidence = if (StreamAvEvidence.isEnabled(BuildConfig.DEBUG) { StreamDebug.prop("av_evidence") }) {
        runCatching {
            StreamAvEvidence(
                directory = File(GraphicsTuner.sharedDir(), "shared/stream-av-evidence/sample-${System.currentTimeMillis()}-${System.nanoTime()}"),
                onDumped = { result ->
                    result.onSuccess { dump ->
                        Log.i(TAG, "av evidence stopped=${dump.reason} video=${dump.videoFrames} audio=${dump.audioBlocks} budget=${dump.budgetBytes}/${StreamAvEvidence.MAX_BYTES} files=${dump.files.joinToString { it.absolutePath }}")
                    }.onFailure { error -> Log.w(TAG, "av evidence dump failed", error) }
                },
            )
        }.onFailure { error -> Log.w(TAG, "av evidence unavailable", error) }.getOrNull()
    } else null

    private val player = PcmPlayer(jitter, options = audioOptions, source = audioRenderer,
        drift = audioDrift, count = ::countAudio,
        onOutputMapping = { info, first, outputNs, timestampValid ->
            synchronized(lock) {
                lastAudioMapping = info
                lastAudioOutputFrame = first
                lastAudioOutputNs = outputNs
                lastAudioTimestampValid = timestampValid
            }
        }, onPcmPlayed = avEvidence?.let { evidence ->
        { played: StreamPcmPlayed ->
            runCatching {
                val estimate = if (played.seq != null && played.captureNs > 0L) clock.estimateHost(played.captureNs) else null
                evidence.recordAudio(played, estimate?.guestNs, estimate != null, clock.rttNs)
            }
            Unit
        }
    }) { captureNs, outputNs ->
        if (captureNs > 0L) clock.estimateHost(captureNs, outputNs)?.let { estimate ->
            stats.addClockSample(LatencyStats.Metric.AUDIO, outputNs - estimate.guestNs, estimate.errorBoundNs)
        }
    }

    /** Created in [start], off the main thread (opening a socket there is a StrictMode violation). */
    @Volatile private var input: InputSender? = null

    private val listener = object : StreamClient.Listener {
        override fun onState(state: StreamClient.State) {
            if (ended) return
            when (state) {
                StreamClient.State.Connecting -> synchronized(lock) {
                    weakWifiPolicy.connectionReset()
                    _ui.update { it.copy(status = ViewerStatus.CONNECTING, weakWifi = false) }
                }
                is StreamClient.State.Connected -> {
                    clock.reset()
                    stats.rttNs = -1L
                    // The host's welcome has the real size; the endpoint's is what stream_ready promised.
                    synchronized(lock) {
                        weakWifiPolicy.connectionReset()
                        videoConfig = null
                        width = state.welcome.width
                        height = state.welcome.height
                    }
                    _ui.update { it.copy(status = ViewerStatus.WATCHING, width = state.welcome.width, height = state.welcome.height, waitingForVideo = true, weakWifi = false) }
                    if (endpoint.audio && !ended) {
                        audioRecovery.connected(audioSourceEpoch(), player.isActive)?.let(::startAudioOutput)
                    }
                }
                StreamClient.State.Reconnecting -> {
                    audioRecovery.reconnecting()
                    stats.reconnects += 1
                    clock.reset()
                    stats.rttNs = -1L
                    jitter.reset()
                    indexedAudio.reset()
                    audioDrift.reset()
                    player.requestFlush()
                    releaseTouchInput()
                    synchronized(lock) {
                        videoConfig = null
                        weakWifiPolicy.connectionReset()
                        _ui.update { it.copy(status = ViewerStatus.RECONNECTING, waitingForVideo = true, weakWifi = false) }
                    }
                }
                is StreamClient.State.Ended -> finish(messageFor(state.reason))
            }
        }

        override fun onConfig(csd: ByteArray) = onConfig(csd, 0L)

        override fun onConfig(csd: ByteArray, codecEpoch: Long) {
            runCatching { avEvidence?.configure(csd) }
            synchronized(lock) {
                val level = StreamCaps.LEVELS.getOrNull(stats.level)?.takeIf { it.width == width && it.height == height }
                val config = DecoderRequest(csd, width, height, codecEpoch, level?.fps ?: 60)
                videoConfig = config
                weakWifiPolicy.codecEpoch(codecEpoch)
                _ui.update { it.copy(waitingForVideo = true, weakWifi = false) }
                decoder?.configure(config.csd, config.width, config.height, config.codecEpoch)
            }
        }

        override fun onVideo(frame: MediaFrame.Video, receivedNs: Long) = onVideo(frame, receivedNs, 0L)

        override fun onVideo(frame: MediaFrame.Video, receivedNs: Long, codecEpoch: Long) {
            avEvidence?.let { evidence ->
                runCatching {
                    evidence.recordVideo(
                        seq = frame.seq,
                        key = frame.key,
                        data = frame.data,
                        captureHostNs = frame.captureNs,
                        sourceGuestNs = if (frame.captureNs > 0L) clock.estimateHost(frame.captureNs, receivedNs)?.guestNs else null,
                        receivedNs = receivedNs,
                        clockRttNs = clock.rttNs,
                    )
                }
            }
            stats.frameReceived()
            if (frame.captureNs > 0L && frame.encodedNs >= frame.captureNs) {
                stats.add(LatencyStats.Metric.ENCODE, frame.encodedNs - frame.captureNs)
            }
            val d = synchronized(lock) { decoder }
            if (d == null) {
                stats.count("dec_no_surface")
                stats.frameDropped()
            } else d.offer(frame, receivedNs, codecEpoch)
        }

        override fun onAudio(frame: MediaFrame.Audio) {
            onAudioChunk(frame.seq, frame.captureNs, frame.pcm, frame.frames)
        }

        override fun onInfo(info: MediaFrame.Info) {
            synchronized(lock) {
                width = info.width
                height = info.height
                stats.bitrate = info.bitrate
                stats.level = info.level
                val maxBitrate = StreamCaps.LEVELS.getOrNull(info.level)?.maxBitrate ?: 0
                val weak = weakWifiPolicy.quality(info.level, info.bitrate, maxBitrate, System.nanoTime())
                _ui.update { it.copy(width = info.width, height = info.height, weakWifi = weak) }
            }
        }

        override fun onPong(t0: Long, t1: Long, t2: Long, t3: Long) {
            val accepted = clock.add(t0, t1, t2, t3)
            stats.rttNs = clock.rttNs
            synchronized(lock) {
                val weak = weakWifiPolicy.roundTrip(t3 - t0 - (t2 - t1), accepted && clock.ready, t3)
                _ui.update { it.copy(weakWifi = weak) }
            }
            val bounds = clock.interval(t3)
            Log.i(TAG, "CLOCK transport=tcp t0=$t0 t1=$t1 t2=$t2 t3=$t3 accepted=$accepted ready=${clock.ready} offset_lo_ns=${bounds?.lowerNs ?: "-"} offset_hi_ns=${bounds?.upperNs ?: "-"} error_bound_ns=${clock.errorBoundNs} drift_ppm=${clock.driftPpm} resets=${clock.resets}")
        }

        override fun onDelivery(report: com.gamebex.client.stream.proto.DeliveryWire.Report) {
            synchronized(lock) {
                if (ended) return
                weakWifiPolicy.delivery(report)
                _ui.update { it.copy(weakWifi = weakWifiPolicy.tick(System.nanoTime())) }
            }
        }
    }

    private val client = StreamClient(endpoint, listener,
        decoderPending = { synchronized(lock) { decoder?.pendingCount ?: 0 } },
        attachId = attachId,
        onCounter = { name, delta -> stats.count(name, delta) },
    )

    fun start() {
        scope.launch {
            val sender = InputSender(endpoint.host, endpoint.inputPort, endpoint.token, { sampleNs, appliedNs, ackNs ->
                if (!ended && appliedNs > 0L) clock.estimateHost(appliedNs, ackNs)?.let { estimate ->
                    stats.addClockSample(LatencyStats.Metric.INPUT, estimate.guestNs - sampleNs, estimate.errorBoundNs)
                }
            }, onAudio = { seq, captureNs, pcm -> onAudioChunk(seq, captureNs, pcm) }, attachId = attachId,
                cs6 = (((if (BuildConfig.DEBUG) StreamDebug.prop("input_cs6") else null) ?: guestFlag("input_cs6")) == "1"),
                onRtt = { sentNs, ackNs -> if (!ended) stats.add(LatencyStats.Metric.INPUT_RTT, ackNs - sentNs) })
            synchronized(inputLock) {
                input = sender
                sender.update(mergePadStates(mapper.state(), touchPadMapper.state()))
            }
            sender.start()
            if (ended) sender.close()
        }
        if (endpoint.audio) player.muted = loopback
        client.start()
        ticker = scope.launch {
            var n = 0
            while (isActive) {
                delay(TICK_MS)
                n += 1
                val now = System.nanoTime()
                stats.tick(now)
                synchronized(lock) {
                    _ui.update { it.copy(weakWifi = weakWifiPolicy.tick(now)) }
                }
                emitRun(now)
                runCatching { avEvidence?.tick() }
                if (synchronized(inputLock) { mapper.leaveRequested(System.nanoTime()) }) {
                    leave()
                    return@launch
                }
                if (n % TICKS_PER_SECOND == 0) refreshStats(n % (TICKS_PER_SECOND * 5) == 0)
            }
        }
    }

    fun attachSurface(surface: Surface) {
        val old = synchronized(lock) {
            val previous = decoder
            lateinit var d: VideoDecoder
            d = VideoDecoder(surface, stats, clock, onVideoReady = {
                synchronized(lock) {
                    if (!ended && decoder === d && videoConfig != null && d.hasRenderedCurrentConfig) {
                        stats.beginMeasurement(System.nanoTime())
                        _ui.update { ui -> if (ui.status == ViewerStatus.WATCHING) ui.copy(waitingForVideo = false) else ui }
                    }
                }
            }, latestOutput = latestOutput, codecDiag = codecDiag, decoderOverride = decoderOverride, vendorDiscovery = vendorDiscovery) { client.requestKeyframe() }
            decoder = d
            _ui.update { it.copy(waitingForVideo = true) }
            videoConfig?.let { d.configure(it.csd, it.width, it.height, it.codecEpoch) }
            previous
        }
        old?.close()
    }

    fun detachSurface() {
        releaseTouchInput()
        val d = synchronized(lock) { decoder.also { decoder = null } }
        d?.close()
    }

    /** A controller key; true when it belongs to player 2 (the Activity then consumes it). */
    fun onKey(keyCode: Int, down: Boolean, atNs: Long): Boolean {
        val (mine, leaveRequested) = synchronized(inputLock) {
            if (ended) return false
            val mine = mapper.onKey(keyCode, down, atNs)
            if (mine) updateInput(atNs)
            mine to mapper.leaveRequested(atNs)
        }
        if (leaveRequested) leave()
        return mine
    }

    fun onAxes(values: FloatArray, atNs: Long) = synchronized(inputLock) {
        if (ended || values.size < 8) return@synchronized
        mapper.onAxes(values[0], values[1], values[2], values[3], values[4], values[5], values[6], values[7])
        updateInput(atNs)
    }

    /** Guest controls, not touches on the video. Their Start/Select never trigger the physical leave combo. */
    fun onTouchKey(keyCode: Int, down: Boolean, atNs: Long) = synchronized(inputLock) {
        if (ended || (down && !touchInputReady())) return@synchronized
        if (touchPadMapper.onKey(keyCode, down, atNs)) updateInput(atNs)
    }

    fun onTouchAxes(leftX: Float, leftY: Float, atNs: Long) = synchronized(inputLock) {
        if (ended || !touchInputReady()) return@synchronized
        touchPadMapper.onAxes(leftX, leftY, 0f, 0f, 0f, 0f, 0f, 0f)
        updateInput(atNs)
    }

    /** One complete touch-controller state (both sticks, triggers, hat, buttons). */
    fun onTouchState(state: PadState, atNs: Long) = synchronized(inputLock) {
        if (ended || !touchInputReady()) return@synchronized
        touchPadMapper.setTouchState(state)
        updateInput(atNs)
    }

    private fun touchInputReady(): Boolean =
        _ui.value.status == ViewerStatus.WATCHING && !_ui.value.waitingForVideo

    /** All mapper changes and sender updates share this lock, including lifecycle releases. */
    private fun updateInput(atNs: Long = System.nanoTime()) {
        input?.update(mergePadStates(mapper.state(), touchPadMapper.state()), atNs)
    }

    /** Pad hidden, gesture cancelled or video surface detached: keep the physical controller held. */
    fun releaseTouchInput() = synchronized(inputLock) {
        touchPadMapper.reset()
        updateInput()
    }

    /** Focus lost or session ended: player 2 lets go of both sources. */
    fun releaseInput() = synchronized(inputLock) {
        mapper.reset()
        touchPadMapper.reset()
        input?.cancel()
    }

    fun setLinkLost(lost: Boolean) {
        _ui.update { if (it.linkLost == lost) it else it.copy(linkLost = lost) }
    }

    private fun onAudioChunk(seq: Int, captureNs: Long, pcm: ByteArray, frames: Int = pcm.size / 4) {
        if (ended || !audioRecovery.accepting) return
        if (frames !in 1..512 || pcm.size != frames * 4) {
            stats.count("audio_invalid_packet")
            return
        }
        val now = System.nanoTime()
        val renderer = audioRenderer
        if (renderer != null) renderer.push(seq, captureNs, frames, pcm, now)
        else {
            if (frames != StreamCaps.AUDIO_CHUNK_FRAMES) {
                stats.count("audio_invalid_packet")
                return
            }
            audioDrift.observePacket(seq, frames, now)
            jitter.push(seq, captureNs, pcm)
        }
    }

    private fun audioSourceEpoch(): Long = audioRenderer?.epoch() ?: jitter.generation

    private fun startAudioOutput(attempt: AudioOutputRecovery.Attempt) {
        scope.launch {
            if (ended || !audioRecovery.claim(attempt)) return@launch
            player.stop()
            if (ended || !audioRecovery.isCurrent(attempt)) return@launch
            audioRenderer?.resetOutput()
            val started = player.start { reason -> onAudioFailure(attempt, reason) }
            audioRecovery.started(attempt)
            if (!started) onAudioFailure(attempt, "previous guest output has not stopped")
            else if (!ended && audioRecovery.isCurrent(attempt)) _ui.update { it.copy(message = null) }
        }
    }

    private fun onAudioFailure(attempt: AudioOutputRecovery.Attempt, reason: String) {
        if (ended) return
        when (val failure = audioRecovery.failed(attempt, audioSourceEpoch())) {
            AudioOutputRecovery.Failure.Ignored -> Unit
            is AudioOutputRecovery.Failure.Retry -> {
                _ui.update { it.copy(message = "Guest audio failed ($reason); retrying guest output.") }
                startAudioOutput(failure.attempt)
            }
            AudioOutputRecovery.Failure.Disabled -> {
                _ui.update { it.copy(message = "Guest audio is unavailable ($reason). Host sound is unchanged.") }
            }
        }
    }

    private fun countAudio(name: String, delta: Long) {
        if (delta <= 0L) return
        stats.count(name, delta)
        if (name == "audio_render_to_first_write_ns") synchronized(lock) {
            audioRenderWriteSamples += 1L
            audioRenderWriteMaxNs = maxOf(audioRenderWriteMaxNs, delta)
            val bucket = (delta / 1_000_000L).coerceAtMost(11L).toInt()
            audioRenderWriteBuckets[bucket] += 1L
        }
    }

    private fun refreshAudioCounters() {
        stats.audioBufferMs = if (audioRenderer != null) indexedAudio.snapshot().spanFrames * 1_000 / StreamCaps.AUDIO_RATE else jitter.depthMs
        stats.audioUnderruns = audioRenderer?.gapEpisodes ?: jitter.underruns
        stats.trackUnderruns = player.trackUnderruns
    }

    fun toggleStats() {
        _ui.update { it.copy(showStats = !it.showStats) }
        refreshStats(false)
    }

    /** The guest leaves: host gets BYE and a neutral pad, and everything here stops. */
    fun leave() {
        if (ended) return
        finish(null)
    }

    private fun finish(message: String?) {
        if (ended) {
            if (message != null) _ui.update { it.copy(message = message) }
            return
        }
        synchronized(inputLock) {
            ended = true
            audioRecovery.close()
            player.close()
            releaseInput()
        }
        runCatching { avEvidence?.close() }
        // Both closes write to the network (three neutral input packets, then BYE), and leave() and
        // onDestroy() reach here on the UI thread, where that throws NetworkOnMainThreadException.
        val sender = input
        Thread({
            runCatching { sender?.close() }
            runCatching { client.close() }
            runCatching { player.stop() }
        }, "gbx-viewer-close").apply {
            isDaemon = true
            start()
        }
        synchronized(lock) { refreshAudioCounters() }
        detachSurface()
        val now = System.nanoTime()
        stats.finish(now)
        emitRun(now)
        Log.i(TAG, "${stats.statsLine(now)} audio_origin=${endpoint.audioTimestampOrigin}")
        _ui.update { it.copy(status = ViewerStatus.ENDED, message = message, weakWifi = false) }
        ticker?.cancel()
        scope.cancel()
        // With a message the viewer stays up to show it; the Back button closes it.
        if (message == null) onLeave()
    }

    private fun refreshStats(log: Boolean) {
        synchronized(lock) { refreshAudioCounters() }
        val now = System.nanoTime()
        if (_ui.value.showStats) _ui.update { it.copy(stats = StreamStatsText.lines(stats.snapshot(now), endpoint.audioTimestampOrigin, activeFlagsLine)) }
        if (log) {
            Log.i(TAG, "${stats.statsLine(now)} audio_origin=${endpoint.audioTimestampOrigin}")
            val mapping = synchronized(lock) {
                "audio_source_frame=${lastAudioMapping?.firstSourceFrame ?: -1} audio_fraction=${lastAudioMapping?.fraction ?: 0.0} " +
                    "audio_source_frames=${lastAudioMapping?.sourceFrames ?: 0.0} audio_output_frame=$lastAudioOutputFrame " +
                    "audio_output_ns=$lastAudioOutputNs audio_timestamp_valid=$lastAudioTimestampValid"
            }
            val renderWrite = synchronized(lock) {
                "audio_render_to_first_write_samples=$audioRenderWriteSamples audio_render_to_first_write_max_ns=$audioRenderWriteMaxNs " +
                    "audio_render_to_first_write_hist_ms=${audioRenderWriteBuckets.joinToString(",")}"
            }
            Log.i(TAG, "AUDIO drift_ppm=${audioDrift.ppm} drift_valid=${audioDrift.valid} " +
                "ratio=${audioRenderer?.control?.ratio ?: 1.0} total_target_frames=${audioRenderer?.control?.targetFrames ?: 0} " +
                "track_queued_frames=${player.queuedOutputFrames} lead_frames=${audioOptions.leadFrames} " +
                "jit=${audioOptions.jit} regulator=${audioOptions.regulator} plc=${audioOptions.plc} " +
                "audio_source=${if (endpoint.audioTimestampOrigin == "bridge-arrival") "RSOUND_INGRESS" else "LOOPBACK_ESTIMATE"} $mapping $renderWrite")
        }
    }

    private fun emitRun(nowNs: Long) {
        val lines = stats.runLines(nowNs, endpoint.audioTimestampOrigin)
        if (lines.isEmpty()) return
        runCatching {
            val directory = File(GraphicsTuner.sharedDir(), "shared/stream-run-evidence")
            check(directory.isDirectory || directory.mkdirs())
            val label = runLabel.replace(Regex("[^A-Za-z0-9_.:-]"), "_").take(96)
            val file = File(directory, "run-$label-${System.currentTimeMillis()}-$nowNs.txt")
            file.writeText(lines.joinToString("\n", postfix = "\n"))
            Log.i(TAG, "RUNFILE path=${file.absolutePath} label=$label")
        }.onFailure { error -> Log.w(TAG, "RUNFILE unavailable", error) }
        StreamStatsText.runLogLines(lines).forEach { Log.i(TAG, it) }
    }

    private fun messageFor(reason: StreamClient.EndReason): String? = when (reason) {
        StreamClient.EndReason.LEFT -> null
        StreamClient.EndReason.HOST_STOPPED -> TogetherCopy.streamHostStoppedSharing
        StreamClient.EndReason.REPLACED -> TogetherCopy.streamReplaced
        StreamClient.EndReason.BAD_TOKEN -> TogetherCopy.streamRefused
        StreamClient.EndReason.BAD_VERSION -> TogetherCopy.streamUpdateBoth
        StreamClient.EndReason.LOST -> TogetherCopy.streamLost(endpoint.hostName)
    }

    private companion object {
        const val TAG = "GameBEXStream"
        const val TICK_MS = 100L
        const val TICKS_PER_SECOND = 10
    }
}
