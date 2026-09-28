package com.gamebex.source

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import java.io.File
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    if (args.size != 1) {
        System.err.println("usage: checkSource <sourceDir>")
        exitProcess(2)
    }
    val dumpMissing = System.getProperty("dumpMissing") == "true"
    exitProcess(FixtureRunner.run(File(args[0]), dumpMissing))
}

/**
 * Validates a source repository directory: definition, platform-map
 * extraction assertion, and every operation case in fixtures/cases.json.
 * Returns a process exit code and prints one line per case plus a summary.
 * No secrets are dumped: only case names, field paths, and short snippets.
 */
object FixtureRunner {
    private val json = Json { encodeDefaults = true; explicitNulls = true }
    private val strictJson = Json {}

    fun run(root: File, dumpMissing: Boolean = false): Int {
        val canonicalRoot = runCatching { root.canonicalFile }.getOrNull()
            ?: return fail("cannot resolve ${root.path}")
        fun resolveChild(path: String): File? {
            val child = runCatching { File(canonicalRoot, path).canonicalFile }.getOrNull() ?: return null
            if (child != canonicalRoot && !child.path.startsWith(canonicalRoot.path + File.separator)) return null
            return child
        }
        val defFiles = canonicalRoot.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".source.json") }
            .orEmpty()
        if (defFiles.size != 1) return fail("want exactly one *.source.json, found ${defFiles.size}")
        val defFile = defFiles[0]
        val defBytes = defFile.readBytes()
        val validated = try {
            DefinitionValidator.parseAndValidate(defBytes)
        } catch (e: SourceException) {
            return fail("invalid definition: ${e.failure} ${e.fieldPath} ${causeOf(e)}")
        }
        val revision = sha256Hex(defBytes)
        println("definition ${validated.id} ${validated.def.version} rev ${revision.take(12)}")
        val registryFile = resolveChild("fixtures/registry-platforms.json")
        if (registryFile != null && registryFile.isFile) {
            val expectedPlatforms = try {
                strictJson.decodeFromString<Map<String, String>>(registryFile.readText())
            } catch (e: IllegalArgumentException) {
                return fail("fixtures/registry-platforms.json is not a string map")
            }
            if (expectedPlatforms != validated.platforms) {
                val missing = expectedPlatforms.keys - validated.platforms.keys
                val extra = validated.platforms.keys - expectedPlatforms.keys
                val changed = expectedPlatforms.keys.intersect(validated.platforms.keys)
                    .filter { expectedPlatforms[it] != validated.platforms[it] }
                return fail("platform map drift: missing=$missing extra=$extra changed=$changed")
            }
            println("platforms ${validated.platforms.size} entries match registry fixture")
        }
        val casesFile = resolveChild("fixtures/cases.json") ?: return fail("missing fixtures/cases.json")
        if (!casesFile.isFile) return fail("missing fixtures/cases.json")
        val cases = try {
            strictJson.decodeFromString<JsonArray>(casesFile.readText())
        } catch (e: IllegalArgumentException) {
            return fail("fixtures/cases.json is not a JSON array: ${short(e.message)}")
        }
        var passed = 0
        var staged = 0
        for (index in cases.indices) {
            val raw = cases[index] as? JsonObject ?: return fail("case[$index] is not an object")
            for (key in listOf("name", "operation", "input", "requests", "expected")) {
                if (key !in raw) return fail("case[$index] lacks '$key'")
            }
            if (raw.keys != setOf("name", "operation", "input", "requests", "expected")) {
                return fail("case[$index] has unexpected keys ${raw.keys}")
            }
            val name = (raw["name"] as? JsonPrimitive)?.contentOrNull ?: return fail("case[$index] lacks a name")
            val result = runCatching {
                runCase(validated, revision, name, raw, ::resolveChild, dumpMissing)
            }.getOrElse { e ->
                return fail("case '$name': runner error ${short(e.message)}")
            }
            if (!result.ok) {
                if (dumpMissing && result.message.startsWith("STAGED ")) {
                    println("case '$name': ${result.message}")
                    staged++
                    continue
                }
                return fail("case '$name': ${result.message}")
            }
            println("ok $name")
            passed++
        }
        if (staged > 0) return fail("$staged file(s) staged for review; re-run after review")
        println("$passed cases passed")
        return 0
    }

    private data class Outcome(val ok: Boolean, val message: String)

    private fun runCase(
        validated: ValidatedDefinition,
        revision: String,
        name: String,
        raw: JsonObject,
        resolveChild: (String) -> File?,
        dumpMissing: Boolean,
    ): Outcome {
        val operation = (raw["operation"] as? JsonPrimitive)?.contentOrNull
            ?: return Outcome(false, "missing operation")
        if (operation !in validated.def.operations) return Outcome(false, "unknown operation $operation")
        val input = raw["input"] as? JsonObject ?: return Outcome(false, "input must be an object")
        val scripts = raw["requests"] as? JsonArray ?: return Outcome(false, "requests must be an array")
        val expectedPath = (raw["expected"] as? JsonPrimitive)?.contentOrNull
            ?: return Outcome(false, "missing expected path")
        val expectedFile = resolveChild(expectedPath) ?: return Outcome(false, "expected escapes the repo: $expectedPath")
        data class Script(
            val method: String,
            val url: String,
            val query: Map<String, String>,
            val form: Map<String, String>,
            val headers: Map<String, String>,
            val status: Int,
            val responseHeaders: Map<String, List<String>>,
            val body: String,
        )
        fun stringMap(e: JsonElement?, what: String): Map<String, String>? {
            if (e == null || e is JsonNull) return emptyMap()
            val o = e as? JsonObject ?: return null
            val out = LinkedHashMap<String, String>()
            for ((k, v) in o) {
                val s = (v as? JsonPrimitive)?.contentOrNull ?: return null
                out[k] = s
            }
            return out
        }
        val parsed = ArrayList<Script>()
        for (i in scripts.indices) {
            val s = scripts[i] as? JsonObject ?: return Outcome(false, "requests[$i] is not an object")
            val method = (s["method"] as? JsonPrimitive)?.contentOrNull
                ?: return Outcome(false, "requests[$i] lacks method")
            val url = (s["url"] as? JsonPrimitive)?.contentOrNull
                ?: return Outcome(false, "requests[$i] lacks url")
            val query = stringMap(s["query"], "query") ?: return Outcome(false, "requests[$i].query must be a string map")
            val form = stringMap(s["form"], "form") ?: return Outcome(false, "requests[$i].form must be a string map")
            val headers = stringMap(s["headers"], "headers")
                ?: return Outcome(false, "requests[$i].headers must be a string map")
            val status = (s["responseStatus"] as? JsonPrimitive)?.intOrNull ?: 200
            val responseHeaders = stringMap(s["responseHeaders"], "responseHeaders")
                ?: return Outcome(false, "requests[$i].responseHeaders must be a string map")
            val responsePath = (s["responseFile"] as? JsonPrimitive)?.contentOrNull
                ?: return Outcome(false, "requests[$i] lacks responseFile")
            val responseFile = resolveChild(responsePath)
                ?: return Outcome(false, "requests[$i] escapes the repo: $responsePath")
            if (!responseFile.isFile) return Outcome(false, "requests[$i] missing file: $responsePath")
            parsed.add(
                Script(
                    method, url, query, form,
                    headers.mapKeys { it.key.lowercase() }, status,
                    responseHeaders.map { (k, v) -> k to listOf(v) }.toMap(),
                    responseFile.readText(Charsets.UTF_8),
                ),
            )
        }
        var next = 0
        val transport = object : SourceTransport {
            override suspend fun execute(request: EvaluatedRequest): SourceResponse {
                if (next >= parsed.size) {
                    throw ScriptMismatch("extra request #$next: ${request.method} ${request.url}")
                }
                val want = parsed[next++]
                if (request.method != want.method) {
                    throw ScriptMismatch("requests[${next - 1}].method: want ${want.method}, got ${request.method}")
                }
                if (request.url != want.url) {
                    throw ScriptMismatch("requests[${next - 1}].url: want ${want.url}, got ${request.url}")
                }
                if (request.query != want.query) {
                    throw ScriptMismatch("requests[${next - 1}].query: want ${want.query}, got ${request.query}")
                }
                if (request.form != want.form) {
                    throw ScriptMismatch("requests[${next - 1}].form: want ${want.form}, got ${request.form}")
                }
                val gotHeaders = request.headers.mapKeys { it.key.lowercase() }
                if (gotHeaders != want.headers) {
                    throw ScriptMismatch("requests[${next - 1}].headers: want ${want.headers}, got $gotHeaders")
                }
                // Mirror the metadata client's status contract: non-2xx is a typed HTTP failure.
                if (want.status !in 200..299) {
                    throw SourceException(SourceFailure.HTTP, validated.id, operation, statusCode = want.status)
                }
                return SourceResponse(want.status, want.responseHeaders, want.body, request.url)
            }
        }
        val source = SchemaRomSource(validated, transport, revision)
        val actual: JsonElement = try {
            runBlocking { runOperation(source, validated, transport, operation, input) }
        } catch (e: ScriptMismatch) {
            return Outcome(false, e.message ?: "request mismatch")
        } catch (e: SourceException) {
            if (e.failure == SourceFailure.HTTP && next < parsed.size) {
                // Fallthrough check below reports unused scripts; HTTP itself may be the assertion.
            }
            return checkExpectedError(expectedFile, dumpMissing, e, operation)
        }
        if (next < parsed.size) return Outcome(false, "missing requests: ${parsed.size - next} scripted call(s) never ran")
        val expectedElement: JsonElement
        try {
            if (!expectedFile.isFile) {
                if (!dumpMissing) return Outcome(false, "missing expected file: $expectedPath")
                expectedFile.parentFile?.mkdirs()
                expectedFile.writeText(canonical(actual) + "\n")
                return Outcome(false, "STAGED $expectedPath for review; re-run after review")
            }
            expectedElement = strictJson.parseToJsonElement(expectedFile.readText())
        } catch (e: IllegalArgumentException) {
            return Outcome(false, "bad expected file: ${short(e.message)}")
        }
        if (expectedElement is JsonObject && expectedElement.keys == setOf("error")) {
            return Outcome(false, "expected error ${(expectedElement["error"] as? JsonPrimitive)?.content}, got a value")
        }
        val diff = firstDiff(expectedElement, actual, "$")
        if (diff != null) return Outcome(false, "mismatch at $diff")
        return Outcome(true, "")
    }

    private fun checkExpectedError(
        expectedFile: File,
        dumpMissing: Boolean,
        thrown: SourceException,
        operation: String,
    ): Outcome {
        if (!expectedFile.isFile) {
            if (!dumpMissing) return Outcome(false, "threw ${thrown.failure}, and $expectedFile is missing")
            expectedFile.parentFile?.mkdirs()
            expectedFile.writeText(canonical(JsonObject(mapOf("error" to JsonPrimitive(thrown.failure.name)))) + "\n")
            return Outcome(false, "STAGED ${expectedFile.name} for review; re-run after review")
        }
        val expected = try {
            strictJson.parseToJsonElement(expectedFile.readText())
        } catch (e: IllegalArgumentException) {
            return Outcome(false, "bad expected file: ${short(e.message)}")
        }
        if (expected is JsonObject && expected.keys == setOf("error")) {
            val want = (expected["error"] as? JsonPrimitive)?.contentOrNull
            if (want == thrown.failure.name) return Outcome(true, "")
            return Outcome(false, "threw ${thrown.failure}, expected $want")
        }
        return Outcome(false, "threw ${thrown.failure} (${short(causeOf(thrown))}) for $operation")
    }

    private suspend fun runOperation(
        source: SchemaRomSource,
        validated: ValidatedDefinition,
        transport: SourceTransport,
        operation: String,
        input: JsonObject,
    ): JsonElement {
        fun reqString(name: String): String =
            (input[name] as? JsonPrimitive)?.takeIf { it.contentOrNull != null }?.content
                ?: throw ScriptMismatch("input lacks required string '$name'")
        fun optString(name: String): String? {
            val e = input[name] ?: return null
            if (e is JsonNull) return null
            return (e as? JsonPrimitive)?.contentOrNull
                ?: throw ScriptMismatch("input '$name' must be a string or null")
        }
        return when (operation) {
            "consoles" -> json.encodeToJsonElement(source.consoles())
            "types" -> json.encodeToJsonElement(source.types())
            "latest" -> json.encodeToJsonElement(source.latest(optString("cursor")))
            "browse" -> json.encodeToJsonElement(source.browse(optString("consoleId"), optString("typeId"), optString("cursor")))
            "search" -> json.encodeToJsonElement(source.search(reqString("query"), optString("platformId"), optString("cursor")))
            "detail" -> json.encodeToJsonElement(source.detail(decodeInput<SourceRef>(input, "ref")))
            "variants" -> json.encodeToJsonElement(source.variants(decodeInput<SourceRef>(input, "ref")))
            "resolveDownload" -> json.encodeToJsonElement(source.resolveDownload(decodeInput<DownloadFile>(input, "file")))
            else -> WorkflowRunner(validated, transport).run(operation, input)
        }
    }

    private inline fun <reified T> decodeInput(input: JsonObject, name: String): T {
        val e = input[name] ?: throw ScriptMismatch("input lacks '$name'")
        return try {
            strictJson.decodeFromJsonElement<T>(e)
        } catch (ex: IllegalArgumentException) {
            throw ScriptMismatch("input '$name' malformed: ${short(ex.message)}")
        }
    }

    private class ScriptMismatch(message: String) : Exception(message)

    private fun fail(message: String): Int {
        System.err.println("checkSource FAILED: $message")
        return 1
    }

    private fun causeOf(e: SourceException): String = short(e.cause?.message)

    private fun short(message: String?): String {
        if (message == null) return ""
        val cleaned = message.replace('\n', ' ').replace('\r', ' ')
        return if (cleaned.length <= 200) cleaned else cleaned.take(200) + "..."
    }

    /** Canonical JSON: object keys sorted, array order preserved, nulls kept. */
    fun canonical(e: JsonElement): String = buildString { appendCanonical(e) }

    private fun StringBuilder.appendCanonical(e: JsonElement) {
        when (e) {
            is JsonNull -> append("null")
            is JsonPrimitive -> {
                when {
                    e.isString -> {
                        append('"')
                        for (c in e.content) {
                            when (c) {
                                '"' -> append("\\\"")
                                '\\' -> append("\\\\")
                                '\n' -> append("\\n")
                                '\r' -> append("\\r")
                                '\t' -> append("\\t")
                                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
                            }
                        }
                        append('"')
                    }
                    else -> append(e.content)
                }
            }
            is JsonArray -> {
                append('[')
                e.forEachIndexed { i, v ->
                    if (i > 0) append(',')
                    appendCanonical(v)
                }
                append(']')
            }
            is JsonObject -> {
                append('{')
                e.entries.sortedBy { it.key }.forEachIndexed { i, (k, v) ->
                    if (i > 0) append(',')
                    appendCanonical(JsonPrimitive(k))
                    append(':')
                    appendCanonical(v)
                }
                append('}')
            }
        }
    }

    private fun firstDiff(want: JsonElement, got: JsonElement, path: String): String? {
        if (want is JsonObject && got is JsonObject) {
            for (k in want.keys) {
                if (k !in got) return "$path.$k (missing)"
            }
            for (k in got.keys) {
                if (k !in want) return "$path.$k (unexpected)"
            }
            for (k in want.keys) {
                firstDiff(want[k]!!, got[k]!!, "$path.$k")?.let { return it }
            }
            return null
        }
        if (want is JsonArray && got is JsonArray) {
            if (want.size != got.size) return "$path (size ${want.size} != ${got.size})"
            for (i in want.indices) {
                firstDiff(want[i], got[i], "$path[$i]")?.let { return it }
            }
            return null
        }
        if (want is JsonPrimitive && got is JsonPrimitive && want.content == got.content && want.isString == got.isString) {
            return null
        }
        if (want is JsonNull && got is JsonNull) return null
        return "$path (want ${short(want.toString())}, got ${short(got.toString())})"
    }
}
