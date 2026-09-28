package com.gamebex.source

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Shared builders for runtime tests. */
object TestFixtures {
    fun lit(value: JsonElement): JsonObject = buildJsonObject {
        put("op", "literal")
        put("value", value)
    }

    fun lit(value: String?): JsonObject = lit(if (value == null) JsonNull else JsonPrimitive(value))
    fun lit(value: Boolean): JsonObject = lit(JsonPrimitive(value))
    fun lit(value: Number): JsonObject = lit(JsonPrimitive(value))

    fun ref(vararg segments: Any): JsonObject = buildJsonObject {
        put("op", "ref")
        put(
            "path",
            buildJsonArray {
                for (s in segments) {
                    when (s) {
                        is String -> add(s)
                        is Int -> add(s)
                        is Long -> add(s)
                        else -> throw IllegalArgumentException("bad segment $s")
                    }
                }
            },
        )
    }

    fun get(value: JsonObject, vararg segments: Any): JsonObject = buildJsonObject {
        put("op", "get")
        put("value", value)
        put(
            "path",
            buildJsonArray {
                for (s in segments) {
                    when (s) {
                        is String -> add(s)
                        is Int -> add(s)
                        is Long -> add(s)
                        else -> throw IllegalArgumentException("bad segment $s")
                    }
                }
            },
        )
    }

    fun obj(vararg fields: Pair<String, JsonObject>): JsonObject = buildJsonObject {
        put("op", "object")
        put("fields", buildJsonObject { for ((k, v) in fields) put(k, v) })
    }

    fun arr(vararg items: JsonObject): JsonObject = buildJsonObject {
        put("op", "array")
        put("items", buildJsonArray { for (i in items) add(i) })
    }

    fun queryParamWorkflow(
        inputs: Set<String> = setOf("cursor"),
        url: String = "https://catalog.example.com/items",
        extraQuery: Map<String, JsonObject> = mapOf("page" to ref("input", "cursor")),
    ): Workflow = Workflow(
        inputs = inputs,
        steps = listOf(
            WorkflowStep(
                name = "response",
                request = RequestSpec(
                    method = "GET",
                    purpose = "catalog",
                    url = lit(url),
                    query = extraQuery,
                ),
            ),
        ),
        result = obj("items" to arr(), "next" to lit(null)),
    )

    fun defaultOps(): Map<String, Workflow> = mapOf(
        "consoles" to Workflow(emptySet(), emptyList(), lit(JsonArray(emptyList()))),
        "types" to Workflow(emptySet(), emptyList(), lit(JsonArray(emptyList()))),
        "latest" to queryParamWorkflow(),
        "browse" to queryParamWorkflow(setOf("consoleId", "typeId", "cursor")),
        "search" to queryParamWorkflow(setOf("query", "platformId", "cursor")),
        "detail" to Workflow(setOf("ref"), emptyList(), lit(null)),
        "variants" to Workflow(setOf("ref"), emptyList(), lit(JsonArray(emptyList()))),
        "resolveDownload" to Workflow(setOf("file"), emptyList(), lit(null)),
    )

    fun minimalDefinition(ops: Map<String, Workflow> = defaultOps()): SourceDefinition =
        SourceDefinition(
            schemaVersion = 1,
            id = "synthetic",
            name = "Synthetic",
            author = "tests",
            version = "1.0.0",
            requires = setOf("http-v1", "expressions-v1", "catalog-v1"),
            origins = SourceOrigins(
                catalog = setOf("https://catalog.example.com"),
                download = setOf("https://dl.example.com"),
                artwork = setOf("https://art.example.com"),
            ),
            platforms = mapOf("snes-slug" to "snes", "gb-slug" to "gb"),
            operations = ops,
        )

    fun validated(def: SourceDefinition = minimalDefinition()): ValidatedDefinition =
        DefinitionValidator.validate(def)

    fun failingTransport(): SourceTransport = object : SourceTransport {
        override suspend fun execute(request: EvaluatedRequest): SourceResponse =
            throw AssertionError("transport must not be called")
    }

    /** Records requests and answers from a script keyed by method+url. */
    class ScriptedTransport(
        private val scripts: Map<String, SourceResponse> = emptyMap(),
        private val default: ((EvaluatedRequest) -> SourceResponse)? = null,
    ) : SourceTransport {
        val requests = ArrayList<EvaluatedRequest>()
        override suspend fun execute(request: EvaluatedRequest): SourceResponse {
            requests.add(request)
            val key = request.method + " " + request.url
            return scripts[key] ?: default?.invoke(request)
                ?: SourceResponse(200, emptyMap(), "{}", request.url)
        }
    }

    fun jsonResponse(body: String, url: String = "https://catalog.example.com/items"): SourceResponse =
        SourceResponse(200, mapOf("Content-Type" to listOf("application/json")), body, url)
}
