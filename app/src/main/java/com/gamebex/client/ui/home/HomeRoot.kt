package com.gamebex.client.ui.home

import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import com.gamebex.client.ui.console.ConsoleFocusMemory
import com.gamebex.client.ui.console.ConsoleHints
import com.gamebex.client.ui.console.ControllerGlyph
import com.gamebex.client.ui.console.ControllerHint
import com.gamebex.client.ui.console.ConsoleColors
import com.gamebex.client.ui.console.ConsoleGeometry
import com.gamebex.client.ui.console.LaunchStatusPanel
import com.gamebex.client.ui.console.LocalConsoleGeometry
import com.gamebex.client.ui.console.fieldAfterExternalQuery
import com.gamebex.client.ui.console.consoleKeys
import com.gamebex.client.ui.console.isSystemReducedMotion
import com.gamebex.client.ui.console.rememberScreenFocus
import com.gamebex.client.ui.console.screenFocus
import com.gamebex.client.ui.home.components.GroupLabel
import com.gamebex.client.ui.home.components.HomeCardView
import com.gamebex.client.ui.home.model.CardKey
import com.gamebex.client.ui.home.model.HomeCopy
import com.gamebex.client.ui.home.model.HomeIntent
import com.gamebex.client.ui.home.model.HomeScreenModel
import com.gamebex.client.ui.home.model.TvContent
import com.gamebex.client.ui.home.model.TvModel
import com.gamebex.client.ui.home.tv.HomeActionRow
import com.gamebex.client.ui.home.tv.TvPane
import com.gamebex.client.ui.presentation.label
import kotlin.math.roundToInt

/**
 * Home layout, controller focus map, touch map and the hint strip. The
 * signature is frozen except for [externalQuery] (defaulted); `HomeFocus`
 * holds the pure state machine and `HomeFocusTest` covers it.
 *
 * Behavior:
 *  1. The search field's `TextFieldValue` lives here (`rememberSaveable`) and
 *     is the source of truth — typing sends `Query(text)` and is never
 *     re-derived from a model echo; only Clear resets it. External query
 *     changes come from [externalQuery] (`store.query` on the real screen).
 *  2. Editing owns the keyboard: entering edit focuses the field and shows
 *     the IME, ending it (IME Search, ✕, B, tapping a card) hides the IME and
 *     returns focus to the screen root; X re-requests the field.
 *  3. `bringRowIntoView` is keyed on the selected key and row index only, and
 *     stands down for a second after a user drag in touch mode.
 *  4. Grid rhythm: 26 px group-label rows with per-item padding (first card at
 *     y 128) and a 24 px bottom fade while more content is below.
 *  5. The TV channel is the selected card's `position + 1`.
 *  6. A D-pad press with nowhere to go nudges the selected card 5 px for
 *     160 ms (skipped under reduced motion).
 */
