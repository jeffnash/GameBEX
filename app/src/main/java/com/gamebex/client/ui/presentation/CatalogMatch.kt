package com.gamebex.client.ui.presentation

import com.gamebex.client.data.PlatformRegistry
import com.gamebex.client.data.RomEntry
import com.gamebex.client.data.local.GameEntity
import com.gamebex.client.data.local.normTitle
import com.gamebex.source.SourceKey

/**
 * Catalog ↔ library identity: deterministic client-side matching
 * so Discover can show honest Play/Get status. Match strength, strongest
 * first — every branch is safe in the false-negative direction (shows Get
 * and opens catalog detail; never boots the wrong local file):
 * 1. exact SourceKey (source-downloaded rows)
 * 2. normalized title + folder (USER-imported rows only; unique or none)
 *
 * Tombstones never count as owned: an entry whose only rows are
 * deleted maps to [CatalogMatch.DownloadAgain] ("Download again"), so
 * Discover and Search never offer Play on a missing file.
 */

/** Identity outcome for one catalog entry. */
sealed interface CatalogMatch {
    /** A local row the player owns (file present, or absent media kept). */
    data class Owned(val game: GameEntity) : CatalogMatch
    /** Tombstone: deleted source game kept for "Download again". */
    data class DownloadAgain(val game: GameEntity) : CatalogMatch
}

/** Entry → library folder: the source's platform id, validated. */
fun folderForCatalogEntry(e: RomEntry): String? =
    e.platformId?.takeIf { PlatformRegistry.byFolder.containsKey(it) }

/**
 * Newest-first pick: available rows win, ties break by row id. A deleted
 * row (`deletedAtMs > 0`) is never an owned fallback; when every row is
 * deleted the entry maps to [CatalogMatch.DownloadAgain] instead.
 */
private fun pick(rows: List<GameEntity>): CatalogMatch {
    rows.filter { it.available }.maxByOrNull { it.id }?.let { return CatalogMatch.Owned(it) }
    rows.filter { it.deletedAtMs <= 0 }.maxByOrNull { it.id }?.let { return CatalogMatch.Owned(it) }
    return CatalogMatch.DownloadAgain(rows.maxBy { it.id })
}

/**
 * @param browseFolder library folder of the browsed console feed, or null in
 * the mixed Latest feed (then per-entry [folderForCatalogEntry] applies).
 * @return local row keyed by catalog [SourceKey].
 */
fun matchCatalog(
    entries: List<RomEntry>,
    games: List<GameEntity>,
    browseFolder: String?,
): Map<SourceKey, CatalogMatch> {
    if (entries.isEmpty() || games.isEmpty()) return emptyMap()
    val byKey = games.filter { it.sourceId != null && it.sourceItemKey != null }
        .groupBy { SourceKey(it.sourceId!!, it.sourceItemKey!!) }
        .mapValues { (_, v) -> pick(v) }
    val userByTitleFolder = games.filter { it.sourceId == null }
        .groupBy { normTitle(it.title) to it.folder }
    return buildMap {
        for (e in entries) {
            val direct = byKey[e.key]
            if (direct != null) {
                put(e.key, direct)
                continue
            }
            val folder = browseFolder ?: folderForCatalogEntry(e) ?: continue
            // Title-only is a guess: exactly one playable row, else no match.
            val candidates = userByTitleFolder[normTitle(e.title) to folder].orEmpty()
                .filter { it.available && it.deletedAtMs <= 0 }
            if (candidates.size == 1) put(e.key, CatalogMatch.Owned(candidates[0]))
        }
    }
}
