package com.gamebex.client.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** Search list section: idle/loading/results/empty/failure are distinct. */
class SearchStateTest {
    @Test fun failureWinsOverEverything() {
        assertEquals(SearchState.FAILURE, resolveSearchState("mario", true, 3, "boom"))
    }

    @Test fun searchingIsLoading() {
        assertEquals(SearchState.LOADING, resolveSearchState("mario", true, 0, null))
    }

    @Test fun resultsShownWhenPresent() {
        assertEquals(SearchState.RESULTS, resolveSearchState("mario", false, 2, null))
    }

    @Test fun blankQueryWithoutResultsIsIdle() {
        assertEquals(SearchState.IDLE, resolveSearchState("  ", false, 0, null))
    }

    @Test fun queryWithoutResultsIsEmpty() {
        assertEquals(SearchState.EMPTY, resolveSearchState("zzz-no-such", false, 0, null))
    }
}
