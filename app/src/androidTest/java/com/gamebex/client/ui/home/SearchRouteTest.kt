package com.gamebex.client.ui.home

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gamebex.client.SEARCH_ARG_QUERY
import com.gamebex.client.SEARCH_ROUTE
import com.gamebex.client.isSearchRoute
import com.gamebex.client.searchRouteFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * "See every result" pre-fills Search. The optional query lives in the
 * route pattern (`search?initialQuery={initialQuery}`); without it,
 * navigating with a query never matches and `initialQuery` never reaches
 * SearchViewModel's SavedStateHandle. The graph below reuses MainActivity's
 * exported route constants and builder, so the declaration can't drift from
 * the test.
 */
@RunWith(AndroidJUnit4::class)
class SearchRouteTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun seeEveryResultRouteDeliversTheInitialQuery() {
        var seen: String? = null
        lateinit var nav: androidx.navigation.NavHostController
        compose.setContent {
            nav = rememberNavController()
            NavHost(nav, startDestination = "home") {
                composable("home") {}
                composable(
                    SEARCH_ROUTE,
                    arguments = listOf(navArgument(SEARCH_ARG_QUERY) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    }),
                ) { entry ->
                    seen = entry.arguments?.getString(SEARCH_ARG_QUERY)
                }
            }
        }
        compose.runOnIdle { nav.navigate(searchRouteFor("mario the hedgehog")) }
        compose.waitUntil(WAIT_MS) { seen == "mario the hedgehog" }
        assertEquals("mario the hedgehog", seen)
    }

    @Test
    fun bareSearchFromDiscoverStillMatches() {
        var reached = false
        lateinit var nav: androidx.navigation.NavHostController
        compose.setContent {
            nav = rememberNavController()
            NavHost(nav, startDestination = "home") {
                composable("home") {}
                composable(
                    SEARCH_ROUTE,
                    arguments = listOf(navArgument(SEARCH_ARG_QUERY) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    }),
                ) { entry ->
                    reached = true
                    assertEquals(null, entry.arguments?.getString(SEARCH_ARG_QUERY))
                }
            }
        }
        compose.runOnIdle { nav.navigate("search") }
        compose.waitUntil(WAIT_MS) { reached }
    }

    @Test
    fun searchRouteMatchingRecognizesThePattern() {
        assertTrue(isSearchRoute(SEARCH_ROUTE))
        assertTrue(isSearchRoute("search?initialQuery=mario"))
        assertFalse(isSearchRoute("sources"))
        assertFalse(isSearchRoute(null))
    }

    private companion object {
        const val WAIT_MS = 15_000L
    }
}
