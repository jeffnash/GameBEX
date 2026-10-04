package com.gamebex.client.ui.together

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import com.gamebex.client.ui.console.ConsoleGeometry
import com.gamebex.client.ui.console.LocalConsoleGeometry
import androidx.compose.ui.input.key.KeyEvent as ComposeKeyEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.performKeyPress
import com.gamebex.client.ui.GameBexTheme
import com.gamebex.client.ui.console.ConsoleGeometryRoot
import com.gamebex.client.ui.console.ConsoleHints
import com.gamebex.client.ui.console.RailHost
import com.gamebex.client.ui.console.rememberConsoleHints

internal object TogetherUiTestSupport {
    val rail = object : RailHost { override fun enterRail() = true }
}

internal fun ComposeContentTestRule.setTogetherContent(
    fontScale: Float? = null,
    content: @Composable (ConsoleHints) -> Unit,
) {
    setContent {
        val screen = @Composable { GameBexTheme { ConsoleGeometryRoot { val hints = rememberConsoleHints(); content(hints) } } }
        if (fontScale == null) {
            screen()
        } else {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) { screen() }
        }
    }
}

/** The theme, console canvas and hints the together screens expect. */
@Composable
internal fun TogetherTestFrame(content: @Composable (ConsoleHints) -> Unit) {
    GameBexTheme { ConsoleGeometryRoot { val hints = rememberConsoleHints(); content(hints) } }
}

/** GameBEX text size and system font scale for a layout test; change them between checks. */
internal class TestDisplay(textScale: Float = 1f, fontScale: Float = 1f) {
    var textScale by mutableFloatStateOf(textScale)
    var fontScale by mutableFloatStateOf(fontScale)
}

/** A Moto G 2025 window in landscape, in dp. */
internal val PHONE_WINDOW = DpSize(914.dp, 411.dp)

/**
 * Renders [content] on a phone-shaped console canvas at [display]'s GameBEX text
 * size (DisplayPrefs factor) and system font scale. The canvas can be wider than
 * the test window, so measure with [box], not the clipped bounds.
 */
internal fun ComposeContentTestRule.setTogetherContentAt(display: TestDisplay, content: @Composable (ConsoleHints) -> Unit) {
    setContent {
        val density = LocalDensity.current.density
        CompositionLocalProvider(LocalDensity provides Density(density, display.fontScale)) {
            GameBexTheme {
                Box(Modifier.requiredSize(PHONE_WINDOW)) {
                    ConsoleGeometryRoot {
                        val scale = LocalConsoleGeometry.current.scale
                        CompositionLocalProvider(LocalConsoleGeometry provides ConsoleGeometry(scale, display.textScale)) {
                            val hints = rememberConsoleHints()
                            content(hints)
                        }
                    }
                }
            }
        }
    }
}

/** Unclipped bounds in root pixels. */
internal fun SemanticsNodeInteraction.box(): Rect {
    val node = fetchSemanticsNode()
    return Rect(node.positionInRoot, node.size.toSize())
}

/** True when a Text node's laid-out lines don't fit its height or its maxLines (it is cut). */
internal fun SemanticsNodeInteraction.textIsCut(): Boolean {
    val results = mutableListOf<TextLayoutResult>()
    fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
    return results.single().didOverflowHeight
}

private fun Key.androidKeyCode(): Int = when (this) {
    Key.ButtonA -> AndroidKeyEvent.KEYCODE_BUTTON_A
    Key.ButtonB -> AndroidKeyEvent.KEYCODE_BUTTON_B
    Key.ButtonY -> AndroidKeyEvent.KEYCODE_BUTTON_Y
    Key.DirectionDown -> AndroidKeyEvent.KEYCODE_DPAD_DOWN
    Key.DirectionUp -> AndroidKeyEvent.KEYCODE_DPAD_UP
    Key.DirectionLeft -> AndroidKeyEvent.KEYCODE_DPAD_LEFT
    Key.DirectionRight -> AndroidKeyEvent.KEYCODE_DPAD_RIGHT
    else -> error("Unsupported test key: $this")
}

internal class TogetherKeyInputScope(private val interaction: SemanticsNodeInteraction) {
    @OptIn(ExperimentalTestApi::class)
    fun pressKey(key: Key) {
        val down = AndroidKeyEvent(AndroidKeyEvent.ACTION_DOWN, key.androidKeyCode())
        interaction.performKeyPress(ComposeKeyEvent(down))
        runCatching { interaction.performKeyPress(ComposeKeyEvent(AndroidKeyEvent.changeAction(down, AndroidKeyEvent.ACTION_UP))) }
    }
}

@OptIn(ExperimentalTestApi::class)
internal fun SemanticsNodeInteraction.performKeyInput(
    block: TogetherKeyInputScope.() -> Unit,
): SemanticsNodeInteraction {
    TogetherKeyInputScope(this).block()
    return this
}
