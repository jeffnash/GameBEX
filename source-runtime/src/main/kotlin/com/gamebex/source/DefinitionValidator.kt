package com.gamebex.source

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.select.QueryParser
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** A definition that passed semantic validation. Only the validator constructs these. */
class ValidatedDefinition internal constructor(val def: SourceDefinition) {
    val id: String get() = def.id
    val platforms: Map<String, String> get() = def.platforms
}

object DefinitionValidator {
    const val MAX_DEFINITION_BYTES = 1_048_576
    const val MAX_EXPRESSION_DEPTH = 64

    private val strictJson = Json {}
    private val idRegex = Regex("[a-z][a-z0-9._-]*")
    private val identRegex = Regex("[A-Za-z_][A-Za-z0-9_]*")
    private val ipv4Regex = Regex("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}")
    private val matchesSelector = Regex(":[mM]atches(Own|WholeText|WholeOwnText)?\\s*\\(")
    private val regexAttrSelector = Regex("\\[[^\\]]*~=")

    private val requiredCaps = setOf("http-v1", "expressions-v1", "catalog-v1")
    private val allowedCaps = requiredCaps + "assist-v1"
    private val fallbackFailures = setOf(SourceFailure.NETWORK, SourceFailure.HTTP, SourceFailure.INVALID_RESPONSE)
    private val deniedHeaders = setOf("cookie", "authorization", "host", "content-length", "connection", "range")

    val PUBLIC_OPERATIONS = setOf(
        "consoles", "types", "latest", "browse", "search",
        "detail", "variants", "resolveDownload",
    )
    private val purposes = setOf("catalog", "download", "artwork", "assist")
    private val sourceProps = setOf("id", "name", "platforms", "headers")

    fun parseAndValidate(bytes: ByteArray): ValidatedDefinition {
        if (bytes.size > MAX_DEFINITION_BYTES) {
            throw SourceException(
                SourceFailure.INVALID_DEFINITION, null, null, fieldPath = "",
                cause = IllegalArgumentException("definition exceeds 1 MiB"),
            )
        }
        val text = try {
            val decoder: CharsetDecoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            throw SourceException(
                SourceFailure.INVALID_DEFINITION, null, null, fieldPath = "",
                cause = IllegalArgumentException("definition is not valid UTF-8"),
            )
        }
        val def = try {
            strictJson.decodeFromString<SourceDefinition>(text)
        } catch (e: IllegalArgumentException) {
            throw SourceException(
                SourceFailure.INVALID_DEFINITION, null, null, fieldPath = "",
                cause = e,
            )
        }
        return validate(def)
    }

    fun validate(def: SourceDefinition): ValidatedDefinition {
        val v = Checker(def.id)
        v.checkRoot(def)
        return ValidatedDefinition(def)
    }

    private class Checker(val defId: String) {
        fun fail(path: String, msg: String): Nothing {
            throw SourceException(
                SourceFailure.INVALID_DEFINITION, defId, null, fieldPath = path,
                cause = IllegalArgumentException(msg),
            )
        }

        fun checkRoot(def: SourceDefinition) {
            if (def.schemaVersion != 1) fail("schemaVersion", "schemaVersion must be 1")
            if (!idRegex.matches(def.id)) fail("id", "id must match [a-z][a-z0-9._-]*")
            if (def.id.startsWith("builtin")) fail("id", "id must not start with 'builtin'")
            if (def.name.isBlank()) fail("name", "name must be nonblank")
            if (def.author.isBlank()) fail("author", "author must be nonblank")
            if (def.version.isBlank()) fail("version", "version must be nonblank")
            for (cap in def.requires) {
                if (cap !in allowedCaps) fail("requires", "unsupported capability $cap")
            }
            for (cap in requiredCaps) {
                if (cap !in def.requires) fail("requires", "missing required capability $cap")
            }
            if (def.assist.enabled && "assist-v1" !in def.requires) {
                fail("requires", "assist-v1 required when assist.enabled")
            }
            checkOrigins("origins.catalog", def.origins.catalog)
            checkOrigins("origins.download", def.origins.download)
            checkOrigins("origins.artwork", def.origins.artwork)
            checkOrigins("origins.assist", def.origins.assist)
            checkLiteralHeaders("defaults.headers", def.defaults.headers)
            for ((alias, hostId) in def.platforms) {
                if (alias.isBlank() || hostId.isBlank()) fail("platforms", "platform entries must be nonblank")
            }
            for (status in def.assist.triggerStatuses) {
                if (status !in 100..599) fail("assist.triggerStatuses", "status $status out of range")
            }
            def.assist.userAgent?.let {
                if (it.contains('\r') || it.contains('\n')) fail("assist.userAgent", "CR/LF not allowed")
            }
            for (op in PUBLIC_OPERATIONS) {
                if (op !in def.operations) fail("operations", "missing public operation $op")
            }
            for ((name, wf) in def.operations) {
                if (name.isBlank()) fail("operations", "operation name must be nonblank")
                checkWorkflow("operations.$name", name, wf, def)
            }
            checkFallbackCycles(def)
            checkInvokeCycles(def)
            for ((name, wf) in def.operations) {
                if (wf.cache) checkCacheable(name, wf, def)
            }
        }

