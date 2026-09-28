package com.gamebex.client.ui.home.components

import kotlin.math.min

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.gamebex.client.ui.SpaceGrotesk
import com.gamebex.client.ui.console.ConsoleColors
import com.gamebex.client.ui.console.ConsoleGeometry
import com.gamebex.client.ui.console.LocalConsoleGeometry
import com.gamebex.client.ui.home.model.ArtRef
import com.gamebex.client.ui.home.model.HomeCard

/**
 * "All {N}" tile, 417×104: a mini stack of up to 3 cases, or a download glyph
 * when the group has nothing to stack, then the title and a group-specific line.
 */
@Composable
internal fun AllTile(
    card: HomeCard,
    selected: Boolean,
    focused: Boolean,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val g = LocalConsoleGeometry.current
    val ts = homeTextScale(g)
    // Same title line budget as GameCard.
    val bodyH = 22f * ts
    val titleH = 84f - 2f - bodyH
    val titlePx = titleFontPx(titleH, ts)
    CardBox(
        selected = selected,
        focused = focused,
        onTap = onTap,
        modifier = modifier,
        fill = ConsoleColors.Background,
        dashed = true,
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
            StackArt(card.stack)
            Spacer(Modifier.width(g.x(16)))
            Column {
                Text(
                    text = card.title,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = cardTitleStyle(
                        fontSize = (titlePx * g.scale * ts).sp,
                        color = ConsoleColors.TextPrimary,
                        fontFamily = SpaceGrotesk,
                        fontWeight = FontWeight.Bold,
                    ),
                    modifier = Modifier.heightIn(max = g.x(titleH)),
                )
                Spacer(Modifier.height(g.x(2)))
                Text(
                    text = card.body.orEmpty(),
                    color = ConsoleColors.TextSecondary,
                    fontSize = (18f * g.scale * ts).sp,
                    lineHeight = (22f * g.scale * ts).sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.heightIn(max = g.x(bodyH)),
                )
            }
        }
    }
}

/** Up to 3 mini covers in the 112×84 well; the first one is on top. */
@Composable
private fun StackArt(stack: List<ArtRef>) {
    val g = LocalConsoleGeometry.current
    Box(Modifier.size(g.x(112), g.x(84))) {
        if (stack.isEmpty()) {
            // Downloads All tile: a mint download glyph, not an empty well.
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = g.x(30), y = g.x(10))
                    .size(g.x(48), g.x(64))
                    .drawBehind {
                        val o = g.x(3).toPx()
                        drawRoundRect(
                            color = ConsoleColors.Background.copy(alpha = 0.5f),
                            topLeft = Offset(o, o),
                            size = size,
                            cornerRadius = CornerRadius(g.x(3).toPx()),
                        )
                    }
                    .clip(RoundedCornerShape(g.x(3)))
                    .background(ConsoleColors.HighSurface),
                contentAlignment = Alignment.Center,
            ) {
                DownloadGlyph(
                    tint = ConsoleColors.Success,
                    modifier = Modifier.size(g.x(26)),
                )
            }
        } else {
            // Composed back-to-front so the first cover draws last (on top).
            for (i in minOf(stack.size, 3) - 1 downTo 0) {
                MiniCover(
                    art = stack[i],
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset(x = g.x(26 * i), y = g.x(10)),
                )
            }
        }
    }
}

@Composable
private fun MiniCover(art: ArtRef, modifier: Modifier = Modifier) {
    val g = LocalConsoleGeometry.current
    val shadow = g.x(3)
    Box(
        modifier = modifier
            .size(g.x(48), g.x(64))
            .drawBehind {
                val o = shadow.toPx()
                drawRoundRect(
                    color = ConsoleColors.Background.copy(alpha = 0.5f),
                    topLeft = Offset(o, o),
                    size = size,
                    cornerRadius = CornerRadius(g.x(3).toPx()),
                )
            }
            .clip(RoundedCornerShape(g.x(3)))
            .background(ConsoleColors.HighSurface),
    ) {
        MiniCoverArt(art)
    }
}

@Composable
private fun MiniCoverArt(art: ArtRef) {
    val fallbackTitle = art.title.orEmpty()
    if (art.kind == com.gamebex.client.ui.home.model.ArtKind.NONE) {
        GameCaseArt(art.systemFolder, fallbackTitle, CaseArtSize.MINI, Modifier.fillMaxSize())
        return
    }
    val image = rememberArtImage(art, 48, 64)
    if (image == null) {
        GameCaseArt(art.systemFolder, fallbackTitle, CaseArtSize.MINI, Modifier.fillMaxSize())
        return
    }
    val painter = coil.compose.rememberAsyncImagePainter(image.model, image.loader)
    if (painter.state is coil.compose.AsyncImagePainter.State.Error) {
        GameCaseArt(art.systemFolder, fallbackTitle, CaseArtSize.MINI, Modifier.fillMaxSize())
    } else {
        androidx.compose.foundation.Image(
            painter = painter,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
        )
    }
}

private fun homeTextScale(g: ConsoleGeometry): Float = min(g.textScale, 1.1f)
