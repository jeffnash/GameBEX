package com.gamebex.client.ui.home.components

import kotlin.math.min

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.gamebex.client.ui.SpaceGrotesk
import com.gamebex.client.ui.console.ConsoleColors
import com.gamebex.client.ui.console.ConsoleGeometry
import com.gamebex.client.ui.console.LocalConsoleGeometry
import com.gamebex.client.ui.home.model.HomeCard
import com.gamebex.client.ui.home.model.InviteKind

/** Setup / roll invite, 848×104: an 84×84 icon well, the title and the body. */
@Composable
internal fun InviteCard(
    card: HomeCard,
    selected: Boolean,
    focused: Boolean,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val g = LocalConsoleGeometry.current
    val kind = inviteKindOf(card)
    val ts = homeTextScale(g)
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
            val tint = if (kind == InviteKind.CHECK_SYSTEMS) {
                ConsoleColors.Warning
            } else {
                ConsoleColors.Accent
            }
            Box(
                modifier = Modifier
                    .size(g.x(84))
                    .border(
                        g.x(2),
                        ConsoleColors.Steel,
                        RoundedCornerShape(g.x(10)),
                    )
                    .background(ConsoleColors.HighSurface, RoundedCornerShape(g.x(10))),
                contentAlignment = Alignment.Center,
            ) {
                InviteGlyph(kind, tint, Modifier.size(g.x(36)))
            }
            Spacer(Modifier.width(g.x(16)))
            Column(verticalArrangement = Arrangement.Center) {
                Text(
                    text = card.title,
                    color = ConsoleColors.TextPrimary,
                    fontFamily = SpaceGrotesk,
                    fontSize = (28f * g.scale * ts).sp,
                    lineHeight = (28f * g.scale * ts).sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.size(g.x(5)))
                Text(
                    text = card.body.orEmpty(),
                    color = ConsoleColors.TextSecondary,
                    fontSize = (18f * g.scale * ts).sp,
                    lineHeight = (22f * g.scale * ts).sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.heightIn(max = g.x(84f - 5f - 28f * ts)),
                )
            }
        }
    }
}

/** `HomeCard` has no kind field; the CardKey carries it ("invite:SCAN_PHONE"). */
internal fun inviteKindOf(card: HomeCard): InviteKind? =
    card.key.value.removePrefix("invite:")
        .let { name -> InviteKind.entries.firstOrNull { it.name == name } }

/** Scan phone uses the folder glyph to match its TV notice ("FIND YOUR GAMES"). */
internal enum class InviteIcon { PLUG, FOLDER, WARN, DICE }

internal fun inviteIconOf(kind: InviteKind?): InviteIcon = when (kind) {
    InviteKind.SCAN_PHONE -> InviteIcon.FOLDER
    InviteKind.CHECK_SYSTEMS -> InviteIcon.WARN
    InviteKind.ROLL_AGAIN -> InviteIcon.DICE
    // ADD_PLUGIN and unknown kinds.
    else -> InviteIcon.PLUG
}

@Composable
private fun InviteGlyph(kind: InviteKind?, tint: Color, modifier: Modifier) {
    when (inviteIconOf(kind)) {
        InviteIcon.FOLDER -> FolderGlyph(tint, modifier)
        InviteIcon.WARN -> WarnGlyph(tint, modifier)
        InviteIcon.DICE -> DiceGlyph(tint, modifier)
        InviteIcon.PLUG -> PlugGlyph(tint, modifier)
    }
}

private fun homeTextScale(g: ConsoleGeometry): Float = min(g.textScale, 1.1f)
