package com.gamebex.client.together.session

import com.gamebex.client.data.local.GameEntity
import com.gamebex.client.data.local.normTitle
import com.gamebex.client.net.RomFingerprint
import com.gamebex.client.together.MatchResult
import com.gamebex.client.together.OfferedGame
import com.gamebex.client.together.TogetherMode

/** Pure candidate ordering and file-identity matching for Play together. */
class GameMatcher(
    private val rows: suspend (folders: Set<String>) -> List<GameEntity>,
    private val fingerprint: suspend (GameEntity) -> RomFingerprint?,
    private val launchFileLength: suspend (GameEntity) -> Long?,
) {
    suspend fun match(offer: OfferedGame, mode: TogetherMode, downloadable: Boolean): MatchResult {
        val all = rows(com.gamebex.client.together.TogetherSupport.matchFolders(mode, offer.folder))
        val candidates = sequenceOf(
            all.filter {
                offer.sourceId != null && it.sourceId == offer.sourceId &&
                    it.sourceItemKey == offer.sourceItemKey
            },
            all.filter {
                (it.launchRelPath ?: it.relPath).substringAfterLast('/').equals(
                    offer.fileName,
                    ignoreCase = true,
                )
            },
            all.filter { normTitle(it.title) == normTitle(offer.title) },
        ).flatten().distinctBy { it.id }.take(5).toList()

        if (candidates.isEmpty()) return MatchResult.Missing(downloadable)

        var firstReachable: Long? = null
        for (candidate in candidates) {
            val length = launchFileLength(candidate) ?: continue
            if (firstReachable == null) firstReachable = candidate.id
            val sameFile = if (offer.crc != null) {
                fingerprint(candidate)?.let { it.crc == offer.crc && it.size == offer.romSize } == true
            } else {
                length == offer.fileSize
            }
            if (sameFile) return MatchResult.Found(candidate.id)
        }
        return firstReachable?.let { MatchResult.Mismatch(it) } ?: MatchResult.Missing(downloadable)
    }
}
