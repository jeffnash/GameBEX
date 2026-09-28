package com.gamebex.client.ui.home

import com.gamebex.client.ui.console.ControllerGlyph
import com.gamebex.client.ui.home.model.ArtRef
import com.gamebex.client.ui.home.model.CardKey
import com.gamebex.client.ui.home.model.CardKind
import com.gamebex.client.ui.home.model.CardState
import com.gamebex.client.ui.home.model.HeaderModel
import com.gamebex.client.ui.home.model.HomeAction
import com.gamebex.client.ui.home.model.HomeCard
import com.gamebex.client.ui.home.model.HomeCopy
import com.gamebex.client.ui.home.model.HomeDestination
import com.gamebex.client.ui.home.model.HomeGroup
import com.gamebex.client.ui.home.model.HomeIntent
import com.gamebex.client.ui.home.model.HomeMode
import com.gamebex.client.ui.home.model.HomeScreenModel
import com.gamebex.client.ui.home.model.HomeSection
import com.gamebex.client.ui.home.model.InviteKind
import com.gamebex.client.ui.home.model.SizeInfo
import com.gamebex.client.ui.home.model.TvContent
import com.gamebex.client.ui.home.model.TvModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure Home focus map: one test per table row, plus the touch
 * map, selection reconciliation and the hint strip.
 */
class HomeFocusTest {

    private val a1 = CardKey.local(1)
    private val a2 = CardKey.local(2)
    private val a3 = CardKey.local(3) // no secondary action
    private val b1 = CardKey.local(4) // span 2
    private val b2 = CardKey.local(5)
    private val c1 = CardKey.local(6)
    private val c2 = CardKey.local(7)
    private val roll = CardKey.invite(InviteKind.ROLL_AGAIN)

    private fun card(
        key: CardKey,
        span: Int = 1,
        secondary: Boolean = true,
        kind: CardKind = CardKind.GAME,
        state: CardState = CardState.None,
        primary: HomeAction = HomeAction.Play(1),
        primaryLabel: String = "Play",
    ) = HomeCard(
        key = key,
        kind = kind,
        title = key.value,
        cardLine = "",
        state = state,
        primary = primary,
        primaryLabel = primaryLabel,
        secondary = if (secondary) HomeAction.Go(HomeDestination.Downloads) else null,
        secondaryLabel = if (secondary) "Details" else null,
        tv = TvModel(TvContent.TestCard("t"), "S", null, null, null),
        span = span,
        position = 0,
    )

    private fun section(group: HomeGroup, vararg cards: HomeCard) =
        HomeSection(group, group.name, null, cards.toList())

    /**
     * Rows: `[a1,a2] [a3] [b1(span 2)] [b2] [c1,c2]` across three sections.
     */
    private fun home(
        mode: HomeMode = HomeMode.HOME,
        defaultKey: CardKey? = a1,
        sections: List<HomeSection> = defaultSections(),
    ) = HomeScreenModel(
        mode = mode,
        header = HeaderModel("Search", "Browse", null, 0),
        sections = sections,
        query = if (mode == HomeMode.SEARCH) "sonic" else "",
        defaultKey = defaultKey,
    )

    private fun defaultSections() = listOf(
        section(HomeGroup.RESUME, card(a1), card(a2), card(a3, secondary = false)),
        section(HomeGroup.DOWNLOADING, card(b1, span = 2), card(b2)),
        section(HomeGroup.NEW, card(c1), card(c2)),
    )

    private fun gridAt(key: CardKey) = HomeFocusState(zone = FocusZone.Grid, selected = key)

    private fun press(
        state: HomeFocusState,
        model: HomeScreenModel,
        key: FocusKey,
    ) = HomeFocus.press(state, model, key)

    @Test fun rowsFillTwoPerRowAndBreakPerSection() {
        val rows = HomeFocus.rows(home())
        assertEquals(listOf(listOf(a1, a2), listOf(a3), listOf(b1), listOf(b2), listOf(c1, c2)), rows)
    }

    @Test fun spanTwoCardTakesAWholeRow() {
        val model = home(sections = listOf(
            section(HomeGroup.RESUME, card(a1), card(b1, span = 2), card(a2)),
        ))
        assertEquals(listOf(listOf(a1), listOf(b1), listOf(a2)), HomeFocus.rows(model))
    }

