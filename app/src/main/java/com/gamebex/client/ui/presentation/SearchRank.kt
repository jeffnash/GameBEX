package com.gamebex.client.ui.presentation

import com.gamebex.client.data.RomEntry
import com.gamebex.client.data.local.normTitle

/**
 * Client-side search ranking. The WP REST `search` order buries the real
 * game under partial-word hits, so the client re-sorts every result page:
 * normalized exact match first, then prefix, then full word coverage in
 * order, then partial coverage. Within a tier, entries whose console matches
 * a query token ("64" → Nintendo 64) float above same-title ports on other
 * consoles. Unofficial-release markers (hack, demo, beta…) demote within
 * their tier — never filtered, just ranked below the legit release. Sort is
 * stable: ties keep server order.
 */
fun rankSearchResults(query: String, entries: List<RomEntry>): List<RomEntry> {
    if (entries.size < 2) return entries
    val q = queryWords(query)
    if (q.isEmpty()) return entries
    val qNorm = normTitle(query)
    return entries.mapIndexed { index, e ->
        Scored(scoreOf(q, qNorm, e), index, e)
    }.sortedWith(
        compareBy({ it.score.tier }, { it.score.consoleMiss }, { it.score.demoted }, { it.score.missing }, { it.index }),
    ).map { it.entry }
}

private val DEMOTE_WORDS = setOf(
    "hack", "mod", "demo", "sample", "prototype", "proto", "beta",
    "homebrew", "translation", "translated", "unlicensed", "bootleg",
    "multicart", "pirate", "kiosk", "debug",
)

private fun queryWords(s: String): List<String> =
    s.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }

private data class Score(val tier: Int, val consoleMiss: Int, val demoted: Int, val missing: Int)
private data class Scored(val score: Score, val index: Int, val entry: RomEntry)

private fun scoreOf(q: List<String>, qNorm: String, e: RomEntry): Score {
    val tNorm = normTitle(e.title)
    val t = queryWords(e.title)
    val demoted = if (t.any { it in DEMOTE_WORDS }) 1 else 0
    val consoleMiss = if (consoleHit(q, e)) 0 else 1
    if (qNorm.isNotBlank() && tNorm == qNorm) return Score(0, consoleMiss, demoted, 0)
    if (qNorm.isNotBlank() && tNorm.startsWith(qNorm)) return Score(1, consoleMiss, demoted, 0)
    var missing = 0
    var ti = 0
    var ordered = true
    for (w in q) {
        val anyHit = t.any { it.startsWith(w) }
        if (!anyHit) {
            missing++
            continue
        }
        var found = -1
        for (i in ti until t.size) {
            if (t[i].startsWith(w)) {
                found = i
                break
            }
        }
        if (found < 0) ordered = false else ti = found + 1
    }
    val tier = when {
        missing == 0 && ordered -> 2
        missing == 0 -> 3
        else -> 4 + missing
    }
    return Score(tier, consoleMiss, demoted, missing)
}

/** Typed console abbreviations that never appear in console names. */
private val CONSOLE_ALIASES = mapOf("n64" to "64")

/** True when a query token names the entry's console ("64", "ps2", "snes"). */
private fun consoleHit(q: List<String>, e: RomEntry): Boolean {
    val platform = e.platformId?.let { com.gamebex.client.data.PlatformRegistry.byFolder[it] }
    val consoleWords = (
        queryWords(e.consoleName.orEmpty()) +
            queryWords(platform?.display.orEmpty()) + queryWords(platform?.slug.orEmpty())
        )
        .filter { it.length > 1 || it.all(Char::isDigit) }
        .toSet()
    if (consoleWords.isEmpty()) return false
    val expanded = q + q.mapNotNull { CONSOLE_ALIASES[it] }
    return expanded.any { it in consoleWords }
}
