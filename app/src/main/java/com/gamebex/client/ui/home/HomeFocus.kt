package com.gamebex.client.ui.home

import com.gamebex.client.ui.console.ControllerGlyph
import com.gamebex.client.ui.console.ControllerHint
import com.gamebex.client.ui.home.model.CardKey
import com.gamebex.client.ui.home.model.HomeCopy
import com.gamebex.client.ui.home.model.CardKind
import com.gamebex.client.ui.home.model.HomeAction
import com.gamebex.client.ui.home.model.HomeCard
import com.gamebex.client.ui.home.model.HomeDestination
import com.gamebex.client.ui.home.model.HomeIntent
import com.gamebex.client.ui.home.model.HomeMode
import com.gamebex.client.ui.home.model.HomeScreenModel
import com.gamebex.client.ui.home.model.InviteKind

/** Controller keys the Home focus map handles. */
enum class FocusKey { LEFT, RIGHT, UP, DOWN, A, B, X, Y, LB, RB }

/** Where the cursor sits: HEADER (an item), GRID (the selected card), ACTIONS. */
sealed interface FocusZone {
    data class Header(val item: HeaderItem) : FocusZone

    /** The grid cursor lives in [HomeFocusState.selected]. */
    data object Grid : FocusZone

    /** 0 = primary button, 1 = secondary. */
    data class Actions(val button: Int) : FocusZone {
        init { require(button == 0 || button == 1) { "button must be 0 or 1" } }
    }
}

/**
 * Focus state. [touch] marks touch mode: the selection moves but no
 * D-pad ring is drawn (zone = GRID with no D-pad ring).
 */
data class HomeFocusState(
    val zone: FocusZone,
    val selected: CardKey? = null,
    /** Search field owns the D-pad and the keyboard is open. */
    val editing: Boolean = false,
    val touch: Boolean = false,
)

/** One focus transition: the new state plus at most one intent to dispatch. */
data class FocusResult(val state: HomeFocusState, val intent: HomeIntent? = null)

/**
 * Pure Home focus map: state, transitions, selection reconciliation and
 * hint copy. No Android or Compose types; unit-tested in `HomeFocusTest`.
 */
object HomeFocus {

    /**
     * Grid rows: each section starts a new row, cards fill rows 2 at
     * a time, a `span = 2` card takes a whole row. Group labels aren't rows.
     */
    fun rows(model: HomeScreenModel): List<List<CardKey>> {
        val rows = mutableListOf<List<CardKey>>()
        for (section in model.sections) {
            val pending = mutableListOf<CardKey>()
            fun flush() {
                if (pending.isNotEmpty()) {
                    rows += pending.toList()
                    pending.clear()
                }
            }
            for (card in section.cards) {
                if (card.span >= 2) {
                    flush()
                    rows += listOf(card.key)
                } else {
                    pending += card.key
                    if (pending.size == 2) flush()
                }
            }
            flush()
        }
        return rows
    }

    /**
     * Entry state ("Default" and "Memory"): restore [rememberedKey]
     * when it still exists, else the card at [rememberedIndex] clamped, else
     * `model.defaultKey`. The cursor starts on the grid when there is a card.
     */
    fun stateFor(model: HomeScreenModel, rememberedKey: CardKey?, rememberedIndex: Int?): HomeFocusState {
        val keys = model.cards.map { it.key }
        val selected = when {
            rememberedKey != null && rememberedKey in keys -> rememberedKey
            rememberedIndex != null && keys.isNotEmpty() ->
                keys[rememberedIndex.coerceIn(0, keys.size - 1)]
            else -> model.defaultKey?.takeIf { it in keys }
        }
        return HomeFocusState(
            zone = if (selected != null) FocusZone.Grid else FocusZone.Header(HeaderItem.SEARCH),
            selected = selected,
        )
    }

    /**
     * Model rebuilds: the cursor follows its `CardKey`; if the key
     * vanished, the cursor goes to the nearest card — the card at the same
     * flat index, clamped. [previousKeys] is the flat card order of the
     * previous model.
     */
    fun reconcile(state: HomeFocusState, model: HomeScreenModel, previousKeys: List<CardKey>): HomeFocusState {
        val keys = model.cards.map { it.key }
        val selected = state.selected
        if (selected != null && selected in keys) return normalize(state, model)
        val next = when {
            selected != null && selected in previousKeys && keys.isNotEmpty() ->
                keys[previousKeys.indexOf(selected).coerceAtMost(keys.size - 1)]
            model.defaultKey != null && model.defaultKey in keys -> model.defaultKey
            else -> keys.firstOrNull()
        } ?: return normalize(state.copy(zone = FocusZone.Header(HeaderItem.SEARCH), selected = null), model)
        return normalize(state.copy(zone = FocusZone.Grid, selected = next), model)
    }

