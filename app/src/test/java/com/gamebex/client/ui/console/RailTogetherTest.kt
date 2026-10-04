package com.gamebex.client.ui.console

import com.gamebex.client.consoleDestinationFor
import com.gamebex.client.tabOwning
import com.gamebex.client.together.TogetherCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Play together hub is a rail tab of its own, and still a System page when System opens it. */
class RailTogetherTest {
    @Test fun theRailOpensTheHubRouteJustAboveSystem() {
        assertEquals("together", ConsoleDestination.Together.route)
        assertEquals(ConsoleDestination.System.ordinal - 1, ConsoleDestination.Together.ordinal)
        // Home's header pill reads exactly like the rail cell.
        assertEquals(TogetherCopy.navLabel, ConsoleDestination.Together.label)
    }

    @Test fun everyRailCellFitsAboveTheHintBand() {
        // Cells are 72 tall from y 104; the hint band starts at HINT_TOP.
        assertTrue(104 + 72 * ConsoleDestination.entries.size <= ConsoleDesign.HINT_TOP)
    }

    @Test fun theHubLightsTogetherWhenItOwnsTheStack() {
        fun dest(owner: String, vararg stack: String) = consoleDestinationFor(tabOwning(owner) { it in stack })
        assertEquals(ConsoleDestination.Together, consoleDestinationFor("together"))
        assertEquals(ConsoleDestination.Together, dest("together", "library", "together"))
        // A session started from the hub stays under the Together tab.
        assertEquals(ConsoleDestination.Together, dest("together", "library", "together", "together/host/{gameId}"))
        assertEquals(ConsoleDestination.Together, dest("together", "library", "together", "together/guest", "emulators"))
        // System › Device › Play together pushes the hub onto System, which stays lit.
        assertEquals(ConsoleDestination.System, dest("emulators", "library", "emulators", "together"))
        // The hub left the stack: Home owns it again.
        assertEquals(ConsoleDestination.Home, dest("together", "library"))
    }
}
