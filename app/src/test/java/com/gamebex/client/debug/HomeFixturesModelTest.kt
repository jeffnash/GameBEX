package com.gamebex.client.debug

import com.gamebex.client.data.PlayStates
import com.gamebex.client.ui.home.HomeCardFactory
import com.gamebex.client.ui.home.model.CardKey
import com.gamebex.client.ui.home.model.CardKind
import com.gamebex.client.ui.home.model.HomeCopy
import com.gamebex.client.ui.home.model.HomeGroup
import com.gamebex.client.ui.home.model.InviteKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fixtures are built through the real pipeline (`HomeGrouping.build`
 * + `HomeCardFactory`), pinning the Surprise section order and Roll again
 * span, and the "All downloads" tile ending every DOWNLOADING section.
 */
class HomeFixturesModelTest {

    private val now = 1_700_000_000_000L

    @Test
    fun surpriseModelHoldsSurpriseResumeDownloadingNewSectionsInOrderAndRollAgainCardHasSpanOne() {
        val model = HomeFixtures.model("surprise", now)
        assertEquals(
            listOf(HomeGroup.SURPRISE, HomeGroup.RESUME, HomeGroup.DOWNLOADING, HomeGroup.NEW),
            model.sections.map { it.group },
        )
        // The pick leads the SURPRISE row (and owns the default selection).
        assertEquals(CardKind.GAME, model.sections[0].cards.first().kind)
        val roll = model.sections[0].cards.single { it.key == CardKey.invite(InviteKind.ROLL_AGAIN) }
        assertEquals(1, roll.span)
    }

    @Test
    fun downloadingAllTileIsTitledAllDownloadsAndNeverAllN() {
        val tile = HomeCardFactory { PlayStates.Status.Ready }
            .allTile(HomeGroup.DOWNLOADING, total = 4, more = 3, stack = emptyList())
        assertEquals(HomeCopy.allDownloads, tile.title)
        assertNotEquals(HomeCopy.allTitle(4), tile.title)

        // The `downloading` fixture carries 4 jobs and must not read "All 4".
        val section = HomeFixtures.model("downloading", now)
            .sections.single { it.group == HomeGroup.DOWNLOADING }
        val all = section.cards.last()
        assertEquals(CardKind.ALL_TILE, all.kind)
        assertEquals(HomeCopy.allDownloads, all.title)
        assertNotEquals(HomeCopy.allTitle(4), all.title)
    }

    @Test
    fun everyFixtureStateWithADownloadingSectionEndsThatSectionWithTheAllTile() {
        var seen = 0
        for (state in HomeFixtures.states) {
            for (section in HomeFixtures.model(state, now).sections) {
                if (section.group != HomeGroup.DOWNLOADING) continue
                seen++
                assertEquals(
                    "$state's DOWNLOADING section must end with the ALL_TILE",
                    CardKind.ALL_TILE, section.cards.last().kind,
                )
            }
        }
        assertTrue("expected fixture states with a DOWNLOADING section", seen > 0)
    }

    @Test
    fun downloadingRowsAreEngineJobCardsKeyedByTheirDownloadId() {
        val section = HomeFixtures.model("normal", now)
            .sections.single { it.group == HomeGroup.DOWNLOADING }
        val job = section.cards.first()
        assertEquals(CardKey.download(101), job.key)
        assertEquals(101L, job.downloadId)
    }

    @Test
    fun searchAndSearchNoPluginFixturesKeepTheQuerySonicForTheAndroidTests() {
        assertEquals("sonic", HomeFixtures.model("search", now).query)
        assertEquals("sonic", HomeFixtures.model("searchnoplugin", now).query)
    }
}
