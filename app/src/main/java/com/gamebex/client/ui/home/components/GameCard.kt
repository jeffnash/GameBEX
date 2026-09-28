package com.gamebex.client.ui.home.components

import kotlin.math.min

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.gamebex.client.ui.SpaceGrotesk
import com.gamebex.client.ui.console.ConsoleColors
import com.gamebex.client.ui.console.ConsoleGeometry
import com.gamebex.client.ui.console.LocalConsoleGeometry
import com.gamebex.client.ui.home.model.HomeCard

/** Game card, 417×104: a 112×84 thumbnail, a title of up to 2 lines and the state line. */
@Composable
internal fun GameCard(
    card: HomeCard,
    selected: Boolean,
    focused: Boolean,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val g = LocalConsoleGeometry.current
    val ts = homeTextScale(g)
    // The 84 px content box holds the title, a 2 px gap and one state line.
    val stateH = 22f * ts
    val titleH = 84f - 2f - stateH
    val titlePx = titleFontPx(titleH, ts)
    val titleSp: TextUnit = (titlePx * g.scale * ts).sp
    CardBox(
        selected = selected,
        focused = focused,
        onTap = onTap,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = g.x(10),
                    top = g.x(10),
                    bottom = g.x(10),
                    end = g.x(14),
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CardThumb(
                art = card.thumb,
                title = card.title,
                state = card.state,
                systemFolder = card.system?.folder,
            )
            Spacer(Modifier.width(g.x(16)))
            Column(
                modifier = Modifier.fillMaxHeight(),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = card.title,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = cardTitleStyle(
                        fontSize = titleSp,
                        color = ConsoleColors.TextPrimary,
                        fontFamily = SpaceGrotesk,
                        fontWeight = FontWeight.Bold,
                    ),
                    modifier = Modifier.heightIn(max = g.x(titleH)),
                )
                Spacer(Modifier.height(g.x(2)))
                StateLine(
                    card = card,
                    modifier = Modifier.heightIn(max = g.x(stateH)),
                )
            }
        }
    }
}

private fun homeTextScale(g: ConsoleGeometry): Float = min(g.textScale, 1.1f)