@Composable
fun HomeRoot(
    model: HomeScreenModel,
    onIntent: (HomeIntent) -> Unit,
    focusMemory: ConsoleFocusMemory,
    hints: ConsoleHints,
    externalQuery: String = model.query,
    /** A Home (launcher) press; changes reset the screen. */
    homeResetToken: Long = 0,
    reducedMotion: Boolean = isSystemReducedMotion(),
) {
    val g = LocalConsoleGeometry.current
    val density = LocalDensity.current
    val screenFocus = rememberScreenFocus()
    val gridState = rememberLazyGridState()
    val searchFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    // The field's source of truth. Kept across
    // recreation; only a Clear (✕ or B in SEARCH mode) resets it. Echo
    // detection compares only against the LAST query we sent — "" and older
    // values are real external changes (store-side clears), never echoes.
    var fieldValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(model.query))
    }
    var lastSent by remember { mutableStateOf(model.query) }
    LaunchedEffect(externalQuery) {
        val next = fieldAfterExternalQuery(externalQuery, fieldValue.text, lastSent)
        if (next.text != fieldValue.text) {
            fieldValue = TextFieldValue(next.text, TextRange(next.text.length))
        }
        lastSent = next.lastSent
    }
    // The field survives process death; re-assert it to the fresh store so
    // grouping matches what the user sees.
    LaunchedEffect(Unit) {
        if (fieldValue.text.isNotEmpty()) onIntent(HomeIntent.Query(fieldValue.text))
    }

    // Focus state: restored from "home:selected". The store's first
    // emission is an empty model, so the remembered cursor is re-applied at
    // the first non-empty model — coming back from an emulator lands on the
    // same card, even after the activity was recreated and the memory rode
    // SavedStateHandle back.
    val rememberedKey = remember { focusMemory.recallKey(FOCUS_KEY_TAG)?.let(::CardKey) }
    val rememberedIndex = remember {
        if (focusMemory.has(FOCUS_INDEX_TAG)) focusMemory.recall(FOCUS_INDEX_TAG) else null
    }
    var restored by remember { mutableStateOf(false) }
    var state by remember {
        mutableStateOf(HomeFocus.stateFor(model, rememberedKey, rememberedIndex))
    }
    // The cursor follows its card key across model rebuilds; a vanished
    // key falls back to the card at the same flat index, clamped. Across a
    // mode change (e.g. Y -> Surprise) or a selectionToken bump (Roll again,
    // mode resets) it defaults to the model's defaultKey, so
    // the picked game leads the TV, not the previously selected card.
    var lastKeys by remember { mutableStateOf(model.cards.map { it.key }) }
    var lastMode by remember { mutableStateOf(model.mode) }
    var lastToken by remember { mutableLongStateOf(model.selectionToken) }
    LaunchedEffect(model) {
        val next = when {
            !restored && state.selected == null && model.cards.isNotEmpty() ->
                HomeFocus.stateFor(model, rememberedKey, rememberedIndex)
            model.mode != lastMode || model.selectionToken != lastToken ->
                HomeFocus.reconcileModeChange(state, model)
            else -> HomeFocus.reconcile(state, model, lastKeys)
        }
        if (model.cards.isNotEmpty()) restored = true
        lastKeys = model.cards.map { it.key }
        lastMode = model.mode
        lastToken = model.selectionToken
        if (next != state) state = next
    }

    // A Home (launcher) intent resets the screen — clear the
    // query, exit Surprise, close editing, cursor back to defaultKey.
    LaunchedEffect(homeResetToken) {
        if (homeResetToken == 0L) return@LaunchedEffect
        fieldValue = TextFieldValue("")
        lastSent = ""
        state = HomeFocus.reconcileModeChange(state.copy(editing = false, touch = false), model)
        onIntent(HomeIntent.ClearSearch)
        onIntent(HomeIntent.ExitSurprise)
    }
    // Selection memory.
    LaunchedEffect(state.selected) {
        state.selected?.let { key ->
            focusMemory.storeKey(FOCUS_KEY_TAG, key.value)
            HomeFocus.selectedIndex(model, state).takeIf { it >= 0 }
                ?.let { focusMemory.store(FOCUS_INDEX_TAG, it) }
        }
    }

    val selectedCard = model.card(state.selected)
    // In-flight work remains visible even when the user moves selection.
    // Settled recovery follows the focused game as before.
    val status = model.status?.takeIf { it.busy || it.gameId == selectedCard?.gameId }
    val recovery = status?.takeIf { !it.busy || it.canCancel }
    val focusGameId = selectedCard?.gameId
    LaunchedEffect(focusGameId) {
        if (model.status?.let { !it.busy && it.gameId != focusGameId } == true) {
            onIntent(HomeIntent.DismissStatus)
        }
    }

    // The keyboard follows editing; ending editing returns focus to the
    // screen root so the next D-pad press goes to the router, not the IME.
    LaunchedEffect(state.editing) {
        if (state.editing) {
            searchFocus.requestFocus()
            keyboard?.show()
        } else {
            keyboard?.hide()
            screenFocus.requestFocus()
        }
    }

    // Last user drag (touch mode suppresses auto-scroll for a second).
    var lastDragMs by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(gridState) {
        gridState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) {
                lastDragMs = android.os.SystemClock.uptimeMillis()
            }
        }
    }
    // Keep the selected row fully visible, scrolling as little as possible
    // (8 px margin). Keyed on the selected key's ROW CONTENT — when sections
    // change above the cursor the row's
    // INDEX shifts but its keys don't, so the viewport never snaps back; and
    // the scroll stands down for 1 s after a user drag.
    val selectedRowKeys = HomeFocus.rows(model).firstOrNull { state.selected in it }.orEmpty()
    LaunchedEffect(state.selected, selectedRowKeys) {
        val key = state.selected ?: return@LaunchedEffect
        if (!HomeFocus.shouldBringRowIntoView(
                nowMs = android.os.SystemClock.uptimeMillis(),
                lastDragMs = lastDragMs,
            )
        ) {
            return@LaunchedEffect
        }
        bringRowIntoView(gridState, model, key, with(density) { g.x(8).toPx() })
    }

    // Edge bump — 5 px nudge of the selected card for 160 ms when the
    // D-pad has nowhere to go (skipped under reduced motion).
    val bumpOffset = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    var bump by remember { mutableStateOf<Pair<FocusKey, Int>?>(null) }
    LaunchedEffect(bump) {
        val (dir, token) = bump ?: return@LaunchedEffect
        if (token == 0 || reducedMotion) return@LaunchedEffect
        val d = with(density) { g.x(5).toPx() }
        val target = when (dir) {
            FocusKey.LEFT -> Offset(-d, 0f)
            FocusKey.RIGHT -> Offset(d, 0f)
            FocusKey.UP -> Offset(0f, -d)
            FocusKey.DOWN -> Offset(0f, d)
            else -> Offset.Zero
        }
        bumpOffset.animateTo(target, tween(80))
        bumpOffset.animateTo(Offset.Zero, tween(80))
    }

    fun apply(result: FocusResult) {
        state = result.state
        // Clear is the one thing that resets the field.
        if (result.intent == HomeIntent.ClearSearch) {
            fieldValue = TextFieldValue("")
            lastSent = ""
        }
        result.intent?.let(onIntent)
    }
    fun dispatch(key: FocusKey): Boolean {
        // While the search field is editing, the D-pad belongs to the field —
        // except Down, which submits.
        if (state.editing &&
            key != FocusKey.A && key != FocusKey.B && key != FocusKey.X &&
            key != FocusKey.Y && key != FocusKey.DOWN
        ) {
            return false
        }
        val before = state
        val result = HomeFocus.press(state, model, key)
        apply(result)
        // A press that goes nowhere bumps the selected card.
        HomeFocus.edgeBumpDirection(before, result, key)?.let { dir ->
            if (!reducedMotion) bump = dir to (bump?.second?.plus(1) ?: 1)
        }
        return true
    }

    val hintList = if (!state.editing && recovery != null) listOfNotNull(
        recovery.action?.let { ControllerHint(ControllerGlyph.A, it.label()) },
        recovery.secondaryAction?.let { ControllerHint(ControllerGlyph.X, it.label()) },
        ControllerHint(ControllerGlyph.B, if (recovery.canCancel) "Cancel" else "Dismiss"),
    ) else HomeFocus.hints(
        mode = model.mode,
        state = state,
        selected = selectedCard,
    )
    LaunchedEffect(hintList) { hints.set(hintList) }

    // MainActivity draws edge to edge, so
    // `adjustResize` never resizes the window and the IME covers the canvas.
    // While editing, the TV collapses and the grid takes the full canvas
    // width and the whole strip above the keyboard at FULL size — no uniform
    // shrink (the mockup's whole-stage fit made everything tiny in
    // landscape, where the keyboard eats half the screen). Results stay
    // visible and readable; the grid scrolls inside the strip.
    val view = LocalView.current
    val imeBottomPx = WindowInsets.ime.getBottom(density)
    var canvasTopPx by remember { mutableFloatStateOf(0f) }
    val visibleDp = with(density) { (view.height - imeBottomPx).toDp() } -
        with(density) { canvasTopPx.toDp() }

    Box(
        modifier = Modifier
            .size(g.x(1600), g.x(720))
            .onGloballyPositioned { coords ->
                canvasTopPx = coords.positionInWindow().y
            }
            .screenFocus(screenFocus)
            // While editing, hardware Enter submits the search
            // and must not reach FocusArcade's Enter -> A mapping.
            .enterSubmitsWhileEditing(
                editing = { state.editing },
                submit = { apply(HomeFocus.submit(state, model)); true },
            )
            .consoleKeys(
                onLeft = { dispatch(FocusKey.LEFT) },
                onRight = { dispatch(FocusKey.RIGHT) },
                onUp = { dispatch(FocusKey.UP) },
                onDown = { dispatch(FocusKey.DOWN) },
                onA = {
                    // While editing, A submits the search — it
                    // must never reach Activate (or the quick-play panel).
                    if (state.editing) {
                        dispatch(FocusKey.A)
                    } else when {
                        recovery?.action != null -> { onIntent(HomeIntent.RetryStatus); true }
                        else -> dispatch(FocusKey.A)
                    }
                },
                onB = {
                    // While editing, the first B closes editing
                    // and keeps the query (the panel's dismiss waits).
                    if (state.editing) {
                        dispatch(FocusKey.B)
                    } else when {
                        recovery != null -> { onIntent(HomeIntent.DismissStatus); true }
                        else -> dispatch(FocusKey.B)
                    }
                },
                onX = {
                    if (!state.editing && recovery?.secondaryAction != null) {
                        onIntent(HomeIntent.SecondaryStatus)
                        true
                    } else {
                        val wasEditing = state.editing
                        val handled = dispatch(FocusKey.X)
                        // X while already editing re-opens the IME and re-requests
                        // the field.
                        if (wasEditing) {
                            searchFocus.requestFocus()
                            keyboard?.show()
                        }
                        handled
                    }
                },
                onY = { dispatch(FocusKey.Y) },
                onLB = { dispatch(FocusKey.LB) },
                onRB = { dispatch(FocusKey.RB) },
            ),
    ) {
        HomeHeader(
            header = model.header,
            value = fieldValue,
            onValueChange = { next ->
                fieldValue = next
                lastSent = next.text
                onIntent(HomeIntent.Query(next.text))
            },
            focused = (state.zone as? FocusZone.Header)?.item?.takeIf { !state.touch || state.editing },
            searchFocus = searchFocus,
            onSubmit = { apply(HomeFocus.submit(state, model)) },
            onSearchTap = { apply(HomeFocus.tapSearch(state)) },
            onClear = { apply(HomeFocus.clearSearch(state)) },
            onNav = { dest -> apply(HomeFocus.tapNav(state, dest)) },
            // Editing starts from the editor's real focus, so
            // tapping the text itself (not just the row) enters editing.
            onFocusChange = { gained -> if (gained) apply(HomeFocus.editorFocused(state)) },
            showKeyHint = true,
            modifier = Modifier.offset(g.x(24), g.x(8)),
        )

        // While editing, the TV and the
        // action row collapse and the grid takes the full canvas width and
        // the whole strip below the header at FULL size (scrollable). The
        // canvas never rescales — cards keep their pixel size; the grid
        // scrolls, and the header/search field never move.
        val editing = state.editing
        val gridWidth = if (editing) g.x(1600 - 48) else g.x(848)
        val gridHeight = if (editing) {
            (visibleDp - g.x(96) - g.x(8)).coerceAtLeast(g.x(120))
        } else {
            g.x(536)
        }

        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(2),
            modifier = Modifier
                .offset(g.x(24), g.x(96))
                .size(gridWidth, gridHeight),
            horizontalArrangement = Arrangement.spacedBy(g.x(14)),
        ) {
            // Per-item padding carries the rhythm — cards sit 10 px
            // apart, label rows are 26 px tight between them (the first one
            // 6 px down, so the first card starts at y 128).
            model.sections.forEachIndexed { sectionIndex, section ->
                item(key = "label:${section.group.name}", span = { GridItemSpan(2) }) {
                    Box(Modifier.labelRowModifier(g, first = sectionIndex == 0)) {
                        GroupLabel(section)
                    }
                }
                for (card in section.cards) {
                    item(
                        key = card.key.value,
                        span = { GridItemSpan(card.span.coerceIn(1, 2)) },
                    ) {
                        val bumpThis = card.key == state.selected
                        Box(
                            Modifier
                                .padding(bottom = g.x(10))
                                .then(
                                    if (bumpThis) {
                                        Modifier.offset {
                                            IntOffset(
                                                bumpOffset.value.x.roundToInt(),
                                                bumpOffset.value.y.roundToInt(),
                                            )
                                        }
                                    } else {
                                        Modifier
                                    },
                                ),
                        ) {
                            HomeCardView(
                                card = card,
                                selected = card.key == state.selected,
                                // The D-pad cursor shows only in pad mode.
                                focused = card.key == state.selected &&
                                    !state.touch && state.zone == FocusZone.Grid,
                                onTap = { apply(HomeFocus.tapCard(state, card.key)) },
                                // TalkBack: the accessibility double-tap runs the
                                // primary action directly — select AND activate
                                // in one gesture.
                                onActivate = {
                                    apply(HomeFocus.tapCard(state.copy(selected = card.key), card.key))
                                },
                            )
                        }
                    }
                }
            }
        }

        // 24 px bottom fade while more content is below the fold.
        val moreBelow by remember { derivedStateOf { gridState.canScrollForward } }
        if (moreBelow) {
            Box(
                Modifier
                    .offset(g.x(24), g.x(96) + gridHeight - g.x(24))
                    .size(gridWidth, g.x(24))
                    .background(
                        Brush.verticalGradient(
                            0f to ConsoleColors.Background.copy(alpha = 0f),
                            1f to ConsoleColors.Background,
                        ),
                    ),
            )
        }

        // The TV and the action row collapse while editing —
        // the strip above the keyboard belongs to the results.
        if (!editing) {
            val card = selectedCard
            val channel = (card?.position ?: -1) + 1
            TvPane(
                tv = card?.tv ?: TvModel(
                    content = TvContent.TestCard(HomeCopy.noGameInserted),
                    stateKey = HomeCopy.gameBex,
                    stateDetail = null,
                    glyph = null,
                    factsLine = null,
                ),
                // The channel number is the card's flat position + 1.
                channel = channel,
                // The swap keys on the selected card's key,
                // not its position — Roll again with the same position swaps.
                selectionKey = card?.key?.value ?: channel.toString(),
                reducedMotion = reducedMotion,
                onTap = { apply(HomeFocus.tapPrimary(state)) },
                modifier = Modifier.offset(g.x(904), g.x(96)),
            )
            HomeActionRow(
                card = card,
                focusedButton = (state.zone as? FocusZone.Actions)
                    ?.takeIf { !state.touch }?.button ?: -1,
                onPrimary = { apply(HomeFocus.tapPrimary(state)) },
                onSecondary = { apply(HomeFocus.tapSecondary(state)) },
                modifier = Modifier.offset(g.x(904), g.x(536)),
            )
        }

        status?.let { st ->
            LaunchStatusPanel(
                status = st,
                onAction = { onIntent(HomeIntent.RetryStatus) },
                onDismiss = { onIntent(HomeIntent.DismissStatus) },
                onSecondaryAction = { onIntent(HomeIntent.SecondaryStatus) },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = g.x(24), bottom = g.x(52)),
            )
        }
    }
}

