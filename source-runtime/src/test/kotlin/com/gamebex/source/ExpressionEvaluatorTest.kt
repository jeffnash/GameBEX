package com.gamebex.source

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ExpressionEvaluatorTest {
    private val def = TestFixtures.validated()

    private suspend fun evaluateForTest(expr: JsonObject, input: JsonObject): kotlinx.serialization.json.JsonElement {
        val evaluator = ExpressionEvaluator(def, TestFixtures.failingTransport())
        val out = evaluator.evaluate(expr, EvalEnv(input))
        return (out as EvalValue.Json).element
    }

    private fun input(vararg pairs: Pair<String, kotlinx.serialization.json.JsonElement>): JsonObject =
        buildJsonObject { for ((k, v) in pairs) put(k, v) }

    private suspend fun expectInvalid(expr: JsonObject, input: JsonObject = JsonObject(emptyMap())) {
        try {
            evaluateForTest(expr, input)
        } catch (e: SourceException) {
            assertEquals(SourceFailure.INVALID_RESPONSE, e.failure)
            return
        }
        fail("expected INVALID_RESPONSE")
    }

    @Test fun literalDoesNotInterpolateInput() = runTest {
        val expression = Json.parseToJsonElement(
            """{"op":"literal","value":"${'$'}{input.query}"}""",
        ).jsonObject
        val input = buildJsonObject { put("query", "secret") }
        assertEquals(JsonPrimitive("${'$'}{input.query}"), evaluateForTest(expression, input))
    }

    @Test fun queryTextIsEncodedAsOneValue() {
        val url = "https://example.org/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", "Mario & Zelda")
            .build()
        assertEquals("Mario & Zelda", url.queryParameter("q"))
        assertEquals(setOf("q"), url.queryParameterNames)
    }

    @Test fun refReadsInputAndMissingIsNull() = runTest {
        val expr = TestFixtures.obj(
            "a" to TestFixtures.ref("input", "q"),
            "b" to TestFixtures.ref("input", "missing", "deep"),
        )
        val out = evaluateForTest(expr, input("q" to JsonPrimitive("hi"))).jsonObject
        assertEquals(JsonPrimitive("hi"), out["a"])
        assertEquals(JsonNull, out["b"])
    }

    @Test fun refReadsSourceProps() = runTest {
        val expr = TestFixtures.obj(
            "id" to TestFixtures.ref("source", "id"),
            "gb" to TestFixtures.ref("source", "platforms", "gb-slug"),
        )
        val out = evaluateForTest(expr, JsonObject(emptyMap())).jsonObject
        assertEquals(JsonPrimitive("synthetic"), out["id"])
        assertEquals(JsonPrimitive("gb"), out["gb"])
    }

    @Test fun objectAndArrayBuild() = runTest {
        val expr = TestFixtures.obj(
            "list" to TestFixtures.arr(TestFixtures.lit(1), TestFixtures.lit("x")),
        )
        val out = evaluateForTest(expr, JsonObject(emptyMap())).jsonObject
        assertEquals(buildJsonArray {
            add(1)
            add("x")
        }, out["list"])
    }

    @Test fun jsonParsesAndRejectsMalformed() = runTest {
        val good = buildJsonObject {
            put("op", "json")
            put("value", TestFixtures.lit("""{"a":[1,2]}"""))
        }
        assertEquals(JsonPrimitive(2), (evaluateForTest(good, JsonObject(emptyMap())) as JsonObject)["a"].let {
            (it as JsonArray)[1]
        })
        expectInvalid(
            buildJsonObject {
                put("op", "json")
                put("value", TestFixtures.lit("{oops"))
            },
        )
    }

    @Test fun getTraversesAndMissesNull() = runTest {
        val doc = buildJsonObject {
            put("items", buildJsonArray { add(buildJsonObject { put("t", "x") }) })
        }
        val expr = TestFixtures.obj(
            "hit" to TestFixtures.get(TestFixtures.ref("input", "doc"), "items", 0, "t"),
            "miss" to TestFixtures.get(TestFixtures.ref("input", "doc"), "items", 9, "t"),
        )
        val out = evaluateForTest(expr, input("doc" to doc)).jsonObject
        assertEquals(JsonPrimitive("x"), out["hit"])
        assertEquals(JsonNull, out["miss"])
    }

    @Test fun htmlSelectTextAttrAbsolute() = runTest {
        val html = """<html><body><a class="g" href="/roms/abc">Abc &amp; Co</a></body></html>"""
        val docExpr = buildJsonObject {
            put("op", "html")
            put("value", TestFixtures.lit(html))
        }
        val firstLink = buildJsonObject {
            put("op", "first")
            put(
                "value",
                buildJsonObject {
                    put("op", "select")
                    put("value", docExpr)
                    put("css", "a.g")
                },
            )
        }
        val expr = TestFixtures.obj(
            "text" to buildJsonObject {
                put("op", "text")
                put("value", firstLink)
            },
            "href" to buildJsonObject {
                put("op", "attr")
                put("value", firstLink)
                put("name", "href")
                put("absolute", false)
            },
        )
        val out = evaluateForTest(expr, JsonObject(emptyMap())).jsonObject
        assertEquals(JsonPrimitive("Abc & Co"), out["text"])
        assertEquals(JsonPrimitive("/roms/abc"), out["href"])
    }

    @Test fun mapFilterFirstOrdered() = runTest {
        val nums = buildJsonArray {
            add(3)
            add(1)
            add(2)
        }
        val expr = TestFixtures.obj(
            "mapped" to buildJsonObject {
                put("op", "map")
                put("value", TestFixtures.ref("input", "nums"))
                put("as", "n")
                put(
                    "body",
                    buildJsonObject {
                        put("op", "add")
                        put("left", TestFixtures.ref("n"))
                        put("right", TestFixtures.lit(10))
                    },
                )
            },
            "kept" to buildJsonObject {
                put("op", "filter")
                put("value", TestFixtures.ref("input", "nums"))
                put("as", "n")
                put(
                    "predicate",
                    buildJsonObject {
                        put("op", "lt")
                        put("left", TestFixtures.ref("n"))
                        put("right", TestFixtures.lit(3))
                    },
                )
            },
        )
        val out = evaluateForTest(expr, input("nums" to nums)).jsonObject
        assertEquals("[13,11,12]", out["mapped"].toString())
        assertEquals("[1,2]", out["kept"].toString())
    }

    @Test fun filterKeepsNodeListsAsNodes() = runTest {
        val html = "<ul><li>keep-a</li><li>drop</li><li>keep-b</li></ul>"
        val doc = buildJsonObject {
            put("op", "html")
            put("value", TestFixtures.lit(html))
        }
        val kept = buildJsonObject {
            put("op", "filter")
            put(
                "value",
                buildJsonObject {
                    put("op", "select")
                    put("value", doc)
                    put("css", "li")
                },
            )
            put("as", "li")
            put(
                "predicate",
                buildJsonObject {
                    put("op", "contains")
                    put(
                        "value",
                        buildJsonObject {
                            put("op", "text")
                            put("value", TestFixtures.ref("li"))
                        },
                    )
                    put("needle", TestFixtures.lit("keep"))
                    put("ignoreCase", false)
                },
            )
        }
        // Selecting text out of the kept nodes proves they stayed nodes.
        val expr = buildJsonObject {
            put("op", "map")
            put("value", kept)
            put("as", "k")
            put(
                "body",
                buildJsonObject {
                    put("op", "text")
                    put("value", TestFixtures.ref("k"))
                },
            )
        }
        assertEquals("[\"keep-a\",\"keep-b\"]", evaluateForTest(expr, JsonObject(emptyMap())).toString())
    }

    @Test fun coalesceLazyAndBlankAware() = runTest {
        val expr = buildJsonObject {
            put("op", "coalesce")
            put(
                "values",
                buildJsonArray {
                    add(TestFixtures.ref("input", "missing"))
                    add(TestFixtures.lit("   "))
                    add(TestFixtures.lit("kept"))
                    add(TestFixtures.ref("input", "alsoMissing", "deep"))
                },
            )
        }
        assertEquals(JsonPrimitive("kept"), evaluateForTest(expr, JsonObject(emptyMap())))
    }

    @Test fun ifEvaluatesOneBranch() = runTest {
        val expr = buildJsonObject {
            put("op", "if")
            put("condition", TestFixtures.lit(true))
            put("then", TestFixtures.lit("yes"))
            put("else", TestFixtures.ref("input", "missing", "x", "y"))
        }
        assertEquals(JsonPrimitive("yes"), evaluateForTest(expr, JsonObject(emptyMap())))
    }

    @Test fun eqSemantics() = runTest {
        fun eq(l: JsonObject, r: JsonObject, ignoreCase: Boolean = false) = buildJsonObject {
            put("op", "eq")
            put("left", l)
            put("right", r)
            put("ignoreCase", ignoreCase)
        }
        // Equal strings.
        assertEquals(JsonPrimitive(true), evaluateForTest(eq(TestFixtures.lit("a"), TestFixtures.lit("a")), JsonObject(emptyMap())))
        // Case differs without ignoreCase.
        assertEquals(JsonPrimitive(false), evaluateForTest(eq(TestFixtures.lit("A"), TestFixtures.lit("a")), JsonObject(emptyMap())))
        // Case-insensitive match.
        assertEquals(JsonPrimitive(true), evaluateForTest(eq(TestFixtures.lit("A"), TestFixtures.lit("a"), true), JsonObject(emptyMap())))
        // No implicit number/string equality.
        assertEquals(JsonPrimitive(false), evaluateForTest(eq(TestFixtures.lit(1), TestFixtures.lit("1")), JsonObject(emptyMap())))
        // Numeric equality across int/long spellings.
        assertEquals(JsonPrimitive(true), evaluateForTest(eq(TestFixtures.lit(2), TestFixtures.lit(2L)), JsonObject(emptyMap())))
    }

    @Test fun ltAndNotStrict() = runTest {
        val ltExpr = buildJsonObject {
            put("op", "lt")
            put("left", TestFixtures.lit(1))
            put("right", TestFixtures.lit(2))
        }
        assertEquals(JsonPrimitive(true), evaluateForTest(ltExpr, JsonObject(emptyMap())))
        expectInvalid(
            buildJsonObject {
                put("op", "lt")
                put("left", TestFixtures.lit("a"))
                put("right", TestFixtures.lit(2))
            },
        )
        expectInvalid(
            buildJsonObject {
                put("op", "not")
                put("value", TestFixtures.lit("x"))
            },
        )
    }

    @Test fun stringOperators() = runTest {
        fun one(op: String, vararg extra: Pair<String, kotlinx.serialization.json.JsonElement>): JsonObject =
            buildJsonObject {
                put("op", op)
                put("value", TestFixtures.ref("input", "s"))
                for ((k, v) in extra) put(k, v)
            }
        val s = JsonPrimitive("  Foo/Bar.html\\\\")
        assertEquals(JsonPrimitive("Foo/Bar.html\\\\"), evaluateForTest(one("trim"), input("s" to s)))
        assertEquals(
            JsonPrimitive("  Foo/Bar.html"),
            evaluateForTest(one("trimEnd", "chars" to JsonPrimitive("\\")), input("s" to s)),
        )
        assertEquals(
            JsonPrimitive("Bar.html\\\\"),
            evaluateForTest(one("after", "delimiter" to JsonPrimitive("Foo/")), input("s" to s)),
        )
        assertEquals(
            JsonPrimitive("  Foo/Bar"),
            evaluateForTest(one("beforeLast", "delimiter" to JsonPrimitive(".")), input("s" to s)),
        )
        assertEquals(
            JsonPrimitive("  Foo/Bar.html//"),
            evaluateForTest(
                buildJsonObject {
                    put("op", "replace")
                    put("value", TestFixtures.ref("input", "s"))
                    put("old", "\\\\")
                    put("new", "//")
                },
                input("s" to s),
            ),
        )
        assertEquals(
            JsonPrimitive("Fish & Chips"),
            evaluateForTest(one("htmlDecode"), input("s" to JsonPrimitive("Fish &amp; Chips"))),
        )
        assertEquals(
            JsonPrimitive("abc123"),
            evaluateForTest(
                buildJsonObject {
                    put("op", "concat")
                    put("values", buildJsonArray { add(TestFixtures.lit("abc")); add(TestFixtures.lit(null)); add(TestFixtures.lit(123)) })
                },
                JsonObject(emptyMap()),
            ),
        )
        assertEquals(
            JsonPrimitive("42"),
            evaluateForTest(
                buildJsonObject {
                    put("op", "digits")
                    put("value", TestFixtures.lit("4*2 reviews"))
                },
                JsonObject(emptyMap()),
            ),
        )
    }

    @Test fun numericParsingLenient() = runTest {
        fun parse(op: String, v: kotlinx.serialization.json.JsonElement) = buildJsonObject {
            put("op", op)
            put("value", TestFixtures.lit(v))
        }
        assertEquals(JsonPrimitive(42), evaluateForTest(parse("int", JsonPrimitive(" 42 ")), JsonObject(emptyMap())))
        assertEquals(JsonNull, evaluateForTest(parse("int", JsonPrimitive("x")), JsonObject(emptyMap())))
        assertEquals(JsonPrimitive(4.5), evaluateForTest(parse("double", JsonPrimitive("4.5")), JsonObject(emptyMap())))
        assertEquals(JsonNull, evaluateForTest(parse("double", JsonNull), JsonObject(emptyMap())))
    }

    @Test fun addOverflowRaises() = runTest {
        expectInvalid(
            buildJsonObject {
                put("op", "add")
                put("left", TestFixtures.lit(Long.MAX_VALUE))
                put("right", TestFixtures.lit(1))
            },
        )
    }

    @Test fun sizeDistinctSort() = runTest {
        val rows = buildJsonArray {
            add(buildJsonObject {
                put("n", "b")
                put("c", 1)
            })
            add(buildJsonObject { put("n", "a") })
            add(buildJsonObject {
                put("n", "c")
                put("c", 9)
            })
        }
        val sorted = buildJsonObject {
            put("op", "sort")
            put("value", TestFixtures.ref("input", "rows"))
            put("field", "c")
            put("descending", true)
        }
        val out = evaluateForTest(sorted, input("rows" to rows)) as JsonArray
        assertEquals("c", (out[0] as JsonObject)["n"]!!.jsonPrimitive.content)
        assertEquals("b", (out[1] as JsonObject)["n"]!!.jsonPrimitive.content)
        // Absent sort field sorts last.
        assertEquals("a", (out[2] as JsonObject)["n"]!!.jsonPrimitive.content)

        val dupes = buildJsonArray {
            add("x")
            add("y")
            add("x")
        }
        val distinct = buildJsonObject {
            put("op", "distinct")
            put("value", TestFixtures.ref("input", "dupes"))
        }
        assertEquals("[\"x\",\"y\"]", evaluateForTest(distinct, input("dupes" to dupes)).toString())
        val size = buildJsonObject {
            put("op", "size")
            put("value", TestFixtures.ref("input", "dupes"))
        }
        assertEquals(JsonPrimitive(3), evaluateForTest(size, input("dupes" to dupes)))
    }

    @Test fun platformLookupPathSegment() = runTest {
        val plat = buildJsonObject {
            put("op", "platform")
            put("value", TestFixtures.lit("snes-slug"))
        }
        assertEquals(JsonPrimitive("snes"), evaluateForTest(plat, JsonObject(emptyMap())))
        val missing = buildJsonObject {
            put("op", "platform")
            put("value", TestFixtures.lit("nope"))
        }
        assertEquals(JsonNull, evaluateForTest(missing, JsonObject(emptyMap())))

        val consoles = buildJsonArray {
            add(buildJsonObject {
                put("id", 7)
                put("slug", "snes-slug")
            })
            add(buildJsonObject {
                put("id", 9)
                put("slug", "gb-slug")
            })
        }
        val lookup = buildJsonObject {
            put("op", "lookup")
            put("value", TestFixtures.ref("input", "consoles"))
            put("field", "slug")
            put("key", TestFixtures.lit("gb-slug"))
        }
        val found = evaluateForTest(lookup, input("consoles" to consoles)) as JsonObject
        assertEquals(JsonPrimitive(9), found["id"])

        val seg = buildJsonObject {
            put("op", "pathSegment")
            put("value", TestFixtures.lit("a/b c?"))
        }
        assertEquals(JsonPrimitive("a%2Fb%20c%3F"), evaluateForTest(seg, JsonObject(emptyMap())))
    }

    @Test fun recordBudgetRejectsHugeSelect() = runTest {
        val big = buildString {
            append("<ul>")
            repeat(10_001) { append("<li>x</li>") }
            append("</ul>")
        }
        expectInvalid(
            buildJsonObject {
                put("op", "select")
                put(
                    "value",
                    buildJsonObject {
                        put("op", "html")
                        put("value", TestFixtures.lit(big))
                    },
                )
                put("css", "li")
            },
        )
    }

    @Test fun invokeOutsideWorkflowRejected() = runTest {
        expectInvalid(
            buildJsonObject {
                put("op", "invoke")
                put("operation", "types")
                put("input", TestFixtures.obj())
            },
        )
    }

    @Test fun responseValuesCannotEscape() = runTest {
        // A bare steps ref would leak a Response wrapper; strict object building rejects it.
        val evaluator = ExpressionEvaluator(def, TestFixtures.failingTransport())
        val env = EvalEnv(JsonObject(emptyMap()), mapOf("r" to EvalValue.Resp(SourceResponse(200, emptyMap(), "x", "https://catalog.example.com/"))))
        try {
            evaluator.evaluate(TestFixtures.obj("leak" to TestFixtures.ref("steps", "r")), env)
            fail("expected INVALID_RESPONSE")
        } catch (e: SourceException) {
            assertEquals(SourceFailure.INVALID_RESPONSE, e.failure)
            assertTrue(e.message!!.contains("INVALID_RESPONSE"))
        }
    }
}