    /**
     * Model rebuild across a **mode change** (Surprise must show the picked
     * game) and across a **selectionToken bump** (Roll again / mode resets
     * force the cursor to `defaultKey`): the cursor
     * defaults to [HomeScreenModel.defaultKey] instead of following the
     * previous selection. While editing, the zone stays HEADER/SEARCH so the
     * field keeps its focus ring (the first keystroke's HOME->SEARCH mode
     * change must not steal focus from the editor).
     */
    fun reconcileModeChange(state: HomeFocusState, model: HomeScreenModel): HomeFocusState {
        val keys = model.cards.map { it.key }
        val zone = if (state.editing) FocusZone.Header(HeaderItem.SEARCH) else FocusZone.Grid
        val default = model.defaultKey?.takeIf { it in keys }
            ?: return normalize(state.copy(zone = FocusZone.Header(HeaderItem.SEARCH), selected = null), model)
        return normalize(state.copy(zone = zone, selected = default), model)
    }

    /** The new model may drop the secondary action under an ACTIONS cursor. */
    private fun normalize(state: HomeFocusState, model: HomeScreenModel): HomeFocusState =
        if (state.zone is FocusZone.Actions && state.zone.button == 1 &&
            model.card(state.selected)?.secondary == null
        ) {
            state.copy(zone = FocusZone.Actions(0))
        } else {
            state
        }

    /** Flat index of the cursor, for focus memory alongside the key. */
    fun selectedIndex(model: HomeScreenModel, state: HomeFocusState): Int =
        model.cards.indexOfFirst { it.key == state.selected }

    /** Grid row index holding [key], or -1. */
    fun rowIndexOf(model: HomeScreenModel, key: CardKey?): Int =
        if (key == null) -1 else rows(model).indexOfFirst { row -> key in row }

    /**
     * Edge bump: a directional press that leaves the cursor
     * exactly where it was pushed against a wall; the caller nudges the
     * selected card 5 px in the pressed direction for 160 ms. Returns the
     * bump direction, or null when the press moved the cursor (or wasn't a
     * D-pad direction).
     */
    fun edgeBumpDirection(state: HomeFocusState, result: FocusResult, key: FocusKey): FocusKey? {
        if (key !in setOf(FocusKey.LEFT, FocusKey.RIGHT, FocusKey.UP, FocusKey.DOWN)) return null
        val moved = result.state.zone != state.zone || result.state.selected != state.selected
        return if (moved) null else key
    }

    /**
     * Auto-scroll: keep the selected row visible, but never
     * snap the list back within one second of a user drag — the drag is the
     * user's scroll. (The rebuild-triggered snap-back is avoided separately:
     * the caller keys the scroll on the selected key's row CONTENT, not on
     * the row index, so sections shifting above the cursor don't re-fire it.)
     */
    fun shouldBringRowIntoView(nowMs: Long, lastDragMs: Long?): Boolean =
        lastDragMs == null || nowMs - lastDragMs >= 1_000L

    /** One controller press. Pure. */
    fun press(state: HomeFocusState, model: HomeScreenModel, key: FocusKey): FocusResult = when (key) {
        FocusKey.LEFT -> move(state, model, 0, -1)
        FocusKey.RIGHT -> move(state, model, 0, 1)
        FocusKey.UP -> move(state, model, -1, 0)
        // D-pad Down while editing submits and moves to the first result
        // (A already did).
        FocusKey.DOWN -> if (state.editing) submit(state, model) else move(state, model, 1, 0)
        FocusKey.A -> if (state.editing) submit(state, model) else activate(state, model)
        FocusKey.B -> back(state, model)
        FocusKey.X -> FocusResult(
            state.copy(zone = FocusZone.Header(HeaderItem.SEARCH), editing = true, touch = false),
        )
        FocusKey.Y -> surprise(state, model)
        FocusKey.LB -> jumpSection(state, model, -1)
        FocusKey.RB -> jumpSection(state, model, 1)
    }

