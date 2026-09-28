package com.gamebex.client.data.local

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Perf harness: times scans of generated 100 / 1,000 / 10,000-file trees and counts DAO calls
 * through a Room query callback. SELECTs must stay O(1) (one preload) whatever the file count.
 * Timings print as BENCH lines.
 */
@RunWith(AndroidJUnit4::class)
class LibraryScanBenchTest {
    private fun genTree(root: File, n: Int) {
        val plat = File(root, "gb").apply { mkdirs() }
        repeat(n) { i ->
            File(plat, "bench_%05d.gb".format(i)).writeBytes(ByteArray(128) { i.toByte() })
        }
    }

    private fun scanTimed(db: LibraryDb, root: File): Triple<Int, Int, Int> =
        // The query counter lives on the db builder; this only runs the scan.
        runBlocking(Dispatchers.IO) { LibraryScanner.scanVolume(db, "bench", root) }

    private fun benchSize(n: Int) {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(ctx.cacheDir, "scanbench_$n").apply { deleteRecursively(); mkdirs() }
        try {
            genTree(root, n)
            val selects = AtomicInteger()
            val writes = AtomicInteger()
            val seenSql = java.util.Collections.synchronizedSet(mutableSetOf<String>())
            val db = Room.inMemoryDatabaseBuilder(ctx, LibraryDb::class.java)
                .setQueryCallback(
                    { sql, _ ->
                        val s = sql.trimStart().uppercase()
                        when {
                            s.startsWith("SELECT") -> {
                                selects.incrementAndGet()
                                if (seenSql.size < 12) seenSql.add(s.take(160))
                            }
                            s.startsWith("INSERT") || s.startsWith("UPDATE") ||
                                s.startsWith("DELETE") -> writes.incrementAndGet()
                        }
                    },
                    Executor { it.run() },
                )
                .build()
            try {
                val t0 = System.nanoTime()
                val (added, updated, _) = scanTimed(db, root)
                val ms = (System.nanoTime() - t0) / 1_000_000.0
                val s1 = selects.get()
                val w1 = writes.get()
                val t1 = System.nanoTime()
                val (added2, updated2, _) = scanTimed(db, root)
                val ms2 = (System.nanoTime() - t1) / 1_000_000.0
                println("BENCH scan $n files: pass1=${"%.0f".format(ms)}ms " +
                    "selects=${selects.get()} writes=${writes.get()} | " +
                    "pass2=${"%.0f".format(ms2)}ms " +
                    "selects=${selects.get() - s1} writes=${writes.get() - w1}")
                if (n <= 100) seenSql.forEach { println("BENCH-SQL $it") }
                assertEquals("all $n files must scan in", n, added)
                assertEquals(0, updated)
                assertEquals("steady-state pass adds nothing", 0, added2)
                assertEquals(0, updated2)
                assertTrue(
                    "pass1 issued ${selects.get()} SELECTs for $n files, want <= 8",
                    selects.get() <= 8,
                )
                assertTrue(
                    "pass1 took ${"%.0f".format(ms)}ms for $n files, bound 120000ms",
                    ms < 120_000,
                )
            } finally {
                db.close()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun benchScan100() = benchSize(100)

    @Test fun benchScan1000() = benchSize(1_000)

    @Test fun benchScan10000() = benchSize(10_000)
}
