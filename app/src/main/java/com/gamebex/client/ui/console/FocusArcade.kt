package com.gamebex.client.ui.console

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.focusable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription

/**
 * Focus model: explicit selection state per shelf, never incidental
 * Compose ordering. Screens route all controller input
 * through [consoleKeys]; visuals read selection state; [FocusRequester]s
 * only mirror selection for accessibility.
 */

/** Selection within one shelf/row. */
class ShelfState(count: Int, initial: Int = 0) {
    var count: Int = count
        private set

    /** Pending selection while empty: applied when data arrives. */
    private var pending: Int? = if (count <= 0 && initial != 0) initial else null

    var selected by mutableIntStateOf(initial.coerceIn(0, maxOf(count - 1, 0)))

    fun resize(newCount: Int) {
        val keep = selected
        count = newCount
        val p = pending
        if (p != null && newCount > 0) {
            pending = null
            selected = p.coerceIn(0, newCount - 1)
        } else {
            selected = keep.coerceIn(0, maxOf(newCount - 1, 0))
        }
        if (newCount <= 0) pending = pending ?: keep
    }

    /** False at the edge (caller may move to rail/elsewhere). */
    fun left(): Boolean = if (selected > 0) {
        selected--
        true
    } else false

    fun right(): Boolean = if (selected < count - 1) {
        selected++
        true
    } else false

    fun select(i: Int) {
        if (count <= 0) {
            pending = i
            selected = 0
        } else {
            pending = null
            selected = i.coerceIn(0, count - 1)
        }
    }

    /**
     * Keeps the remembered item selected across insertions and removals;
     * an unknown id keeps the current position.
     */
    fun selectStableId(id: Any?, ids: List<Any?>) {
        pending = null
        selected = stableIndexFor(ids, id, selected).coerceIn(0, maxOf(count - 1, 0))
    }
}

fun stableIndexFor(ids: List<Any?>, id: Any?, fallback: Int): Int {
    if (id == null || ids.isEmpty()) return fallback
    return ids.indexOf(id).takeIf { it >= 0 } ?: fallback
}

@Composable
fun rememberShelfState(count: Int, initial: Int = 0): ShelfState {
    val s = remember { ShelfState(count, initial) }
    s.resize(count)
    return s
}

/**
 * Vertical-list counterpart to AnchoredShelf: keeps [selected] on screen as
 * D-pad/joystick moves it. Without this the focus ring walks off the visible
 * window and only touch scrolling reveals it. Instant when reduced motion.
 */
@Composable
fun FollowSelected(listState: androidx.compose.foundation.lazy.LazyListState, selected: Int, size: Int) {
    val reducedMotion = isSystemReducedMotion()
    LaunchedEffect(selected, size) {
        if (size <= 0) return@LaunchedEffect
        val i = selected.coerceIn(0, size - 1)
        if (reducedMotion) listState.scrollToItem(i) else listState.animateScrollToItem(i)
    }
}

/** Nearest-center-X pick for vertical shelf travel. */
fun nearestIndexByX(centers: List<Float>, x: Float): Int {
    if (centers.isEmpty()) return 0
    var best = 0
    var bestD = Float.MAX_VALUE
    for (i in centers.indices) {
        val d = kotlin.math.abs(centers[i] - x)
        if (d < bestD) {
            bestD = d
            best = i
        }
    }
    return best
}

/** Per-destination focus memory: tag -> selected index. */
class ConsoleFocusMemory(private val saved: SavedStateHandle? = null) {
    private val mem = mutableStateMapOf<String, Int>()
    private val keyMem = mutableStateMapOf<String, String>()
    fun recall(tag: String, fallback: Int = 0): Int =
        mem[tag] ?: saved?.get<Int>("fm:$tag")?.also { mem[tag] = it } ?: fallback
    fun has(tag: String): Boolean = mem.containsKey(tag) || saved?.contains("fm:$tag") == true
    fun store(tag: String, index: Int) {
        mem[tag] = index
        saved?.set("fm:$tag", index)
    }

    /** Stable-ID memory: survives insertions that shift indices. */
    fun recallKey(tag: String): String? =
        keyMem[tag] ?: saved?.get<String>("fk:$tag")?.also { keyMem[tag] = it }
    fun hasKey(tag: String): Boolean = keyMem.containsKey(tag) || saved?.contains("fk:$tag") == true
    fun storeKey(tag: String, id: String) {
        keyMem[tag] = id
        saved?.set("fk:$tag", id)
    }
    fun clearKey(tag: String) {
        keyMem.remove(tag)
        saved?.remove<String>("fk:$tag")
    }
}

