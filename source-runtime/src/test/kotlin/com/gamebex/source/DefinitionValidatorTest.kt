package com.gamebex.source

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DefinitionValidatorTest {
    private fun expectInvalid(def: SourceDefinition): SourceException {
        try {
            DefinitionValidator.validate(def)
        } catch (e: SourceException) {
            assertEquals(SourceFailure.INVALID_DEFINITION, e.failure)
            return e
        }
        fail("expected INVALID_DEFINITION")
        throw AssertionError()
    }

    private fun base() = TestFixtures.minimalDefinition()

    private fun withOp(name: String, wf: Workflow): SourceDefinition =
        base().copy(operations = base().operations + (name to wf))

    @Test fun validMinimalDefinitionPasses() {
        val v = DefinitionValidator.validate(base())
        assertEquals("synthetic", v.id)
    }

    @Test fun validRichDefinitionPasses() {
        // Cached taxonomy helper, invoke, fallback, selectors, POST form.
        val rawConsoles = Workflow(
            inputs = emptySet(),
            steps = listOf(
                WorkflowStep(
                    "response",
                    request = RequestSpec(
                        method = "GET", purpose = "catalog",
                        url = TestFixtures.lit("https://catalog.example.com/consoles"),
                        query = mapOf("per_page" to TestFixtures.lit(100)),
                        headers = mapOf("Accept-Language" to TestFixtures.lit("en-US")),
                    ),
                ),
                WorkflowStep(
                    "parsed",
                    value = buildJsonObject {
                        put("op", "json")
                        put("value", TestFixtures.ref("steps", "response"))
                    },
                ),
            ),
            result = TestFixtures.ref("steps", "parsed"),
            cache = true,
        )
        val consoles = Workflow(
            inputs = emptySet(),
            steps = emptyList(),
            result = buildJsonObject {
                put("op", "map")
                put(
                    "value",
                    buildJsonObject {
                        put("op", "invoke")
                        put("operation", "rawConsoles")
                        put("input", TestFixtures.obj())
                    },
                )
                put("as", "c")
                put(
                    "body",
                    TestFixtures.obj(
                        "id" to TestFixtures.get(TestFixtures.ref("c"), "id"),
                        "name" to TestFixtures.get(TestFixtures.ref("c"), "name"),
                    ),
                )
            },
        )
        val search = Workflow(
            inputs = setOf("query", "platformId", "cursor"),
            steps = listOf(
                WorkflowStep(
                    "response",
                    request = RequestSpec(
                        method = "POST", purpose = "catalog",
                        url = TestFixtures.lit("https://catalog.example.com/token"),
                        form = mapOf("q" to TestFixtures.ref("input", "query")),
                    ),
                ),
            ),
            result = buildJsonObject {
                put("op", "select")
                put(
                    "value",
                    buildJsonObject {
                        put("op", "html")
                        put("value", TestFixtures.ref("steps", "response"))
                    },
                )
                put("css", "a.title[href*=game]")
            },
            fallback = WorkflowFallback("restSearch", onEmpty = true, onFailures = setOf(SourceFailure.HTTP)),
        )
        val restSearch = Workflow(
            inputs = setOf("query", "platformId", "cursor"),
            steps = emptyList(),
            result = TestFixtures.lit(JsonArray(emptyList())),
        )
        val ops = base().operations + mapOf(
            "rawConsoles" to rawConsoles,
            "consoles" to consoles,
            "search" to search,
            "restSearch" to restSearch,
        )
        DefinitionValidator.validate(base().copy(operations = ops))
    }

    @Test fun unknownOperatorRejected() {
        val bad = TestFixtures.obj("x" to buildJsonObject { put("op", "nope") })
        expectInvalid(withOp("types", Workflow(emptySet(), emptyList(), bad)))
    }

    @Test fun unknownKeyRejected() {
        val bad = buildJsonObject {
            put("op", "literal")
            put("value", JsonPrimitive(1))
            put("bogus", JsonPrimitive(2))
        }
        expectInvalid(withOp("types", Workflow(emptySet(), emptyList(), bad)))
    }

    @Test fun missingPublicOperationRejected() {
        val ops = base().operations - "search"
        val e = expectInvalid(base().copy(operations = ops))
        assertTrue(e.fieldPath!!.contains("operations"))
    }

    @Test fun stepWithBothPayloadsRejected() {
        val wf = Workflow(
            emptySet(),
            listOf(
                WorkflowStep(
                    "x",
                    request = RequestSpec(url = TestFixtures.lit("https://catalog.example.com/")),
                    value = TestFixtures.lit(1),
                ),
            ),
            TestFixtures.lit(1),
        )
        expectInvalid(withOp("types", wf))
    }

    @Test fun stepWithNeitherPayloadRejected() {
        val wf = Workflow(emptySet(), listOf(WorkflowStep("x")), TestFixtures.lit(1))
        expectInvalid(withOp("types", wf))
    }

    @Test fun forwardStepReferenceRejected() {
        val wf = Workflow(
            emptySet(),
            listOf(
                WorkflowStep("a", value = TestFixtures.ref("steps", "b")),
                WorkflowStep("b", value = TestFixtures.lit(1)),
            ),
            TestFixtures.lit(1),
        )
        expectInvalid(withOp("types", wf))
    }

    @Test fun unknownInputReferenceRejected() {
        val wf = Workflow(emptySet(), emptyList(), TestFixtures.ref("input", "nope"))
        expectInvalid(withOp("types", wf))
    }

    @Test fun bindingScopeIsLexical() {
        // `c` escapes its map body.
        val bad = buildJsonObject {
            put("op", "array")
            put(
                "items",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("op", "map")
                            put("value", TestFixtures.lit(JsonArray(emptyList())))
                            put("as", "c")
                            put("body", TestFixtures.ref("c"))
                        },
                    )
                    add(TestFixtures.ref("c"))
                },
            )
        }
        expectInvalid(withOp("types", Workflow(emptySet(), emptyList(), bad)))
    }

    @Test fun cyclicInvokeRejected() {
        val a = Workflow(
            emptySet(), emptyList(),
            buildJsonObject {
                put("op", "invoke")
                put("operation", "helperB")
                put("input", TestFixtures.obj())
            },
        )
        val b = Workflow(
            emptySet(), emptyList(),
            buildJsonObject {
                put("op", "invoke")
                put("operation", "helperA")
                put("input", TestFixtures.obj())
            },
        )
        expectInvalid(base().copy(operations = base().operations + mapOf("helperA" to a, "helperB" to b)))
    }

    @Test fun cyclicFallbackRejected() {
        val a = Workflow(
            emptySet(), emptyList(), TestFixtures.lit(1),
            fallback = WorkflowFallback("helperB"),
        )
        val b = Workflow(
            emptySet(), emptyList(), TestFixtures.lit(1),
            fallback = WorkflowFallback("helperA"),
        )
        expectInvalid(base().copy(operations = base().operations + mapOf("helperA" to a, "helperB" to b)))
    }

    @Test fun fallbackCannotCatchPolicy() {
        val wf = Workflow(
            setOf("query", "platformId", "cursor"), emptyList(),
            TestFixtures.lit(JsonArray(emptyList())),
            fallback = WorkflowFallback("types", onFailures = setOf(SourceFailure.POLICY)),
        )
        expectInvalid(withOp("search", wf))
    }

    @Test fun fallbackInputsMustBeAvailable() {
        val helper = Workflow(setOf("extra"), emptyList(), TestFixtures.lit(1))
        val wf = Workflow(
            setOf("query"), emptyList(), TestFixtures.lit(1),
            fallback = WorkflowFallback("helper"),
        )
        expectInvalid(base().copy(operations = base().operations + mapOf("helper" to helper, "types" to wf)))
    }

    @Test fun unsupportedCapabilityRejected() {
        expectInvalid(base().copy(requires = setOf("http-v1", "expressions-v1", "catalog-v1", "scripts-v9")))
    }

    @Test fun missingCapabilityRejected() {
        expectInvalid(base().copy(requires = setOf("http-v1", "expressions-v1")))
    }

    @Test fun assistRequiresAssistCapability() {
        val def = base().copy(assist = AssistPolicy(enabled = true))
        expectInvalid(def)
    }

    @Test fun reservedBuiltinIdRejected() {
        expectInvalid(base().copy(id = "builtin-extra"))
    }

    @Test fun malformedIdRejected() {
        expectInvalid(base().copy(id = "Synthetic!"))
    }

    @Test fun maliciousSelectorRejected() {
        val bad = buildJsonObject {
            put("op", "select")
            put("value", TestFixtures.lit("<p>hi</p>"))
            put("css", "p:matchesOwn(hi)")
        }
        val e = expectInvalid(withOp("types", Workflow(emptySet(), emptyList(), bad)))
        assertTrue(e.fieldPath!!.contains("css"))
    }

    @Test fun regexAttrSelectorRejected() {
        val bad = buildJsonObject {
            put("op", "select")
            put("value", TestFixtures.lit("<p>hi</p>"))
            put("css", "a[href~=game]")
        }
        expectInvalid(withOp("types", Workflow(emptySet(), emptyList(), bad)))
    }

    @Test fun invalidSelectorReportsPath() {
        val bad = buildJsonObject {
            put("op", "select")
            put("value", TestFixtures.lit("<p>hi</p>"))
            put("css", "div[[[")
        }
        val e = expectInvalid(withOp("types", Workflow(emptySet(), emptyList(), bad)))
        assertTrue(e.fieldPath!!.endsWith(".css"))
    }

    @Test fun insecureOriginRejected() {
        val origins = base().origins.copy(catalog = setOf("http://catalog.example.com"))
        expectInvalid(base().copy(origins = origins))
    }

    @Test fun ipOriginRejected() {
        val origins = base().origins.copy(catalog = setOf("https://10.42.1.2"))
        expectInvalid(base().copy(origins = origins))
    }

    @Test fun duplicateOriginFormsRejected() {
        val origins = base().origins.copy(catalog = setOf("https://catalog.example.com", "https://catalog.example.com:443"))
        expectInvalid(base().copy(origins = origins))
    }

    @Test fun deniedHeaderRejected() {
        val wf = TestFixtures.queryParamWorkflow(
            extraQuery = mapOf("page" to TestFixtures.ref("input", "cursor")),
        )
        val step = wf.steps[0].copy(
            request = wf.steps[0].request!!.copy(headers = mapOf("Cookie" to TestFixtures.lit("x"))),
        )
        expectInvalid(withOp("latest", wf.copy(steps = listOf(step))))
    }

    @Test fun getWithFormRejected() {
        val wf = TestFixtures.queryParamWorkflow()
        val step = wf.steps[0].copy(
            request = wf.steps[0].request!!.copy(form = mapOf("a" to TestFixtures.lit("b"))),
        )
        expectInvalid(withOp("latest", wf.copy(steps = listOf(step))))
    }

    @Test fun cachedPostRejected() {
        val wf = Workflow(
            emptySet(),
            listOf(
                WorkflowStep(
                    "r",
                    request = RequestSpec(
                        method = "POST", purpose = "catalog",
                        url = TestFixtures.lit("https://catalog.example.com/"),
                    ),
                ),
            ),
            TestFixtures.lit(1),
            cache = true,
        )
        expectInvalid(base().copy(operations = base().operations + ("helper" to wf)))
    }

    @Test fun resolveDownloadCacheRejected() {
        val wf = Workflow(setOf("file"), emptyList(), TestFixtures.lit(null), cache = true)
        expectInvalid(withOp("resolveDownload", wf))
    }

    @Test fun badSchemaVersionRejected() {
        expectInvalid(base().copy(schemaVersion = 2))
    }

    @Test fun oversizedDefinitionRejected() {
        val e = try {
            DefinitionValidator.parseAndValidate(ByteArray(DefinitionValidator.MAX_DEFINITION_BYTES + 1))
            null
        } catch (e: SourceException) {
            e
        }
        assertNotNull(e)
        assertEquals(SourceFailure.INVALID_DEFINITION, e!!.failure)
    }

    @Test fun malformedJsonRejected() {
        try {
            DefinitionValidator.parseAndValidate("{oops".toByteArray())
            fail("expected failure")
        } catch (e: SourceException) {
            assertEquals(SourceFailure.INVALID_DEFINITION, e.failure)
        }
    }

    @Test fun strictConfigRejectsUnknownKeys() {
        val text = """{"schemaVersion":1,"id":"x","name":"n","author":"a","version":"v",
          |"requires":["http-v1","expressions-v1","catalog-v1"],
          |"origins":{"catalog":[],"download":[],"artwork":[]},
          |"platforms":{},"operations":{},"surprise":true}""".trimMargin()
        try {
            DefinitionValidator.parseAndValidate(text.toByteArray())
            fail("expected failure")
        } catch (e: SourceException) {
            assertEquals(SourceFailure.INVALID_DEFINITION, e.failure)
        }
    }

    @Test fun schemaResourceCoversLanguage() {
        val stream = javaClass.classLoader!!.getResourceAsStream("source.schema.json")
        assertNotNull(stream)
        val schema = Json.parseToJsonElement(stream!!.bufferedReader().readText()).jsonObject
        val defs = schema["\$defs"]!!.jsonObject
        for (op in listOf("Literal", "Ref", "Object", "Array", "Json", "Get", "Html", "Select", "Text",
            "Attr", "Map", "Filter", "First", "Coalesce", "If", "Eq", "Lt", "Not", "Contains",
            "Concat", "Trim", "TrimEnd", "HtmlDecode", "Replace", "Before", "After", "BeforeLast",
            "AfterLast", "Int", "Double", "Digits", "Add", "Size", "Distinct", "Sort", "Platform",
            "Lookup", "Invoke", "PathSegment")) {
            assertTrue("missing op$op", defs.containsKey("op$op"))
        }
        for (key in listOf("origins", "defaults", "assist", "workflow", "step", "request", "fallback", "expr")) {
            assertTrue("missing $key", defs.containsKey(key))
        }
    }
}
