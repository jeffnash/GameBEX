package com.gamebex.client.stream.shell

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class RSoundPlaybackTest {
    @Test fun deviceMinimumIsAllocationCapacityNotEffectivePlaybackBuffer() {
        val buffers = rsoundChooseBufferSizes(16416)
        assertEquals(16416, buffers.capacityBytes)
        assertEquals(768, buffers.requestedFrames)
        assertTrue(buffers.capacityBytes > RSoundPlayback.MAX_BUFFER_BYTES)
        assertTrue(buffers.requestedFrames * RSoundProtocol.FRAME_BYTES <= RSoundPlayback.MAX_BUFFER_BYTES)
    }

    @Test fun allocatesAtLeastMinimumWhileRequesting480Or768Frames() {
        for (desiredFrames in listOf(480, 768)) {
            for (minimum in listOf(960, 16416, RSoundPlayback.MAX_ALLOCATION_BYTES)) {
                val buffers = rsoundChooseBufferSizes(minimum, desiredFrames)
                assertEquals(maxOf(minimum, desiredFrames * RSoundProtocol.FRAME_BYTES), buffers.capacityBytes)
                assertEquals(desiredFrames, buffers.requestedFrames)
                rsoundCheckEffectiveBufferFrames(buffers.requestedFrames)
            }
        }
    }

    @Test fun rejectsUnsupportedOrUnreasonableAllocationBounds() {
        for (minimum in listOf(Int.MIN_VALUE, -2, -1, 0, RSoundPlayback.MAX_ALLOCATION_BYTES + 1, Int.MAX_VALUE)) {
            assertThrows(IllegalStateException::class.java) { rsoundChooseBufferSizes(minimum) }
        }
    }

    @Test fun rejectsInvalidRequestedFramesWithoutOverflow() {
        val maximumFrames = RSoundPlayback.MAX_BUFFER_BYTES / RSoundProtocol.FRAME_BYTES
        assertEquals(maximumFrames, rsoundChooseBufferSizes(16416, maximumFrames).requestedFrames)
        for (frames in listOf(Int.MIN_VALUE, -1, 0, maximumFrames + 1, Int.MAX_VALUE)) {
            assertThrows(IllegalStateException::class.java) { rsoundChooseBufferSizes(16416, frames) }
        }
    }

    @Test fun actualPlaybackBufferMustStillMeet40MillisecondCap() {
        val maximumFrames = RSoundPlayback.MAX_BUFFER_BYTES / RSoundProtocol.FRAME_BYTES
        for (frames in listOf(480, 768, maximumFrames)) rsoundCheckEffectiveBufferFrames(frames)
        for (frames in listOf(-6, -1, 0, maximumFrames + 1, 16416 / RSoundProtocol.FRAME_BYTES, Int.MAX_VALUE)) {
            assertThrows(IllegalStateException::class.java) { rsoundCheckEffectiveBufferFrames(frames) }
        }
    }

    @Test fun handlesPartialAndTransientZeroWritesWithoutDroppingBytes() {
        val pcm = ByteArray(960) { it.toByte() }
        val accepted = ByteArrayOutputStream()
        val calls = AtomicInteger()
        val player = RSoundTestPlayer(writer = { bytes, offset, count ->
            val size = if (calls.getAndIncrement() == 1) 0 else minOf(count, 37)
            accepted.write(bytes, offset, size)
            size
        })
        val writtenCounts = mutableListOf<Int>()
        assertTrue(rsoundWriteFully(player, pcm, onWritten = { writtenCounts.add(it) }) { true })
        assertEquals(pcm.size, writtenCounts.sum())
        assertTrue(writtenCounts.all { it in 1..37 })
        assertArrayEquals(pcm, accepted.toByteArray())
        assertTrue(calls.get() > 2)
    }

    @Test fun rejectsErrorsStalledWritesAndInvalidCountsWithFiniteAttempts() {
        for (result in listOf(-6, 0, 961)) {
            val calls = AtomicInteger()
            val player = RSoundTestPlayer(writer = { _, _, _ -> calls.incrementAndGet(); result })
            assertThrows(IOException::class.java) { rsoundWriteFully(player, ByteArray(960)) { true } }
            assertTrue(calls.get() <= 3)
        }
    }

    @Test fun blockingPlaybackPacesWriterAndCloseUnblocksWithoutWriteLock() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val calls = AtomicInteger()
        val player = RSoundTestPlayer(
            writer = { _, _, _ -> calls.incrementAndGet(); entered.countDown(); release.await(); -1 },
            onClose = { release.countDown() },
        )
        val worker = Thread {
            try {
                rsoundWriteFully(player, ByteArray(1920)) { !player.closed }
            } catch (_: IOException) {
                Unit
            } finally {
                finished.countDown()
            }
        }.apply { isDaemon = true; start() }
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertFalse(finished.await(40, TimeUnit.MILLISECONDS))
            assertEquals(1, calls.get())
            player.close()
            assertTrue(finished.await(1, TimeUnit.SECONDS))
        } finally {
            player.close()
            worker.join(1000)
        }
    }

    @Test fun countsOnlySuccessfulWritesEvenWhenLaterPartialWriteFails() {
        val calls = AtomicInteger()
        val writtenCounts = mutableListOf<Int>()
        val player = RSoundTestPlayer(writer = { _, _, _ ->
            when (calls.incrementAndGet()) {
                1 -> 4
                2 -> 0
                else -> -6
            }
        })
        assertThrows(IOException::class.java) {
            rsoundWriteFully(player, ByteArray(960), onWritten = { writtenCounts.add(it) }) { true }
        }
        assertEquals(listOf(4), writtenCounts)
        assertEquals(3, calls.get())
    }

    @Test fun rejectsInvalidWriteCountsWithoutReportingAcceptedBytes() {
        for (result in listOf(-6, 961)) {
            val writtenCounts = mutableListOf<Int>()
            val player = RSoundTestPlayer(writer = { _, _, _ -> result })
            assertThrows(IOException::class.java) {
                rsoundWriteFully(player, ByteArray(960), onWritten = { writtenCounts.add(it) }) { true }
            }
            assertTrue(writtenCounts.isEmpty())
        }
    }

    @Test fun cancellationBetweenPartialWritesPreventsAnyFurtherWrite() {
        var active = true
        val calls = AtomicInteger()
        val player = RSoundTestPlayer(writer = { _, _, _ -> calls.incrementAndGet(); active = false; 4 })
        var writtenBytes = 0
        assertFalse(rsoundWriteFully(player, ByteArray(960), onWritten = { writtenBytes += it }) { active })
        assertEquals(4, writtenBytes)
        assertEquals(1, calls.get())
    }
}

internal class RSoundTestPlayer(
    private val initialized: Boolean = true,
    override val bufferBytes: Int = 1920,
    private val writer: (ByteArray, Int, Int) -> Int = { _, _, count -> count },
    private val onStart: () -> Unit = {},
    private val onClose: () -> Unit = {},
) : RSoundPlayer {
    @Volatile var closed = false
    @Volatile var playedFrames = 0L
    val started = AtomicInteger()
    val writtenBytes = AtomicInteger()
    override val ready: Boolean get() = initialized && !closed

    override fun start() {
        check(ready)
        onStart()
        started.incrementAndGet()
    }

    override fun write(bytes: ByteArray, offset: Int, count: Int): Int {
        if (closed) return -1
        val accepted = writer(bytes, offset, count)
        if (accepted > 0) writtenBytes.addAndGet(accepted)
        return accepted
    }

    override fun stats(): RSoundPlaybackStats = RSoundPlaybackStats(playedFrames, playedFrames, null, null, 0)

    override fun close() {
        if (closed) return
        closed = true
        onClose()
    }
}
