package com.gamebex.client.data

/**
 * Shared ROM-name cleanup (No-Intro / Redump / TOSEC style). Pure.
 * Scanner display titles and ScreenScraper search keys both start here.
 */
object TitleText {
    private val PAREN_TAG = Regex("\\([^)]*\\)")
    private val BRACKET_TAG = Regex("\\[[^]]*]")
    private val SPACES = Regex("\\s+")

    /** Drops "(USA)", "(Rev 1)", "[!]", "[T+Eng]"... tags; collapses spaces. */
    fun stripTags(name: String): String = name
        .replace(PAREN_TAG, " ")
        .replace(BRACKET_TAG, " ")
        .replace(SPACES, " ").trim()

    /**
     * Library display title from a file name WITHOUT its extension:
     * tags stripped, underscores as spaces, internal periods kept
     * ("Dr. Mario", "Super Mario Bros."). Dot-separated scene names with
     * no spaces ("Super.Mario.World") read as words. Falls back to [base]
     * when cleanup would leave nothing.
     */
    fun displayTitle(base: String): String {
        var s = base.replace('_', ' ')
        if (!s.contains(' ') && s.count { it == '.' } >= 2) s = s.replace('.', ' ')
        s = stripTags(s)
            // "Title , The" leftovers from stripped tags; dangling separators.
            .replace(Regex("\\s+([,;:])"), "$1")
            .trim(' ', '-', ',', ';', ':')
            .trim()
        return s.ifBlank { base.replace(SPACES, " ").trim().ifBlank { base } }
    }
}
