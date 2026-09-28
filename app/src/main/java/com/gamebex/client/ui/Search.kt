package com.gamebex.client.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamebex.client.data.RomEntry
import com.gamebex.client.data.toStableId
import com.gamebex.client.sources.SourceAccess
import com.gamebex.client.ui.presentation.rankSearchResults
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SearchState { IDLE, LOADING, RESULTS, EMPTY, FAILURE }

/** Query + fetch snapshot → the one list section to render. */
fun resolveSearchState(query: String, searching: Boolean, resultCount: Int, error: String?): SearchState =
    when {
        error != null -> SearchState.FAILURE
        searching -> SearchState.LOADING
        resultCount > 0 -> SearchState.RESULTS
        query.isBlank() -> SearchState.IDLE
        else -> SearchState.EMPTY
    }

/**
 * Unique, Bundle-safe LazyColumn keys for result rows: the stable catalog
 * id, suffixed only when a source returns the same entry twice (duplicate
 * keys crash LazyColumn).
 */
fun searchResultKeys(results: List<RomEntry>): List<String> {
    val seen = HashMap<String, Int>()
    return results.map { e ->
        val base = e.key.toStableId()
        val n = seen[base] ?: 0
        seen[base] = n + 1
        if (n == 0) base else "$base#$n"
    }
}

class SearchViewModel(private val sources: SourceAccess, private val saved: SavedStateHandle) : ViewModel() {
    // Adopt the live selection synchronously: async adoption races
    // StateFlow conflation (a switch before the collector runs).
    private var currentSourceId: String? = sources.selected.value?.id
    private var lastSelection: com.gamebex.client.sources.SourceSelection? = sources.selected.value
    // A deep-linked "initialQuery" (Home search) seeds the field only when
    // no query is saved for the current source yet.
    private val queryFlow = MutableStateFlow(
        currentSourceId?.let { saved.get<String>("q:$it") }.orEmpty()
            .ifEmpty { saved.get<String>("initialQuery").orEmpty() },
    )
    val query: StateFlow<String> = queryFlow
    private val retryFlow = MutableStateFlow(0)
    private val _results = MutableStateFlow<List<RomEntry>>(emptyList())
    val results: StateFlow<List<RomEntry>> = _results
    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error
    private val _recent = MutableStateFlow<List<String>>(
        currentSourceId?.let { saved.get<ArrayList<String>>("recent:$it")?.toList() }.orEmpty(),
    )
    val recent: StateFlow<List<String>> = _recent
    private val _sourceName = MutableStateFlow<String?>(null)
    val sourceName: StateFlow<String?> = _sourceName
    private val _sourceUnavailable = MutableStateFlow(false)
    val sourceUnavailable: StateFlow<Boolean> = _sourceUnavailable
    private var searchGen = 0

    private fun stashState() {
        val id = currentSourceId ?: return
        saved["q:$id"] = queryFlow.value
        saved["recent:$id"] = ArrayList(_recent.value)
    }

    private fun restoreState(id: String?) {
        queryFlow.value = id?.let { saved.get<String>("q:$it") }.orEmpty()
        _recent.value = id?.let { saved.get<ArrayList<String>>("recent:$it")?.toList() }.orEmpty()
    }

    init {
        viewModelScope.launch {
            sources.selected.collectLatest { sel ->
                // The construction snapshot counts as handled: only real
                // switches stash, clear, and restore.
                if (sel == lastSelection) return@collectLatest
                lastSelection = sel
                stashState()
                searchGen++
                currentSourceId = sel?.id
                _results.value = emptyList()
                _error.value = null
                _searching.value = false
                _sourceName.value = null
                _sourceUnavailable.value = sel == null
                restoreState(sel?.id)
            }
        }
        viewModelScope.launch {
            @OptIn(FlowPreview::class)
            combine(
                queryFlow.debounce(350),
                sources.selected,
                retryFlow,
            ) { q, sel, _ -> q to sel }.collectLatest { (q, sel) ->
                val gen = ++searchGen
                if (q.isBlank()) {
                    _results.value = emptyList()
                    _error.value = null
                    _searching.value = false
                    return@collectLatest
                }
                if (sel == null) {
                    _results.value = emptyList()
                    _searching.value = false
                    _sourceUnavailable.value = true
                    _error.value = UserErrors.noSource()
                    return@collectLatest
                }
                _searching.value = true
                _error.value = null
                _sourceUnavailable.value = false
                try {
                    val source = withContext(Dispatchers.IO) { sources.open(sel.id, sel.revision) }
                    if (gen != searchGen || currentSourceId != sel.id) return@collectLatest
                    _sourceName.value = source.name
                    val raw = withContext(Dispatchers.IO) { source.search(q).items }
                    if (gen != searchGen || currentSourceId != sel.id) return@collectLatest
                    _results.value = rankSearchResults(q, raw)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (gen != searchGen || currentSourceId != sel.id) return@collectLatest
                    _results.value = emptyList()
                    logW("GameBEXSearch", "search failed", e)
                    _sourceUnavailable.value = e is com.gamebex.source.SourceException &&
                        e.failure == com.gamebex.source.SourceFailure.UNAVAILABLE
                    _error.value = UserErrors.forSearch(e, _sourceName.value)
                }
                if (gen == searchGen && currentSourceId == sel.id) _searching.value = false
            }
        }
    }

    fun onQuery(q: String) {
        queryFlow.value = q
        currentSourceId?.let { saved["q:$it"] = q }
    }

    /** Re-runs the current query; the counter survives StateFlow conflation. */
    fun retry() {
        if (queryFlow.value.isBlank() || _searching.value) return
        retryFlow.value++
    }

    fun commitRecent(q: String) {
        val clean = q.trim()
        if (clean.isEmpty()) return
        _recent.value = (listOf(clean) + _recent.value).distinct().take(8)
        currentSourceId?.let { saved["recent:$it"] = ArrayList(_recent.value) }
    }

    fun removeRecent(q: String) {
        _recent.value = _recent.value - q
        currentSourceId?.let { saved["recent:$it"] = ArrayList(_recent.value) }
    }
}
