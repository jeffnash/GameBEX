package com.gamebex.client.ui.console

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The caller's `query` is only ignored when it equals the LAST
 * query this field sent (our own echo). "" and older sent values never
 * shield a real programmatic change.
 */
class ConsoleSearchFieldTest {

    @Test
    fun libraryClearFiltersEmptiesTheFieldEvenAfterOurOwnEmptySent() {
        // The field cleared once (sent ""), then the user typed "mario"
        // (lastSent "mario"). Library's "Clear filters" sets "" again — a
        // real external change, not an echo of the last send.
        assertTrue(adoptExternalQuery("", "mario", "mario"))
    }

    @Test
    fun searchRecentQueryPickFillsTheFieldEvenWhenTypedBefore() {
        // "sonic" was sent long ago (lastSent is "mario" now). Picking the
        // recent query "sonic" must fill the field.
        assertTrue(adoptExternalQuery("sonic", "mario", "mario"))
    }

    @Test
    fun onlyTheLastSentValueCountsAsAnEcho() {
        // An older sent value ("mari") must not shield a programmatic set;
        // only the LAST sent ("mario") is ours.
        assertTrue(adoptExternalQuery("mari", "mario", "mario"))
        assertFalse(adoptExternalQuery("mario", "mario", "mario"))
    }

    @Test
    fun ourOwnLatestEchoIsIgnored() {
        assertFalse(adoptExternalQuery("mario", "mari", "mario"))
    }

    @Test
    fun ourOwnClearEchoIsIgnored() {
        // We cleared (sent ""), the caller echoes "" — nothing to adopt.
        assertFalse(adoptExternalQuery("", "", ""))
    }

    @Test
    fun theValueAlreadyShownIsNoChange() {
        assertFalse(adoptExternalQuery("sonic", "sonic", "sonic"))
    }

    @Test
    fun foreignValuesAreAdopted() {
        // The fixture swaps states under the field ("zelda" -> "sonic").
        assertTrue(adoptExternalQuery("sonic", "zelda", "zelda"))
    }

    @Test
    fun anAdoptedQueryDoesNotBlockTheNextClear() {
        // The field was filled from outside (fixture state, recent pick)
        // while lastSent was still the initial "". The next external ""
        // must clear it, or the old text sits over an unfiltered grid.
        val filled = fieldAfterExternalQuery("sonic", "", "")
        assertEquals("sonic", filled.text)
        assertEquals("sonic", filled.lastSent)
        val cleared = fieldAfterExternalQuery("", filled.text, filled.lastSent)
        assertEquals("", cleared.text)
        assertEquals("", cleared.lastSent)
    }
}