    /** IME Search / Enter while editing: focus the first result card. */
    fun submit(state: HomeFocusState, model: HomeScreenModel): FocusResult {
        val first = model.cards.firstOrNull()?.key ?: model.defaultKey
            ?: return FocusResult(state.copy(editing = false))
        return FocusResult(
            state.copy(zone = FocusZone.Grid, selected = first, editing = false, touch = false),
        )
    }

    /** Tap an unselected card selects it; tapping the selected card activates it. */
    fun tapCard(state: HomeFocusState, key: CardKey): FocusResult =
        if (key == state.selected) {
            FocusResult(state.copy(editing = false, touch = true), HomeIntent.Activate(key))
        } else {
            FocusResult(
                state.copy(zone = FocusZone.Grid, selected = key, editing = false, touch = true),
            )
        }

    /** Tap the TV or the primary button runs the action. */
    fun tapPrimary(state: HomeFocusState): FocusResult = FocusResult(
        state.copy(zone = FocusZone.Actions(0), editing = false, touch = true),
        state.selected?.let { HomeIntent.Activate(it) },
    )

    /** Tap the secondary button. */
    fun tapSecondary(state: HomeFocusState): FocusResult = FocusResult(
        state.copy(zone = FocusZone.Actions(1), editing = false, touch = true),
        state.selected?.let { HomeIntent.Secondary(it) },
    )

    /** Tap the search field starts editing. */
    fun tapSearch(state: HomeFocusState): FocusResult = FocusResult(
        state.copy(zone = FocusZone.Header(HeaderItem.SEARCH), editing = true, touch = true),
    )

    /**
     * The editor's real focus starts editing (tapping the text
     * itself bypassed the outer tap handler). Already-editing states are left
     * alone — the X-key path focuses the field AFTER setting editing and must
     * not flip the state into touch mode.
     */
    fun editorFocused(state: HomeFocusState): FocusResult =
        if (state.editing) FocusResult(state)
        else FocusResult(
            state.copy(zone = FocusZone.Header(HeaderItem.SEARCH), editing = true, touch = true),
        )

    /** Tap a nav item opens it. */
    fun tapNav(state: HomeFocusState, dest: HomeDestination): FocusResult {
        val item = when (dest) {
            is HomeDestination.Library -> HeaderItem.LIBRARY
            HomeDestination.Discover -> HeaderItem.BROWSE
            HomeDestination.Downloads -> HeaderItem.DOWNLOADS
            HomeDestination.System -> HeaderItem.SYSTEM
            else -> null
        }
        return FocusResult(
            state.copy(
                zone = item?.let { FocusZone.Header(it) } ?: state.zone,
                editing = false,
                touch = true,
            ),
            HomeIntent.Nav(dest),
        )
    }

    /** The ✕ in the search field clears the query and closes editing. */
    fun clearSearch(state: HomeFocusState): FocusResult =
        FocusResult(state.copy(editing = false), HomeIntent.ClearSearch)

    private fun activate(state: HomeFocusState, model: HomeScreenModel): FocusResult {
        val s = state.copy(touch = false)
        val selected = state.selected
        return when (val zone = s.zone) {
            FocusZone.Grid ->
                FocusResult(s, selected?.let { HomeIntent.Activate(it) })
            is FocusZone.Actions -> FocusResult(
                s,
                selected?.let {
                    if (zone.button == 0) HomeIntent.Activate(it) else HomeIntent.Secondary(it)
                },
            )
            is FocusZone.Header -> when (zone.item) {
                HeaderItem.SEARCH -> FocusResult(s.copy(editing = true))
                HeaderItem.LIBRARY -> FocusResult(s, HomeIntent.Nav(HomeDestination.Library()))
                HeaderItem.BROWSE -> FocusResult(s, HomeIntent.Nav(HomeDestination.Discover))
                HeaderItem.DOWNLOADS -> FocusResult(s, HomeIntent.Nav(HomeDestination.Downloads))
                HeaderItem.SYSTEM -> FocusResult(s, HomeIntent.Nav(HomeDestination.System))
            }
        }
    }