    @Test fun gridLeftFromSecondColumnGoesToFirst() {
        val r = press(gridAt(a2), home(), FocusKey.LEFT)
        assertEquals(a1, r.state.selected)
        assertEquals(FocusZone.Grid, r.state.zone)
        assertNull(r.intent)
    }

    @Test fun gridLeftFromFirstColumnStays() {
        val r = press(gridAt(a1), home(), FocusKey.LEFT)
        assertEquals(a1, r.state.selected)
        assertNull(r.intent)
    }

    @Test fun gridRightFromFirstColumnGoesToSecond() {
        assertEquals(a2, press(gridAt(a1), home(), FocusKey.RIGHT).state.selected)
    }

    @Test fun gridRightFromSecondColumnGoesToActionsPrimary() {
        val r = press(gridAt(a2), home(), FocusKey.RIGHT)
        assertEquals(FocusZone.Actions(0), r.state.zone)
        assertEquals(a2, r.state.selected)
    }

    @Test fun gridRightFromSingleCardRowGoesToActionsPrimary() {
        assertEquals(FocusZone.Actions(0), press(gridAt(a3), home(), FocusKey.RIGHT).state.zone)
    }

    @Test fun gridRightFromSpanTwoCardGoesToActionsPrimary() {
        assertEquals(FocusZone.Actions(0), press(gridAt(b1), home(), FocusKey.RIGHT).state.zone)
    }

    @Test fun gridDownMovesRowAndClampsColumnToThatRow() {
        // (r,1) ↓ → (r+1, min(1, last index of that row)) = a3 at (1,0).
        assertEquals(a3, press(gridAt(a2), home(), FocusKey.DOWN).state.selected)
        // Column is preserved when the next row has it: (r,0) ↓ → (r+1,0).
        assertEquals(b1, press(gridAt(a3), home(), FocusKey.DOWN).state.selected)
        assertEquals(c1, press(gridAt(b2), home(), FocusKey.DOWN).state.selected)
    }

    @Test fun gridUpMovesRowAndClampsColumnToThatRow() {
        assertEquals(a1, press(gridAt(a3), home(), FocusKey.UP).state.selected)
        // b1 is at column 0; up to the [a3] row lands on a3.
        assertEquals(a3, press(gridAt(b1), home(), FocusKey.UP).state.selected)
        // (r,1) ↑ to a one-card row clamps to that row's only card.
        assertEquals(b2, press(gridAt(c2), home(), FocusKey.UP).state.selected)
    }

    @Test fun gridUpFromFirstRowGoesToHeaderSearch() {
        val r = press(gridAt(a1), home(), FocusKey.UP)
        assertEquals(FocusZone.Header(HeaderItem.SEARCH), r.state.zone)
    }

    @Test fun gridDownFromLastRowStays() {
        val r = press(gridAt(c2), home(), FocusKey.DOWN)
        assertEquals(c2, r.state.selected)
        assertEquals(FocusZone.Grid, r.state.zone)
    }

    @Test fun lbJumpsToFirstCardOfPreviousSection() {
        assertEquals(b1, press(gridAt(a3), home(), FocusKey.RB).state.selected)
        assertEquals(c1, press(gridAt(b2), home(), FocusKey.RB).state.selected)
        assertEquals(b1, press(gridAt(c2), home(), FocusKey.LB).state.selected)
    }

    @Test fun lbAtFirstSectionStays() {
        val r = press(gridAt(a2), home(), FocusKey.LB)
        assertEquals(a2, r.state.selected)
    }

    @Test fun rbAtLastSectionStays() {
        val r = press(gridAt(c1), home(), FocusKey.RB)
        assertEquals(c1, r.state.selected)
    }

    @Test fun actionsPrimaryRightGoesToSecondaryWhenPresent() {
        val r = press(HomeFocusState(FocusZone.Actions(0), a1), home(), FocusKey.RIGHT)
        assertEquals(FocusZone.Actions(1), r.state.zone)
    }

    @Test fun actionsPrimaryRightStaysWithoutSecondary() {
        val r = press(HomeFocusState(FocusZone.Actions(0), a3), home(), FocusKey.RIGHT)
        assertEquals(FocusZone.Actions(0), r.state.zone)
    }

    @Test fun actionsSecondaryLeftGoesToPrimary() {
        val r = press(HomeFocusState(FocusZone.Actions(1), a1), home(), FocusKey.LEFT)
        assertEquals(FocusZone.Actions(0), r.state.zone)
    }

