package com.gamebex.client.stream.guest

import android.util.Log
import android.view.Surface
import com.gamebex.client.stream.StreamCaps
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

    private val player = PcmPlayer(jitter) { captureNs, outputNs ->
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
                    _ui.update { it.copy(status = ViewerStatus.WATCHING, width = state.welcome.width, height = state.welcome.height) }
                }
                StreamClient.State.Reconnecting -> {
                    stats.reconnects += 1
                    jitter.reset()
                    _ui.update { it.copy(status = ViewerStatus.RECONNECTING) }
                }
                is StreamClient.State.Ended -> finish(messageFor(state.reason))
            }
        }

        override fun onConfig(csd: ByteArray) {
            val d = synchronized(lock) {
                this@StreamViewerSession.csd = csd
                decoder
            }
            d?.configure(csd, width, height)
        }

        override fun onVideo(frame: MediaFrame.Video, receivedNs: Long) {
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
            input = sender
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
                if (mapper.leaveRequested(System.nanoTime())) {
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
            val d = VideoDecoder(surface, stats, clock) { client.requestKeyframe() }
            decoder = d
            old to csd
        }
        old?.close()
        c?.let { decoder?.configure(it, width, height) }
    }

    fun detachSurface() {
        val d = synchronized(lock) { decoder.also { decoder = null } }
        d?.close()
    }

    /** A controller key; true when it belongs to player 2 (the Activity then consumes it). */
    fun onKey(keyCode: Int, down: Boolean, atNs: Long): Boolean {
        if (ended) return false
        val mine = mapper.onKey(keyCode, down, atNs)
        if (mine) input?.update(mapper.state(), atNs)
        if (mapper.leaveRequested(atNs)) leave()
        return mine
    }

    fun onAxes(values: FloatArray, atNs: Long) {
        if (ended || values.size < 8) return
        mapper.onAxes(values[0], values[1], values[2], values[3], values[4], values[5], values[6], values[7])
        input?.update(mapper.state(), atNs)
    }

    /** Focus lost or controller gone: player 2 lets go of everything. */
    fun releaseInput() {
        mapper.reset()
        input?.update(mapper.state())
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
        ended = true
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
        if (_ui.value.showStats) _ui.update { it.copy(stats = StreamStatsText.lines(stats.snapshot(now))) }
        if (log) Log.i(TAG, stats.statsLine(now))
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
