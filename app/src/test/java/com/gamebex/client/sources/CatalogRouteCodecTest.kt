package com.gamebex.client.sources

import com.gamebex.source.RomEntry
import com.gamebex.source.SourceRef
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogRouteCodecTest {
    private fun entry() = RomEntry(
        ref = SourceRef(
            sourceId = "acme",
            itemKey = "gba/pokemon-emerald",
            pageUrl = "https://catalog.example.com/roms/game-boy-advance/pokemon-emerald.html",
            data = JsonObject(mapOf("postId" to JsonPrimitive(2516))),
        ),
        title = "Pokémon Emerald | 100% ピクロス",
        platformId = "gba",
        consoleName = "Game Boy Advance",
        coverUrl = "https://img/x.png?a=1&b=|x|",
    )

    @Test fun roundTripWithSeparatorsUnicodeAndPercents() {
        val e = entry()
        val decoded = decodeCatalogPayload(encodeCatalogPayload(e))
        assertTrue(decoded is CatalogRouteResult.Current)
        assertEquals(e, (decoded as CatalogRouteResult.Current).entry)
    }

    @Test fun detailRouteSurvivesNavigationDecoding() {
        // Navigation decodes %XX in path args but preserves "+".
        // Model that exactly: "+" must never reach the decoder.
        val route = com.gamebex.client.detailRouteFor(entry())
        val arg = route.removePrefix("detail/")
        val navDecoded = java.net.URLDecoder.decode(arg.replace("+", "%2B"), "UTF-8")
        val decoded = decodeCatalogPayload(navDecoded)
        assertEquals(entry(), (decoded as CatalogRouteResult.Current).entry)
    }

    @Test fun corruptJsonIsInvalid() {
        assertTrue(decodeCatalogPayload("v2:{not json") is CatalogRouteResult.Invalid)
        assertTrue(decodeCatalogPayload("v2:") is CatalogRouteResult.Invalid)
        assertTrue(decodeCatalogPayload("garbage") is CatalogRouteResult.Invalid)
        assertTrue(decodeCatalogPayload("") is CatalogRouteResult.Invalid)
    }

    @Test fun prePluginPipePayloadIsInvalid() {
        val pipe = "2516|pokemon-emerald|Pok%C3%A9mon+Emerald|https%3A%2F%2Fx%2Fp|" +
            "https%3A%2F%2Fimg%2Fx.png|Game+Boy+Advance|game-boy-advance"
        assertTrue(decodeCatalogPayload(pipe) is CatalogRouteResult.Invalid)
        assertTrue(decodeCatalogPayload("1|s|T|https%3A%2F%2Fx") is CatalogRouteResult.Invalid)
    }

    @Test fun currentCarriesItsOwnSourceId() {
        val e = entry().copy(ref = entry().ref.copy(sourceId = "other"))
        val decoded = decodeCatalogPayload(encodeCatalogPayload(e))
        assertEquals("other", (decoded as CatalogRouteResult.Current).entry.ref.sourceId)
    }
}