    /** B: first matching step of the chain. Always consumed. */
    private fun back(state: HomeFocusState, model: HomeScreenModel): FocusResult {
        // The FIRST B while editing just closes editing and
        // KEEPS the query; the chain (SEARCH mode -> ClearSearch) only runs
        // from the second B.
        if (state.editing) return FocusResult(state.copy(editing = false, touch = false))
        val s = state.copy(editing = false, touch = false)
        return when (model.mode) {
            HomeMode.SEARCH -> FocusResult(s, HomeIntent.ClearSearch)
            HomeMode.SURPRISE -> FocusResult(s, HomeIntent.ExitSurprise)
            else -> {
                val selected = s.selected
                when {
                    s.zone != FocusZone.Grid && (selected != null || model.defaultKey != null) ->
                        FocusResult(s.copy(zone = FocusZone.Grid, selected = selected ?: model.defaultKey))
                    selected != null && model.defaultKey != null && selected != model.defaultKey ->
                        FocusResult(s.copy(zone = FocusZone.Grid, selected = model.defaultKey))
                    else -> FocusResult(s) // nothing left to do; never exits the app
                }
            }
        }
    }

    /** Y: Surprise in HOME and FIRST_RUN; RollAgain in SURPRISE. */
    private fun surprise(state: HomeFocusState, model: HomeScreenModel): FocusResult {
        val s = state.copy(touch = false)
        return when (model.mode) {
            HomeMode.HOME, HomeMode.FIRST_RUN -> FocusResult(s, HomeIntent.Surprise)
            HomeMode.SURPRISE -> {
                // The frozen HomeIntent has no RollAgain: the ROLL_AGAIN
                // invite carries HomeAction.RollAgain, activated like a card.
                val roll = model.cards.firstOrNull {
                    it.key == CardKey.invite(InviteKind.ROLL_AGAIN) && it.primary !is HomeAction.None
                }?.key
                FocusResult(s, roll?.let { HomeIntent.Activate(it) } ?: HomeIntent.Surprise)
            }
            HomeMode.SEARCH -> FocusResult(s)
        }
    }

    /** LB / RB: first card of the previous / next section (GRID only). */
    private fun jumpSection(state: HomeFocusState, model: HomeScreenModel, dir: Int): FocusResult {
        val s = state.copy(touch = false)
        if (s.zone != FocusZone.Grid) return FocusResult(s)
        val at = model.sections.indexOfFirst { section -> section.cards.any { it.key == state.selected } }
        if (at < 0) return FocusResult(s)
        val target = model.sections.getOrNull(at + dir)?.cards?.firstOrNull()?.key
            ?: return FocusResult(s)
        return FocusResult(s.copy(zone = FocusZone.Grid, selected = target))
    }

    /** D-pad travel: (row, col) moves. */
    private fun move(state: HomeFocusState, model: HomeScreenModel, dRow: Int, dCol: Int): FocusResult {
        val s = state.copy(touch = false)
        return when (val zone = s.zone) {
            is FocusZone.Header -> moveHeader(s, model, zone, dRow, dCol)
            is FocusZone.Actions -> moveActions(s, model, zone, dRow, dCol)
            FocusZone.Grid -> moveGrid(s, model, dRow, dCol)
        }
    }

    private fun moveHeader(
        state: HomeFocusState,
        model: HomeScreenModel,
        zone: FocusZone.Header,
        dRow: Int,
        dCol: Int,
    ): FocusResult {
        val items = HeaderItem.entries
        val at = items.indexOf(zone.item)
        if (dCol != 0) {
            val next = items[(at + dCol).coerceIn(0, items.size - 1)]
            return FocusResult(state.copy(zone = FocusZone.Header(next)))
        }
        if (dRow > 0) {
            return when (zone.item) {
                // GRID (selected, else defaultKey).
                HeaderItem.SEARCH -> (state.selected ?: model.defaultKey)?.let {
                    FocusResult(state.copy(zone = FocusZone.Grid, selected = it))
                } ?: FocusResult(state)
                else -> FocusResult(state.copy(zone = FocusZone.Actions(0)))
            }
        }
        return FocusResult(state)
    }

    private fun moveActions(
        state: HomeFocusState,
        model: HomeScreenModel,
        zone: FocusZone.Actions,
        dRow: Int,
        dCol: Int,
    ): FocusResult {
        if (dRow < 0) return FocusResult(state.copy(zone = FocusZone.Header(HeaderItem.DOWNLOADS)))
        if (dRow > 0) return FocusResult(state)
        val selected = state.selected
        return when {
            dCol < 0 && zone.button == 1 -> FocusResult(state.copy(zone = FocusZone.Actions(0)))
            dCol < 0 -> FocusResult(
                state.copy(zone = FocusZone.Grid, selected = selected ?: model.defaultKey),
            )
            dCol > 0 && zone.button == 0 && model.card(selected)?.secondary != null ->
                FocusResult(state.copy(zone = FocusZone.Actions(1)))
            else -> FocusResult(state)
        }
    }

