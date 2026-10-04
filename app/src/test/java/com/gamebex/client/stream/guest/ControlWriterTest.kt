package com.gamebex.client.stream.guest

import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlWriterTest {
    private class BlockedOutput : OutputStream() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val writes = LinkedBlockingQueue<Int>()
        @Volatile var closed = false
        override fun write(value: Int) {
            entered.countDown()
            try {
                release.await(2, TimeUnit.SECONDS)
                if (closed) throw IOException("closed")
                writes += value
            } finally { if (closed) exited.countDown() }
        }
        override fun close() { closed = true; release.countDown() }
    }

    @Test
    fun readerProgressWhileOutputBlockedAndPendingKindsAreBounded() {
        val out = BlockedOutput()
        val writer = ControlWriter(out, { out.close() })
        try {
            assertTrue(writer.offer(ControlWriter.Kind.PING) { byteArrayOf(1) })
            assertTrue(out.entered.await(1, TimeUnit.SECONDS))
            repeat(100) { i ->
                assertTrue(writer.offer(ControlWriter.Kind.REPORT) { byteArrayOf(i.toByte()) })
                assertTrue(writer.offer(ControlWriter.Kind.PING) { byteArrayOf(2) })
            }
            assertTrue(writer.offer(ControlWriter.Kind.KEYFRAME) { byteArrayOf(3) })
            out.release.countDown()
            assertEquals(1, out.writes.poll(1, TimeUnit.SECONDS))
            assertEquals(3, out.writes.poll(1, TimeUnit.SECONDS))
            assertEquals(2, out.writes.poll(1, TimeUnit.SECONDS))
            assertEquals(99, out.writes.poll(1, TimeUnit.SECONDS))
            assertTrue(out.writes.isEmpty())
        } finally { writer.close() }
    }

    @Test
    fun timestampAssignedAtWriteNotEnqueueAndEncoderCanOfferOutsideLock() {
        val out = BlockedOutput()
        var now = 10L
        val writer = ControlWriter(out, { out.close() }, clock = { now })
        try {
            writer.offer(ControlWriter.Kind.KEYFRAME) { byteArrayOf(1) }
            assertTrue(out.entered.await(1, TimeUnit.SECONDS))
            writer.offer(ControlWriter.Kind.PING) { time ->
                writer.offer(ControlWriter.Kind.REPORT) { byteArrayOf(3) }
                byteArrayOf(time.toByte())
            }
            now = 20L
            out.release.countDown()
            assertEquals(1, out.writes.poll(1, TimeUnit.SECONDS))
            assertEquals(20, out.writes.poll(1, TimeUnit.SECONDS))
            assertEquals(3, out.writes.poll(1, TimeUnit.SECONDS))
        } finally { writer.close() }
    }

    @Test
    fun finiteLeaveClosesDeadSocketAndUnblocksWrite() {
        val out = BlockedOutput()
        val writer = ControlWriter(out, { out.close() })
        writer.offer(ControlWriter.Kind.PING) { byteArrayOf(1) }
        assertTrue(out.entered.await(1, TimeUnit.SECONDS))
        val before = System.nanoTime()
        writer.leave()
        assertTrue(System.nanoTime() - before < 1_000_000_000L)
        assertTrue(out.exited.await(1, TimeUnit.SECONDS))
        assertFalse(writer.offer(ControlWriter.Kind.REPORT) { byteArrayOf(2) })
    }

    @Test
    fun failureCallbackOnlyClosesItsOwnConnection() {
        val failed = CountDownLatch(1)
        val closes = LinkedBlockingQueue<String>()
        val old = ControlWriter(object : OutputStream() {
            override fun write(value: Int) { throw IOException("dead") }
        }, { closes += "old" }, { failed.countDown() })
        val next = ControlWriter(object : OutputStream() {
            override fun write(value: Int) { closes += "next-write" }
        }, { closes += "next-close" })
        try {
            old.offer(ControlWriter.Kind.PING) { byteArrayOf(1) }
            assertTrue(failed.await(1, TimeUnit.SECONDS))
            next.offer(ControlWriter.Kind.PING) { byteArrayOf(2) }
            val observed = mutableListOf<String>()
            repeat(2) { observed += closes.poll(1, TimeUnit.SECONDS) ?: throw AssertionError("missing write/close") }
            assertTrue(observed.contains("old"))
            assertTrue(observed.contains("next-write"))
            assertFalse(observed.contains("next-close"))
        } finally { old.close(); next.close() }
    }
}
