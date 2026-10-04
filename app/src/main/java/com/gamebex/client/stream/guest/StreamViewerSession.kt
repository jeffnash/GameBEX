package com.gamebex.client.stream.guest

import android.util.Log
import android.view.Surface
import com.gamebex.client.BuildConfig
import com.gamebex.client.launch.GraphicsTuner
import com.gamebex.client.stream.StreamCaps
import com.gamebex.client.stream.StreamDebug
import com.gamebex.client.stream.StreamEndpoint
import com.gamebex.client.stream.proto.MediaFrame
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
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val stats = LatencyStats()
    private val clock = ClockSync()
    private val jitter = PcmJitterBuffer()
    private val mapper = PadMapper()
    private val touchPadMapper = PadMapper()
    private val inputLock = Any()
    private val lock = Any()
    private var decoder: VideoDecoder? = null
    private var csd: ByteArray? = null
    private var width = endpoint.width
    private var height = endpoint.height
    private var ticker: Job? = null
    @Volatile private var ended = false
    private var startLevel = -1

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

    private val player = PcmPlayer(jitter, onPcmPlayed = avEvidence?.let { evidence ->
        { played: StreamPcmPlayed ->
            runCatching {
                val ready = clock.ready
                val captureGuestNs = if (ready && played.seq != null) clock.hostToGuest(played.captureNs) else null
                evidence.recordAudio(played, captureGuestNs, ready, clock.rttNs)
            }
            Unit
        }
    }) { captureNs, outputNs ->
        if (clock.ready) stats.add(LatencyStats.Metric.AUDIO, outputNs - clock.hostToGuest(captureNs))
    }

    /** Created in [start], off the main thread (opening a socket there is a StrictMode violation). */
    @Volatile private var input: InputSender? = null

    private val listener = object : StreamClient.Listener {
        override fun onState(state: StreamClient.State) {
            when (state) {
                StreamClient.State.Connecting -> _ui.update { it.copy(status = ViewerStatus.CONNECTING) }
                is StreamClient.State.Connected -> {
                    // The host's welcome has the real size; the endpoint's is what stream_ready promised.
                    width = state.welcome.width
                    height = state.welcome.height
                    _ui.update { it.copy(status = ViewerStatus.WATCHING, width = state.welcome.width, height = state.welcome.height, waitingForVideo = true) }
                }
                StreamClient.State.Reconnecting -> {
                    stats.reconnects += 1
                    jitter.reset()
                    releaseTouchInput()
                    _ui.update { it.copy(status = ViewerStatus.RECONNECTING, waitingForVideo = true) }
                }
                is StreamClient.State.Ended -> finish(messageFor(state.reason))
            }
        }

        override fun onConfig(csd: ByteArray) {
            runCatching { avEvidence?.configure(csd) }
            val d = synchronized(lock) {
                this@StreamViewerSession.csd = csd
                decoder
            }
            d?.configure(csd, width, height)
        }

        override fun onVideo(frame: MediaFrame.Video, receivedNs: Long) {
            avEvidence?.let { evidence ->
                runCatching {
                    evidence.recordVideo(
                        seq = frame.seq,
                        key = frame.key,
                        data = frame.data,
                        captureHostNs = frame.captureNs,
                        sourceGuestNs = if (clock.ready) clock.hostToGuest(frame.captureNs) else null,
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
            if (d == null) stats.frameDropped() else d.offer(frame, receivedNs)
        }

        override fun onAudio(frame: MediaFrame.Audio) {
            onAudioChunk(frame.seq, frame.captureNs, frame.pcm)
        }

        override fun onInfo(info: MediaFrame.Info) {
            width = info.width
            height = info.height
            stats.bitrate = info.bitrate
            stats.level = info.level
            // "Weak" means the host had to lower quality below where this stream started, not that it
            // started below level 0 (the phones start at level 1, early check V-2).
            if (startLevel < 0) startLevel = info.level
            val startMax = StreamCaps.LEVELS.getOrNull(startLevel)?.maxBitrate ?: StreamCaps.LEVELS[0].maxBitrate
            val weak = info.level > startLevel || info.bitrate < startMax * WEAK_BITRATE_RATIO
            _ui.update { it.copy(width = info.width, height = info.height, weakWifi = weak) }
        }

        override fun onPong(t0: Long, t1: Long, t2: Long, t3: Long) {
            clock.add(t0, t1, t2, t3)
            stats.rttNs = clock.rttNs
        }
    }

    private val client = StreamClient(endpoint, listener, { report() })

    fun start() {
        scope.launch {
            val sender = InputSender(endpoint.host, endpoint.inputPort, endpoint.token, { sampleNs, appliedNs, ackNs ->
                stats.add(LatencyStats.Metric.INPUT_RTT, ackNs - sampleNs)
                if (clock.ready) stats.add(LatencyStats.Metric.INPUT, clock.hostToGuest(appliedNs) - sampleNs)
            }, onAudio = { seq, captureNs, pcm -> onAudioChunk(seq, captureNs, pcm) })
            synchronized(inputLock) {
                input = sender
                sender.update(mergePadStates(mapper.state(), touchPadMapper.state()))
            }
            sender.start()
            if (ended) sender.close()
        }
        client.start()
        if (endpoint.audio) {
            player.muted = loopback
            player.start()
        }
        ticker = scope.launch {
            var n = 0
            while (isActive) {
                delay(TICK_MS)
                n += 1
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
        val (old, c) = synchronized(lock) {
            val old = decoder
            val d = VideoDecoder(surface, stats, clock, onVideoReady = {
                _ui.update { ui -> if (ui.status == ViewerStatus.WATCHING) ui.copy(waitingForVideo = false) else ui }
            }) { client.requestKeyframe() }
            decoder = d
            old to csd
        }
        old?.close()
        c?.let { decoder?.configure(it, width, height) }
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
        updateInput()
    }

    fun setLinkLost(lost: Boolean) {
        _ui.update { if (it.linkLost == lost) it else it.copy(linkLost = lost) }
    }

    private fun onAudioChunk(seq: Int, captureNs: Long, pcm: ByteArray) {
        jitter.push(seq, captureNs, pcm)
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
            releaseInput()
        }
        runCatching { avEvidence?.close() }
        // Both closes write to the network (three neutral input packets, then BYE), and leave() and
        // onDestroy() reach here on the UI thread, where that throws NetworkOnMainThreadException.
        val sender = input
        Thread({
            runCatching { sender?.close() }
            runCatching { client.close() }
        }, "gbx-viewer-close").apply {
            isDaemon = true
            start()
        }
        runCatching { player.stop() }
        detachSurface()
        Log.i(TAG, stats.statsLine(System.nanoTime()))
        _ui.update { it.copy(status = ViewerStatus.ENDED, message = message) }
        ticker?.cancel()
        scope.cancel()
        // With a message the viewer stays up to show it; the Back button closes it.
        if (message == null) onLeave()
    }

    private fun refreshStats(log: Boolean) {
        stats.audioBufferMs = jitter.depthMs
        stats.audioUnderruns = jitter.underruns
        stats.trackUnderruns = player.trackUnderruns
        val now = System.nanoTime()
        if (_ui.value.showStats) _ui.update { it.copy(stats = StreamStatsText.lines(stats.snapshot(now), endpoint.audioTimestampOrigin)) }
        if (log) Log.i(TAG, "${stats.statsLine(now)} audio_origin=${endpoint.audioTimestampOrigin}")
    }

    private fun report(): MediaFrame.Report {
        val p95 = stats.percentileMs(LatencyStats.Metric.VIDEO, 0.95)
        return MediaFrame.Report(
            received = stats.framesReceived.toInt(),
            decoded = stats.framesRendered.toInt(),
            rendered = stats.framesRendered.toInt(),
            videoP95Us = if (p95 == null) 0 else (p95 * 1_000).toInt(),
            audioUnderruns = jitter.underruns,
        )
    }

    private fun messageFor(reason: StreamClient.EndReason): String? = when (reason) {
        StreamClient.EndReason.LEFT -> null
        StreamClient.EndReason.HOST_STOPPED -> TogetherCopy.streamEnded(endpoint.hostName)
        StreamClient.EndReason.REPLACED -> TogetherCopy.streamReplaced
        StreamClient.EndReason.BAD_TOKEN -> TogetherCopy.streamRefused
        StreamClient.EndReason.BAD_VERSION -> TogetherCopy.streamUpdateBoth
        StreamClient.EndReason.LOST -> TogetherCopy.streamLost(endpoint.hostName)
    }

    private companion object {
        const val TAG = "GameBEXStream"
        const val TICK_MS = 100L
        const val TICKS_PER_SECOND = 10

        /** Below this share of the starting level's bitrate the host has cut quality (the "weak Wi-Fi" notice). */
        const val WEAK_BITRATE_RATIO = 0.8
    }
}