    @Test fun actionsPrimaryLeftGoesToGridSelectedCard() {
        val r = press(HomeFocusState(FocusZone.Actions(1), a1), home(), FocusKey.LEFT)
        val r2 = press(HomeFocusState(FocusZone.Actions(0), a1), home(), FocusKey.LEFT)
        assertEquals(FocusZone.Grid, r2.state.zone)
        assertEquals(a1, r2.state.selected)
        assertEquals(FocusZone.Actions(0), r.state.zone) // secondary ← is primary
    }

    @Test fun actionsUpGoesToHeaderDownloads() {
        for (button in 0..1) {
            val r = press(HomeFocusState(FocusZone.Actions(button), a1), home(), FocusKey.UP)
            assertEquals(FocusZone.Header(HeaderItem.DOWNLOADS), r.state.zone)
        }
    }

    @Test fun headerLeftRightMoveBetweenItemsClamped() {
        val model = home()
        val search = HomeFocusState(FocusZone.Header(HeaderItem.SEARCH))
        val system = HomeFocusState(FocusZone.Header(HeaderItem.SYSTEM))
        assertEquals(HeaderItem.SEARCH, (press(search, model, FocusKey.LEFT).state.zone as FocusZone.Header).item)
        assertEquals(HeaderItem.LIBRARY, (press(search, model, FocusKey.RIGHT).state.zone as FocusZone.Header).item)
        assertEquals(HeaderItem.SYSTEM, (press(system, model, FocusKey.RIGHT).state.zone as FocusZone.Header).item)
        assertEquals(HeaderItem.DOWNLOADS, (press(system, model, FocusKey.LEFT).state.zone as FocusZone.Header).item)
    }

    @Test fun headerSearchDownGoesToGridSelectedElseDefault() {
        val model = home()
        val fromHeader = HomeFocusState(FocusZone.Header(HeaderItem.SEARCH), selected = a3)
        val r = press(fromHeader, model, FocusKey.DOWN)
        assertEquals(FocusZone.Grid, r.state.zone)
        assertEquals(a3, r.state.selected)
        // No selection: defaultKey.
        val noSel = press(HomeFocusState(FocusZone.Header(HeaderItem.SEARCH)), model, FocusKey.DOWN)
        assertEquals(model.defaultKey, noSel.state.selected)
    }

    @Test fun headerOtherDownGoesToActionsPrimary() {
        for (item in listOf(HeaderItem.LIBRARY, HeaderItem.BROWSE, HeaderItem.DOWNLOADS, HeaderItem.SYSTEM)) {
            val r = press(HomeFocusState(FocusZone.Header(item), a1), home(), FocusKey.DOWN)
            assertEquals(FocusZone.Actions(0), r.state.zone)
        }
    }

    @Test fun xEverywhereOpensSearchEditing() {
        val model = home()
        val starts = listOf(
            gridAt(a1),
            HomeFocusState(FocusZone.Actions(1), a1),
            HomeFocusState(FocusZone.Header(HeaderItem.SYSTEM)),
        )
        for (s in starts) {
            val r = press(s, model, FocusKey.X)
            assertEquals(FocusZone.Header(HeaderItem.SEARCH), r.state.zone)
            assertTrue(r.state.editing)
            assertNull(r.intent)
        }
    }

    @Test fun ySurprisesInHomeAndFirstRun() {
        for (mode in listOf(HomeMode.HOME, HomeMode.FIRST_RUN)) {
            val r = press(gridAt(a1), home(mode = mode), FocusKey.Y)
            assertEquals(HomeIntent.Surprise, r.intent)
        }
    }

    @Test fun yRollsAgainInSurprise() {
        val model = home(
            mode = HomeMode.SURPRISE,
            defaultKey = a1,
            sections = listOf(section(HomeGroup.SURPRISE, card(a1), card(roll, kind = CardKind.INVITE))),
        )
        val r = press(gridAt(a1), model, FocusKey.Y)
        assertEquals(HomeIntent.Activate(roll), r.intent)
    }

    @Test fun yDoesNothingInSearch() {
        val r = press(gridAt(a1), home(mode = HomeMode.SEARCH), FocusKey.Y)
        assertNull(r.intent)
    }

    @Test fun aInGridActivatesSelected() {
        assertEquals(HomeIntent.Activate(a1), press(gridAt(a1), home(), FocusKey.A).intent)
    }

