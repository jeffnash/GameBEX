package com.gamebex.client.data.local

import com.gamebex.client.data.PlatformRegistry
import com.gamebex.client.data.RomEntry

/**
 * Normalized title for catalog matching: region tags, case and punctuation
 * never distinguish two releases of one game.
 */
fun normTitle(t: String): String =
    t.replace(Regex("\\([^)]*\\)"), "")
        .lowercase()
        .filter { it.isLetterOrDigit() }

/** Console display name ("Nintendo DS") → library folder ("nds"), if known. */
fun folderForConsoleName(name: String?): String? {
    if (name.isNullOrBlank()) return null
    return PlatformRegistry.all.firstOrNull { it.display.equals(name.trim(), ignoreCase = true) }?.folder
}

/** GameBEX console slug ("game-boy") → library folder ("gb"), if known. */
fun folderForConsoleSlug(slug: String?): String? {
    if (slug.isNullOrBlank()) return null
    return PlatformRegistry.all.firstOrNull { it.slug.equals(slug.trim(), ignoreCase = true) }?.folder
}

/** Library folder ("gb") → GameBEX console slug ("game-boy"), if known. */
fun consoleSlugForFolder(folder: String): String? =
    PlatformRegistry.all.firstOrNull { it.folder == folder }?.slug

/**
 * Best source catalog hit for a local row: the normalized title must match
 * exactly AND the hit's platform must equal the row's folder. No
 * runner-up: a title-exact hit on another platform is a MISS, never
 * borrowed art (an Arcade box on a Game Boy page is worse than no art).
 */
fun matchSourceEntry(title: String, folder: String, entries: List<RomEntry>): RomEntry? {
    if (entries.isEmpty()) return null
    val want = normTitle(title)
    if (want.isBlank()) return null
    return entries.firstOrNull { e ->
        normTitle(e.title) == want && e.platformId == folder
    }
}
