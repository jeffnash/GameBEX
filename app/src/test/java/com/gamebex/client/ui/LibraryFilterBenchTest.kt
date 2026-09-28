package com.gamebex.client.ui

import com.gamebex.client.data.local.GameEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Perf harness: times the All Games filter+sort projection over a
 * generated 10,000-row library. Prints per-case ms to stdout so CI logs
 * carry the measurement; the assertion is a generous
 * no-pathological-regression bound, not a tight perf gate.
 */
class LibraryFilterBenchTest {
    private val rows: List<GameEntity> = List(10_000) { i ->
        GameEntity(
            id = i.toLong() + 1,
            title = "Game ${i % 500} Deluxe Edition",
            folder = listOf("snes", "gba", "psx", "genesis")[i % 4],
            volumeId = "sd:TEST",
            relPath = "snes/game_$i.sfc",
            fileName = "game_$i.sfc",
            sizeBytes = (i * 9973L) % 4_000_000_000L,
            mtimeMs = 1_700_000_000_000L + i * 1_000L,
            ext = "sfc",
            favorite = i % 7 == 0,
            playCount = i % 11,
            lastPlayedMs = 1_700_000_000_000L + (i % 1000) * 60_000L,
            addedAtMs = 1_700_000_000_000L + i * 500L,
            available = i % 13 != 0,
        )
    }

    private fun timeMs(warmup: Int = 2, runs: Int = 5, block: () -> List<GameEntity>): Double {
        repeat(warmup) { block() }
        val ts = List(runs) {
            val t0 = System.nanoTime()
            val out = block()
            // Defeat dead-code elimination: consume the result.
            if (out.isEmpty() && rows.isNotEmpty()) throw AssertionError("empty")
            (System.nanoTime() - t0) / 1_000_000.0
        }
        return ts.average()
    }

    @Test fun benchFilterSort10k() {
        val recent = timeMs { filterSortGames(rows, "", null, false, LibSort.Recent) }
        val query = timeMs { filterSortGames(rows, "game 42", null, false, LibSort.Title) }
        val folderFav = timeMs { filterSortGames(rows, "", "gba", true, LibSort.System) }
        val size = timeMs { filterSortGames(rows, "game", null, false, LibSort.Size) }
        println("BENCH filterSort 10k rows: recent=${"%.1f".format(recent)}ms " +
            "query=${"%.1f".format(query)}ms folderFav=${"%.1f".format(folderFav)}ms " +
            "size=${"%.1f".format(size)}ms")
        val worst = maxOf(recent, query, folderFav, size)
        assertTrue("filter+sort 10k rows took ${"%.0f".format(worst)}ms, bound 2000ms", worst < 2_000)
    }

    @Test fun filterSortStaysCorrect() {
        val out = filterSortGames(rows, "game 1", "snes", false, LibSort.Title)
        assertTrue(out.isNotEmpty())
        assertTrue(out.all { it.available && it.folder == "snes" && it.title.contains("game 1", true) })
        assertEquals(out.sortedBy { it.title.lowercase() }.map { it.id }, out.map { it.id })
    }
}
