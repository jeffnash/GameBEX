package com.gamebex.client.ui.console

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight

/** Logical controller actions (Xbox names); [glyphLabel] prints the real button. */
enum class ControllerGlyph(val label: String) {
    A("A"), B("B"), X("X"), Y("Y"), Start("≡"), Back("◉"),
    LB("LB"), RB("RB"),
}

@Composable
fun ControllerGlyphBadge(
    glyph: ControllerGlyph,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    fill: Color = ConsoleColors.TextPrimary,
) {
    val g = LocalConsoleGeometry.current
    // Wrap-content with a fixed line box: never clips the glyph, never lets
    // tall OEM font metrics inflate the parent (CTA height stays ~54).
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = g.x(24), minHeight = g.x(24))
            .clip(CircleShape)
            .background(if (filled) fill else Color.Transparent)
            .border(g.x(1.5f), if (filled) fill else ConsoleColors.TextSecondary, CircleShape)
            .padding(horizontal = g.x(7), vertical = g.x(3)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            glyphLabel(glyph, ControllerPrefs.layout, ControllerPrefs.swapConfirm),
            color = if (filled) ConsoleColors.Background else ConsoleColors.TextPrimary,
            fontSize = g.t(15),
            lineHeight = g.t(18),
            fontWeight = FontWeight.Bold,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

data class ControllerHint(val glyph: ControllerGlyph?, val text: String)

/**
 * Dynamic hint channel: the scaffold owns the hint slot, but screens like
 * Game Detail compute their hints from live state. The screen writes via
 * [set]; the scaffold reads [hints].
 */
class ConsoleHints {
    var hints: List<ControllerHint> by androidx.compose.runtime.mutableStateOf<List<ControllerHint>>(emptyList())
    fun set(next: List<ControllerHint>) {
        hints = next
    }
}

@Composable
fun rememberConsoleHints(): ConsoleHints = androidx.compose.runtime.remember { ConsoleHints() }

/** Android keycode a controller sends for [glyph]'s logical action. */
fun keyCodeFor(glyph: ControllerGlyph): Int = when (glyph) {
    ControllerGlyph.A -> android.view.KeyEvent.KEYCODE_BUTTON_A
    ControllerGlyph.B -> android.view.KeyEvent.KEYCODE_BUTTON_B
    ControllerGlyph.X -> android.view.KeyEvent.KEYCODE_BUTTON_X
    ControllerGlyph.Y -> android.view.KeyEvent.KEYCODE_BUTTON_Y
    ControllerGlyph.Start -> android.view.KeyEvent.KEYCODE_BUTTON_START
    ControllerGlyph.Back -> android.view.KeyEvent.KEYCODE_BUTTON_SELECT
    ControllerGlyph.LB -> android.view.KeyEvent.KEYCODE_BUTTON_L1
    ControllerGlyph.RB -> android.view.KeyEvent.KEYCODE_BUTTON_R1
}

/**
 * Touch path for a hint: replays the button press through the Activity so
 * the focused screen handles it exactly like a pad press. The Activity
 * applies the confirm/back swap on the way in; the swap is its own
 * inverse, so pre-applying it here lands on the logical button.
 */
private fun pressGlyph(activity: android.app.Activity, glyph: ControllerGlyph) {
    val code = remapGamepadKey(keyCodeFor(glyph), ControllerPrefs.swapConfirm)
    val now = android.os.SystemClock.uptimeMillis()
    for (action in intArrayOf(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.ACTION_UP)) {
        activity.dispatchKeyEvent(
            android.view.KeyEvent(
                now, now, action, code, 0, 0,
                android.view.KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0,
                android.view.InputDevice.SOURCE_GAMEPAD,
            ),
        )
    }
}

enum class HintPillBackground { AlwaysOn, PressOnly }

fun hintPillFilled(glyph: ControllerGlyph?, emphasizePrimary: Boolean): Boolean =
    emphasizePrimary && glyph == ControllerGlyph.A

fun hintPillBackgroundMode(emphasizePrimary: Boolean): HintPillBackground =
    if (emphasizePrimary) HintPillBackground.PressOnly else HintPillBackground.AlwaysOn

/** Value of [ControllerHintBar]'s emphasizePrimary argument, for call-site tests. */
val HintBarEmphasizePrimary = SemanticsPropertyKey<Boolean>("HintBarEmphasizePrimary")

/**
 * Every glyph hint is also a touch target (tap = that button), so nothing is
 * controller-only. Taps never take focus: the pad's focused item stays the
 * action target. [emphasizePrimary] is Home's look: press-only pill
 * background and a filled pink A glyph.
 */
@Composable
fun ControllerHintBar(
    hints: List<ControllerHint>,
    modifier: Modifier = Modifier,
    emphasizePrimary: Boolean = false,
) {
    val g = LocalConsoleGeometry.current
    val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
    Row(
        modifier = modifier.semantics {
            this[HintBarEmphasizePrimary] = emphasizePrimary
        },
        horizontalArrangement = Arrangement.spacedBy(g.x(10)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (h in hints) {
            val glyph = h.glyph
            val tappable = glyph != null && activity != null
            val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            val pressed by interactionSource.collectIsPressedAsState()
            Box(
                modifier = Modifier
                    .sizeIn(minWidth = g.hitMin(g.x(84)), minHeight = g.hitMin(g.x(84)))
                    .then(
                        if (tappable && glyph != null && activity != null) {
                            Modifier.clickable(
                                interactionSource = interactionSource,
                                indication = null,
                            ) { pressGlyph(activity, glyph) }
                        } else {
                            Modifier
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    modifier = Modifier
                        .then(
                            when (hintPillBackgroundMode(emphasizePrimary)) {
                                HintPillBackground.PressOnly -> Modifier.background(
                                    if (pressed) ConsoleColors.HighSurface else Color.Transparent,
                                    androidx.compose.foundation.shape.RoundedCornerShape(g.x(10)),
                                )
                                HintPillBackground.AlwaysOn ->
                                    if (tappable) {
                                        Modifier
                                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
                                            .background(ConsoleColors.Surface.copy(alpha = 0.72f))
                                    } else {
                                        Modifier
                                    }
                            },
                        )
                        .defaultMinSize(minHeight = g.x(48))
                        .padding(horizontal = g.x(12), vertical = g.x(4)),
                    horizontalArrangement = Arrangement.spacedBy(g.x(8)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (glyph != null) {
                        if (hintPillFilled(glyph, emphasizePrimary)) {
                            ControllerGlyphBadge(glyph, filled = true, fill = ConsoleColors.Accent)
                        } else {
                            ControllerGlyphBadge(glyph)
                        }
                    }
                    Text(
                        h.text,
                        color = ConsoleColors.TextSecondary,
                        fontSize = g.t(17),
                        lineHeight = g.t(20),
                    )
                }
            }
        }
    }
}
