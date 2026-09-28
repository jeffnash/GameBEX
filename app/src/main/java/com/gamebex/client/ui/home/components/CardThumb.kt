package com.gamebex.client.ui.home.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.ImageLoader
import coil.compose.AsyncImagePainter
import coil.compose.LocalImageLoader
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.gamebex.client.sources.SourceImage
import com.gamebex.client.sources.rememberSourceImageModel
import com.gamebex.client.ui.console.ConsoleColors
import com.gamebex.client.ui.console.LocalConsoleGeometry
import com.gamebex.client.ui.console.artImageRequest
import com.gamebex.client.ui.console.rememberArtColor
import com.gamebex.client.ui.home.model.ArtKind
import com.gamebex.client.ui.home.model.ArtRef
import com.gamebex.client.ui.home.model.CardState
import java.io.File

/** A sized Coil request plus the loader that must run it. */
internal class ArtImage(val model: Any, val loader: ImageLoader)

/**
 * The memory-cache key carries the decode size so a thumbnail never serves a
 * full-screen decode or the reverse.
 */
@Composable
internal fun rememberArtImage(art: ArtRef, decodeW: Int, decodeH: Int): ArtImage? {
    val context = LocalContext.current
    val file = artFile(art)
    val scoped = rememberSourceImageModel(artSourceImage(art))
    val model: Any? = remember(file, scoped, art.revision, decodeW, decodeH) {
        when {
            file != null -> sized(
                artImageRequest(context, file, art.revision),
                decodeW,
                decodeH,
            )
            scoped != null -> sized(scoped.request, decodeW, decodeH)
            else -> null
        }
    }
    return model?.let { ArtImage(it, scoped?.loader ?: LocalImageLoader.current) }
}

private fun sized(request: ImageRequest, w: Int, h: Int): ImageRequest =
    request.newBuilder()
        .size(w, h)
        .memoryCacheKey("${request.memoryCacheKey}#${w}x$h")
        .build()

/**
 * No existence check: `ArtRef.kind` already says the factory resolved the file
 * on IO, and a stat() in composition is main-thread IO. A file that vanished
 * loads as a Coil error and falls back to the case art.
 */
internal fun artFile(art: ArtRef): File? {
    val f = when (val m = art.model) {
        is File -> m
        is String -> File(m)
        else -> null
    }
    return f?.takeIf { !it.path.startsWith("http") }
}

/**
 * Plugin-hosted art resolves through the owning source's checked loader, like
 * Discover tiles. Raw http(s) strings never reach a loader directly.
 */
internal fun artSourceImage(art: ArtRef): SourceImage? {
    val m = art.model
    return when {
        m is SourceImage -> m
        m is String && (m.startsWith("http://") || m.startsWith("https://")) ->
            art.sourceId?.let { SourceImage(it, art.revision.toString(), m) }
        else -> null
    }
}

/** Card thumbnail, 112×84. Missing art or a failed load falls back to [GameCaseArt]. */
@Composable
fun CardThumb(
    art: ArtRef,
    title: String,
    state: CardState,
    modifier: Modifier = Modifier,
    systemFolder: String? = null,
) {
    val g = LocalConsoleGeometry.current
    val dim = state is CardState.OffPhone ||
        state is CardState.OnSite ||
        state is CardState.WaitingForConnection
    Box(
        modifier = modifier
            .size(g.x(112), g.x(84))
            .clip(RoundedCornerShape(g.x(6)))
            .background(ConsoleColors.Surface),
    ) {
        Box(Modifier.fillMaxSize().alpha(if (dim) 0.55f else 1f)) {
            ThumbArt(art, title, systemFolder)
        }
        val downloading = state as? CardState.Downloading
        if (downloading != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(g.x(62))
                    .clip(RoundedCornerShape(g.x(31)))
                    .background(ConsoleColors.Background.copy(alpha = 0.7f)),
                contentAlignment = Alignment.Center,
            ) {
                SegmentRing(
                    progress = downloading.total.takeIf { it > 0 }
                        ?.let { downloading.read.toFloat() / it },
                    paused = downloading.paused,
                    modifier = Modifier.size(g.x(56)),
                )
            }
        }
        if (dim) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = g.x(5), bottom = g.x(5))
                    .size(g.x(30), g.x(22))
                    .clip(RoundedCornerShape(g.x(4)))
                    .background(ConsoleColors.Background.copy(alpha = 0.85f)),
                contentAlignment = Alignment.Center,
            ) {
                CloudGlyph(
                    tint = ConsoleColors.TextPrimary,
                    modifier = Modifier.size(g.x(18), g.x(12)),
                )
            }
        }
    }
}

@Composable
private fun ThumbArt(art: ArtRef, title: String, systemFolder: String?) {
    val fallbackTitle = art.title ?: title
    val fallbackFolder = art.systemFolder ?: systemFolder
    when (art.kind) {
        ArtKind.NONE -> GameCaseArt(
            systemFolder = fallbackFolder,
            title = fallbackTitle,
            size = CaseArtSize.THUMB,
            modifier = Modifier.fillMaxSize(),
        )
        ArtKind.SAVE_THUMB -> ThumbImage(art, ContentScale.Crop, fallbackTitle, fallbackFolder)
        ArtKind.COVER -> {
            val file = artFile(art)
            val bed = rememberArtColor(
                artPath = file?.absolutePath,
                fallback = caseColorFor(fallbackFolder, fallbackTitle),
                revision = art.revision,
            )
            Box(Modifier.fillMaxSize().background(bed)) {
                ThumbImage(art, ContentScale.Fit, fallbackTitle, fallbackFolder)
            }
        }
    }
}

@Composable
private fun ThumbImage(art: ArtRef, scale: ContentScale, fallbackTitle: String, fallbackFolder: String?) {
    val image = rememberArtImage(art, 112, 84)
    if (image == null) {
        CaseFallback(art, fallbackTitle, fallbackFolder)
        return
    }
    val painter = rememberAsyncImagePainter(image.model, image.loader)
    if (painter.state is AsyncImagePainter.State.Error) {
        CaseFallback(art, fallbackTitle, fallbackFolder)
    } else {
        Image(
            painter = painter,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = scale,
        )
    }
}

@Composable
internal fun CaseFallback(art: ArtRef, fallbackTitle: String, fallbackFolder: String? = null, large: Boolean = false) {
    GameCaseArt(
        systemFolder = art.systemFolder ?: fallbackFolder,
        title = art.title ?: fallbackTitle,
        size = if (large) CaseArtSize.LARGE else CaseArtSize.THUMB,
        modifier = Modifier.fillMaxSize(),
    )
}
