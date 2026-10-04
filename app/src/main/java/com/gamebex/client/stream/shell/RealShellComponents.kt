package com.gamebex.client.stream.shell

import android.content.Context
import android.os.Build
import android.os.Process
import com.gamebex.client.stream.StreamCaps
import com.gamebex.client.stream.StreamConfig
import com.gamebex.client.stream.StreamDebug
import com.gamebex.client.stream.StreamProbe

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

    override fun openPad(): VirtualPad? = VirtualPads.open()

    override fun videoSource(): VideoSource = AvcScreenSource()

    override fun audioSource(): AudioSource? =
        if (Build.VERSION.SDK_INT >= StreamCaps.MIN_HOST_SDK) ShellContext.get()?.let { PlaybackAudioSource(it) } else null

    override fun audioSource(config: StreamConfig): AudioSource? =
        if (config.rsound) rsoundBridge.acquire() else audioSource()

    override fun mediaChannel(config: StreamConfig): MediaChannel =
        MediaServer(config, logger = { android.util.Log.i("GameBEXStream", it) })
}
