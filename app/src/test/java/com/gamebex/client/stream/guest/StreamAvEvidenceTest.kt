package com.gamebex.client.stream.guest

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Properties
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class StreamAvEvidenceTest {
    @get:Rule val tmp = TemporaryFolder()

    private val csd = byteArrayOf(0, 0, 0, 1, 0x67, 0, 0, 0, 1, 0x68)
    private val key = byteArrayOf(0, 0, 0, 1, 0x65, 7)
    private val delta = byteArrayOf(0, 0, 0, 1, 0x41, 8)

    private fun frame(evidence: StreamAvEvidence, seq: Int, isKey: Boolean = true, clockReady: Boolean = true) {
        evidence.recordVideo(seq, isKey, if (isKey) key else delta, 700L + seq, if (clockReady) 400L + seq else null, 800L + seq, 12)
    }

    private fun played(
        pcm: ByteArray = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8),
        bytes: Int = pcm.size,
        seq: Int? = 9,
        valid: Boolean = true,
        firstByte: Long = 1920,
    ) = StreamPcmPlayed(
        pcm = pcm,
        bytes = bytes,
        seq = seq,
        captureNs = if (seq == null) 0 else 300,
        outputNs = 5_000_123L,
        timestampValid = valid,
        timestampNs = 123L,
        timestampFramePosition = 240,
        firstWrittenFrame = firstByte / 4,
        firstWrittenByte = firstByte,
        observedNs = 456L,
        muted = false,
    )

    private fun csv(directory: File, name: String): List<Map<String, String>> {
        val lines = File(directory, name).readLines()
        val header = lines.first().split(',')
        return lines.drop(1).map { header.zip(it.split(',')).toMap() }
    }

    private fun metadata(directory: File): Properties = Properties().apply {
        File(directory, "metadata.properties").inputStream().use { load(it) }
    }

    @Test
    fun releaseAndUnsetPropertyCannotEnableOrReadProperty() {
        var calls = 0
        assertFalse(StreamAvEvidence.isEnabled(false) { calls += 1; "1" })
        assertEquals(0, calls)
        assertFalse(StreamAvEvidence.isEnabled(true) { null })
        assertFalse(StreamAvEvidence.isEnabled(true) { "true" })
        assertFalse(StreamAvEvidence.isEnabled(true) { throw IllegalStateException("unavailable") })
        assertTrue(StreamAvEvidence.isEnabled(true) { "1" })
    }

    @Test
    fun startsOnlyWithClockConfigAndKeyAndKeepsSourceFrameIds() {
        val directory = File(tmp.root, "sample")
        val tasks = mutableListOf<() -> Unit>()
        val evidence = StreamAvEvidence(directory, execute = { tasks += it })
        frame(evidence, 1)
        evidence.configure(csd)
        evidence.recordAudio(played(), 100, true, 12)
        frame(evidence, 2, clockReady = false)
        frame(evidence, 3, isKey = false)
        frame(evidence, 40)
        frame(evidence, 42, isKey = false)
        evidence.close()
        assertEquals(1, tasks.size)
        assertFalse(directory.exists())
        tasks.single()()
        val rows = csv(directory, "video.csv")
        assertEquals(listOf("0", "1"), rows.map { it["frameIndex"] })
        assertEquals(listOf("40", "42"), rows.map { it["seq"] })
        assertEquals(listOf("440", "442"), rows.map { it["sourceGuestNs"] })
        assertEquals(listOf("840", "842"), rows.map { it["receivedNs"] })
        assertEquals(listOf("true", "false"), rows.map { it["key"] })
        assertArrayEquals(csd + key + delta, File(directory, "video.h264").readBytes())
        assertTrue(csv(directory, "audio.csv").isEmpty())
    }

    @Test
    fun exactWrittenPrefixSilenceAndHardwareMappingAreReversibleAndCopied() {
        val directory = File(tmp.root, "sample")
        val tasks = mutableListOf<() -> Unit>()
        val evidence = StreamAvEvidence(directory, execute = { tasks += it })
        val config = csd.copyOf()
        evidence.configure(config)
        config.fill(99)
        frame(evidence, 4)
        evidence.recordAudio(played(), 100, false, 12)
        val pcm = byteArrayOf(1, 2, 3, 4, 9, 9, 9, 9)
        evidence.recordAudio(played(pcm, bytes = 4), 100, true, 12)
        pcm.fill(99)
        evidence.recordAudio(played(ByteArray(8), seq = null, valid = false, firstByte = 1924), null, true, 12)
        evidence.close()
        tasks.single()()
        assertArrayEquals(byteArrayOf(1, 2, 3, 4) + ByteArray(8), File(directory, "audio.s16le").readBytes())
        assertArrayEquals(csd + key, File(directory, "video.h264").readBytes())
        val rows = csv(directory, "audio.csv")
        assertEquals(listOf("0", "4"), rows.map { it["byteOffset"] })
        assertEquals(listOf("4", "8"), rows.map { it["bytes"] })
        assertEquals(listOf("9", ""), rows.map { it["seq"] })
        assertEquals(listOf("false", "true"), rows.map { it["silence"] })
        assertEquals(listOf("true", "false"), rows.map { it["timestampValid"] })
        assertEquals("5000123", rows[0]["outputNs"])
        assertEquals("123", rows[0]["timestampNs"])
        assertEquals("240", rows[0]["timestampFramePosition"])
        assertEquals("480", rows[0]["firstWrittenFrame"])
        assertEquals("1920", rows[0]["firstWrittenByte"])
        assertEquals("100", rows[0]["captureGuestNs"])
        val meta = metadata(directory)
        assertEquals("48000", meta.getProperty("audioRate"))
        assertEquals("2", meta.getProperty("audioChannels"))
        assertEquals("12", meta.getProperty("audioBytes"))
    }

    @Test
    fun changedConfigRequiresAnotherKeyAndEveryByteIsAddressable() {
        val directory = File(tmp.root, "sample")
        val evidence = StreamAvEvidence(directory, execute = { it() })
        evidence.configure(csd)
        frame(evidence, 1)
        val nextConfig = csd + byteArrayOf(0, 0, 0, 1, 0x67, 2)
        evidence.configure(nextConfig)
        frame(evidence, 2, isKey = false)
        frame(evidence, 3)
        evidence.close()
        val bytes = File(directory, "video.h264").readBytes()
        val rows = csv(directory, "video.csv")
        assertEquals(listOf("1", "3"), rows.map { it["seq"] })
        for ((index, row) in rows.withIndex()) {
            val dataOffset = row.getValue("byteOffset").toInt()
            val configOffset = row.getValue("csdByteOffset").toInt()
            assertArrayEquals(key, bytes.copyOfRange(dataOffset, dataOffset + row.getValue("bytes").toInt()))
            assertArrayEquals(if (index == 0) csd else nextConfig, bytes.copyOfRange(configOffset, configOffset + row.getValue("csdBytes").toInt()))
        }
    }

    @Test
    fun durationAutomaticallyDumpsOffThreadOnceWithoutEndingSession() {
        var now = 1000L
        val directory = File(tmp.root, "sample")
        val tasks = mutableListOf<() -> Unit>()
        var dumped: StreamAvEvidence.DumpResult? = null
        val evidence = StreamAvEvidence(directory, nowNs = { now }, durationNs = 30, execute = { tasks += it }, onDumped = { dumped = it.getOrThrow() })
        evidence.configure(csd)
        frame(evidence, 1)
        now += 29
        evidence.tick()
        assertTrue(tasks.isEmpty())
        now += 1
        evidence.tick()
        frame(evidence, 2)
        evidence.recordAudio(played(), 100, true, 12)
        evidence.configure(delta)
        evidence.tick()
        evidence.close()
        assertEquals(1, tasks.size)
        assertFalse(directory.exists())
        tasks.single()()
        assertEquals(StreamAvEvidence.StopReason.DURATION, dumped!!.reason)
        assertEquals(1, dumped!!.videoFrames)
        assertEquals(0, dumped!!.audioBlocks)
        assertEquals("1000", metadata(directory).getProperty("startedGuestNs"))
        assertEquals("1030", metadata(directory).getProperty("stoppedGuestNs"))
    }

    @Test
    fun cumulativeVideoAudioAndRecordOverheadHitCapWithoutAnOversizedCopy() {
        val directory = File(tmp.root, "sample")
        var dumped: StreamAvEvidence.DumpResult? = null
        val evidence = StreamAvEvidence(directory, maxBytes = 140000, execute = { it() }, onDumped = { dumped = it.getOrThrow() })
        evidence.configure(csd)
        frame(evidence, 1)
        evidence.recordAudio(played(ByteArray(4000)), 100, true, 12)
        evidence.recordVideo(2, false, ByteArray(10000), 702, 402, 802, 12)
        frame(evidence, 3)
        evidence.recordAudio(played(), 100, true, 12)
        evidence.close()
        val result = dumped!!
        assertEquals(StreamAvEvidence.StopReason.CAP, result.reason)
        assertTrue(result.budgetBytes <= 140000)
        assertEquals(1, result.videoFrames)
        assertEquals(1, result.audioBlocks)
        assertEquals(4000L, File(directory, "audio.s16le").length())
        assertArrayEquals(csd + key, File(directory, "video.h264").readBytes())
    }

    @Test
    fun tinyBlocksCannotBypassMemoryCapThroughMetadata() {
        val directory = File(tmp.root, "sample")
        var dumped: StreamAvEvidence.DumpResult? = null
        val evidence = StreamAvEvidence(directory, maxBytes = 140000, execute = { it() }, onDumped = { dumped = it.getOrThrow() })
        evidence.configure(csd)
        frame(evidence, 1)
        repeat(1000) { evidence.recordAudio(played(ByteArray(4)), 100, true, 12) }
        evidence.close()
        assertEquals(StreamAvEvidence.StopReason.CAP, dumped!!.reason)
        assertTrue(dumped!!.audioBlocks in 1..40)
        assertTrue(dumped!!.budgetBytes <= 140000)
        assertEquals(dumped!!.audioBlocks * 4L, File(directory, "audio.s16le").length())
    }

    @Test
    fun closeBeforeStartCreatesNoFilesAndCannotRestart() {
        val directory = File(tmp.root, "sample")
        val tasks = mutableListOf<() -> Unit>()
        val evidence = StreamAvEvidence(directory, execute = { tasks += it })
        evidence.configure(csd)
        evidence.close()
        evidence.configure(csd)
        frame(evidence, 1)
        evidence.recordAudio(played(), 100, true, 12)
        evidence.close()
        assertTrue(tasks.isEmpty())
        assertFalse(directory.exists())
    }

    @Test
    fun closeFreezesBeforeWriterRunsAndRejectsLateCallbacks() {
        val directory = File(tmp.root, "sample")
        val tasks = mutableListOf<() -> Unit>()
        val evidence = StreamAvEvidence(directory, execute = { tasks += it })
        evidence.configure(csd)
        frame(evidence, 1)
        evidence.close()
        frame(evidence, 2)
        evidence.recordAudio(played(), 100, true, 12)
        evidence.close()
        assertEquals(1, tasks.size)
        tasks.single()()
        assertEquals(1, csv(directory, "video.csv").size)
        assertTrue(csv(directory, "audio.csv").isEmpty())
        assertEquals("CLOSED", metadata(directory).getProperty("stopReason"))
    }

    @Test
    fun realWriterUsesAnotherThreadAndReportsFileErrorsWithoutThrowing() {
        val directory = File(tmp.root, "sample")
        val complete = CountDownLatch(1)
        val caller = Thread.currentThread()
        var writer: Thread? = null
        var result: Result<StreamAvEvidence.DumpResult>? = null
        val evidence = StreamAvEvidence(directory, onDumped = {
            writer = Thread.currentThread()
            result = it
            complete.countDown()
        })
        evidence.configure(csd)
        frame(evidence, 1)
        evidence.close()
        assertTrue(complete.await(5, TimeUnit.SECONDS))
        assertTrue(writer !== caller)
        assertTrue(result!!.isSuccess)

        val blockedDirectory = tmp.newFile("not-a-directory")
        var failed = false
        val failing = StreamAvEvidence(blockedDirectory, execute = { it() }, onDumped = { failed = it.isFailure })
        failing.configure(csd)
        frame(failing, 1)
        failing.close()
        assertTrue(failed)
    }
}