private const val FOCUS_KEY_TAG = "home:selected"
private const val FOCUS_INDEX_TAG = "home:selected:i"

/**
 * While the editor is editing, hardware Enter submits the
 * search and is consumed here so it can never reach FocusArcade's
 * Enter -> A mapping (`consoleKeys`), which would activate the selected card.
 */
private fun Modifier.enterSubmitsWhileEditing(
    editing: () -> Boolean,
    submit: () -> Boolean,
): Modifier = onPreviewKeyEvent { e ->
    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
    when (e.key) {
        Key.Enter, Key.NumPadEnter, Key.DirectionCenter ->
            if (editing()) submit() else false
        else -> false
    }
}

/**
 * Group-label row: 26 px tall, no grid gap above or below
 * (the 10 px above a label is the previous card's per-item padding; the
 * first label sits 6 px down so the first card starts at y 128). The label
 * itself keeps its 36 px box, bottom-aligned 8 px above the row floor — the
 * mockup's `.gl` padding — shifted up out of the row's bounds.
 */
private fun Modifier.labelRowModifier(g: ConsoleGeometry, first: Boolean): Modifier =
    this
        .then(if (first) Modifier.padding(top = g.x(6)) else Modifier)
        .size(width = g.x(848), height = g.x(26))
        .layout { measurable, constraints ->
            val placeable = measurable.measure(
                constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity),
            )
            layout(constraints.maxWidth, constraints.maxHeight) {
                placeable.place(0, constraints.maxHeight - placeable.height - g.x(8).roundToPx())
            }
        }