    @Test fun aInActionsRunsPrimaryOrSecondary() {
        val model = home()
        assertEquals(
            HomeIntent.Activate(a1),
            press(HomeFocusState(FocusZone.Actions(0), a1), model, FocusKey.A).intent,
        )
        assertEquals(
            HomeIntent.Secondary(a1),
            press(HomeFocusState(FocusZone.Actions(1), a1), model, FocusKey.A).intent,
        )
    }

    @Test fun aInHeaderOpensTheItemAndSearchStartsEditing() {
        val model = home()
        val search = press(HomeFocusState(FocusZone.Header(HeaderItem.SEARCH)), model, FocusKey.A)
        assertTrue(search.state.editing)
        assertNull(search.intent)
        assertEquals(
            HomeIntent.Nav(HomeDestination.Library()),
            press(HomeFocusState(FocusZone.Header(HeaderItem.LIBRARY)), model, FocusKey.A).intent,
        )
        assertEquals(
            HomeIntent.Nav(HomeDestination.Discover),
            press(HomeFocusState(FocusZone.Header(HeaderItem.BROWSE)), model, FocusKey.A).intent,
        )
        assertEquals(
            HomeIntent.Nav(HomeDestination.Downloads),
            press(HomeFocusState(FocusZone.Header(HeaderItem.DOWNLOADS)), model, FocusKey.A).intent,
        )
        assertEquals(
            HomeIntent.Nav(HomeDestination.System),
            press(HomeFocusState(FocusZone.Header(HeaderItem.SYSTEM)), model, FocusKey.A).intent,
        )
    }

    @Test fun aWhileEditingSubmitsToFirstResultCard() {
        val state = HomeFocusState(FocusZone.Header(HeaderItem.SEARCH), editing = true)
        val r = press(state, home(), FocusKey.A)
        assertEquals(FocusZone.Grid, r.state.zone)
        assertEquals(a1, r.state.selected) // first card in the grid
        assertTrue(!r.state.editing)
    }

    @Test fun bInSearchClearsSearch() {
        for (state in listOf(gridAt(a1), HomeFocusState(FocusZone.Header(HeaderItem.LIBRARY)))) {
            val r = press(state, home(mode = HomeMode.SEARCH), FocusKey.B)
            assertEquals(HomeIntent.ClearSearch, r.intent)
            assertTrue(!r.state.editing)
        }
    }

    @Test fun bInSurpriseExitsSurprise() {
        val r = press(gridAt(a1), home(mode = HomeMode.SURPRISE), FocusKey.B)
        assertEquals(HomeIntent.ExitSurprise, r.intent)
    }

    @Test fun bOutsideGridReturnsToGrid() {
        val model = home()
        for (state in listOf(
            HomeFocusState(FocusZone.Header(HeaderItem.LIBRARY), a1),
            HomeFocusState(FocusZone.Actions(1), a1),
        )) {
            val r = press(state, model, FocusKey.B)
            assertEquals(FocusZone.Grid, r.state.zone)
            assertEquals(a1, r.state.selected)
            assertNull(r.intent)
            assertTrue(!r.state.editing)
        }
        // While editing, the FIRST B only closes editing (the
        // query and the zone survive); the second B runs the chain to GRID.
        val editing = HomeFocusState(FocusZone.Header(HeaderItem.SEARCH), a1, editing = true)
        val first = press(editing, model, FocusKey.B)
        assertEquals(FocusZone.Header(HeaderItem.SEARCH), first.state.zone)
        assertFalse(first.state.editing)
        assertNull(first.intent)
        val second = press(first.state, model, FocusKey.B)
        assertEquals(FocusZone.Grid, second.state.zone)
        assertEquals(a1, second.state.selected)
        assertNull(second.intent)
    }

    @Test fun bResetsSelectionToDefaultKey() {
        val r = press(gridAt(c2), home(), FocusKey.B)
        assertEquals(a1, r.state.selected)
        assertEquals(FocusZone.Grid, r.state.zone)
        assertNull(r.intent)
    }

    @Test fun bOnDefaultSelectionDoesNothingButStaysConsumed() {
        val r = press(gridAt(a1), home(), FocusKey.B)
        assertEquals(gridAt(a1), r.state)
        assertNull(r.intent)
    }

    @Test fun stateForRestoresRememberedKey() {
        assertEquals(c2, HomeFocus.stateFor(home(), rememberedKey = c2, rememberedIndex = 6).selected)
    }

