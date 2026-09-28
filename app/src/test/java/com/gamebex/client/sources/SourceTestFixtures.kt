package com.gamebex.client.sources

import com.gamebex.source.RequestDefaults
import com.gamebex.source.SourceDefinition
import com.gamebex.source.SourceOrigins
import com.gamebex.source.Workflow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object SourceTestFixtures {
    private val json = Json {}

    private fun literalEmpty(): kotlinx.serialization.json.JsonObject = buildJsonObject {
        put("op", "literal")
        put("value", JsonArray(emptyList()))
    }

    private fun op(vararg inputs: String) = Workflow(
        inputs = inputs.toSet(),
        steps = emptyList(),
        result = literalEmpty(),
    )

    fun definition(
        id: String,
        version: String,
        extraArtwork: Set<String> = emptySet(),
    ): SourceDefinition = SourceDefinition(
        schemaVersion = 1,
        id = id,
        name = "Shop $id",
        author = "tests",
        version = version,
        requires = setOf("http-v1", "expressions-v1", "catalog-v1"),
        origins = SourceOrigins(
            catalog = setOf("https://catalog.example.com"),
            download = setOf("https://dl.example.com"),
            artwork = setOf("https://art.example.com") + extraArtwork,
        ),
        defaults = RequestDefaults(),
        platforms = mapOf("slug-a" to "gb"),
        operations = mapOf(
            "consoles" to op(),
            "types" to op(),
            "latest" to op("cursor"),
            "browse" to op("consoleId", "typeId", "cursor"),
            "search" to op("query", "platformId", "cursor"),
            "detail" to op("ref"),
            "variants" to op("ref"),
            "resolveDownload" to op("file"),
        ),
    )

    fun bytes(id: String, version: String, extraArtwork: Set<String> = emptySet()): ByteArray =
        json.encodeToString(definition(id, version, extraArtwork)).toByteArray(Charsets.UTF_8)
}
