package com.gamebex.client.sources

import com.gamebex.source.AssistPolicy
import com.gamebex.source.CatalogPage
import com.gamebex.source.ConsoleItem
import com.gamebex.source.DownloadFile
import com.gamebex.source.RomDetail
import com.gamebex.source.RomEntry
import com.gamebex.source.RomSource
import com.gamebex.source.SourceException
import com.gamebex.source.SourceFailure
import com.gamebex.source.SourceRef
import com.gamebex.source.TransferRequest
import com.gamebex.source.TypeItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Controllable RomSource: scripted results, failure injection, call log. */
open class FakeRomSource(
    override val id: String,
    override val name: String = "Fake $id",
    override val revision: String = "rev-$id",
    override val assist: AssistPolicy = AssistPolicy(),
) : RomSource {
    var consolesResult: List<ConsoleItem> = emptyList()
    var typesResult: List<TypeItem> = emptyList()
    var latestResult: suspend (String?) -> CatalogPage = { CatalogPage(emptyList()) }
    var browseResult: suspend (String?, String?, String?) -> CatalogPage = { _, _, _ -> CatalogPage(emptyList()) }
    var searchResult: suspend (String) -> CatalogPage = { CatalogPage(emptyList()) }
    var detailResult: suspend (SourceRef) -> RomDetail = { throw UnsupportedOperationException("detail") }
    var variantsResult: suspend (SourceRef) -> List<DownloadFile> = { emptyList() }
    var resolveResult: suspend (DownloadFile) -> TransferRequest = {
        throw UnsupportedOperationException("resolveDownload")
    }

    val calls = mutableListOf<String>()

    override suspend fun consoles(): List<ConsoleItem> {
        calls += "consoles"
        return consolesResult
    }

    override suspend fun types(): List<TypeItem> {
        calls += "types"
        return typesResult
    }

    override suspend fun latest(cursor: String?): CatalogPage {
        calls += "latest:$cursor"
        return latestResult(cursor)
    }

    override suspend fun browse(consoleId: String?, typeId: String?, cursor: String?): CatalogPage {
        calls += "browse:$consoleId:$typeId:$cursor"
        return browseResult(consoleId, typeId, cursor)
    }

    override suspend fun search(query: String, platformId: String?, cursor: String?): CatalogPage {
        calls += "search:$query"
        return searchResult(query)
    }

    override suspend fun detail(ref: SourceRef): RomDetail {
        calls += "detail:${ref.itemKey}"
        return detailResult(ref)
    }

    override suspend fun variants(ref: SourceRef): List<DownloadFile> {
        calls += "variants:${ref.itemKey}"
        return variantsResult(ref)
    }

    override suspend fun resolveDownload(file: DownloadFile): TransferRequest {
        calls += "resolve:${file.variantKey}"
        return resolveResult(file)
    }
}

class FakeSourceAccess : SourceAccess {
    private val _ready = MutableStateFlow(false)
    override val ready: StateFlow<Boolean> = _ready
    private val _selected = MutableStateFlow<SourceSelection?>(null)
    override val selected: StateFlow<SourceSelection?> = _selected
    val sources = mutableMapOf<String, FakeRomSource>()
    private val revisions = mutableMapOf<String, String>()

    fun setReady() {
        _ready.value = true
    }

    fun select(id: String?, revision: String? = null, generation: Long = 1) {
        _selected.value = id?.let { SourceSelection(it, revision ?: "rev-$it", generation) }
    }

    fun install(source: FakeRomSource) {
        sources[source.id] = source
        revisions[source.id] = source.revision
    }

    override suspend fun open(id: String, revision: String?): RomSource =
        sources[id] ?: throw SourceException(SourceFailure.UNAVAILABLE, id, null)

    override fun installedRevision(id: String): String? = revisions[id]

    var httpOverride: com.gamebex.source.SourceHttp? = null

    override suspend fun httpFor(id: String, revision: String?): com.gamebex.source.SourceHttp =
        httpOverride ?: throw SourceException(SourceFailure.UNAVAILABLE, id, null)

    override fun isAvailable(id: String, revision: String?): Boolean =
        ready.value && sources.containsKey(id) &&
            (revision == null || revisions[id] == revision)
}