    @Test fun stateForFallsBackToClampedFlatIndex() {
        val model = home()
        val gone = CardKey.local(99)
        // The remembered key vanished; the same flat index (6) is out of
        // range in a 2-card model → clamped to the last card.
        val short = home(sections = listOf(section(HomeGroup.RESUME, card(a1), card(a2))))
        assertEquals(a2, HomeFocus.stateFor(short, rememberedKey = gone, rememberedIndex = 6).selected)
        // In range: the card at the remembered flat index.
        assertEquals(b1, HomeFocus.stateFor(model, rememberedKey = gone, rememberedIndex = 3).selected)
    }

    @Test fun stateForFallsBackToDefaultKey() {
        assertEquals(a1, HomeFocus.stateFor(home(), rememberedKey = null, rememberedIndex = null).selected)
    }

    @Test fun reconcileFollowsTheCardKeyAcrossGroups() {
        val next = home(sections = listOf(
            section(HomeGroup.NEW, card(c1), card(a1)),
        ))
        val r = HomeFocus.reconcile(gridAt(a1), next, listOf(a1, a2, a3, b1, b2, c1, c2))
        assertEquals(a1, r.selected)
        assertEquals(FocusZone.Grid, r.zone)
    }

    @Test fun reconcileMovesToNearestCardWhenKeyVanishes() {
        val previous = listOf(a1, a2, a3, b1, b2, c1, c2)
        // c2 (flat index 6) vanishes; the model now has 4 cards → clamped to 3.
        val next = home(sections = listOf(
            section(HomeGroup.RESUME, card(a1), card(a2), card(a3)),
            section(HomeGroup.NEW, card(c1)),
        ))
        assertEquals(c1, HomeFocus.reconcile(gridAt(c2), next, previous).selected)
        // a3 (flat index 2) vanishes from a 3-card model → index 2 clamps to 2.
        val three = home(sections = listOf(section(HomeGroup.RESUME, card(a1), card(a2), card(c1))))
        assertEquals(c1, HomeFocus.reconcile(gridAt(a3), three, previous).selected)
    }

    @Test fun reconcileDropsSecondaryFocusWhenSecondaryVanishes() {
        val next = home(sections = listOf(section(HomeGroup.RESUME, card(a1, secondary = false))))
        val r = HomeFocus.reconcile(HomeFocusState(FocusZone.Actions(1), a1), next, listOf(a1))
        assertEquals(FocusZone.Actions(0), r.zone)
    }

    @Test fun tapUnselectedCardSelectsWithoutIntent() {
        val r = HomeFocus.tapCard(gridAt(a1), a2)
        assertEquals(a2, r.state.selected)
        assertEquals(FocusZone.Grid, r.state.zone)
        assertTrue(r.state.touch)
        assertNull(r.intent)
    }

    @Test fun tapSelectedCardActivates() {
        assertEquals(HomeIntent.Activate(a1), HomeFocus.tapCard(gridAt(a1), a1).intent)
    }

    @Test fun tapPrimaryAndTvActivate() {
        val r = HomeFocus.tapPrimary(gridAt(a1))
        assertEquals(HomeIntent.Activate(a1), r.intent)
        assertEquals(FocusZone.Actions(0), r.state.zone)
    }

    @Test fun tapSecondaryRunsSecondary() {
        val r = HomeFocus.tapSecondary(gridAt(a1))
        assertEquals(HomeIntent.Secondary(a1), r.intent)
        assertEquals(FocusZone.Actions(1), r.state.zone)
    }

    @Test fun tapSearchStartsEditingAndClearStops() {
        val editing = HomeFocus.tapSearch(gridAt(a1))
        assertTrue(editing.state.editing)
        assertEquals(FocusZone.Header(HeaderItem.SEARCH), editing.state.zone)
        val cleared = HomeFocus.clearSearch(editing.state)
        assertEquals(HomeIntent.ClearSearch, cleared.intent)
        assertTrue(!cleared.state.editing)
    }

    @Test fun tapNavOpensTheDestination() {
        val r = HomeFocus.tapNav(gridAt(a1), HomeDestination.Downloads)
        assertEquals(HomeIntent.Nav(HomeDestination.Downloads), r.intent)
        assertEquals(FocusZone.Header(HeaderItem.DOWNLOADS), r.state.zone)
        assertTrue(r.state.touch)
    }

