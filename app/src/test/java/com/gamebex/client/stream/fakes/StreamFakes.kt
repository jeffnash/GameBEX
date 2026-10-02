package com.gamebex.client.stream.fakes

import com.gamebex.client.data.Emu
import com.gamebex.client.data.local.GameEntity
import com.gamebex.client.stream.P2Prep
import com.gamebex.client.stream.StreamCaps
import com.gamebex.client.stream.StreamConfig
import com.gamebex.client.stream.StreamHost
import com.gamebex.client.stream.StreamProbe
import com.gamebex.client.stream.StreamStarted
import com.gamebex.client.stream.StreamStatus
import com.gamebex.client.stream.StreamPlayer2
import com.gamebex.client.stream.VideoLevel
import com.gamebex.client.stream.proto.PadState
import com.gamebex.client.stream.shell.AudioSink
import com.gamebex.client.stream.shell.AudioSource
import com.gamebex.client.stream.shell.EncodedFrame
import com.gamebex.client.stream.shell.MediaChannel
import com.gamebex.client.stream.shell.MediaStats
import com.gamebex.client.stream.shell.PcmChunk
import com.gamebex.client.stream.shell.ShellComponents
import com.gamebex.client.stream.shell.VideoControl
import com.gamebex.client.stream.shell.VideoSink
import com.gamebex.client.stream.shell.VideoSource
import com.gamebex.client.stream.shell.VirtualPad
import java.util.Collections

/** Host-app side: records every call; results are settable. */
class FakeStreamHost(
    var probeValue: StreamProbe = StreamProbe(shellUid = 2000, sdk = 35, uhid = true, hidTool = true, displayApi = true, audioApi = true, avcEncoder = true),
    var startValue: StreamStarted = StreamStarted(ok = true, mediaPort = 55441, inputPort = 55442, width = 1600, height = 720, audio = true, padBackend = "uhid"),
    var statusValue: StreamStatus? = StreamStatus(running = true, guestConnected = true, fps = 60, bitrate = 8_000_000),
) : StreamHost {
    val configs: MutableList<StreamConfig> = Collections.synchronizedList(mutableListOf())
    @Volatile var probes = 0
    @Volatile var stops = 0
    @Volatile var statusCalls = 0

    override suspend fun probe(): StreamProbe {
        probes += 1
        return probeValue
    }

    override suspend fun start(config: StreamConfig): StreamStarted {
        configs += config
        return startValue
    }

    override suspend fun status(): StreamStatus? {
        statusCalls += 1
        return statusValue
    }

    override suspend fun stop() {
        stops += 1
    }
}

class FakeStreamPlayer2(var prep: P2Prep = P2Prep.Written) : StreamPlayer2 {
    val prepared = Collections.synchronizedList(mutableListOf<Pair<Long, Emu>>())
    @Volatile var restores = 0

    override suspend fun prepare(game: GameEntity, emu: Emu): P2Prep {
        prepared += game.id to emu
        return prep
    }

    override suspend fun restore(): Boolean {
        restores += 1
        return true
    }
}

class FakeVirtualPad(override val backend: String = "fake") : VirtualPad {
    val writes: MutableList<PadState> = Collections.synchronizedList(mutableListOf())
    @Volatile var closed = false
    @Volatile var alive = true

    override fun write(state: PadState): Boolean {
        if (closed || !alive) return false
        writes += state
        return true
    }

    override fun close() {
        closed = true
    }
}

class FakeVideoSource(initial: VideoLevel) : VideoSource {
    @Volatile override var level: VideoLevel = initial
        private set
    @Volatile var sink: VideoSink? = null
    @Volatile var currentBitrate = 0
    @Volatile var keyframeRequests = 0
    @Volatile var levelChanges = 0
    @Volatile var stopped = false

    override fun start(level: VideoLevel, bitrate: Int, sink: VideoSink) {
        this.level = level
        currentBitrate = bitrate
        this.sink = sink
    }

    override fun requestKeyframe() {
        keyframeRequests += 1
    }

    override fun setBitrate(bps: Int) {
        currentBitrate = bps
    }

    override fun changeLevel(level: VideoLevel, bitrate: Int) {
        this.level = level
        currentBitrate = bitrate
        levelChanges += 1
    }

    override fun stop() {
        stopped = true
    }

    /** Pushes one frame as the encoder thread would. */
    fun emit(seq: Int, key: Boolean, size: Int = 1_000, captureNs: Long = System.nanoTime()) {
        sink?.onFrame(EncodedFrame(seq, key, captureNs, System.nanoTime(), ByteArray(size) { (it and 0x7F).toByte() }))
    }
}

class FakeAudioSource(private val works: Boolean = true) : AudioSource {
    @Volatile var sink: AudioSink? = null
    @Volatile var stopped = false

    override fun start(sink: AudioSink): Boolean {
        if (!works) return false
        this.sink = sink
        return true
    }

    override fun stop() {
        stopped = true
    }

    fun emit(seq: Int) {
        sink?.onPcm(PcmChunk(seq, System.nanoTime(), 480, ByteArray(480 * 4)))
    }
}

class FakeMediaChannel(private val boundPort: Int = 55441) : MediaChannel {
    val configs: MutableList<ByteArray> = Collections.synchronizedList(mutableListOf())
    val frames: MutableList<EncodedFrame> = Collections.synchronizedList(mutableListOf())
    val pcm: MutableList<PcmChunk> = Collections.synchronizedList(mutableListOf())
    val failures: MutableList<String> = Collections.synchronizedList(mutableListOf())
    @Volatile var control: VideoControl? = null
    @Volatile var opened = false
    @Volatile var started = false
    @Volatile var closed = false
    @Volatile var audioOn = false
    @Volatile var failOpen = false
    @Volatile var statsValue = MediaStats(false, 0, 0, 0, 0, 0, 0, 0L)

    override val port: Int get() = boundPort

    override fun open(preferredPort: Int) {
        if (failOpen) throw com.gamebex.client.stream.shell.StreamFailure(com.gamebex.client.stream.StreamError.NETWORK, "fake open failure")
        opened = true
    }

    override fun start(control: VideoControl, level: VideoLevel, bitrate: Int, audio: Boolean) {
        this.control = control
        started = true
        audioOn = audio
    }

    override fun stats(): MediaStats = statsValue

    override fun close() {
        closed = true
    }

    override fun onConfig(csd: ByteArray) {
        configs += csd
    }

    override fun onFrame(frame: EncodedFrame) {
        frames += frame
    }

    override fun onFailure(reason: String) {
        failures += reason
    }

    override fun onPcm(chunk: PcmChunk) {
        pcm += chunk
    }
}

class FakeShellComponents(
    val pad: FakeVirtualPad? = FakeVirtualPad(),
    val video: FakeVideoSource = FakeVideoSource(StreamCaps.LEVELS[0]),
    val audio: FakeAudioSource? = FakeAudioSource(),
    val media: FakeMediaChannel = FakeMediaChannel(),
    var probeValue: StreamProbe = StreamProbe(shellUid = 2000, sdk = 35, uhid = true, hidTool = true, displayApi = true, audioApi = true, avcEncoder = true),
) : ShellComponents {
    @Volatile var padOpens = 0

    override fun probe(): StreamProbe = probeValue

    override fun openPad(): VirtualPad? {
        padOpens += 1
        return pad
    }

    override fun videoSource(): VideoSource = video

    override fun audioSource(): AudioSource? = audio

    override fun mediaChannel(config: StreamConfig): MediaChannel = media
}
