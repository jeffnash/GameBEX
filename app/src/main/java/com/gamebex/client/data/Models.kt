package com.gamebex.client.data

import kotlinx.serialization.json.Json

/** Shared lenient JSON reader for generic app payloads (BIOS docs, seeds). */
val AppJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
}

// Aliases so `data.*` imports resolve to the source runtime's shared DTOs.
typealias RomEntry = com.gamebex.source.RomEntry
typealias RomDetail = com.gamebex.source.RomDetail
typealias DownloadFile = com.gamebex.source.DownloadFile
typealias ConsoleItem = com.gamebex.source.ConsoleItem
typealias TypeItem = com.gamebex.source.TypeItem

/** Archive exts the prep pipeline unpacks. */
val DOWNLOAD_ARCHIVE_EXTS = setOf("zip", "7z", "rar")

/**
 * Catalog download filename: preserve the source name whenever it carries
 * a plausible extension (archive, or short alphanumeric like .iso/.cue/
 * .7z.001 parts); only extensionless/bare names default to .zip. Never
 * stack a second extension onto a real one ("game.7z" stays "game.7z").
 */
fun downloadFileNameFor(source: String): String {
    val safe = source.replace('/', '_').trim().trim('.')
    if (safe.isEmpty()) return "rom.zip"
    val ext = safe.substringAfterLast('.', "")
    if (ext.isEmpty()) return "$safe.zip"
    val plausible = ext.length <= 6 && ext.all { it.isLetterOrDigit() }
    return if (plausible) safe else "$safe.zip"
}

fun shouldPromptForChoice(fileCount: Int, hasPick: Boolean): Boolean =
    !hasPick && fileCount > 1

private val stableKeyJson = Json { encodeDefaults = true }

/**
 * String stable id for catalog rows: JSON-encoded SourceKey under an
 * explicit catalog prefix (never ambiguous-separator concatenation).
 * Local rows keep `local:<gameId>`.
 */
fun com.gamebex.source.SourceKey.toStableId(): String =
    "catalog:" + stableKeyJson.encodeToString(com.gamebex.source.SourceKey.serializer(), this)

fun String.htmlDecoded(): String = this
    .replace("&#8217;", "'").replace("&#8211;", "–").replace("&#038;", "&")
    .replace("&#38;", "&").replace("&amp;", "&").replace("&quot;", "\"")
    .replace("&#039;", "'").replace("&lt;", "<").replace("&gt;", ">")
    .replace("&#8212;", "—").replace("&#8220;", "\"").replace("&#8221;", "\"")