        fun checkOrigins(path: String, origins: Set<String>) {
            val seen = HashSet<String>()
            for (origin in origins) {
                val url = origin.toHttpUrlOrNull()
                if (url == null || url.scheme != "https") fail(path, "origin must be an https URL: $origin")
                if (url.username.isNotEmpty() || url.password.isNotEmpty()) {
                    fail(path, "origin must not carry credentials: $origin")
                }
                if (url.encodedPath != "/") fail(path, "origin must not carry a path: $origin")
                if (url.encodedQuery != null || url.encodedFragment != null) {
                    fail(path, "origin must not carry query/fragment: $origin")
                }
                if (url.port != 443) fail(path, "only the default https port is allowed: $origin")
                val host = url.host
                if (host.contains('*')) fail(path, "wildcard hosts are not allowed: $origin")
                if (host.contains(':') || ipv4Regex.matches(host)) {
                    fail(path, "IP literals are not allowed: $origin")
                }
                val normalized = "https://$host"
                if (!seen.add(normalized)) fail(path, "duplicate origin in another form: $origin")
            }
        }

        fun checkLiteralHeaders(path: String, headers: Map<String, String>) {
            for ((name, value) in headers) {
                checkHeaderName("$path[$name]", name)
                if (value.contains('\r') || value.contains('\n')) {
                    fail("$path[$name]", "CR/LF not allowed in header value")
                }
            }
        }

        fun checkHeaderName(path: String, name: String) {
            if (name.isBlank()) fail(path, "header name must be nonblank")
            if (name.contains('\r') || name.contains('\n')) fail(path, "CR/LF not allowed in header name")
            val lower = name.lowercase()
            if (lower in deniedHeaders || lower.startsWith("proxy-")) {
                fail(path, "header is host-reserved: $name")
            }
        }

        fun checkWorkflow(path: String, name: String, wf: Workflow, def: SourceDefinition) {
            for (input in wf.inputs) {
                if (!identRegex.matches(input)) fail("$path.inputs", "input must be an identifier: $input")
            }
            val stepNames = HashSet<String>()
            wf.steps.forEachIndexed { index, step ->
                val sp = "$path.steps[$index]"
                if (step.name.isBlank()) fail(sp, "step name must be nonblank")
                if (!stepNames.add(step.name)) fail(sp, "duplicate step name ${step.name}")
                val hasRequest = step.request != null
                val hasValue = step.value != null
                if (hasRequest == hasValue) fail(sp, "step needs exactly one of request/value")
                val ctx = ExprCtx(wf.inputs, wf.steps.take(index).map { it.name }.toSet(), emptyList())
                step.request?.let { checkRequest("$sp.request", name, it, ctx, def) }
                step.value?.let { checkExpr(it, ctx, def, "$sp.value", 0) }
            }
            val allSteps = wf.steps.map { it.name }.toSet()
            checkExpr(wf.result, ExprCtx(wf.inputs, allSteps, emptyList()), def, "$path.result", 0)
            wf.fallback?.let { fb ->
                val target = def.operations[fb.operation]
                    ?: fail("$path.fallback", "unknown fallback operation ${fb.operation}")
                for (input in target.inputs) {
                    if (input !in wf.inputs) {
                        fail("$path.fallback", "fallback needs input $input not present here")
                    }
                }
                for (f in fb.onFailures) {
                    if (f !in fallbackFailures) fail("$path.fallback", "fallback cannot catch $f")
                }
            }
        }

