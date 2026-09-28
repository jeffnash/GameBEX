package com.gamebex.client.ui.console

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import coil.compose.AsyncImage
import coil.compose.LocalImageLoader
import com.gamebex.client.sources.SourceImage
import com.gamebex.client.sources.rememberSourceImageModel
import com.gamebex.client.ui.home.components.CaseArtSize
import com.gamebex.client.ui.home.components.GameCaseArt
import com.gamebex.client.ui.text.displayTitle
import java.io.File

enum class GameCoverStatus { Continue, New, Downloading, Owned }

/**
 * Missing or failed art shows the procedural game case ([GameCaseArt]),
 * never an empty rectangle. Motion never blocks selection.
 */
@Composable
fun GameCover(
    title: String,
    cover: Any?,
    focused: Boolean,
    modifier: Modifier = Modifier,
    tint: Color = ConsoleColors.Accent,
    status: GameCoverStatus? = null,
    /** False where a sibling CTA already rings focus (featured covers). */
    ring: Boolean = true,
    /** Artwork revision: busts caches when art is replaced at a stable path. */
    artRevision: Any? = null,
    reducedMotion: Boolean = isSystemReducedMotion(),
    /** System folder for the no-art game case. */
    systemFolder: String? = null,
) {
    val g = LocalConsoleGeometry.current
    val targetScale = if (focused) ConsoleDesign.FOCUS_GAME_SCALE else 1f
    val targetLift = if (focused) ConsoleDesign.FOCUS_LIFT_PX else 0f
    val animScale by animateFloatAsState(
        targetScale, animationSpec = ConsoleMotion.focus(), label = "cover-scale",
    )
    val animLift by animateDpAsState(
        g.x(targetLift), animationSpec = ConsoleMotion.focus(), label = "cover-lift",
    )
    val scale = if (reducedMotion) targetScale else animScale
    val lift = if (reducedMotion) g.x(targetLift) else animLift
    val radius = g.x(ConsoleDesign.RADIUS_CARD)
    val shape = RoundedCornerShape(radius)
    val context = LocalContext.current
    // Source images resolve through the owning source's checked loader;
    // naked http(s) strings never load (catalog art arrives as SourceImage).
    val scoped = rememberSourceImageModel(cover as? SourceImage)
    val path = cover as? String
    val file = path?.let { File(it) }?.takeIf { it.isFile }
    // Versioned request for local files: replacements reload.
    val model: Any? = remember(file, scoped, artRevision) {
        when {
            file != null -> artImageRequest(context, file, artRevision)
            scoped != null -> scoped.request
            else -> null
        }
    }
    val loader = scoped?.loader ?: LocalImageLoader.current
    var artFailed by remember(model) { mutableStateOf(false) }
    val shadowPx = g.x(ConsoleDesign.HARD_SHADOW)
    Box(
        modifier = modifier
            .offset(y = lift)
            .scale(scale)
            .semantics { contentDescription = title }
            .drawBehind {
                // Hard one-step pixel shadow, drawn before the clip.
                if (focused && ring) {
                    val o = shadowPx.toPx()
                    drawRoundRect(
                        color = ConsoleColors.AccentShade.copy(alpha = 0.55f),
                        topLeft = Offset(o, o),
                        size = size,
                        cornerRadius = CornerRadius(radius.toPx()),
                    )
                }
            }
            .clip(shape)
            .background(ConsoleColors.Surface)
            .border(
                if (focused && ring) g.x(ConsoleDesign.FOCUS_STROKE) else g.x(1),
                if (focused && ring) ConsoleColors.FocusRing else ConsoleColors.Steel,
                shape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (model == null || artFailed) {
            GameCaseArt(
                systemFolder = systemFolder,
                title = displayTitle(title),
                size = CaseArtSize.LARGE,
                modifier = Modifier.fillMaxSize(),
                fallbackColor = tint,
            )
        } else {
            // Fit against surface: odd-aspect art letterboxes cleanly.
            AsyncImage(
                model = model,
                imageLoader = loader,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
                onError = { artFailed = true },
            )
        }
        if (status != null) {
            StatusBadge(status, Modifier.align(Alignment.BottomEnd).padding(g.x(6)))
        }
    }
}

@Composable
private fun StatusBadge(status: GameCoverStatus, modifier: Modifier) {
    val g = LocalConsoleGeometry.current
    val (label, fill, ink) = when (status) {
        GameCoverStatus.Continue -> Triple("PLAYING", ConsoleColors.Accent, ConsoleColors.Background)
        GameCoverStatus.New -> Triple("NEW", ConsoleColors.HighSurface, ConsoleColors.TextPrimary)
        GameCoverStatus.Downloading -> Triple("GETTING", ConsoleColors.HighSurface, ConsoleColors.Warning)
        GameCoverStatus.Owned -> Triple("OWNED", ConsoleColors.HighSurface, ConsoleColors.Success)
    }
    Box(
        modifier = modifier
            .height(g.x(24))
            .clip(RoundedCornerShape(g.x(ConsoleDesign.RADIUS_BADGE)))
            .background(fill)
            .padding(horizontal = g.x(6)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label, color = ink, fontSize = g.tMin(12), lineHeight = g.tMin(14),
            fontWeight = FontWeight.Bold, maxLines = 1,
        )
    }
}

