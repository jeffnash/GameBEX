package com.gamebex.source

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
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
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WorkflowRunnerTest {
    private fun defWith(vararg ops: Pair<String, Workflow>): ValidatedDefinition {
        val base = TestFixtures.minimalDefinition()
        return DefinitionValidator.validate(base.copy(operations = base.operations + ops.toMap()))
    }

    @Test fun stepsRunInOrderAndFeedResult() = runTest {
        val wf = Workflow(
            inputs = setOf("seed"),
            steps = listOf(
                WorkflowStep("a", value = TestFixtures.ref("input", "seed")),
                WorkflowStep("b", value = TestFixtures.ref("steps", "a")),
            ),
            result = TestFixtures.obj("out" to TestFixtures.ref("steps", "b")),
        )
        val def = defWith("helper" to wf)
        val runner = WorkflowRunner(def, TestFixtures.failingTransport())
        val out = runner.run("helper", buildJsonObject { put("seed", 7) }).jsonObject
        assertEquals(JsonPrimitive(7), out["out"])
    }

    @Test fun oneHttpRequestPerDeclaredRequest() = runTest {
        val tx = TestFixtures.ScriptedTransport()
        val def = TestFixtures.validated()
        val runner = WorkflowRunner(def, tx)
        runner.run("latest", buildJsonObject { put("cursor", JsonNull) }, setOf("cursor"))
        assertEquals(1, tx.requests.size)
        val req = tx.requests[0]
        assertEquals("GET", req.method)
        assertEquals(NetworkPurpose.CATALOG, req.purpose)
        // Null values are omitted, not sent as "null".
        assertTrue(req.query.isEmpty())
    }

    @Test fun nullInputsRejectedWhereNotPermitted() = runTest {
        val runner = WorkflowRunner(TestFixtures.validated(), TestFixtures.failingTransport())
        try {
            runner.run("detail", buildJsonObject { put("ref", JsonNull) })
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test fun sameResponseMintFallback() = runTest {
        // mint POSTs once; parseMintJson falls back to parseMintHtml on the same body.
        var posts = 0
        val tx = object : SourceTransport {
            override suspend fun execute(request: EvaluatedRequest): SourceResponse {
                posts++
                return SourceResponse(
                    200, emptyMap(),
                    """<a href="https://dl.example.com/f.zip?token=abc">dl</a>""",
                    request.url,
                )
            }
        }
        val parseJson = Workflow(
            inputs = setOf("body"),
            steps = emptyList(),
            result = buildJsonObject {
                put("op", "json")
                put("value", TestFixtures.ref("input", "body"))
            },
            fallback = WorkflowFallback("parseMintHtml", onFailures = setOf(SourceFailure.INVALID_RESPONSE)),
        )
        val parseHtml = Workflow(
            inputs = setOf("body"),
            steps = emptyList(),
            result = buildJsonObject {
                put("op", "attr")
                put(
                    "value",
                    buildJsonObject {
                        put("op", "first")
                        put(
                            "value",
                            buildJsonObject {
                                put("op", "select")
                                put(
                                    "value",
                                    buildJsonObject {
                                        put("op", "html")
                                        put("value", TestFixtures.ref("input", "body"))
                                    },
                                )
                                put("css", "a[href*=token]")
                            },
                        )
                    },
                )
                put("name", "href")
                put("absolute", true)
            },
        )
        val mint = Workflow(
            inputs = setOf("file"),
            steps = listOf(
                WorkflowStep(
                    "response",
                    request = RequestSpec(
                        method = "POST", purpose = "catalog",
                        url = TestFixtures.lit("https://catalog.example.com/mint"),
                        form = mapOf("action" to TestFixtures.lit("k_get_download")),
                    ),
                ),
            ),
            result = buildJsonObject {
                put("op", "invoke")
                put("operation", "parseMintJson")
                put(
                    "input",
                    TestFixtures.obj("body" to TestFixtures.get(TestFixtures.ref("steps", "response"), "body")),
                )
            },
        )
        val def = defWith("mint" to mint, "parseMintJson" to parseJson, "parseMintHtml" to parseHtml)
        val runner = WorkflowRunner(def, tx)
        val out = runner.run("mint", buildJsonObject { put("file", TestFixtures.obj()) })
        assertEquals(JsonPrimitive("https://dl.example.com/f.zip?token=abc"), out)
        assertEquals(1, posts)
    }

    @Test fun explicitFailureFallbackAndEmptyFallback() = runTest {
        val tx = TestFixtures.ScriptedTransport(
            default = { throw SourceException(SourceFailure.HTTP, "synthetic", "primary", statusCode = 500) },
        )
        val primary = Workflow(
            inputs = emptySet(),
            steps = listOf(
                WorkflowStep(
                    "r",
                    request = RequestSpec(
                        method = "GET", purpose = "catalog",
                        url = TestFixtures.lit("https://catalog.example.com/primary"),
                    ),
                ),
            ),
            result = TestFixtures.lit("primary"),
            fallback = WorkflowFallback("secondary", onFailures = setOf(SourceFailure.HTTP)),
        )
        val secondary = Workflow(emptySet(), emptyList(), TestFixtures.lit("fallback"))
        val def = defWith("primary" to primary, "secondary" to secondary)
        val runner = WorkflowRunner(def, tx)
        assertEquals(JsonPrimitive("fallback"), runner.run("primary", JsonObject(emptyMap())))

        // onEmpty triggers on an empty array.
        val emptyPrimary = Workflow(
            emptySet(), emptyList(), TestFixtures.lit(JsonArray(emptyList())),
            fallback = WorkflowFallback("secondary", onEmpty = true),
        )
        val def2 = defWith("emptyPrimary" to emptyPrimary, "secondary" to secondary)
        val runner2 = WorkflowRunner(def2, TestFixtures.failingTransport())
        assertEquals(JsonPrimitive("fallback"), runner2.run("emptyPrimary", JsonObject(emptyMap())))

        // Unconfigured failures do not fall back.
        val strict = Workflow(
            emptySet(), emptyList(),
            buildJsonObject {
                put("op", "json")
                put("value", TestFixtures.lit("{bad"))
            },
            fallback = WorkflowFallback("secondary", onFailures = setOf(SourceFailure.HTTP)),
        )
        val def3 = defWith("strict" to strict, "secondary" to secondary)
        try {
            WorkflowRunner(def3, TestFixtures.failingTransport()).run("strict", JsonObject(emptyMap()))
            fail("expected INVALID_RESPONSE")
        } catch (e: SourceException) {
            assertEquals(SourceFailure.INVALID_RESPONSE, e.failure)
        }
    }

    @Test fun cancellationBypassesFallback() = runTest {
        val tx = object : SourceTransport {
            override suspend fun execute(request: EvaluatedRequest): SourceResponse {
                throw CancellationException("cancelled")
            }
        }
        val primary = Workflow(
            emptySet(),
            listOf(
                WorkflowStep(
                    "r",
                    request = RequestSpec(
                        method = "GET", purpose = "catalog",
                        url = TestFixtures.lit("https://catalog.example.com/primary"),
                    ),
                ),
            ),
            TestFixtures.lit("primary"),
            fallback = WorkflowFallback(
                "secondary",
                onFailures = setOf(SourceFailure.NETWORK, SourceFailure.HTTP, SourceFailure.INVALID_RESPONSE),
            ),
        )
        val secondary = Workflow(emptySet(), emptyList(), TestFixtures.lit("fallback"))
        val def = defWith("primary" to primary, "secondary" to secondary)
        try {
            WorkflowRunner(def, tx).run("primary", JsonObject(emptyMap()))
            fail("expected CancellationException")
        } catch (e: CancellationException) {
            assertEquals("cancelled", e.message)
        }
    }

    @Test fun cachedWorkflowRunsOnce() = runTest {
        var calls = 0
        val tx = TestFixtures.ScriptedTransport(
            default = {
                calls++
                TestFixtures.jsonResponse("""[{"id":"a"}]""")
            },
        )
        val helper = Workflow(
            emptySet(),
            listOf(
                WorkflowStep(
                    "r",
                    request = RequestSpec(
                        method = "GET", purpose = "catalog",
                        url = TestFixtures.lit("https://catalog.example.com/tax"),
                    ),
                ),
            ),
            buildJsonObject {
                put("op", "json")
                put("value", TestFixtures.ref("steps", "r"))
            },
            cache = true,
        )
        val top = Workflow(
            emptySet(), emptyList(),
            buildJsonObject {
                put("op", "array")
                put(
                    "items",
                    buildJsonArray {
                        repeat(2) {
                            add(
                                buildJsonObject {
                                    put("op", "invoke")
                                    put("operation", "helper")
                                    put("input", TestFixtures.obj())
                                },
                            )
                        }
                    },
                )
            },
        )
        val def = defWith("helper" to helper, "top" to top)
        val runner = WorkflowRunner(def, tx)
        runner.run("top", JsonObject(emptyMap()))
        runner.run("top", JsonObject(emptyMap()))
        assertEquals(1, calls)
    }

    @Test fun invokeMemoizesWithinOneRun() = runTest {
        var calls = 0
        val tx = TestFixtures.ScriptedTransport(
            default = {
                calls++
                TestFixtures.jsonResponse("""{"v":1}""")
            },
        )
        val helper = Workflow(
            inputs = setOf("p"),
            steps = listOf(
                WorkflowStep(
                    "r",
                    request = RequestSpec(
                        method = "GET", purpose = "catalog",
                        url = TestFixtures.lit("https://catalog.example.com/h"),
                        query = mapOf("p" to TestFixtures.ref("input", "p")),
                    ),
                ),
            ),
            result = buildJsonObject {
                put("op", "json")
                put("value", TestFixtures.ref("steps", "r"))
            },
        )
        val top = Workflow(
            emptySet(), emptyList(),
            buildJsonObject {
                put("op", "array")
                put(
                    "items",
                    buildJsonArray {
                        repeat(2) {
                            add(
                                buildJsonObject {
                                    put("op", "invoke")
                                    put("operation", "helper")
                                    put("input", TestFixtures.obj("p" to TestFixtures.lit("same")))
                                },
                            )
                        }
                    },
                )
            },
        )
        val def = defWith("helper" to helper, "top" to top)
        WorkflowRunner(def, tx).run("top", JsonObject(emptyMap()))
        assertEquals(1, calls)
    }
}
