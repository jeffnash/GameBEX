package com.gamebex.client.stream.shell

import android.content.Context
import android.os.Build
import android.os.Process
import com.gamebex.client.stream.StreamCaps
import com.gamebex.client.stream.StreamConfig
import com.gamebex.client.stream.StreamDebug
import com.gamebex.client.stream.StreamProbe
import com.gamebex.client.stream.p2.P2SlotMode

/**
 * The shell process's real parts: pad, capture, encoder and media
 * server, playback capture. [context] is the
 * Shizuku-provided package context; audio uses [ShellContext] instead, which
 * speaks as the shell package.
 */
class RealShellComponents(@Suppress("unused") private val context: Context?) : ShellComponents {
    @Volatile var onAudioIdle: (() -> Unit)? = null
    private val rsoundBridge = RSoundBridge(
        sourceFactory = { RSoundAudioSource(audioSource(), quickAckEnabled = StreamDebug.prop("rsound_quickack") != "0") },
        onIdle = { onAudioIdle?.invoke() },
        logger = { android.util.Log.i("GameBEXStream", it) },
        emulatorAlive = RetroArchActivityProbe()::isAlive,
    )

    val retainedAudio: Boolean get() = rsoundBridge.retained

    fun closeAudioBridge() = rsoundBridge.close()

    override fun probe(): StreamProbe = StreamProbe(
        shellUid = Process.myUid(),
        sdk = Build.VERSION.SDK_INT,
        uhid = VirtualPads.uhidAvailable(),
        hidTool = VirtualPads.hidToolAvailable(),
        displayApi = Build.VERSION.SDK_INT >= StreamCaps.MIN_HOST_SDK,
        audioApi = PlaybackAudioSource.available(),
        avcEncoder = runCatching { AvcEncoders.rank(AvcEncoders.list(), StreamCaps.LEVELS.last()).isNotEmpty() }.getOrDefault(false),
    )

    override fun openPad(): VirtualPad? =
        if (StreamDebug.flag("no_pad")) {
            android.util.Log.i("GameBEXStream", "pad: none (debug.gamebex.no_pad=1; input dropped)")
            NoPad
        } else {
            VirtualPads.open()
        }

    override fun openPad(config: StreamConfig): VirtualPad? {
        if (StreamDebug.flag("no_pad")) {
            android.util.Log.i("GameBEXStream", "pad: none (debug.gamebex.no_pad=1; input dropped)")
            return NoPad
        }
        // The adb prop wins on debug builds (lab override); unknown values stay off.
        val mode = P2SlotMode.fromWire(
            shellString("p2_slot_mode", config.p2SlotMode, P2SlotMode.entries.map { it.wire }.toSet()),
        )
        if (mode == P2SlotMode.OFF) return VirtualPads.open()
        android.util.Log.i("GameBEXStream", "pad: P2 slot workaround ${mode.wire}")
        return P2SlotSession.open(
            mode,
            timing = P2SlotSession.Timing(
                raUpTimeoutMs = shellInt("p2_slot_ra_up_ms", 60_000, 0..300_000).toLong(),
                registerWaitMs = shellInt("p2_slot_register_ms", 750, 0..10_000).toLong(),
                realPadTimeoutMs = shellInt("p2_slot_pad_wait_ms", 30_000, 0..300_000).toLong(),
                blipSettleMs = shellInt("p2_slot_blip_settle_ms", 3_000, 0..60_000).toLong(),
            ),
        )
    }

    override fun videoSource(): VideoSource = AvcScreenSource()
    override fun videoSource(config: StreamConfig): VideoSource = AvcScreenSource(config = config)

    override fun audioSource(): AudioSource? =
        if (Build.VERSION.SDK_INT >= StreamCaps.MIN_HOST_SDK) ShellContext.get()?.let { PlaybackAudioSource(it) } else null

    override fun audioSource(config: StreamConfig): AudioSource? =
        if (config.rsound) rsoundBridge.acquire() else audioSource()

    override fun mediaChannel(config: StreamConfig): MediaChannel =
        MediaServer(config, logger = { android.util.Log.i("GameBEXStream", it) })
}
