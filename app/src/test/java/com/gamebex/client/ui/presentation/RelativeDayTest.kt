package com.gamebex.client.ui.presentation

import org.junit.Assert.assertEquals
import org.junit.Test

/** [relativeDay] / [relativeDayLabel] formatting. */
class RelativeDayTest {

    private val day = 24 * 60 * 60 * 1000L
    private val now = 1_800_000_000_000L
    private fun ago(days: Long) = relativeDay(now - days * day, now)

    @Test fun relativeDayPluralsAndUnits() {
        assertEquals("today", ago(0))
        assertEquals("yesterday", ago(1))
        assertEquals("2 days ago", ago(2))
        assertEquals("6 days ago", ago(6))
        assertEquals("1 week ago", ago(7))
        assertEquals("7 weeks ago", ago(55))
        assertEquals("2 months ago", ago(56))
        assertEquals("6 months ago", ago(185))
        assertEquals("11 months ago", ago(364))
        assertEquals("a year ago", ago(365))
        assertEquals("2 years ago", ago(730))
    }

    @Test fun futureStampsNeverGoNegative() {
        assertEquals("today", relativeDay(now + day / 2, now))
        assertEquals("recently", relativeDay(now + 30 * day, now))
    }

    @Test fun standaloneFormIsCapitalized() {
        assertEquals("Today", relativeDayLabel(now, now))
        assertEquals("Yesterday", relativeDayLabel(now - day, now))
        assertEquals("3 days ago", relativeDayLabel(now - 3 * day, now))
        assertEquals("A year ago", relativeDayLabel(now - 400 * day, now))
    }
}
