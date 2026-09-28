package com.gamebex.source

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class SchemaRomSourceTest {
    private val consolesBody = """[{"id":"7","name":"Super Nintendo","platformId":"snes","count":5}]"""
    private val pageBody = """{"items":[{"ref":{"sourceId":"synthetic","itemKey":"https://catalog.example.com/g/1",
      |"pageUrl":"https://catalog.example.com/g/1","data":{"postId":"1"}},
      |"title":"Game One","platformId":"snes","consoleName":"Super Nintendo",
      |"coverUrl":"https://art.example.com/1.png"}],"next":"2"}""".trimMargin()

    private fun sourceFor(scripts: Map<String, SourceResponse>): SchemaRomSource {
        val base = TestFixtures.queryParamWorkflow()
        val latest = base.copy(
            result = buildJsonObject {
                put("op", "json")
                put("value", TestFixtures.ref("steps", "response"))
            },
        )
        val consoles = Workflow(
            emptySet(),
            listOf(
                WorkflowStep(
                    "r",
                    request = RequestSpec(
                        method = "GET", purpose = "catalog",
                        url = TestFixtures.lit("https://catalog.example.com/consoles"),
                    ),
                ),
            ),
            buildJsonObject {
                put("op", "json")
                put("value", TestFixtures.ref("steps", "r"))
            },
        )
        val search = TestFixtures.queryParamWorkflow(
            inputs = setOf("query", "platformId", "cursor"),
            url = "https://catalog.example.com/search",
            extraQuery = mapOf("s" to TestFixtures.ref("input", "query")),
        )
        val ops = TestFixtures.minimalDefinition().operations + mapOf(
            "latest" to latest,
            "consoles" to consoles,
            "search" to search,
        )
        val def = DefinitionValidator.validate(TestFixtures.minimalDefinition(ops))
        return SchemaRomSource(def, TestFixtures.ScriptedTransport(scripts), revision = "rev1")
    }

    @Test fun publicDtoValidityAndPagination() = runTest {
        val src = sourceFor(
            mapOf(
                "GET https://catalog.example.com/consoles" to TestFixtures.jsonResponse(consolesBody),
                "GET https://catalog.example.com/items" to TestFixtures.jsonResponse(pageBody),
            ),
        )
        assertEquals("synthetic", src.id)
        assertEquals("rev1", src.revision)
        val consoles = src.consoles()
        assertEquals(1, consoles.size)
        assertEquals("snes", consoles[0].platformId)
        val page = src.latest(null)
        assertEquals(1, page.items.size)
        assertEquals("2", page.next)
        assertEquals(SourceKey("synthetic", "https://catalog.example.com/g/1"), page.items[0].key)
        assertEquals("https://art.example.com/1.png", page.items[0].coverUrl)
    }

    @Test fun missingOptionalFieldsDecode() = runTest {
        val body = """{"items":[{"ref":{"sourceId":"synthetic","itemKey":"k","pageUrl":"https://catalog.example.com/g/2"},
          |"title":"Bare","platformId":null}]}""".trimMargin()
        val src = sourceFor(mapOf("GET https://catalog.example.com/items" to TestFixtures.jsonResponse(body)))
        val page = src.latest("9")
        assertEquals(1, page.items.size)
        assertNull(page.next)
        assertNull(page.items[0].platformId)
        assertNull(page.items[0].coverUrl)
    }

    @Test fun foreignSourceIdRejected() = runTest {
        val body = """{"items":[{"ref":{"sourceId":"evil","itemKey":"k","pageUrl":"https://catalog.example.com/g/3"},
          |"title":"X","platformId":null}]}""".trimMargin()
        val src = sourceFor(mapOf("GET https://catalog.example.com/items" to TestFixtures.jsonResponse(body)))
        try {
            src.latest(null)
            fail("expected INVALID_RESPONSE")
        } catch (e: SourceException) {
            assertEquals(SourceFailure.INVALID_RESPONSE, e.failure)
        }
    }

    @Test fun blankTitleRejected() = runTest {
        val body = """{"items":[{"ref":{"sourceId":"synthetic","itemKey":"k","pageUrl":"https://catalog.example.com/g/4"},
          |"title":"  ","platformId":null}]}""".trimMargin()
        val src = sourceFor(mapOf("GET https://catalog.example.com/items" to TestFixtures.jsonResponse(body)))
        try {
            src.latest(null)
            fail("expected INVALID_RESPONSE")
        } catch (e: SourceException) {
            assertEquals(SourceFailure.INVALID_RESPONSE, e.failure)
        }
    }

    @Test fun defaultedInputValuesReachWorkflows() = runTest {
        // A defaulted field (empty ref data) must survive input encoding.
        val ops = TestFixtures.minimalDefinition().operations + mapOf(
            "variants" to Workflow(
                setOf("ref"),
                emptyList(),
                buildJsonObject {
                    put("op", "array")
                    put(
                        "items",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("op", "object")
                                    put(
                                        "fields",
                                        buildJsonObject {
                                            put("ref", TestFixtures.ref("input", "ref"))
                                            put("variantKey", TestFixtures.lit("v"))
                                            put("name", TestFixtures.lit("n"))
                                            put("pageUrl", TestFixtures.lit("https://catalog.example.com/g/9"))
                                            put("data", TestFixtures.get(TestFixtures.ref("input", "ref"), "data"))
                                        },
                                    )
                                },
                            )
                        },
                    )
                },
            ),
        )
        val src = SchemaRomSource(
            DefinitionValidator.validate(TestFixtures.minimalDefinition(ops)),
            TestFixtures.failingTransport(), "rev1",
        )
        val files = src.variants(SourceRef("synthetic", "k", "https://catalog.example.com/g/9"))
        assertEquals(JsonObject(emptyMap()), files.single().data)
    }

    @Test fun queriesPassThrough() = runTest {
        val tx = TestFixtures.ScriptedTransport(
            mapOf("GET https://catalog.example.com/search" to TestFixtures.jsonResponse("""{"items":[]}""")),
        )
        val ops = TestFixtures.minimalDefinition().operations + mapOf(
            "search" to TestFixtures.queryParamWorkflow(
                inputs = setOf("query", "platformId", "cursor"),
                url = "https://catalog.example.com/search",
                extraQuery = mapOf("s" to TestFixtures.ref("input", "query")),
            ),
        )
        val src = SchemaRomSource(DefinitionValidator.validate(TestFixtures.minimalDefinition(ops)), tx, "rev1")
        val page = src.search("Mario & Zelda", null, null)
        assertEquals(0, page.items.size)
        assertEquals(1, tx.requests.size)
        assertEquals("Mario & Zelda", tx.requests[0].query["s"])
    }
}