        fun checkRequest(path: String, op: String, request: RequestSpec, ctx: ExprCtx, def: SourceDefinition) {
            if (request.method != "GET" && request.method != "POST") fail(path, "method must be GET or POST")
            if (request.purpose !in purposes) fail(path, "unknown purpose ${request.purpose}")
            if (request.method == "GET" && request.form.isNotEmpty()) fail(path, "GET must not carry form fields")
            checkExpr(request.url, ctx, def, "$path.url", 0)
            for ((k, e) in request.query) {
                if (k.isBlank()) fail("$path.query", "query name must be nonblank")
                checkExpr(e, ctx, def, "$path.query[$k]", 0)
            }
            for ((k, e) in request.form) {
                if (k.isBlank()) fail("$path.form", "form name must be nonblank")
                checkExpr(e, ctx, def, "$path.form[$k]", 0)
            }
            for ((k, e) in request.headers) {
                checkHeaderName("$path.headers[$k]", k)
                checkExpr(e, ctx, def, "$path.headers[$k]", 0)
            }
        }

        data class ExprCtx(val inputs: Set<String>, val steps: Set<String>, val bindings: List<String>)

        fun checkExpr(e: JsonElement, ctx: ExprCtx, def: SourceDefinition, path: String, depth: Int) {
            if (depth > MAX_EXPRESSION_DEPTH) fail(path, "expression nesting exceeds 64")
            if (e !is JsonObject) fail(path, "expression must be an object")
            val op = (e["op"] as? JsonPrimitive)?.contentOrNull
                ?: fail(path, "expression needs a string op")
            val keys = e.keys - "op"
            fun needObject(): JsonObject = e
            fun req(name: String): JsonElement =
                e[name] ?: fail(path, "$op needs field $name")
            fun reqString(name: String): String =
                (req(name) as? JsonPrimitive)?.contentOrNull
                    ?: fail(path, "$op.$name must be a string")
            fun reqBool(name: String): Boolean =
                (req(name) as? JsonPrimitive)?.booleanOrNull
                    ?: fail(path, "$op.$name must be a boolean")
            fun reqExpr(name: String): JsonElement = req(name)
            fun reqExprArray(name: String): JsonArray =
                req(name) as? JsonArray ?: fail(path, "$op.$name must be an array")
            fun only(vararg allowed: String) {
                val extra = keys - allowed.toSet()
                if (extra.isNotEmpty()) fail(path, "$op has unknown keys $extra")
            }
            fun sub(element: JsonElement, subPath: String, subCtx: ExprCtx = ctx) {
                checkExpr(element, subCtx, def, subPath, depth + 1)
            }
            when (op) {
                "literal" -> { only("value"); req("value") }
                "ref" -> {
                    only("path"); checkRefPath(req("path"), ctx, def, path)
                }
                "object" -> {
                    only("fields")
                    val fields = req("fields") as? JsonObject ?: fail(path, "object.fields must be an object")
                    for ((k, v) in fields) {
                        if (k.isBlank()) fail(path, "object field name must be nonblank")
                        sub(v, "$path.fields[$k]")
                    }
                    needObject()
                }
                "array" -> {
                    only("items")
                    reqExprArray("items").forEachIndexed { i, v -> sub(v, "$path.items[$i]") }
                }
                "json", "html", "text" -> { only("value"); sub(reqExpr("value"), "$path.value") }
                "get" -> {
                    only("value", "path"); sub(reqExpr("value"), "$path.value")
                    checkSegArray(req("path"), path)
                }
                "select" -> {
                    only("value", "css"); sub(reqExpr("value"), "$path.value")
                    checkSelector(reqString("css"), "$path.css")
                }
                "attr" -> {
                    only("value", "name", "absolute"); sub(reqExpr("value"), "$path.value")
                    reqString("name"); reqBool("absolute")
                }
                "map" -> {
                    only("value", "as", "body")
                    val alias = checkAlias(reqString("as"), path)
                    sub(reqExpr("value"), "$path.value")
                    sub(req("body"), "$path.body", ctx.copy(bindings = ctx.bindings + alias))
                }
                "filter" -> {
                    only("value", "as", "predicate")
                    val alias = checkAlias(reqString("as"), path)
                    sub(reqExpr("value"), "$path.value")
                    sub(req("predicate"), "$path.predicate", ctx.copy(bindings = ctx.bindings + alias))
                }
                "first", "not", "trim", "htmlDecode", "int", "double", "digits",
                "size", "distinct", "platform", "pathSegment",
                -> { only("value"); sub(reqExpr("value"), "$path.value") }
                "coalesce", "concat" -> {
                    only("values")
                    reqExprArray("values").forEachIndexed { i, v -> sub(v, "$path.values[$i]") }
                }
                "if" -> {
                    only("condition", "then", "else")
                    sub(req("condition"), "$path.condition")
                    sub(req("then"), "$path.then"); sub(req("else"), "$path.else")
                }
                "eq" -> {
                    only("left", "right", "ignoreCase")
                    sub(req("left"), "$path.left"); sub(req("right"), "$path.right")
                    e["ignoreCase"]?.let {
                        if ((it as? JsonPrimitive)?.booleanOrNull == null) {
                            fail(path, "eq.ignoreCase must be a boolean")
                        }
                    }
                }
                "lt", "add" -> {
                    only("left", "right")
                    sub(req("left"), "$path.left"); sub(req("right"), "$path.right")
                }
                "contains" -> {
                    only("value", "needle", "ignoreCase")
                    sub(reqExpr("value"), "$path.value"); sub(req("needle"), "$path.needle")
                    reqBool("ignoreCase")
                }
                "trimEnd" -> {
                    only("value", "chars"); sub(reqExpr("value"), "$path.value")
                    reqString("chars")
                }
                "replace" -> {
                    only("value", "old", "new"); sub(reqExpr("value"), "$path.value")
                    reqString("old"); reqString("new")
                }
                "before", "after", "beforeLast", "afterLast" -> {
                    only("value", "delimiter"); sub(reqExpr("value"), "$path.value")
                    val d = reqString("delimiter")
                    if (d.isEmpty()) fail(path, "$op delimiter must be nonempty")
                }
                "sort" -> {
                    only("value", "field", "descending")
                    sub(reqExpr("value"), "$path.value")
                    reqString("field"); reqBool("descending")
                }
                "lookup" -> {
                    only("value", "field", "key")
                    sub(reqExpr("value"), "$path.value")
                    reqString("field"); sub(req("key"), "$path.key")
                }
                "invoke" -> {
                    only("operation", "input")
                    val target = reqString("operation")
                    if (target !in def.operations) fail(path, "unknown operation $target")
                    sub(req("input"), "$path.input")
                }
                else -> fail(path, "unknown operator $op")
            }
        }

