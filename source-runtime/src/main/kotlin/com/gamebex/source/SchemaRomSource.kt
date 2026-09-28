package com.gamebex.source

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** A [RomSource] implemented by running validated definition workflows. */
class SchemaRomSource(
    val definition: ValidatedDefinition,
    transport: SourceTransport,
    override val revision: String,
) : RomSource {
    override val id: String get() = definition.id
    override val name: String get() = definition.def.name
    override val assist: AssistPolicy get() = definition.def.assist

    private val runner = WorkflowRunner(definition, transport)

    /** Website response parsing ignores unrelated fields; config decoding stays strict. */
    private val dtoJson = Json { ignoreUnknownKeys = true }

    /** Workflow inputs keep defaulted values (e.g. empty ref data) so expressions see them. */
    private val inputJson = Json { encodeDefaults = true }

    private val catalogHosts = hostsOf(definition.def.origins.catalog)
    private val downloadHosts = hostsOf(definition.def.origins.download)
    private val artworkHosts = hostsOf(definition.def.origins.artwork)

    private fun input(vararg pairs: Pair<String, JsonObjectArgument>): JsonObject =
        buildJsonObject {
            for ((k, v) in pairs) {
                when (v) {
                    is JsonObjectArgument.Null -> put(k, kotlinx.serialization.json.JsonNull)
                    is JsonObjectArgument.Text -> put(k, JsonPrimitive(v.value))
                    is JsonObjectArgument.Raw -> put(k, v.element)
                }
            }
        }

    private sealed interface JsonObjectArgument {
        data object Null : JsonObjectArgument
        data class Text(val value: String) : JsonObjectArgument
        data class Raw(val element: kotlinx.serialization.json.JsonElement) : JsonObjectArgument
    }

    private fun argOrNull(value: String?): JsonObjectArgument =
        if (value == null) JsonObjectArgument.Null else JsonObjectArgument.Text(value)

    override suspend fun consoles(): List<ConsoleItem> {
        val out = runner.run("consoles", JsonObject(emptyMap()))
        val items = dtoJson.decodeFromJsonElement<List<ConsoleItem>>(out)
        for (item in items) {
            if (item.id.isBlank() || item.name.isBlank()) {
                throw SourceException(SourceFailure.INVALID_RESPONSE, id, "consoles")
            }
            if (item.platformId != null && item.platformId.isBlank()) {
                throw SourceException(SourceFailure.INVALID_RESPONSE, id, "consoles")
            }
        }
        return items
    }

    override suspend fun types(): List<TypeItem> {
        val out = runner.run("types", JsonObject(emptyMap()))
        val items = dtoJson.decodeFromJsonElement<List<TypeItem>>(out)
        for (item in items) {
            if (item.id.isBlank() || item.name.isBlank()) {
                throw SourceException(SourceFailure.INVALID_RESPONSE, id, "types")
            }
        }
        return items
    }

    override suspend fun latest(cursor: String?): CatalogPage {
        val out = runner.run("latest", input("cursor" to argOrNull(cursor)), setOf("cursor"))
        return checkedPage(dtoJson.decodeFromJsonElement<CatalogPage>(out), "latest")
    }

    override suspend fun browse(consoleId: String?, typeId: String?, cursor: String?): CatalogPage {
        val out = runner.run(
            "browse",
            input(
                "consoleId" to argOrNull(consoleId),
                "typeId" to argOrNull(typeId),
                "cursor" to argOrNull(cursor),
            ),
            setOf("consoleId", "typeId", "cursor"),
        )
        return checkedPage(dtoJson.decodeFromJsonElement<CatalogPage>(out), "browse")
    }

    override suspend fun search(query: String, platformId: String?, cursor: String?): CatalogPage {
        val out = runner.run(
            "search",
            input(
                "query" to JsonObjectArgument.Text(query),
                "platformId" to argOrNull(platformId),
                "cursor" to argOrNull(cursor),
            ),
            setOf("platformId", "cursor"),
        )
        return checkedPage(dtoJson.decodeFromJsonElement<CatalogPage>(out), "search")
    }

    override suspend fun detail(ref: SourceRef): RomDetail {
        val out = runner.run(
            "detail",
            input("ref" to JsonObjectArgument.Raw(inputJson.encodeToJsonElement(ref))),
        )
        val detail = dtoJson.decodeFromJsonElement<RomDetail>(out)
        return detail.copy(
            entry = checkedEntry(detail.entry, "detail"),
            screenshots = detail.screenshots.mapNotNull { normalizeFetchUrl(it, artworkHosts) },
        )
    }

    override suspend fun variants(ref: SourceRef): List<DownloadFile> {
        val out = runner.run(
            "variants",
            input("ref" to JsonObjectArgument.Raw(inputJson.encodeToJsonElement(ref))),
        )
        val files = dtoJson.decodeFromJsonElement<List<DownloadFile>>(out)
        return files.map { file ->
            if (file.ref.sourceId != id || file.ref.itemKey != ref.itemKey) {
                throw SourceException(SourceFailure.INVALID_RESPONSE, id, "variants")
            }
            if (file.variantKey.isBlank() || file.name.isBlank()) {
                throw SourceException(SourceFailure.INVALID_RESPONSE, id, "variants")
            }
            val pageUrl = normalizeFetchUrl(file.pageUrl, catalogHosts)
                ?: throw SourceException(SourceFailure.INVALID_RESPONSE, id, "variants")
            file.copy(pageUrl = pageUrl)
        }
    }

    override suspend fun resolveDownload(file: DownloadFile): TransferRequest {
        val out = runner.run(
            "resolveDownload",
            input("file" to JsonObjectArgument.Raw(inputJson.encodeToJsonElement(file))),
        )
        val req = dtoJson.decodeFromJsonElement<TransferRequest>(out)
        val url = normalizeFetchUrl(req.url, downloadHosts)
            ?: throw SourceException(SourceFailure.INVALID_RESPONSE, id, "resolveDownload")
        return req.copy(url = url)
    }

    private fun checkedPage(page: CatalogPage, operation: String): CatalogPage {
        return page.copy(items = page.items.map { checkedEntry(it, operation) })
    }

    private fun checkedEntry(entry: RomEntry, operation: String): RomEntry {
        if (entry.ref.sourceId != id) {
            throw SourceException(SourceFailure.INVALID_RESPONSE, id, operation)
        }
        if (entry.ref.itemKey.isBlank() || entry.title.isBlank()) {
            throw SourceException(SourceFailure.INVALID_RESPONSE, id, operation)
        }
        if (entry.platformId != null && entry.platformId.isBlank()) {
            throw SourceException(SourceFailure.INVALID_RESPONSE, id, operation)
        }
        val pageUrl = normalizeFetchUrl(entry.pageUrl, catalogHosts)
            ?: throw SourceException(SourceFailure.INVALID_RESPONSE, id, operation)
        val cover = entry.coverUrl?.ifBlank { null }?.let { normalizeFetchUrl(it, artworkHosts) }
        return entry.copy(ref = entry.ref.copy(pageUrl = pageUrl), coverUrl = cover)
    }

    /**
     * Confirms a fetched URL belongs to a declared origin host and returns
     * its https form. Plain-http URLs from declared hosts upgrade instead of
     * failing, preserving sources that still emit http artwork links; the
     * transport policy still enforces https on the wire.
     */
    private fun normalizeFetchUrl(url: String, hosts: Set<String>): String? {
        val parsed = url.toHttpUrlOrNull() ?: return null
        if (parsed.scheme != "https" && parsed.scheme != "http") return null
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return null
        if (parsed.port != okhttp3.HttpUrl.defaultPort(parsed.scheme)) return null
        if (parsed.host.lowercase() !in hosts) return null
        if (parsed.scheme == "https") return parsed.toString()
        return parsed.newBuilder().scheme("https").port(443).build().toString()
    }

    companion object {
        private fun hostsOf(origins: Set<String>): Set<String> =
            origins.mapNotNull { raw ->
                raw.toHttpUrlOrNull()?.takeIf { it.scheme == "https" }?.host?.lowercase()
            }.toSet()
    }
}
