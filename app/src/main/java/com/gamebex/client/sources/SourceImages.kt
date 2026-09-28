package com.gamebex.client.sources

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import coil.ImageLoader
import coil.request.ImageRequest
import com.gamebex.client.GameBexApp
import com.gamebex.client.data.RomEntry
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Source-scoped remote image: a catalog/artwork URL bound to the owning
 * source. Rendered only through [SourceImageLoaders]; raw http(s) strings
 * from catalog presentation never reach an image loader directly.
 */
data class SourceImage(
    val sourceId: String,
    val revision: String,
    val url: String,
)

/**
 * Coil cache key for a source image: the (sourceId, revision, URL)
 * tuple, serialized then hashed. No cookies in the key.
 */
fun sourceImageKey(sourceId: String, revision: String, url: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest("$sourceId\n$revision\n$url".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

/** Catalog cover → scoped image; null when there is no remote URL. */
fun RomEntry.coverImage(revision: String?): SourceImage? {
    val url = coverUrl?.takeIf {
        it.startsWith("http://") || it.startsWith("https://")
    } ?: return null
    return SourceImage(ref.sourceId, revision ?: "", url)
}

/**
 * Cached Coil loader per enabled source+revision. Each loader uses the
 * revision's checked artwork client (policy + checked DNS + per-hop
 * redirect validation), never the application default client. Missing
 * or disabled sources resolve to null; cached local files stay
 * renderable through the normal loader. On disable, the loader is shut
 * down (running jobs cancelled, in-memory state dropped) while the
 * shared local-art disk cache is left alone.
 */
class SourceImageLoaders(
    appContext: Context,
    private val sources: SourceRegistry,
    scope: CoroutineScope,
) {
    private val appContext = appContext.applicationContext
    private val mutex = Mutex()
    private val loaders = mutableMapOf<String, ImageLoader>()

    init {
        scope.launch {
            sources.list().collect { list ->
                val active = list.filter { it.enabled }.map { it.id }.toSet()
                mutex.withLock {
                    loaders.keys
                        .filter { it.substringBefore('@') !in active }
                        .forEach { loaders.remove(it)?.shutdown() }
                }
            }
        }
    }

    /** Checked loader for [image], or null when its source is unavailable. */
    suspend fun resolve(image: SourceImage): ImageLoader? {
        val http = runCatching { sources.httpFor(image.sourceId, null) }.getOrNull()
            ?: return null
        val key = "${image.sourceId}@${http.revision}"
        return mutex.withLock {
            loaders.getOrPut(key) {
                ImageLoader.Builder(appContext)
                    .okHttpClient { http.artworkHttpClient() }
                    .build()
            }
        }
    }
}

/** Checked loader + keyed request for one source image. */
data class SourceImageModel(val loader: ImageLoader, val request: ImageRequest)

/**
 * Resolve [image] to a checked loader/request, or null while missing,
 * disabled, or still resolving (callers render their placeholder).
 * Invoke directly in composition — resolving registry state is a
 * composition/lifecycle operation, not a remember-block computation.
 */
@Composable
fun rememberSourceImageModel(image: SourceImage?): SourceImageModel? {
    val context = LocalContext.current
    var model by remember(image) { mutableStateOf<SourceImageModel?>(null) }
    LaunchedEffect(image) {
        model = if (image == null) {
            null
        } else {
            withContext(Dispatchers.IO) {
                val app = context.applicationContext as? GameBexApp
                    ?: return@withContext null
                val loader = app.imageLoaders.resolve(image)
                    ?: return@withContext null
                val revision = app.sources.installedRevision(image.sourceId)
                    ?: return@withContext null
                val key = sourceImageKey(image.sourceId, revision, image.url)
                val request = ImageRequest.Builder(context)
                    .data(image.url)
                    .memoryCacheKey(key)
                    .diskCacheKey(key)
                    .build()
                SourceImageModel(loader, request)
            }
        }
    }
    return model
}
