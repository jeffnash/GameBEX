package com.gamebex.client.together.session

import com.gamebex.client.data.local.GameEntity
import com.gamebex.client.net.RomFingerprint
import com.gamebex.client.together.MatchResult
import com.gamebex.client.together.OfferedGame
import com.gamebex.client.together.TogetherMode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class GameMatcherTest {
    @Test
    fun sourceMatchBeatsFileNameMatch() = runTest {
        val source = game(1, title = "Source", relPath = "source.zip", sourceId = "shop", itemKey = "1")
        val file = game(2, title = "File", relPath = "shared.zip")
        val result = matcher(listOf(file, source), mapOf(1L to fp("GOOD"), 2L to fp("BAD")))
            .match(offer(sourceId = "shop", itemKey = "1", fileName = "shared.zip", crc = "GOOD"), TogetherMode.PSP_ADHOC, false)
        assertEquals(MatchResult.Found(1), result)
    }

    @Test
    fun crcEqualityReturnsFound() = runTest {
        val row = game(1)
        val result = matcher(listOf(row), mapOf(1L to fp("ABCD")))
            .match(offer(crc = "ABCD"), TogetherMode.PSP_ADHOC, false)
        assertEquals(MatchResult.Found(1), result)
    }

    @Test
    fun sameTitleDifferentCrcReturnsMismatchFirstReachable() = runTest {
        val row = game(7, title = "Same title")
        val result = matcher(listOf(row), mapOf(7L to fp("OTHER")))
            .match(offer(title = "Same title", crc = "WANTED"), TogetherMode.PSP_ADHOC, false)
        assertEquals(MatchResult.Mismatch(7), result)
    }

    @Test
    fun noCandidatesReturnsMissingWithDownloadablePassedThrough() = runTest {
        val result = matcher(emptyList(), emptyMap())
            .match(offer(), TogetherMode.PSP_ADHOC, true)
        assertEquals(MatchResult.Missing(true), result)
    }

    @Test
    fun moreThanFiveCandidatesHashesAtMostFive() = runTest {
        val rows = (1L..6L).map { game(it, title = "Same") }
        var fingerprints = 0
        val matcher = GameMatcher(
            rows = { rows },
            fingerprint = { fingerprints++; fp("OTHER") },
            launchFileLength = { 1L },
        )
        val result = matcher.match(offer(title = "Same", crc = "WANTED"), TogetherMode.PSP_ADHOC, false)
        assertEquals(MatchResult.Mismatch(1), result)
        assertEquals(5, fingerprints)
    }

    @Test
    fun crcNullFallsBackToFileLength() = runTest {
        val row = game(1)
        val result = matcher(listOf(row), mapOf(1L to null), lengths = mapOf(1L to 42L))
            .match(offer(crc = null, fileSize = 42L), TogetherMode.PSP_ADHOC, false)
        assertEquals(MatchResult.Found(1), result)
    }

    @Test
    fun unreachableCandidateIsSkipped() = runTest {
        val unreachable = game(1, title = "Same", sourceId = "shop", itemKey = "1")
        val reachable = game(2, title = "Same")
        val result = GameMatcher(
            rows = { listOf(unreachable, reachable) },
            fingerprint = { fp("OTHER") },
            launchFileLength = { if (it.id == 1L) null else 10L },
        ).match(offer(title = "Same", sourceId = "shop", itemKey = "1", crc = "WANTED"), TogetherMode.PSP_ADHOC, false)
        assertEquals(MatchResult.Mismatch(2), result)
    }

    private fun matcher(
        rows: List<GameEntity>,
        fingerprints: Map<Long, RomFingerprint?>,
        lengths: Map<Long, Long?> = rows.associate { it.id to it.sizeBytes },
    ) = GameMatcher(
        rows = { rows },
        fingerprint = { fingerprints[it.id] },
        launchFileLength = { lengths[it.id] },
    )

    private fun game(
        id: Long,
        title: String = "Game",
        relPath: String = "game.zip",
        sourceId: String? = null,
        itemKey: String? = null,
    ) = GameEntity(
        id = id,
        title = title,
        folder = "psp",
        volumeId = "internal",
        relPath = relPath,
        fileName = relPath.substringAfterLast('/'),
        sizeBytes = 42,
        mtimeMs = 1,
        ext = "zip",
        source = if (sourceId == null) "USER" else "SOURCE",
        sourceId = sourceId,
        sourceItemKey = itemKey,
    )

    private fun fp(crc: String) = RomFingerprint(crc, 42, "game.zip")

    private fun offer(
        title: String = "Game",
        fileName: String = "game.zip",
        fileSize: Long = 42,
        crc: String? = "GOOD",
        sourceId: String? = null,
        itemKey: String? = null,
    ) = OfferedGame(
        title = title,
        folder = "psp",
        fileName = fileName,
        fileSize = fileSize,
        crc = crc,
        romSize = 42,
        sourceId = sourceId,
        sourceItemKey = itemKey,
    )
}
