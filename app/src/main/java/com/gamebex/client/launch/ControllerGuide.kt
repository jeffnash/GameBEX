package com.gamebex.client.launch

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One-time guided card for what GameBEX can't automate: NetherSX2 keeps
 * button bindings in private storage, so each new controller needs one
 * Automatic Mapping inside it. Shown before a PS2 launch with a pad
 * connected, once per controller ("Got it") or never again ("Don't show
 * again"). The launch waits for the card only while a host is on screen.
 */
object ControllerGuide {
    private const val PREFS = "controller_guide"
    private const val KEY_NEVER = "nether_never"
    private const val KEY_SEEN = "nether_seen_pads"
    private const val WAIT_MS = 10 * 60_000L

    data class Card(val padName: String, val padKey: String, internal val done: CompletableDeferred<Unit>)

    private val _card = MutableStateFlow<Card?>(null)
    val card: StateFlow<Card?> = _card

    /** Hosts (the activity's dialog slot) currently composed. */
    @Volatile var hosts: Int = 0

    fun padKey(pad: ControllerRegistry.Pad): String = pad.descriptor.ifBlank { pad.name }

    fun padToGuide(never: Boolean, seen: Set<String>, pads: List<ControllerRegistry.Pad>): ControllerRegistry.Pad? =
        if (never) null else pads.firstOrNull { padKey(it) !in seen }

    /** Suspends until the card is answered (or immediately when not needed). */
    suspend fun awaitNetherCard(ctx: Context, pads: List<ControllerRegistry.Pad>) {
        if (hosts <= 0) return
        val p = prefs(ctx)
        val pad = padToGuide(
            p.getBoolean(KEY_NEVER, false),
            p.getStringSet(KEY_SEEN, emptySet()).orEmpty(),
            pads,
        ) ?: return
        val c = Card(pad.label, padKey(pad), CompletableDeferred())
        _card.value = c
        try {
            withTimeoutOrNull(WAIT_MS) { c.done.await() }
        } finally {
            _card.compareAndSet(c, null)
        }
    }

    fun gotIt(ctx: Context, c: Card) {
        val p = prefs(ctx)
        val seen = p.getStringSet(KEY_SEEN, emptySet()).orEmpty() + c.padKey
        p.edit().putStringSet(KEY_SEEN, seen).apply()
        close(c)
    }

    fun neverAgain(ctx: Context, c: Card) {
        prefs(ctx).edit().putBoolean(KEY_NEVER, true).apply()
        close(c)
    }

    /** Back/outside tap: launch continues, the card returns next time. */
    fun dismiss(c: Card) = close(c)

    private fun close(c: Card) {
        _card.compareAndSet(c, null)
        c.done.complete(Unit)
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