        fun checkAlias(alias: String, path: String): String {
            if (!identRegex.matches(alias)) fail(path, "binding must be an identifier: $alias")
            if (alias == "input" || alias == "source" || alias == "steps") {
                fail(path, "binding must not shadow $alias")
            }
            return alias
        }

        fun checkRefPath(e: JsonElement, ctx: ExprCtx, def: SourceDefinition, path: String) {
            val arr = e as? JsonArray ?: fail(path, "ref.path must be an array")
            if (arr.isEmpty()) fail(path, "ref.path must not be empty")
            val segs = arr.map { seg ->
                val p = seg as? JsonPrimitive ?: fail(path, "ref segments must be strings or integers")
                if (p.isString) p.content else p.longOrNull?.also {
                    if (it < 0) fail(path, "ref index must be nonnegative")
                } ?: fail(path, "ref segments must be strings or integers")
            }
            val root = segs[0] as? String ?: fail(path, "ref root must be a string")
            when (root) {
                "input" -> {
                    if (segs.size > 1) {
                        val field = segs[1] as? String ?: fail(path, "input field must be a string")
                        if (field !in ctx.inputs) fail(path, "unknown input $field")
                    }
                }
                "source" -> {
                    if (segs.size > 1) {
                        val prop = segs[1] as? String ?: fail(path, "source property must be a string")
                        if (prop !in sourceProps) fail(path, "source exposes only id/name/platforms/headers")
                    }
                }
                "steps" -> {
                    if (segs.size < 2) fail(path, "steps ref needs a step name")
                    val name = segs[1] as? String ?: fail(path, "step name must be a string")
                    if (name !in ctx.steps) fail(path, "unknown or later step $name")
                }
                else -> {
                    if (root !in ctx.bindings) fail(path, "unknown ref root $root")
                }
            }
        }