    private fun moveGrid(state: HomeFocusState, model: HomeScreenModel, dRow: Int, dCol: Int): FocusResult {
        val rows = rows(model)
        if (rows.isEmpty()) return FocusResult(state)
        var at = rows.indexOfFirst { row -> state.selected in row }
        if (at < 0) at = 0
        val col = rows[at].indexOf(state.selected).coerceAtLeast(0)
        if (dCol != 0) {
            val nextCol = col + dCol
            return when {
                nextCol in rows[at].indices -> FocusResult(state.copy(selected = rows[at][nextCol]))
                dCol > 0 -> FocusResult(state.copy(zone = FocusZone.Actions(0)))
                else -> FocusResult(state) // (r, 0) ← stays: the rail is hidden
            }
        }
        if (dRow != 0) {
            val nextRow = at + dRow
            if (nextRow !in rows.indices) {
                return if (dRow < 0) {
                    FocusResult(state.copy(zone = FocusZone.Header(HeaderItem.SEARCH)))
                } else {
                    FocusResult(state) // last row: ↓ stays
                }
            }
            val row = rows[nextRow]
            return FocusResult(state.copy(selected = row[col.coerceAtMost(row.size - 1)]))
        }
        return FocusResult(state)
    }

    /** "Primary short": the primary label without the size. */
    fun primaryShort(card: HomeCard): String =
        if (card.kind == CardKind.GAME) HomeCopy.primaryShort(card.state) else card.primaryLabel

    /**
     * Hint strip, at most 4 pills. [settledAction] names the
     * quick-play panel's A action; while it is shown it owns A and B, exactly
     * as the current Home does.
     */
    fun hints(
        mode: HomeMode,
        state: HomeFocusState,
        selected: HomeCard?,
        settledAction: String? = null,
    ): List<ControllerHint> {
        fun a(label: String?) = label?.let { ControllerHint(ControllerGlyph.A, it) }
        val aLabel = when {
            settledAction != null -> settledAction
            state.zone is FocusZone.Header -> HomeCopy.hintOpen
            selected == null || selected.primary is HomeAction.None -> null
            (state.zone as? FocusZone.Actions)?.button == 1 ->
                selected.secondaryLabel ?: primaryShort(selected)
            else -> primaryShort(selected)
        }
        if (settledAction != null) {
            return listOfNotNull(
                a(aLabel),
                ControllerHint(ControllerGlyph.B, HomeCopy.hintDismiss),
            )
        }
        val search = ControllerHint(ControllerGlyph.X, HomeCopy.hintSearch)
        return when (mode) {
            HomeMode.SEARCH -> listOfNotNull(
                a(aLabel),
                ControllerHint(ControllerGlyph.B, HomeCopy.hintClearSearch),
                search,
            )
            HomeMode.SURPRISE -> listOfNotNull(
                a(aLabel),
                ControllerHint(ControllerGlyph.Y, HomeCopy.inviteLabel(InviteKind.ROLL_AGAIN)),
                ControllerHint(ControllerGlyph.B, HomeCopy.hintPutBack),
            )
            HomeMode.FIRST_RUN -> listOfNotNull(a(aLabel), search)
            HomeMode.HOME -> when (state.zone) {
                is FocusZone.Header -> listOf(
                    ControllerHint(ControllerGlyph.A, HomeCopy.hintOpen),
                    ControllerHint(ControllerGlyph.B, HomeCopy.hintBack),
                )
                is FocusZone.Actions -> listOfNotNull(
                    a(aLabel),
                    ControllerHint(ControllerGlyph.B, HomeCopy.hintBack),
                )
                FocusZone.Grid -> listOfNotNull(
                    a(aLabel),
                    search,
                    ControllerHint(ControllerGlyph.Y, HomeCopy.hintSurprise),
                )
            }
        }
    }

    /**
     * The smallest scroll that brings the row back inside
     * the viewport with [margin] to spare. Positive scrolls content up.
     */
    fun minimalScrollDelta(
        viewportStart: Float,
        viewportEnd: Float,
        itemStart: Float,
        itemEnd: Float,
        margin: Float,
    ): Float = when {
        itemStart - margin < viewportStart -> itemStart - margin - viewportStart
        itemEnd + margin > viewportEnd -> itemEnd + margin - viewportEnd
        else -> 0f
    }
}

