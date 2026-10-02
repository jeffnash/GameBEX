package com.gamebex.client.stream.shell

import android.content.Context
import android.os.Build
import android.os.Process
import com.gamebex.client.stream.StreamCaps
import com.gamebex.client.stream.StreamConfig
import com.gamebex.client.stream.StreamProbe

/**
 * The shell process's real parts: pad, capture, encoder and media
 * server, playback capture. [context] is the
 * Shizuku-provided package context; audio uses [ShellContext] instead, which
 * speaks as the shell package.
 */
class RealShellComponents(@Suppress("unused") private val context: Context?) : ShellComponents {
    override fun probe(): StreamProbe = StreamProbe(
        shellUid = Process.myUid(),
        sdk = Build.VERSION.SDK_INT,
        uhid = VirtualPads.uhidAvailable(),
        hidTool = VirtualPads.hidToolAvailable(),
        displayApi = ScreenMirror.available(),
        audioApi = PlaybackAudioSource.available(),
        avcEncoder = runCatching { AvcEncoders.rank(AvcEncoders.list(), StreamCaps.LEVELS.last()).isNotEmpty() }.getOrDefault(false),
    )

    override fun openPad(): VirtualPad? = VirtualPads.open()

    override fun videoSource(): VideoSource = AvcScreenSource()

    override fun audioSource(): AudioSource? =
        if (Build.VERSION.SDK_INT >= StreamCaps.MIN_HOST_SDK) ShellContext.get()?.let { PlaybackAudioSource(it) } else null

    override fun mediaChannel(config: StreamConfig): MediaChannel = MediaServer(config)
}
