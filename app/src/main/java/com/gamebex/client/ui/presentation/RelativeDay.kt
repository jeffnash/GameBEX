package com.gamebex.client.ui.presentation

/**
 * In-sentence relative day ("Last played 3 days ago"): lowercase "today" /
 * "yesterday", proper plurals, weeks up to ~8 weeks, then months, then
 * years. Future stamps (clock skew) within a day read "today"; further
 * out, "recently" (never a negative count).
 *
 * Shared by the game detail, Library and Downloads screens.
 */
fun relativeDay(whenMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    val day = 24 * 60 * 60 * 1000L
    val diff = nowMs - whenMs
    fun n(count: Long, unit: String) = if (count == 1L) "1 $unit ago" else "$count ${unit}s ago"
    val days = diff / day
    return when {
        diff < -day -> "recently"
        diff < day -> "today"
        diff < 2 * day -> "yesterday"
        days < 7 -> n(days, "day")
        days < 56 -> n(days / 7, "week")
        days < 365 -> n((days / 30).coerceIn(2, 11), "month")
        days < 730 -> "a year ago"
        else -> n(days / 365, "year")
    }
}

/** Standalone relative day (fact grids, list cells): "Today", "Yesterday", "3 days ago". */
fun relativeDayLabel(whenMs: Long, nowMs: Long = System.currentTimeMillis()): String =
    relativeDay(whenMs, nowMs).replaceFirstChar { it.uppercaseChar() }
