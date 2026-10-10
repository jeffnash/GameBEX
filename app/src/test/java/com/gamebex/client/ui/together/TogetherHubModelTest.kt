package com.gamebex.client.ui.together

import com.gamebex.client.data.local.GameEntity
import com.gamebex.client.together.TogetherCopy
import com.gamebex.client.together.TogetherMode
import com.gamebex.client.together.TogetherSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TogetherHubModelTest {
    private fun game(
        id: Long,
        folder: String,
        title: String = "Game $id",
        lastPlayedMs: Long = 0,
        coreOverride: String? = null,
        available: Boolean = true,
        deletedAtMs: Long = 0,
    ) = GameEntity(
        id = id, title = title, folder = folder, volumeId = "v", relPath = "r$id", fileName = "x", sizeBytes = 1, mtimeMs = 1, ext = "bin",
        lastPlayedMs = lastPlayedMs, coreOverride = coreOverride, available = available, deletedAtMs = deletedAtMs,
    )

    @Test fun onlyGamesTheGamePageOffers2PForAreListed() {
        val games = listOf(
            game(1, "snes"),
            game(2, "gba"),
            game(3, "psp"),
            game(4, "snes", coreOverride = "bsnes"),
            game(5, "n64"),
            game(6, "snes", available = false),
            game(7, "snes", deletedAtMs = 1),
            game(8, "gb"),
        )
        val listed = hostableGames(games)
        // The gba game and the bsnes-overridden snes game offer STREAM,
        // so only the unavailable/deleted rows stay unlisted.
        assertEquals(setOf(1L, 2L, 3L, 4L, 5L, 8L), listed.map { it.id }.toSet())
        for (row in listed) assertEquals(TogetherSupport.modeFor(games.first { it.id == row.id }), row.mode)
    }

    @Test fun mostRecentlyPlayedComeFirstThenTitles() {
        val listed = hostableGames(
            listOf(
                game(1, "snes", title = "zelda"),
                game(2, "snes", title = "Mario", lastPlayedMs = 100),
                game(3, "nes", title = "Contra", lastPlayedMs = 300),
                game(4, "psp", title = "Ape Escape"),
                game(5, "gb", title = "Bomberman"),
            ),
        )
        assertEquals(listOf("Contra", "Mario", "Ape Escape", "Bomberman", "zelda"), listed.map { it.title })
    }

    @Test fun rowsShowTheSystemAndTheMode() {
        val (snes, gb, psp, ps2) = hostableGames(
            listOf(
                game(1, "snes", lastPlayedMs = 4),
                game(2, "gbc", lastPlayedMs = 3),
                game(3, "psp", lastPlayedMs = 2),
                game(4, "ps2", lastPlayedMs = 1),
            ),
        )
        assertEquals("SNES · RetroArch netplay", hostRowLine(snes))
        assertEquals("Game Boy Color · Game Boy link", hostRowLine(gb))
        assertEquals("PSP · PPSSPP ad hoc", hostRowLine(psp))
        assertEquals("PlayStation 2 · Stream", hostRowLine(ps2))
    }

    @Test fun theEmptyLineNamesOnlySystemsThatCanReallyBeHosted() {
        // Every system the empty line names must have a mode today, or the line is wrong.
        val named = mapOf(
            "NES" to "nes", "SNES" to "snes", "Genesis" to "genesis", "Game Boy" to "gb",
            "PSP" to "psp", "PlayStation" to "psx", "N64" to "n64", "GameCube" to "gc",
        )
        for ((name, folder) in named) {
            assertTrue("$name is named", TogetherCopy.hostEmpty.contains(name))
            assertNotNull("$name can be hosted", TogetherSupport.modeFor(game(1, folder)))
        }
    }

    @Test fun anEmptyListHandsTheCursorOn() {
        assertEquals(HubZone.HOST, TogetherHubFocus.resolve(HubFocus(HubZone.HOST), 2, 2).zone)
        assertEquals(HubZone.NEARBY, TogetherHubFocus.resolve(HubFocus(HubZone.HOST), 0, 2).zone)
        assertEquals(HubZone.ADDRESS, TogetherHubFocus.resolve(HubFocus(HubZone.HOST), 0, 0).zone)
        assertEquals(HubZone.ADDRESS, TogetherHubFocus.resolve(HubFocus(HubZone.NEARBY), 3, 0).zone)
        assertEquals(HubZone.ADDRESS, TogetherHubFocus.resolve(HubFocus(HubZone.ADDRESS), 3, 3).zone)
    }

    @Test fun rowsClampWhenAListShrinks() {
        val at = TogetherHubFocus.resolve(HubFocus(HubZone.HOST, host = 9, nearby = 9), 3, 2)
        assertEquals(2, at.host)
        assertEquals(1, at.nearby)
    }

    @Test fun leftAndRightCrossBetweenHostingAndJoining() {
        val host = HubFocus(HubZone.HOST, host = 1)
        assertEquals(HubZone.NEARBY, TogetherHubFocus.right(host, 3, 2).zone)
        assertEquals(HubZone.ADDRESS, TogetherHubFocus.right(host, 3, 0).zone)
        // Rows are remembered across the move.
        assertEquals(1, TogetherHubFocus.left(TogetherHubFocus.right(host, 3, 2), 3, 2)!!.host)
        assertEquals(HubZone.HOST, TogetherHubFocus.left(HubFocus(HubZone.ADDRESS), 3, 0)!!.zone)
        // The right column stays put on Right.
        assertEquals(HubFocus(HubZone.NEARBY), TogetherHubFocus.right(HubFocus(HubZone.NEARBY), 3, 2))
    }

    @Test fun leftFromTheHostListOrWithNothingToHostLeavesForTheRail() {
        assertNull(TogetherHubFocus.left(HubFocus(HubZone.HOST), 3, 2))
        assertNull(TogetherHubFocus.left(HubFocus(HubZone.NEARBY), 0, 2))
        assertNull(TogetherHubFocus.left(HubFocus(HubZone.ADDRESS), 0, 0))
    }

    @Test fun upAndDownWalkTheHostListAndStopAtItsEnds() {
        assertEquals(1, TogetherHubFocus.down(HubFocus(HubZone.HOST), 3, 0).host)
        assertEquals(2, TogetherHubFocus.down(HubFocus(HubZone.HOST, host = 2), 3, 0).host)
        assertEquals(HubZone.HOST, TogetherHubFocus.down(HubFocus(HubZone.HOST, host = 2), 3, 0).zone)
        assertEquals(0, TogetherHubFocus.up(HubFocus(HubZone.HOST), 3, 0).host)
    }

    @Test fun downPastTheLastNearbyRowReachesTheAddressButtonAndUpComesBack() {
        val last = HubFocus(HubZone.NEARBY, nearby = 1)
        val address = TogetherHubFocus.down(last, 0, 2)
        assertEquals(HubZone.ADDRESS, address.zone)
        assertEquals(HubFocus(HubZone.NEARBY, nearby = 1), TogetherHubFocus.up(address, 0, 2))
        assertEquals(1, TogetherHubFocus.down(HubFocus(HubZone.NEARBY), 0, 2).nearby)
        // No one nearby: Up from the address button stays.
        assertEquals(HubZone.ADDRESS, TogetherHubFocus.up(HubFocus(HubZone.ADDRESS), 0, 0).zone)
        assertEquals(HubZone.ADDRESS, TogetherHubFocus.down(HubFocus(HubZone.ADDRESS), 0, 2).zone)
    }

    @Test fun aHintNamesWhatAWillDo() {
        assertEquals(TogetherCopy.hostAction, TogetherHubFocus.aLabel(HubZone.HOST))
        assertEquals(TogetherCopy.join, TogetherHubFocus.aLabel(HubZone.NEARBY))
        assertEquals(TogetherCopy.joinByAddress, TogetherHubFocus.aLabel(HubZone.ADDRESS))
    }

    @Test fun modesAreTheGamePageDefault() {
        assertEquals(TogetherMode.GB_LINK, hostableGames(listOf(game(1, "gb"))).single().mode)
        assertEquals(TogetherMode.STREAM, hostableGames(listOf(game(1, "psx"))).single().mode)
    }
}