        fun checkSegArray(e: JsonElement, path: String) {
            val arr = e as? JsonArray ?: fail(path, "path must be an array")
            for (seg in arr) {
                val p = seg as? JsonPrimitive ?: fail(path, "path segments must be strings or integers")
                if (!p.isString && (p.longOrNull == null || (p.longOrNull ?: -1) < 0)) {
                    fail(path, "path segments must be strings or nonnegative integers")
                }
            }
        }

        fun checkSelector(css: String, path: String) {
            if (css.isBlank()) fail(path, "selector must be nonblank")
            if (matchesSelector.containsMatchIn(css)) fail(path, ":matches selectors are not allowed")
            if (regexAttrSelector.containsMatchIn(css)) fail(path, "regex attribute selectors are not allowed")
            try {
                QueryParser.parse(css)
            } catch (e: RuntimeException) {
                throw SourceException(
                    SourceFailure.INVALID_DEFINITION, defId, null, fieldPath = path, cause = e,
                )
            }
        }

        fun checkFallbackCycles(def: SourceDefinition) {
            for (name in def.operations.keys) {
                val seen = LinkedHashSet<String>()
                var cur: String? = name
                while (cur != null) {
                    if (!seen.add(cur)) fail("operations.$name", "fallback cycle through $cur")
                    cur = def.operations[cur]?.fallback?.operation
                }
            }
        }

        fun checkInvokeCycles(def: SourceDefinition) {
            val edges = def.operations.mapValues { (_, wf) -> collectInvokes(wf) }
            val visiting = HashSet<String>()
            val done = HashSet<String>()
            fun visit(node: String, trail: List<String>) {
                if (node in done) return
                if (node in visiting) fail("operations.$node", "invoke cycle ${(trail + node).joinToString("->")}")
                visiting.add(node)
                for (next in edges[node].orEmpty()) visit(next, trail + node)
                visiting.remove(node)
                done.add(node)
            }
            for (name in def.operations.keys) visit(name, emptyList())
        }

        fun collectInvokes(wf: Workflow): Set<String> {
            val found = HashSet<String>()
            fun walk(e: JsonElement) {
                if (e !is JsonObject) {
                    if (e is JsonArray) e.forEach(::walk)
                    return
                }
                if ((e["op"] as? JsonPrimitive)?.content == "invoke") {
                    (e["operation"] as? JsonPrimitive)?.contentOrNull?.let(found::add)
                }
                e.values.forEach(::walk)
            }
            wf.steps.forEach { step ->
                step.value?.let(::walk)
                step.request?.let { request ->
                    walk(request.url)
                    (request.query.values + request.form.values + request.headers.values).forEach(::walk)
                }
            }
            walk(wf.result)
            return found
        }

        fun checkCacheable(name: String, wf: Workflow, def: SourceDefinition) {
            if (name == "resolveDownload") fail("operations.$name", "resolveDownload must not be cached")
            val seen = HashSet<String>()
            val queue = ArrayDeque<String>()
            queue.add(name)
            while (queue.isNotEmpty()) {
                val cur = queue.removeFirst()
                if (!seen.add(cur)) continue
                val curWf = def.operations[cur] ?: continue
                for (step in curWf.steps) {
                    if (step.request != null && step.request.method != "GET") {
                        fail("operations.$name", "cached workflows must only use GET")
                    }
                }
                queue.addAll(collectInvokes(curWf))
            }
        }
    }
}
