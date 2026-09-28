package com.gamebex.source

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class SourceKey(val sourceId: String, val itemKey: String)

@Serializable
data class SourceRef(
    val sourceId: String,
    val itemKey: String,
    val pageUrl: String,
    val data: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class RomEntry(
    val ref: SourceRef,
    val title: String,
    val platformId: String?,
    val consoleName: String? = null,
    val coverUrl: String? = null,
) {
    val key: SourceKey get() = SourceKey(ref.sourceId, ref.itemKey)
    val pageUrl: String get() = ref.pageUrl
}

@Serializable
data class ConsoleItem(
    val id: String,
    val name: String,
    val platformId: String?,
    val count: Int = 0,
)

@Serializable
data class TypeItem(val id: String, val name: String, val count: Int = 0)

@Serializable
data class CatalogPage(
    val items: List<RomEntry>,
    val next: String? = null,
)

@Serializable
data class RomDetail(
    val entry: RomEntry,
    val rating: Double? = null,
    val reviewCount: Int? = null,
    val downloads: String? = null,
    val size: String? = null,
    val genres: List<String> = emptyList(),
    val types: List<String> = emptyList(),
    val releaseDate: String? = null,
    val publisher: String? = null,
    val regions: List<String> = emptyList(),
    val description: String? = null,
    val screenshots: List<String> = emptyList(),
    val emulatorName: String? = null,
    val emulatorUrl: String? = null,
)

@Serializable
data class DownloadFile(
    val ref: SourceRef,
    val variantKey: String,
    val name: String,
    val kind: String = "",
    val size: String = "",
    val pageUrl: String,
    val data: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class TransferRequest(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val freshConnection: Boolean = false,
    val preferIpv4: Boolean = false,
    val expectedBytes: Long? = null,
)

@Serializable
data class AssistPolicy(
    val enabled: Boolean = false,
    val triggerStatuses: Set<Int> = emptySet(),
    val javascript: Boolean = false,
    val domStorage: Boolean = false,
    val userAgent: String? = null,
)

/** Provenance carried from a queued download into the library scanner. */
@Serializable
data class DownloadProvenance(
    val ref: SourceRef?,
    val variant: DownloadFile?,
)

interface RomSource {
    val id: String
    val name: String
    val revision: String
    val assist: AssistPolicy
    suspend fun consoles(): List<ConsoleItem>
    suspend fun types(): List<TypeItem>
    suspend fun latest(cursor: String? = null): CatalogPage
    suspend fun browse(consoleId: String?, typeId: String?, cursor: String? = null): CatalogPage
    suspend fun search(query: String, platformId: String? = null, cursor: String? = null): CatalogPage
    suspend fun detail(ref: SourceRef): RomDetail
    suspend fun variants(ref: SourceRef): List<DownloadFile>
    suspend fun resolveDownload(file: DownloadFile): TransferRequest
}
