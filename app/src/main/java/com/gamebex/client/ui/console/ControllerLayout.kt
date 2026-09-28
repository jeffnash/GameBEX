package com.gamebex.client.ui.console

import android.content.Context
import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Which printed labels the player's pad uses (positions are Android's: A = south). */
enum class ControllerLayout(val label: String) {
    Xbox("Xbox"), Nintendo("Nintendo"), PlayStation("PlayStation"),
}

/**
 * Controller preferences. Compose state so hint glyphs relabel live; the
 * key swap itself happens once in MainActivity.dispatchKeyEvent, so every
 * screen and the system's own fallbacks (B → Back) agree on the mapping.
 */
object ControllerPrefs {
    private const val FILE = "controller"
    var layout by mutableStateOf(ControllerLayout.Xbox)
        private set
    /** True: the east button confirms and south goes back (Nintendo habit). */
    var swapConfirm by mutableStateOf(false)
        private set

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        layout = runCatching { ControllerLayout.valueOf(p.getString("layout", null) ?: "") }
            .getOrDefault(ControllerLayout.Xbox)
        swapConfirm = p.getBoolean("swap", false)
    }

    fun set(ctx: Context, layout: ControllerLayout, swapConfirm: Boolean) {
        this.layout = layout
        this.swapConfirm = swapConfirm
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString("layout", layout.name)
            .putBoolean("swap", swapConfirm)
            .apply()
    }
}

/** Face-button swap (A↔B, X↔Y) when [swap]; every other key passes through. */
fun remapGamepadKey(keyCode: Int, swap: Boolean): Int {
    if (!swap) return keyCode
    return when (keyCode) {
        KeyEvent.KEYCODE_BUTTON_A -> KeyEvent.KEYCODE_BUTTON_B
        KeyEvent.KEYCODE_BUTTON_B -> KeyEvent.KEYCODE_BUTTON_A
        KeyEvent.KEYCODE_BUTTON_X -> KeyEvent.KEYCODE_BUTTON_Y
        KeyEvent.KEYCODE_BUTTON_Y -> KeyEvent.KEYCODE_BUTTON_X
        else -> keyCode
    }
}

/**
 * Printed label of the physical button that performs [glyph]'s action.
 * [glyph] names the logical action (A = confirm); with [swap] the action
 * lives on the opposite face button, then [layout] names that button.
 */
fun glyphLabel(glyph: ControllerGlyph, layout: ControllerLayout, swap: Boolean): String {
    val physical = if (!swap) glyph else when (glyph) {
        ControllerGlyph.A -> ControllerGlyph.B
        ControllerGlyph.B -> ControllerGlyph.A
        ControllerGlyph.X -> ControllerGlyph.Y
        ControllerGlyph.Y -> ControllerGlyph.X
        else -> glyph
    }
    return when (layout) {
        ControllerLayout.Xbox -> physical.label
        ControllerLayout.Nintendo -> when (physical) {
            ControllerGlyph.A -> "B"
            ControllerGlyph.B -> "A"
            ControllerGlyph.X -> "Y"
            ControllerGlyph.Y -> "X"
            ControllerGlyph.LB -> "L"
            ControllerGlyph.RB -> "R"
            else -> physical.label
        }
        ControllerLayout.PlayStation -> when (physical) {
            ControllerGlyph.A -> "✕"
            ControllerGlyph.B -> "○"
            ControllerGlyph.X -> "□"
            ControllerGlyph.Y -> "△"
            ControllerGlyph.LB -> "L1"
            ControllerGlyph.RB -> "R1"
            else -> physical.label
        }
    }
}

/**
 * Console text size. A handheld in a controller grip sits farther from the
 * eye than a phone, and canvas body type (17px) lands near 1.6mm on a
 * 263ppi 6.7" panel, so Large is the default.
 */
enum class TextSize(val label: String, val factor: Float) {
    Default("Default", 1f), Large("Large", 1.25f), Larger("Larger", 1.4f),
}

object DisplayPrefs {
    private const val FILE = "display"
    var textSize by mutableStateOf(TextSize.Large)
        private set

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        textSize = runCatching { TextSize.valueOf(p.getString("text", null) ?: "") }
            .getOrDefault(TextSize.Large)
    }

    fun set(ctx: Context, size: TextSize) {
        textSize = size
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString("text", size.name).apply()
    }
}