/** Grid item index of every card: one group-label item per section, then cards. */
private fun cardItemIndices(model: HomeScreenModel): Map<CardKey, Int> {
    val out = LinkedHashMap<CardKey, Int>()
    var index = 0
    for (section in model.sections) {
        index++ // the group label item
        for (card in section.cards) out[card.key] = index++
    }
    return out
}

/** Bring the row holding [key] fully into view with [margin]px to spare. */
private suspend fun bringRowIntoView(
    gridState: LazyGridState,
    model: HomeScreenModel,
    key: CardKey,
    marginPx: Float,
) {
    val row = HomeFocus.rows(model).firstOrNull { key in it } ?: return
    val indices = cardItemIndices(model)
    val first = indices[row.first()] ?: return
    val last = indices[row.last()] ?: return
    val visible = gridState.layoutInfo.visibleItemsInfo.map { it.index }
    if (first !in visible || last !in visible) {
        // Far away (or first frame): jump to the row, then fine-tune below.
        gridState.scrollToItem(first)
    }
    val info = gridState.layoutInfo
    val top = info.visibleItemsInfo.firstOrNull { it.index == first } ?: return
    val bottom = info.visibleItemsInfo.firstOrNull { it.index == last } ?: return
    val delta = HomeFocus.minimalScrollDelta(
        viewportStart = info.viewportStartOffset.toFloat(),
        viewportEnd = info.viewportEndOffset.toFloat(),
        itemStart = top.offset.y.toFloat(),
        itemEnd = (bottom.offset.y + bottom.size.height).toFloat(),
        margin = marginPx,
    )
    if (delta != 0f) gridState.scrollBy(delta)
}
