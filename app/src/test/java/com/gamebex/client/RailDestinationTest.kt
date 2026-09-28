package com.gamebex.client

import com.gamebex.client.ui.console.ConsoleDestination
import org.junit.Assert.assertEquals
import org.junit.Test

class RailDestinationTest {
    @Test fun topLevelRoutesMapToTheirTab() {
        assertEquals(ConsoleDestination.Home, consoleDestinationFor("library"))
        assertEquals(ConsoleDestination.Library, consoleDestinationFor("allgames"))
        assertEquals(ConsoleDestination.Discover, consoleDestinationFor("detail/x"))
        assertEquals(ConsoleDestination.System, consoleDestinationFor("emulators"))
    }

    @Test fun theTabIsTheRootThatOwnsTheBackStack() {
        fun dest(owner: String, vararg stack: String) = consoleDestinationFor(tabOwning(owner) { it in stack })
        assertEquals(ConsoleDestination.Home, dest("library", "library"))
        assertEquals(ConsoleDestination.Home, dest("library", "library", "search"))
        assertEquals(ConsoleDestination.Home, dest("library", "library", "local/{gameId}", "together/guest"))
        assertEquals(ConsoleDestination.Discover, dest("browse", "library", "browse", "detail/{entry}", "sources"))
        assertEquals(ConsoleDestination.Library, dest("allgames", "library", "allgames", "local/{gameId}"))
        assertEquals(ConsoleDestination.System, dest("emulators", "library", "emulators", "together", "together/guest"))
        // A System page pushed from a Library session screen still belongs to Library.
        assertEquals(ConsoleDestination.Library, dest("allgames", "library", "allgames", "local/{gameId}", "together/host/{gameId}", "emulators"))
        // Back out of a tab root: the owner is no longer on the stack, so Home owns it.
        assertEquals(ConsoleDestination.Home, dest("allgames", "library", "local/{gameId}"))
    }

    @Test fun bareGamePageFallsBackToHome() {
        assertEquals(ConsoleDestination.Home, consoleDestinationFor("local/{gameId}"))
    }
}