    @Test fun tapPutsTheCursorInTouchModeWithoutRings() {
        assertTrue(HomeFocus.tapCard(gridAt(a1), a2).state.touch)
        assertTrue(HomeFocus.tapPrimary(gridAt(a1)).state.touch)
        // A pad press returns to pad mode with rings.
        assertTrue(!press(gridAt(a1), home(), FocusKey.RIGHT).state.touch)
    }

    private fun labels(hints: List<com.gamebex.client.ui.console.ControllerHint>) =
        hints.map { it.glyph to it.text }

    private fun saved() = card(a1, state = CardState.Saved(0L, 3))
    private fun offPhone() = card(a1, state = CardState.OffPhone(SizeInfo(1_200_000_000L, "1.2 GB"), 2))

    @Test fun hintHomeGrid() {
        val h = labels(HomeFocus.hints(HomeMode.HOME, gridAt(a1), saved()))
        assertEquals(
            listOf(
                ControllerGlyph.A to "Resume",
                ControllerGlyph.X to "Search",
                ControllerGlyph.Y to "Surprise me",
            ),
            h,
        )
    }

    @Test fun hintHomeActions() {
        val h = labels(HomeFocus.hints(HomeMode.HOME, HomeFocusState(FocusZone.Actions(0), a1), saved()))
        assertEquals(listOf(ControllerGlyph.A to "Resume", ControllerGlyph.B to "Back"), h)
    }

    @Test fun hintHomeHeader() {
        val h = labels(HomeFocus.hints(HomeMode.HOME, HomeFocusState(FocusZone.Header(HeaderItem.LIBRARY)), saved()))
        assertEquals(listOf(ControllerGlyph.A to "Open", ControllerGlyph.B to "Back"), h)
    }

    @Test fun hintSearch() {
        val h = labels(HomeFocus.hints(HomeMode.SEARCH, gridAt(a1), offPhone()))
        assertEquals(
            listOf(
                ControllerGlyph.A to "Download again",
                ControllerGlyph.B to "Clear search",
                ControllerGlyph.X to "Search",
            ),
            h,
        )
    }

    @Test fun hintSurprise() {
        val h = labels(HomeFocus.hints(HomeMode.SURPRISE, gridAt(a1), saved()))
        assertEquals(
            listOf(
                ControllerGlyph.A to "Resume",
                ControllerGlyph.Y to "Roll again",
                ControllerGlyph.B to "Put it back",
            ),
            h,
        )
    }

    @Test fun hintFirstRun() {
        val h = labels(HomeFocus.hints(HomeMode.FIRST_RUN, gridAt(a1), saved()))
        assertEquals(listOf(ControllerGlyph.A to "Resume", ControllerGlyph.X to "Search"), h)
    }

    @Test fun primaryShortStripsTheSize() {
        assertEquals("Download again", HomeFocus.primaryShort(offPhone()))
        assertEquals(
            "Download",
            HomeFocus.primaryShort(card(a1, state = CardState.OnSite(SizeInfo(10, "10 MB")))),
        )
    }

    @Test fun secondaryFocusNamesTheSecondaryAction() {
        val h = labels(HomeFocus.hints(HomeMode.HOME, HomeFocusState(FocusZone.Actions(1), a1), saved()))
        assertEquals(listOf(ControllerGlyph.A to "Details", ControllerGlyph.B to "Back"), h)
    }

    @Test fun settledStatusOwnsAAndB() {
        val h = labels(HomeFocus.hints(HomeMode.HOME, gridAt(a1), saved(), settledAction = "Retry"))
        assertEquals(listOf(ControllerGlyph.A to "Retry", ControllerGlyph.B to "Dismiss"), h)
    }

    @Test fun disabledPrimaryHasNoHint() {
        val waiting = card(a1, state = CardState.WaitingForConnection, primary = HomeAction.None, primaryLabel = "Download when online")
        val h = labels(HomeFocus.hints(HomeMode.HOME, gridAt(a1), waiting))
        assertEquals(listOf(ControllerGlyph.X to "Search", ControllerGlyph.Y to "Surprise me"), h)
    }

    @Test fun hintsNeverExceedFour() {
        for (mode in HomeMode.entries) {
            for (zone in listOf(
                FocusZone.Grid,
                FocusZone.Actions(0),
                FocusZone.Actions(1),
                FocusZone.Header(HeaderItem.SEARCH),
            )) {
                val h = HomeFocus.hints(mode, HomeFocusState(zone, a1), offPhone())
                assertTrue("$mode/$zone -> $h", h.size <= 4)
            }
        }
    }

