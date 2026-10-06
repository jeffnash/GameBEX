package com.gamebex.client.ui.system

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.gamebex.client.ui.console.ConsoleColors
import com.gamebex.client.ui.console.ConsoleDesign
import com.gamebex.client.ui.console.ConsoleFocusMemory
import com.gamebex.client.ui.console.ConsoleHints
import com.gamebex.client.ui.console.ControllerGlyph
import com.gamebex.client.ui.console.ControllerHint
import com.gamebex.client.ui.console.LocalConsoleGeometry
import com.gamebex.client.ui.console.RailHost
import com.gamebex.client.ui.console.consoleKeys
import com.gamebex.client.ui.console.rememberScreenFocus
import com.gamebex.client.ui.console.screenFocus

/**
 * Advanced section (Settings/System → Device → Advanced): power-user screens.
 */
@Composable
fun AdvancedScreen(
    onBack: () -> Unit,
    onStreamFlags: () -> Unit,
    rail: RailHost,
    focusMemory: ConsoleFocusMemory,
    hints: ConsoleHints? = null,
) {
    val g = LocalConsoleGeometry.current
    LaunchedEffect(Unit) {
        hints?.set(
            listOf(
                ControllerHint(ControllerGlyph.A, "Select"),
                ControllerHint(ControllerGlyph.B, "Back"),
            ),
        )
    }
    val screenFocus = rememberScreenFocus()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag("advanced-screen")
            .screenFocus(screenFocus)
            .consoleKeys(
                onLeft = { rail.enterRail() },
                onA = { onStreamFlags(); true },
                onB = { onBack(); true },
            ),
    ) {
        Column(modifier = Modifier.offset(x = g.x(48), y = g.x(30)).width(g.x(1300))) {
            Text(
                "SYSTEM",
                color = ConsoleColors.TextTertiary, fontSize = g.tMin(15),
                lineHeight = g.tMin(18), fontWeight = FontWeight.Bold,
            )
            Text(
                "ADVANCED",
                color = ConsoleColors.TextPrimary, fontSize = g.t(36),
                lineHeight = g.t(40), fontWeight = FontWeight.Black,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = g.x(8)),
            )
            Text(
                "Power-user settings for this phone",
                color = ConsoleColors.TextSecondary, fontSize = g.t(17),
                lineHeight = g.t(20), maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = g.x(4)),
            )
            val shape = RoundedCornerShape(g.x(ConsoleDesign.RADIUS_CARD))
            Row(
                modifier = Modifier
                    .padding(top = g.x(24))
                    .heightIn(min = g.x(72))
                    .clip(shape)
                    .background(ConsoleColors.Elevated)
                    .border(g.x(ConsoleDesign.FOCUS_STROKE), ConsoleColors.FocusRing, shape)
                    .clickable { onStreamFlags() }
                    .testTag("advanced-stream-flags")
                    .padding(horizontal = g.x(16), vertical = g.x(10)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Stream flags",
                        color = ConsoleColors.TextPrimary, fontSize = g.t(17),
                        lineHeight = g.t(22), fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "Both phones · per-phone tuning switches for streaming",
                        color = ConsoleColors.TextSecondary, fontSize = g.tMin(14),
                        lineHeight = g.tMin(18), maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
