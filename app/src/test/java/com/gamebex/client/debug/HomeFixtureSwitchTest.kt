package com.gamebex.client.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The fixture switch decision is pure (`fixtureSwitchFor`); these pin its
 * three branches. The equal-query branch keeps `searchnoplugin` reachable:
 * without it the search field's initial re-assert of "sonic" swaps the
 * fixture to `search`.
 */
class HomeFixtureSwitchTest {

    @Test
    fun queryEqualToTheCurrentFixtureQueryIsIgnored() {
        assertNull(fixtureSwitchFor("sonic", "sonic"))
        assertNull(fixtureSwitchFor("", ""))
    }

    @Test
    fun differentNonBlankQuerySwitchesToTheSearchFixture() {
        assertEquals("search", fixtureSwitchFor("zelda", "sonic"))
        assertEquals("search", fixtureSwitchFor("sonic", ""))
    }

    @Test
    fun blankQuerySwitchesToTheNormalFixture() {
        assertEquals("normal", fixtureSwitchFor("", "sonic"))
        assertEquals("normal", fixtureSwitchFor("   ", "sonic"))
    }
}
