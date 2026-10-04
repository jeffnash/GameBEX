package com.gamebex.client.sources

import com.gamebex.client.ui.UserErrors
import com.gamebex.client.ui.home.model.CardKey
import com.gamebex.client.ui.home.model.HomeCopy
import com.gamebex.client.ui.home.model.SiteResult
import com.gamebex.client.ui.home.model.SiteSearch
import com.gamebex.client.ui.home.model.SizeInfo
import com.gamebex.client.ui.logW
import com.gamebex.client.ui.presentation.rankSearchResults
import com.gamebex.source.RomEntry
import com.gamebex.source.SourceException
import com.gamebex.source.SourceFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException

/**
 * The connected ROM site behind Home's search field.
 *
 * - [pluginName] follows `sources.selected`; the display name is resolved on
 *   [io] and stays null while nothing is installed or enabled.
 * - [search] is one catalog page, client-ranked with
 *   [rankSearchResults]. Selection and connectivity short-circuit before any
 *   request; failures map through [UserErrors.forCatalog], and a network
 *   failure observed while offline reports [SiteResult.Offline].
 * - [size] is the default-download size: exactly one variant with a parseable
 *   size wins, otherwise the detail page's size. Lookups are deduplicated by
 *   an LRU cache, bounded to [SIZE_TIMEOUT_MS] each and at most 2 in flight,
 *   because each one costs the site a request.
 */
class SiteSearchImpl(
    private val sources: SourceAccess,
    private val online: StateFlow<Boolean>,
    scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : SiteSearch {

    private val _pluginName = MutableStateFlow<String?>(null)
    override val pluginName: StateFlow<String?> = _pluginName

    init {
        scope.launch {
            sources.selected.collectLatest { sel ->
                _pluginName.value = resolveName(sel?.id, sel?.revision)
            }
        }
    }

    override suspend fun search(query: String): SiteResult {
        val sel = sources.selected.value ?: return SiteResult.NoPlugin
        if (!online.value) return SiteResult.Offline
        return try {
            // Without a timeout a hung request leaves the screen on "Searching…" forever.
            val items = withTimeout(SEARCH_TIMEOUT_MS) {
                withContext(io) { sources.open(sel.id, sel.revision).search(query).items }
            }
            // A page can repeat an entry; duplicate (sourceId, itemKey) keys
            // crash LazyVerticalGrid.
            val distinct = items.distinctBy { it.ref.sourceId to it.ref.itemKey }
            SiteResult.Found(rankSearchResults(query, distinct))
        } catch (e: TimeoutCancellationException) {
            logW(TAG, "site search timed out", e)
            SiteResult.Failed(SEARCH_TIMEOUT_MESSAGE)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            when {
                isNetworkFailure(e) && !online.value -> SiteResult.Offline
                else -> SiteResult.Failed(UserErrors.forCatalog(e, _pluginName.value))
            }
        }
    }

    override suspend fun size(entry: RomEntry): SizeInfo? {
        val key = CardKey.site(entry.ref.sourceId, entry.ref.itemKey).value
        cacheGet(key)?.let { return it.info }
        return sizeSemaphore.withPermit {
            cacheGet(key)?.let { return it.info }
            // Timeout and failure are transient: report unknown but retry on
            // the next request. Only a completed lookup (even a null one) is
            // cached, so a slow or flaky site never pins "unknown" in place.
            val info = try {
                withTimeout(SIZE_TIMEOUT_MS) { lookupSize(entry) }
            } catch (e: TimeoutCancellationException) {
                logW(TAG, "size lookup timed out", e)
                return null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logW(TAG, "size lookup failed", e)
                return null
            }
            cachePut(key, Cached(info))
            info
        }
    }

    private suspend fun lookupSize(entry: RomEntry): SizeInfo? {
        val source = withContext(io) {
            sources.open(entry.ref.sourceId, revisionFor(entry.ref.sourceId))
        }
        val files = withContext(io) { source.variants(entry.ref) }
        files.singleOrNull()?.let { HomeCopy.parseSize(it.size) }?.let { return sizeInfo(it) }
        val detailSize = withContext(io) { source.detail(entry.ref).size }
        return HomeCopy.parseSize(detailSize, allowBareBytes = false)?.let { sizeInfo(it) }
    }

    private suspend fun resolveName(id: String?, revision: String?): String? {
        if (id == null) return null
        return withContext(io) {
            try {
                sources.open(id, revision).name
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logW(TAG, "plugin name lookup failed", e)
                null
            }
        }
    }

    /** Pin the revision the entry came from while its source stays selected. */
    private fun revisionFor(sourceId: String): String? =
        sources.selected.value?.takeIf { it.id == sourceId }?.revision

    private fun sizeInfo(bytes: Long): SizeInfo = SizeInfo(bytes, HomeCopy.size(bytes))

    private fun isNetworkFailure(e: Exception): Boolean =
        e is IOException || (e is SourceException && e.failure == SourceFailure.NETWORK)

    /** Resolved size lookup; a null [info] is a cached "unknown". */
    private class Cached(val info: SizeInfo?)

    private val sizeSemaphore = Semaphore(2)
    private val cacheMutex = Mutex()
    private val cache = object : LinkedHashMap<String, Cached>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>): Boolean =
            size > MAX_CACHED
    }

    private suspend fun cacheGet(key: String): Cached? = cacheMutex.withLock { cache[key] }

    private suspend fun cachePut(key: String, value: Cached) {
        cacheMutex.withLock { cache[key] = value }
    }

    private companion object {
        const val TAG = "GameBexSiteSearch"
        const val SIZE_TIMEOUT_MS = 8_000L

        const val SEARCH_TIMEOUT_MS = 15_000L
        const val SEARCH_TIMEOUT_MESSAGE = "Search took too long — retry."
        const val MAX_CACHED = 200
    }
}
