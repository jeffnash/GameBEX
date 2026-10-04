package com.gamebex.client.stream.guest

import com.gamebex.client.stream.StreamCaps
import java.io.File

data class StreamPcmPlayed(
    val pcm: ByteArray,
    val bytes: Int,
    val seq: Int?,
    val captureNs: Long,
    val outputNs: Long,
    val timestampValid: Boolean,
    val timestampNs: Long,
    val timestampFramePosition: Long,
    val firstWrittenFrame: Long,
    val firstWrittenByte: Long,
    val observedNs: Long,
    val muted: Boolean,
)

internal class StreamAvEvidence(
    private val directory: File,
    private val nowNs: () -> Long = System::nanoTime,
    private val maxBytes: Long = MAX_BYTES,
    private val durationNs: Long = DURATION_NS,
    private val sampleRate: Int = StreamCaps.AUDIO_RATE,
    private val channels: Int = StreamCaps.AUDIO_CHANNELS,
    private val execute: (() -> Unit) -> Unit = { task ->
        Thread(task, "gbx-av-evidence").apply {
            isDaemon = true
            start()
        }
    },
    private val onDumped: (Result<DumpResult>) -> Unit = {},
) : AutoCloseable {
    enum class StopReason { DURATION, CAP, CLOSED }

    data class DumpResult(
        val directory: File,
        val reason: StopReason,
        val videoFrames: Int,
        val audioBlocks: Int,
        val payloadBytes: Long,
        val budgetBytes: Long,
    ) {
        val files: List<File> get() = FILE_NAMES.map { File(directory, it) }
    }

    private data class Video(
        val seq: Int,
        val key: Boolean,
        val data: ByteArray,
        val csd: ByteArray?,
        val captureHostNs: Long,
        val sourceGuestNs: Long,
        val receivedNs: Long,
        val clockRttNs: Long,
    )

    private data class Audio(val played: StreamPcmPlayed, val captureGuestNs: Long?, val clockRttNs: Long)

    private data class Sample(
        val startedNs: Long?,
        val stoppedNs: Long,
        val reason: StopReason,
        val video: MutableList<Video>,
        val audio: MutableList<Audio>,
        val payloadBytes: Long,
        val budgetBytes: Long,
    )

    private var csd: ByteArray? = null
    private var waitingForKey = true
    private var startedNs: Long? = null
    private var stopped = false
    private var budgetBytes = BASE_BYTES
    private var payloadBytes = 0L
    private var video = mutableListOf<Video>()
    private var audio = mutableListOf<Audio>()

    init {
        require(maxBytes >= BASE_BYTES && maxBytes <= MAX_BYTES)
        require(durationNs in 1..DURATION_NS)
        require(sampleRate > 0 && channels > 0)
    }

    @Synchronized
    fun configure(data: ByteArray) {
        if (stopped || data.isEmpty() || csd?.contentEquals(data) == true) return
        if (expired()) return
        if (!reserve(data.size.toLong() + RECORD_BYTES)) return
        csd = data.copyOf()
        payloadBytes += data.size
        waitingForKey = true
    }

    @Synchronized
    fun recordVideo(
        seq: Int,
        key: Boolean,
        data: ByteArray,
        captureHostNs: Long,
        sourceGuestNs: Long?,
        receivedNs: Long,
        clockRttNs: Long,
    ) {
        if (stopped || expired() || sourceGuestNs == null || captureHostNs <= 0 || data.isEmpty()) return
        val config = csd ?: return
        if (waitingForKey && !key) return
        if (startedNs == null) {
            if (!key) return
            startedNs = nowNs()
        }
        if (!reserve(data.size.toLong() + RECORD_BYTES)) return
        video += Video(seq, key, data.copyOf(), if (key) config else null, captureHostNs, sourceGuestNs, receivedNs, clockRttNs)
        payloadBytes += data.size
        waitingForKey = false
    }

    @Synchronized
    fun recordAudio(played: StreamPcmPlayed, captureGuestNs: Long?, clockReady: Boolean, clockRttNs: Long) {
        if (stopped || expired() || startedNs == null || !clockReady) return
        if (played.bytes <= 0 || played.bytes > played.pcm.size || (played.seq != null && captureGuestNs == null)) return
        if (!reserve(played.bytes.toLong() + RECORD_BYTES)) return
        audio += Audio(played.copy(pcm = played.pcm.copyOf(played.bytes)), captureGuestNs, clockRttNs)
        payloadBytes += played.bytes
    }

    @Synchronized
    fun tick() {
        if (!stopped) expired()
    }

    @Synchronized
    override fun close() {
        if (!stopped) stop(StopReason.CLOSED)
    }

    private fun expired(): Boolean {
        val start = startedNs ?: return false
        if (nowNs() - start < durationNs) return false
        stop(StopReason.DURATION)
        return true
    }

    private fun reserve(bytes: Long): Boolean {
        if (bytes > maxBytes - budgetBytes) {
            stop(StopReason.CAP)
            return false
        }
        budgetBytes += bytes
        return true
    }

    private fun stop(reason: StopReason) {
        if (stopped) return
        stopped = true
        val sample = Sample(startedNs, nowNs(), reason, video, audio, payloadBytes, budgetBytes)
        csd = null
        video = mutableListOf()
        audio = mutableListOf()
        if (sample.startedNs == null && reason != StopReason.CAP) return
        val task = {
            val result = runCatching { dump(sample) }
            sample.video.clear()
            sample.audio.clear()
            runCatching { onDumped(result) }
            Unit
        }
        runCatching { execute(task) }.onFailure { error ->
            sample.video.clear()
            sample.audio.clear()
            runCatching { onDumped(Result.failure(error)) }
        }
    }

    private fun dump(sample: Sample): DumpResult {
        check(directory.mkdirs() || directory.isDirectory) { "Cannot create evidence directory ${directory.absolutePath}" }
        val files = FILE_NAMES.map { File(directory, it) }
        files.forEach { check(it.createNewFile()) { "Evidence file already exists: ${it.absolutePath}" } }
        var videoOffset = 0L
        var audioOffset = 0L
        files[0].outputStream().buffered().use { out ->
            files[1].bufferedWriter().use { csv ->
                csv.appendLine("frameIndex,seq,key,byteOffset,bytes,csdByteOffset,csdBytes,captureHostNs,sourceGuestNs,receivedNs,clockRttNs")
                sample.video.forEachIndexed { index, frame ->
                    val csdOffset = videoOffset
                    val csdBytes = frame.csd?.size ?: 0
                    frame.csd?.let { out.write(it) }
                    videoOffset += csdBytes
                    csv.appendLine("$index,${frame.seq},${frame.key},$videoOffset,${frame.data.size},$csdOffset,$csdBytes,${frame.captureHostNs},${frame.sourceGuestNs},${frame.receivedNs},${frame.clockRttNs}")
                    out.write(frame.data)
                    videoOffset += frame.data.size
                }
            }
        }
        files[2].outputStream().buffered().use { out ->
            files[3].bufferedWriter().use { csv ->
                csv.appendLine("blockIndex,seq,silence,byteOffset,bytes,captureHostNs,captureGuestNs,outputNs,timestampValid,timestampNs,timestampFramePosition,firstWrittenFrame,firstWrittenByte,observedNs,clockRttNs,muted")
                sample.audio.forEachIndexed { index, block ->
                    val p = block.played
                    csv.appendLine("$index,${p.seq ?: ""},${p.seq == null},$audioOffset,${p.bytes},${p.captureNs},${block.captureGuestNs ?: ""},${p.outputNs},${p.timestampValid},${p.timestampNs},${p.timestampFramePosition},${p.firstWrittenFrame},${p.firstWrittenByte},${p.observedNs},${block.clockRttNs},${p.muted}")
                    out.write(p.pcm)
                    audioOffset += p.bytes
                }
            }
        }
        files[4].bufferedWriter().use { meta ->
            meta.appendLine("formatVersion=1")
            meta.appendLine("complete=true")
            meta.appendLine("stopReason=${sample.reason}")
            meta.appendLine("startedGuestNs=${sample.startedNs ?: ""}")
            meta.appendLine("stoppedGuestNs=${sample.stoppedNs}")
            meta.appendLine("maxDurationNs=$durationNs")
            meta.appendLine("maxBudgetBytes=$maxBytes")
            meta.appendLine("retainedBudgetBytes=${sample.budgetBytes}")
            meta.appendLine("retainedPayloadBytes=${sample.payloadBytes}")
            meta.appendLine("baseBudgetBytes=$BASE_BYTES")
            meta.appendLine("perRecordBudgetBytes=$RECORD_BYTES")
            meta.appendLine("videoFrames=${sample.video.size}")
            meta.appendLine("audioBlocks=${sample.audio.size}")
            meta.appendLine("videoBytes=$videoOffset")
            meta.appendLine("audioBytes=$audioOffset")
            meta.appendLine("audioRate=$sampleRate")
            meta.appendLine("audioChannels=$channels")
            meta.appendLine("audioFormat=s16le interleaved")
            meta.appendLine("clock=guest System.nanoTime; source times converted by ClockSync.hostToGuest")
            meta.appendLine("videoTiming=received encoded content source time, not decoder or display presentation")
            meta.appendLine("audioTiming=AudioTrack hardware timestamp extrapolation when timestampValid; playback-head fallback otherwise; not acoustic measurement")
            meta.appendLine("sampleMapping=outputNs + floor(localFrameIndex * 1000000000 / audioRate); use each block separately")
            meta.appendLine("correlation=match an actual decoded game event to its played PCM waveform; capture timestamps alone do not prove upstream FIFO latency")
        }
        return DumpResult(directory, sample.reason, sample.video.size, sample.audio.size, sample.payloadBytes, sample.budgetBytes)
    }

    companion object {
        const val MAX_BYTES = 32L * 1024 * 1024
        const val DURATION_NS = 30_000_000_000L
        private const val BASE_BYTES = 131072L
        private const val RECORD_BYTES = 256L
        private val FILE_NAMES = listOf("video.h264", "video.csv", "audio.s16le", "audio.csv", "metadata.properties")

        fun isEnabled(debugBuild: Boolean, property: () -> String?): Boolean =
            debugBuild && runCatching { property() == "1" }.getOrDefault(false)
    }
}
