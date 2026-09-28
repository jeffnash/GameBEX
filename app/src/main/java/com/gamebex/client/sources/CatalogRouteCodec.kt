package com.gamebex.client.sources

import com.gamebex.source.RomEntry
import kotlinx.serialization.json.Json

sealed interface CatalogRouteResult {
    data class Current(val entry: RomEntry) : CatalogRouteResult
    data object Invalid : CatalogRouteResult
}

private val routeJson = Json { ignoreUnknownKeys = true }

fun encodeCatalogPayload(entry: RomEntry): String =
    "v2:" + routeJson.encodeToString(RomEntry.serializer(), entry)

fun decodeCatalogPayload(raw: String): CatalogRouteResult = try {
    if (raw.startsWith("v2:")) {
        CatalogRouteResult.Current(routeJson.decodeFromString(RomEntry.serializer(), raw.removePrefix("v2:")))
    } else {
        CatalogRouteResult.Invalid
    }
} catch (_: IllegalArgumentException) {
    CatalogRouteResult.Invalid
}