/** Holder so focus memory rides a SavedStateHandle (process-death safe). */
class FocusMemoryHolder(val handle: SavedStateHandle) : ViewModel()

@Composable
fun rememberConsoleFocusMemory(): ConsoleFocusMemory {
    val holder: FocusMemoryHolder = viewModel()
    return remember { ConsoleFocusMemory(holder.handle) }
}

/** Repeats drive navigation only; a held action button must not re-fire. */
fun allowKeyRepeat(isNavigation: Boolean, repeatCount: Int): Boolean =
    isNavigation || repeatCount <= 0

/**
 * One controller router. Handlers return true when consumed. KeyDown only:
 * deterministic under UI tests (down+up pairs fire once) and responsive
 * under key repeat for navigation. Held action buttons are consumed once:
 * repeats are swallowed so they can't double-activate.
 */
fun Modifier.consoleKeys(
    onLeft: (() -> Boolean)? = null,
    onRight: (() -> Boolean)? = null,
    onUp: (() -> Boolean)? = null,
    onDown: (() -> Boolean)? = null,
    onA: (() -> Boolean)? = null,
    onB: (() -> Boolean)? = null,
    onX: (() -> Boolean)? = null,
    onY: (() -> Boolean)? = null,
    onStart: (() -> Boolean)? = null,
    onLB: (() -> Boolean)? = null,
    onRB: (() -> Boolean)? = null,
    /** Select/View/Back-glyph button (◉): secondary context actions. */
    onSelect: (() -> Boolean)? = null,
): Modifier = onPreviewKeyEvent { e ->
    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
    val repeat = e.nativeKeyEvent?.repeatCount ?: 0
    fun nav(h: (() -> Boolean)?): Boolean {
        if (!allowKeyRepeat(true, repeat)) return true
        return h?.invoke() ?: false
    }
    fun act(h: (() -> Boolean)?): Boolean {
        if (!allowKeyRepeat(false, repeat)) return true
        return h?.invoke() ?: false
    }
    when (e.key) {
        Key.DirectionLeft -> nav(onLeft)
        Key.DirectionRight -> nav(onRight)
        Key.DirectionUp -> nav(onUp)
        Key.DirectionDown -> nav(onDown)
        Key.ButtonA, Key.Enter, Key.NumPadEnter, Key.DirectionCenter -> act(onA)
        Key.ButtonB, Key.Back, Key.Escape -> act(onB)
        Key.ButtonX -> act(onX)
        Key.ButtonY -> act(onY)
        Key.ButtonStart -> act(onStart)
        Key.ButtonL1 -> nav(onLB)
        Key.ButtonR1 -> nav(onRB)
        Key.ButtonSelect -> act(onSelect)
        else -> false
    }
}

/**
 * Syncs visual selection to accessibility: the rooted focus node never moves,
 * so each selectable exposes its own selected state + label for TalkBack.
 */
fun Modifier.consoleSelected(selected: Boolean, label: String): Modifier =
    semantics {
        this.selected = selected
        contentDescription = label
        stateDescription = if (selected) "Selected" else "Not selected"
    }

/** Mirror ring: keeps one real focus node synced to selection (a11y). */
@Composable
fun rememberMirrorRequester(): FocusRequester = remember { FocusRequester() }

/**
 * Screen focus anchor: every console screen holds real Compose
 * focus on its root so controller keys always traverse the screen's
 * [consoleKeys] router. Without this, the first key after launch (or after
 * navigation disposes the focused node) is eaten by the focus system to
 * grant initial focus, and looks like a dropped button press.
 */
@Composable
fun rememberScreenFocus(): FocusRequester {
    val r = remember { FocusRequester() }
    // Entry grant only: at composition plus one next-frame retry for the
    // nav-disposal race. Same-frame retries can't steal from dialogs; later
    // re-grants tie to ON_RESUME, never a timer.
    LaunchedEffect(r) {
        r.requestFocus()
        androidx.compose.runtime.withFrameNanos { }
        r.requestFocus()
    }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle, r) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, ev ->
            // Resume can race recomposition (e.g. returning from the
            // external file picker): a focus grant must never crash.
            if (ev == androidx.lifecycle.Lifecycle.Event.ON_RESUME) runCatching { r.requestFocus() }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    return r
}

fun Modifier.screenFocus(r: FocusRequester): Modifier =
    this
        .focusRequester(r)
        .focusable()
