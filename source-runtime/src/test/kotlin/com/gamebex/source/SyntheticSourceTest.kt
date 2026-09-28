package com.gamebex.source

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A second, structurally different source (non-WordPress JSON API, opaque
 * non-URL keys, direct download URLs, no browser assist) passes the same
 * public RomSource contract with no interpreter branches.
 */
class SyntheticSourceTest {
    private fun loadDefinition(): ValidatedDefinition {
        val bytes = javaClass.classLoader!!.getResourceAsStream("synthshop.source.json")!!.readBytes()
        return DefinitionValidator.parseAndValidate(bytes)
    }

    private val systems = """[{"id":"q","name":"Quest Games","slug":"q-games","count":2},
      |{"id":"s","name":"Super Games","slug":"s-games","count":1}]""".trimMargin()
    private val games = """{"games":[{"id":"game-a","title":"Game A","system":"q-games",
      |"cover":"https://art.synth.test/a.png","url":"https://catalog.synth.test/v1/game/game-a"}],
      |"nextPage":null}""".trimMargin()
    private val gameDoc = """{"id":"game-a","title":"Game A","system":"q-games",
      |"cover":"https://art.synth.test/a.png","rating":4.0,"description":"A fine game.",
      |"shots":["https://art.synth.test/a-s1.png"]}""".trimMargin()
    private val filesDoc = """{"files":[{"key":"edition-eu","name":"Game A (EU).zip","kind":"Standard",
      |"size":"1MB","page":"https://catalog.synth.test/v1/game/game-a/files/edition-eu"}]}""".trimMargin()

    private fun source(): SchemaRomSource {
        val tx = TestFixtures.ScriptedTransport(
            mapOf(
                "GET https://catalog.synth.test/v1/systems" to TestFixtures.jsonResponse(systems),
                "GET https://catalog.synth.test/v1/games" to TestFixtures.jsonResponse(games),
                "GET https://catalog.synth.test/v1/game/game-a" to TestFixtures.jsonResponse(gameDoc),
                "GET https://catalog.synth.test/v1/game/game-a/files" to TestFixtures.jsonResponse(filesDoc),
            ),
        )
        return SchemaRomSource(loadDefinition(), tx, "synth-rev")
    }

    @Test fun publicContractEndToEnd() = runTest {
        val src = source()
        assertEquals("synthshop", src.id)
        assertFalse(src.assist.enabled)

        val consoles = src.consoles()
        assertEquals(2, consoles.size)
        assertEquals("gb", consoles[0].platformId)
        assertEquals(0, src.types().size)

        val page = src.latest(null)
        assertEquals(1, page.items.size)
        assertNull(page.next)
        val entry = page.items[0]
        assertEquals(SourceKey("synthshop", "game-a"), entry.key)
        assertEquals("gb", entry.platformId)
        assertEquals("https://art.synth.test/a.png", entry.coverUrl)

        val browsed = src.browse("q", null, null)
        assertEquals(1, browsed.items.size)
        val searched = src.search("game", null, null)
        assertEquals("Game A", searched.items[0].title)

        val detail = src.detail(entry.ref)
        assertEquals(4.0, detail.rating)
        assertEquals("A fine game.", detail.description)
        assertEquals(listOf("https://art.synth.test/a-s1.png"), detail.screenshots)

        val files = src.variants(entry.ref)
        assertEquals(1, files.size)
        assertEquals("edition-eu", files[0].variantKey)

        val transfer = src.resolveDownload(files[0])
        assertEquals("https://dl.synth.test/files/edition-eu.zip", transfer.url)
        assertFalse(transfer.freshConnection)
    }
}
