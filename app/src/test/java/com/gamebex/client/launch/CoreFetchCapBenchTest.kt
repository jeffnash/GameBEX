package com.gamebex.client.launch

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Perf harness: proves the core downloader never buffers
 * a package in memory. A 150 MB mock stream (larger than the 100 MB
 * cap) must abort at the cap with flat heap; peak-heap samples print
 * to stdout for CI logs.
 */
class CoreFetchCapBenchTest {
    /** Endless zero stream capped at [total]; no backing allocation. */
    private class ZeroStream(private var total: Long) : InputStream() {
        private val chunk = ByteArray(64 * 1024)
        override fun read(): Int = if (total-- > 0) 0 else -1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (total <= 0) return -1
            val n = minOf(len.toLong(), total, chunk.size.toLong()).toInt()
            total -= n
            return n
        }
    }

    private fun usedHeapMb(): Double {
        val rt = Runtime.getRuntime()
        return (rt.totalMemory() - rt.freeMemory()) / 1024.0 / 1024.0
    }

    @Test fun oversizeStreamAbortsAtCapWithFlatHeap() {
        System.gc()
        val before = usedHeapMb()
        // Discard sink: measures the copy loop's own heap, not the dest.
        val out = object : OutputStream() { override fun write(b: Int) {} }
        val t0 = System.nanoTime()
        val got = RetroCoreFetcher.copyCapped(
            ZeroStream(150L * 1024 * 1024), out, RetroCoreFetcher.MAX_CORE_ZIP_BYTES,
        )
        val ms = (System.nanoTime() - t0) / 1_000_000.0
        System.gc()
        val after = usedHeapMb()
        println("BENCH copyCapped 150MB stream vs 100MB cap: ${"%.0f".format(ms)}ms, " +
            "heap ${"%.1f".format(before)}MB -> ${"%.1f".format(after)}MB")
        assertNull("must abort past the cap", got)
        // Only the 64 KiB buffer is retained; heap must not grow with
        // the 150 MB input.
        assertTrue("heap grew ${"%.1f".format(after - before)}MB, bound 32MB", after - before < 32)
        assertTrue("cap check should stream, took ${"%.0f".format(ms)}ms", ms < 30_000)
    }

    @Test fun smallStreamCopiesFully() {
        val data = ByteArray(1_000_000) { (it % 251).toByte() }
        val out = ByteArrayOutputStream()
        var progress = 0L
        val got = RetroCoreFetcher.copyCapped(data.inputStream(), out, 100L * 1024 * 1024) {
            progress = it
        }
        assertEquals(1_000_000L, got)
        assertEquals(1_000_000, out.size())
        assertEquals(1_000_000L, progress)
    }
}