    @Test fun minimalScrollDeltaOnlyScrollsWhenNeeded() {
        // Fully visible with room to spare.
        assertEquals(0f, HomeFocus.minimalScrollDelta(0f, 500f, 100f, 200f, 8f), 0f)
        // Top clipped: scroll up by the deficit (negative).
        assertEquals(-18f, HomeFocus.minimalScrollDelta(100f, 500f, 90f, 200f, 8f), 0f)
        // Bottom clipped: scroll down by the surplus.
        assertEquals(28f, HomeFocus.minimalScrollDelta(0f, 500f, 400f, 520f, 8f), 0f)
        // Exactly at the margin: nothing to do.
        assertEquals(0f, HomeFocus.minimalScrollDelta(0f, 500f, 8f, 492f, 8f), 0f)
    }

    @Test fun rowsAreNeverEmpty() {
        assertTrue(HomeFocus.rows(home(sections = emptyList())).isEmpty())
        val one = home(sections = listOf(section(HomeGroup.SETUP, card(a1, kind = CardKind.INVITE))))
        assertEquals(listOf(listOf(a1)), HomeFocus.rows(one))
    }

    @Test fun copyStringsComeFromHomeCopy() {
        // The rows the hint strip consumes: "Download again"
        // and "Download" without a size.
        assertEquals("Download again", HomeCopy.primaryShort(CardState.OffPhone(null, 0)))
        assertEquals("Download", HomeCopy.primaryShort(CardState.OnSite(null)))
    }

    @Test fun artRefNoneIsTheThumbDefault() {
        assertEquals(ArtRef.None, card(a1).thumb)
    }

    @Test fun rowIndexOfFindsTheRowHoldingTheKey() {
        val model = home()
        assertEquals(0, HomeFocus.rowIndexOf(model, a1))
        assertEquals(0, HomeFocus.rowIndexOf(model, a2))
        assertEquals(1, HomeFocus.rowIndexOf(model, a3))
        assertEquals(2, HomeFocus.rowIndexOf(model, b1))
        assertEquals(4, HomeFocus.rowIndexOf(model, c2))
        assertEquals(-1, HomeFocus.rowIndexOf(model, CardKey.local(99)))
        assertEquals(-1, HomeFocus.rowIndexOf(model, null))
    }

    @Test fun edgeBumpOnlyWhenTheCursorDidNotMove() {
        val model = home()
        // LEFT at (row, 0) stays (the rail is hidden) -> bump LEFT.
        val at = gridAt(a3)
        val stuck = press(at, model, FocusKey.LEFT)
        assertEquals(FocusKey.LEFT, HomeFocus.edgeBumpDirection(at, stuck, FocusKey.LEFT))
        // DOWN from the last row stays -> bump DOWN.
        val atLast = gridAt(c2)
        val stuckDown = press(atLast, model, FocusKey.DOWN)
        assertEquals(FocusKey.DOWN, HomeFocus.edgeBumpDirection(atLast, stuckDown, FocusKey.DOWN))
        // A move is not a bump.
        val moved = press(gridAt(a1), model, FocusKey.RIGHT)
        assertNull(HomeFocus.edgeBumpDirection(gridAt(a1), moved, FocusKey.RIGHT))
        // RIGHT to the ACTIONS zone is not a bump.
        val toActions = press(gridAt(a2), model, FocusKey.RIGHT)
        assertNull(HomeFocus.edgeBumpDirection(gridAt(a2), toActions, FocusKey.RIGHT))
        // Non-directional keys never bump, even when they change nothing.
        assertNull(HomeFocus.edgeBumpDirection(at, stuck, FocusKey.A))
        assertNull(HomeFocus.edgeBumpDirection(at, stuck, FocusKey.LB))
    }

    @Test fun anyDragSuppressesAutoScrollForOneSecond() {
        // The auto-scroll stands down for one second after a
        // drag in EVERY mode — the drag is the user's scroll.
        assertFalse(HomeFocus.shouldBringRowIntoView(nowMs = 10_000, lastDragMs = 9_900))
        // ...then resumes.
        assertTrue(HomeFocus.shouldBringRowIntoView(nowMs = 11_000, lastDragMs = 10_000))
        // No drag yet: nothing to stand down for.
        assertTrue(HomeFocus.shouldBringRowIntoView(nowMs = 10_000, lastDragMs = null))
    }

