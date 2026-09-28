package com.gamebex.client.ui.text

import java.util.Locale

/**
 * Shared title helpers (the No-Intro article move and the initials
 * fallback) living outside `ui/home/model/HomeCopy`, so `ui/console` can
 * use them without depending on the home model. Pure.
 */

private val TRAILING_ARTICLE = Regex("""^(.*?),\s*(The|An|A)(\s*[-:].*)?$""", RegexOption.IGNORE_CASE)
private val LEADING_ARTICLES = setOf("the", "a", "an")

/**
 * "Legend of Zelda, The" → "The Legend of Zelda" (also "A" and "An",
 * keeping any "- Game Subtitle" suffix: "Legend of Zelda, The - A Link
 * to the Past" → "The Legend of Zelda - A Link to the Past"; No-Intro
 * form). Used for case titles and search.
 */
fun displayTitle(title: String): String {
    val t = title.trim()
    val m = TRAILING_ARTICLE.matchEntire(t) ?: return t
    return "${m.groupValues[2]} ${m.groupValues[1]}${m.groupValues[3]}".trim()
}

/**
 * Up to two initials from a title, skipping leading articles ("The Legend
 * of Zelda" → "LO"). Code points, not chars, so surrogate pairs (𝐀, emoji
 * scripts) survive intact. A title with no usable initials (blank,
 * whitespace-only, punctuation) falls back to [fallback] — the game's
 * system tag — else "?".
 */
fun initialsOf(title: String, fallback: String? = null): String {
    val words = title.split(Regex("[^\\p{L}\\p{N}]+"))
        .filter { it.isNotEmpty() }
        .dropWhile { it.lowercase(Locale.ENGLISH) in LEADING_ARTICLES }
    val marks = words.take(2).map { w ->
        String(Character.toChars(w.codePointAt(0))).uppercase(Locale.ENGLISH)
    }
    return if (marks.isEmpty()) fallback ?: "?" else marks.joinToString("")
}
