package com.gamebex.client.stream.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class RSoundGuestQueueTest {
    @Test fun blockedSinkDropsOldestAndKeepsSixChunksWithoutHoldingQueueLock() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(7)
        val sequences = CopyOnWriteArrayList<Int>()
        val queue = RSoundGuestQueue(AudioSink {
            sequences.add(it.seq)
            if (it.seq == 1) {
                entered.countDown()
                release.await()
            }
            finished.countDown()
        }, { failure, _ -> throw AssertionError(failure) })
        try {
            queue.enqueue(chunk(1)) { true }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            for (seq in 2..12) queue.enqueue(chunk(seq)) { true }
            assertEquals(6, queue.pendingChunks)
            assertEquals(5L, queue.drops)
            assertEquals(30, RSoundGuestQueue.CAPACITY_MS)
            release.countDown()
            assertTrue(finished.await(1, TimeUnit.SECONDS))
            assertEquals(listOf(1, 7, 8, 9, 10, 11, 12), sequences.toList())
        } finally {
            release.countDown()
            queue.close()
        }
    }

    @Test fun flushDiscardsOldGenerationAndRetainsMonotonicNewSequence() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val sequences = CopyOnWriteArrayList<Int>()
        val queue = RSoundGuestQueue(AudioSink {
            sequences.add(it.seq)
            if (it.seq == 1) {
                entered.countDown()
                release.await()
            } else finished.countDown()
        }, { _, _ -> })
        try {
            queue.enqueue(chunk(1)) { true }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            queue.enqueue(chunk(2)) { true }
            queue.enqueue(chunk(3)) { true }
            queue.flush()
            assertEquals(0, queue.pendingChunks)
            queue.enqueue(chunk(4)) { true }
            release.countDown()
            assertTrue(finished.await(1, TimeUnit.SECONDS))
            assertEquals(listOf(1, 4), sequences.toList())
            assertEquals(2L, queue.drops)
        } finally {
            release.countDown()
            queue.close()
        }
    }

    @Test fun throwingSinkReportsOnceDiscardsBacklogAndFlushAllowsNewGuest() {
        val failed = CountDownLatch(1)
        val recovered = CountDownLatch(1)
        val calls = AtomicInteger()
        val reports = AtomicInteger()
        val queue = RSoundGuestQueue(AudioSink {
            calls.incrementAndGet()
            if (it.seq == 1) throw IOException("guest closed")
            recovered.countDown()
        }, { _, _ -> reports.incrementAndGet(); failed.countDown() })
        try {
            queue.enqueue(chunk(1)) { true }
            assertTrue(failed.await(1, TimeUnit.SECONDS))
            queue.enqueue(chunk(2)) { true }
            queue.enqueue(chunk(3)) { true }
            assertEquals(1, calls.get())
            assertEquals(1, reports.get())
            assertEquals(3L, queue.drops)
            queue.flush()
            queue.enqueue(chunk(4)) { true }
            assertTrue(recovered.await(1, TimeUnit.SECONDS))
            assertEquals(2, calls.get())
        } finally {
            queue.close()
        }
    }

    @Test fun closedQueueReturnsImmediatelyAndRetiredWorkerNeverStartsAnotherSend() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val retired = CountDownLatch(1)
        val calls = AtomicInteger()
        val queue = RSoundGuestQueue(AudioSink {
            calls.incrementAndGet()
            entered.countDown()
            while (release.count > 0) {
                try {
                    release.await()
                } catch (_: InterruptedException) {
                    Unit
                }
            }
            retired.countDown()
        }, { _, _ -> })
        try {
            queue.enqueue(chunk(1)) { true }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            queue.enqueue(chunk(2)) { true }
            val startNs = System.nanoTime()
            queue.close()
            assertTrue(System.nanoTime() - startNs < 100_000_000L)
            assertEquals(0, queue.pendingChunks)
            queue.enqueue(chunk(3)) { true }
            release.countDown()
            assertTrue(retired.await(1, TimeUnit.SECONDS))
            Thread.sleep(20)
            assertEquals(1, calls.get())
        } finally {
            release.countDown()
            queue.close()
        }
    }

    @Test fun retiredGenerationFailureCannotDisableOrReportAgainstReplacementGuest() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val replacement = CountDownLatch(1)
        val reports = AtomicInteger()
        val queue = RSoundGuestQueue(AudioSink {
            if (it.seq == 1) {
                entered.countDown()
                release.await()
                throw IOException("old guest failed after detach")
            }
            replacement.countDown()
        }, { _, _ -> reports.incrementAndGet() })
        try {
            queue.enqueue(chunk(1)) { true }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            queue.flush()
            queue.enqueue(chunk(2)) { true }
            release.countDown()
            assertTrue(replacement.await(1, TimeUnit.SECONDS))
            assertEquals(0, reports.get())
        } finally {
            release.countDown()
            queue.close()
        }
    }

    @Test fun inactiveConnectionIsSkippedWithoutCallingItsSink() {
        val valid = CountDownLatch(1)
        val sequences = CopyOnWriteArrayList<Int>()
        val queue = RSoundGuestQueue(AudioSink { sequences.add(it.seq); valid.countDown() }, { _, _ -> })
        try {
            queue.enqueue(chunk(1)) { false }
            queue.enqueue(chunk(2)) { true }
            assertTrue(valid.await(1, TimeUnit.SECONDS))
            assertEquals(listOf(2), sequences.toList())
        } finally {
            queue.close()
        }
    }

    private fun chunk(seq: Int) = PcmChunk(seq, seq.toLong(), 240, ByteArray(960))
}