    @Test fun aWhileEditingSubmitsToTheFirstResultInsteadOfActivating() {
        val model = home()
        val editing = gridAt(a2).copy(editing = true, zone = FocusZone.Header(HeaderItem.SEARCH))
        val res = press(editing, model, FocusKey.A)
        assertNull(res.intent) // never Activate
        assertFalse(res.state.editing)
        assertEquals(FocusZone.Grid, res.state.zone)
        assertEquals(a1, res.state.selected) // first result card
    }

    @Test fun downWhileEditingSubmitsToTheFirstResult() {
        val model = home()
        val editing = gridAt(a2).copy(editing = true, zone = FocusZone.Header(HeaderItem.SEARCH))
        val res = press(editing, model, FocusKey.DOWN)
        assertNull(res.intent)
        assertFalse(res.state.editing)
        assertEquals(a1, res.state.selected)
    }

    @Test fun firstBClosesEditingAndKeepsTheQuery() {
        // SEARCH mode (a query is set): the FIRST B closes editing only —
        // the query survives; the SECOND B clears it (next test).
        val model = home(mode = HomeMode.SEARCH)
        val editing = gridAt(a1).copy(editing = true, zone = FocusZone.Header(HeaderItem.SEARCH))
        val res = press(editing, model, FocusKey.B)
        assertNull(res.intent) // no ClearSearch
        assertFalse(res.state.editing)
        assertEquals(a1, res.state.selected) // cursor untouched
    }

    @Test fun secondBClearsTheQueryInSearchMode() {
        val model = home(mode = HomeMode.SEARCH)
        val res = press(gridAt(a1), model, FocusKey.B)
        assertEquals(HomeIntent.ClearSearch, res.intent)
    }

    @Test fun modeChangeKeepsTheHeaderZoneWhileEditing() {
        // The first keystroke's HOME -> SEARCH change must not steal the
        // field's focus ring: the zone stays HEADER/SEARCH while editing,
        // while the cursor still resets to defaultKey.
        val search = home(mode = HomeMode.SEARCH, defaultKey = c2)
        val editing = HomeFocusState(
            zone = FocusZone.Header(HeaderItem.SEARCH),
            selected = a1,
            editing = true,
        )
        val next = HomeFocus.reconcileModeChange(editing, search)
        assertEquals(FocusZone.Header(HeaderItem.SEARCH), next.zone)
        assertEquals(c2, next.selected)
        assertTrue(next.editing)
    }

    @Test fun editorRealFocusStartsEditingButDoesNotRestartIt() {
        // Tapping the text itself (editor focus) starts editing.
        val idle = gridAt(a1)
        val started = HomeFocus.editorFocused(idle)
        assertTrue(started.state.editing)
        assertEquals(FocusZone.Header(HeaderItem.SEARCH), started.state.zone)
        assertTrue(started.state.touch)
        // Already editing (the X-key path focused the field): untouched, so
        // the pad-mode flag is never flipped by the focus callback.
        val editing = idle.copy(editing = true, zone = FocusZone.Header(HeaderItem.SEARCH), touch = false)
        assertEquals(editing, HomeFocus.editorFocused(editing).state)
    }

    @Test fun modeChangeDefaultsToTheDefaultKeyNotTheOldSelection() {
        // Surprise: the picked game leads the TV even though the previously
        // selected card still exists in the new model (HOME sections below).
        val surprise = home(mode = HomeMode.SURPRISE, defaultKey = c2, sections = defaultSections())
        val before = gridAt(a1)
        // Plain reconcile keeps the stale cursor;
        assertEquals(a1, HomeFocus.reconcile(before, surprise, listOf(a1)).selected)
        // The mode-change rule lands on the pick.
        val next = HomeFocus.reconcileModeChange(before, surprise)
        assertEquals(c2, next.selected)
        assertEquals(FocusZone.Grid, next.zone)
    }

    @Test fun modeChangeWithoutADefaultFallsBackToTheHeader() {
        val empty = home(mode = HomeMode.SURPRISE, defaultKey = null, sections = emptyList())
        val next = HomeFocus.reconcileModeChange(gridAt(a1), empty)
        assertNull(next.selected)
        assertEquals(FocusZone.Header(HeaderItem.SEARCH), next.zone)
    }
}
