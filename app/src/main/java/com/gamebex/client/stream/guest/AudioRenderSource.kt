package com.gamebex.client.stream.guest

import com.gamebex.client.stream.StreamCaps

internal fun audioFrameNs(frames: Long): Long = frames * 1_000_000_000L / StreamCaps.AUDIO_RATE

data class AudioPlaybackOptions(
    val leadFrames: Int = 768,
    val jit: Boolean = false,
    val regulator: Boolean = false,
    val plc: Boolean = false,
    val outputFrames: Int = 128,
) {
    init {
        require(leadFrames in listOf(256, 384, 512, 768))
        require(outputFrames == 64 || outputFrames == 128)
    }

    companion object {
        fun sample(debug: Boolean, prop: (String) -> String?): AudioPlaybackOptions {
            if (!debug) return AudioPlaybackOptions()
            val lead = prop("audio_lead_frames")?.toIntOrNull()?.takeIf { it in listOf(256, 384, 512, 768) } ?: 768
            val jit = prop("audio_jit") == "1"
            val regulator = prop("audio_regulator") == "1"
            val plc = prop("audio_plc") == "1"
            val quantum = prop("audio_output_frames")?.toIntOrNull()?.takeIf { it == 64 || it == 128 } ?: 128
            return AudioPlaybackOptions(lead, jit, regulator, plc, quantum)
        }
    }
}

data class AudioRenderSpan(
    val outputOffset: Int,
    val outputFrames: Int,
    val seq: Int?,
    val captureNs: Long,
    val firstSourceFrame: Long,
    val fraction: Double = 0.0,
    val sourceFrames: Double = outputFrames.toDouble(),
)

data class AudioRenderInfo(
    val epoch: Long,
    val firstSourceFrame: Long,
    val fraction: Double,
    val outputFrames: Int,
    val priming: Boolean,
    val sourceFrames: Double = if (priming) 0.0 else outputFrames.toDouble(),
    val spans: List<AudioRenderSpan> = emptyList(),
)

interface AudioRenderSource {
    fun epoch(): Long
    fun render(frames: Int, queuedOutputFrames: Int, nowNs: Long, out: ByteArray): AudioRenderInfo
}

internal class JitterAudioRenderSource(private val buffer: PcmJitterBuffer) : AudioRenderSource {
    override fun epoch(): Long = buffer.generation

    override fun render(frames: Int, queuedOutputFrames: Int, nowNs: Long, out: ByteArray): AudioRenderInfo {
        require(frames == StreamCaps.AUDIO_CHUNK_FRAMES && out.size >= frames * 4)
        val epoch = epoch()
        val chunk = buffer.pull()
        if (chunk == null) out.fill(0, 0, frames * 4) else chunk.pcm.copyInto(out, 0, 0, frames * 4)
        return AudioRenderInfo(epoch, 0, 0.0, frames, chunk == null,
            spans = listOf(AudioRenderSpan(0, frames, chunk?.seq, chunk?.captureNs ?: 0L, 0)))
    }
}
