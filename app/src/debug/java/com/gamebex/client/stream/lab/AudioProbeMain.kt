package com.gamebex.client.stream.lab

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import com.gamebex.client.stream.StreamCaps
import com.gamebex.client.stream.shell.PcmChunk
import com.gamebex.client.stream.shell.PlaybackAudioSource
import com.gamebex.client.stream.shell.ShellContext
import com.gamebex.client.stream.shell.ShellRuntime
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.system.exitProcess

/**
 * Debug builds only, emulator only: checks that playback capture works as
 * the shell uid.
 *
 *   CLASSPATH=$(pm path com.gamebex.client | cut -d: -f2) app_process /system/bin \
 *     com.gamebex.client.stream.lab.AudioProbeMain --seconds 5 --tone
 *
 * `--tone` plays a 1 kHz tone (USAGE_GAME) from this process while capturing
 * (Android 14+, where AudioTrack.Builder.setContext exists). Prints one JSON line.
 */
object AudioProbeMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val seconds = args.indexOf("--seconds").takeIf { it >= 0 }?.let { args.getOrNull(it + 1)?.toLongOrNull() } ?: 5L
        ShellRuntime.ensureMainLooper()
        val ctx = ShellContext.get()
        if (ctx == null) {
            println("{\"ok\":false,\"error\":\"no system context\"}")
            exitProcess(1)
        }
        val tone = if ("--tone" in args) startTone(ctx) else null
        var chunks = 0
        var sumSquares = 0.0
        var samples = 0L
        var peak = 0
        val source = PlaybackAudioSource(ctx)
        val ok = source.start { chunk: PcmChunk ->
            synchronized(this) {
                chunks += 1
                var i = 0
                while (i + 1 < chunk.data.size) {
                    val s = (chunk.data[i].toInt() and 0xFF) or (chunk.data[i + 1].toInt() shl 8)
                    sumSquares += s.toDouble() * s
                    samples += 1
                    if (kotlin.math.abs(s) > peak) peak = kotlin.math.abs(s)
                    i += 2
                }
            }
        }
        Thread.sleep(seconds * 1_000L)
        source.stop()
        tone?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        val rms = if (samples == 0L) 0 else sqrt(sumSquares / samples).toInt()
        println(
            "{\"ok\":$ok,\"available\":${PlaybackAudioSource.available()},\"tone\":${tone != null},\"chunks\":$chunks," +
                "\"expectedChunks\":${seconds * 100},\"rms\":$rms,\"peak\":$peak}",
        )
        exitProcess(if (ok && chunks > 0) 0 else 1)
    }

    private fun startTone(ctx: ShellContext): AudioTrack? {
        if (Build.VERSION.SDK_INT < 34) return null
        return runCatching {
            val rate = StreamCaps.AUDIO_RATE
            val frames = rate
            val pcm = ShortArray(frames * 2)
            for (i in 0 until frames) {
                val v = (sin(2 * PI * 1_000.0 * i / rate) * 8_000).toInt().toShort()
                pcm[2 * i] = v
                pcm[2 * i + 1] = v
            }
            val track = AudioTrack.Builder()
                .setContext(ctx)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).build())
                .setAudioFormat(
                    AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .build()
            track.write(pcm, 0, pcm.size)
            track.setLoopPoints(0, frames, -1)
            track.play()
            track
        }.getOrNull()
    }
}
