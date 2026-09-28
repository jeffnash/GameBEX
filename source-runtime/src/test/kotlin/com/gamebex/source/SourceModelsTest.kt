package com.gamebex.source

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

/** Public DTOs round-trip and entry keys derive from refs. */
class SourceModelsTest {
    private val json = Json { encodeDefaults = true }

    @Test fun entryKeyDerivesFromRef() {
        val ref = SourceRef("acme", "https://acme.com/roms/x", "https://acme.com/roms/x")
        val entry = RomEntry(ref, "X", "snes")
        assertEquals(SourceKey("acme", "https://acme.com/roms/x"), entry.key)
        assertEquals("https://acme.com/roms/x", entry.pageUrl)
    }

    @Test fun detailRoundTrip() {
        val ref = SourceRef(
            "acme", "k", "https://acme.com/roms/x",
            buildJsonObject { put("postId", "42") },
        )
        val detail = RomDetail(
            entry = RomEntry(ref, "X", "snes", "SNES", "https://acme.com/c.png"),
            rating = 4.5, reviewCount = 10, genres = listOf("Action"),
            screenshots = listOf("https://acme.com/s1.png"),
        )
        val decoded = json.decodeFromString<RomDetail>(json.encodeToString(detail))
        assertEquals(detail, decoded)
    }

    @Test fun definitionEnvelopeDecodes() {
        val def = json.decodeFromString<SourceDefinition>(
            """{"schemaVersion":1,"id":"acme","name":"Acme","author":"a","version":"1.0.0",
              |"requires":["http-v1","expressions-v1","catalog-v1"],
              |"origins":{"catalog":["https://acme.com"],"download":[],"artwork":[]},
              |"platforms":{"snes":"snes"},"operations":{}}""".trimMargin(),
        )
        assertEquals(1, def.schemaVersion)
        assertEquals(setOf("https://acme.com"), def.origins.catalog)
    }
}
