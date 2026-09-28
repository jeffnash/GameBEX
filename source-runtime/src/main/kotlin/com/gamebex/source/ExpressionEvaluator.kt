package com.gamebex.source

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.math.BigDecimal

/** Internal runtime values. Response/HTML values must never escape into public DTOs. */
sealed interface EvalValue {
    data class Json(val element: JsonElement) : EvalValue
    data class Resp(val response: SourceResponse) : EvalValue
    data class Node(val element: Element) : EvalValue
    data class Nodes(val elements: List<Element>) : EvalValue
}

/** Immutable evaluation environment for one expression. */
data class EvalEnv(
    val input: JsonObject,
    val steps: Map<String, EvalValue> = emptyMap(),
    val bindings: Map<String, EvalValue> = emptyMap(),
)

/** Per-operation extraction budget shared by one top-level workflow run. */
class EvalBudget(val maxRecords: Int = 10_000) {
    var records: Int = 0
        private set

    fun charge(count: Int, sourceId: String?, operation: String?) {
        if (count <= 0) return
        records += count
        if (records > maxRecords) {
            throw SourceException(
                SourceFailure.INVALID_RESPONSE, sourceId, operation,
                cause = IllegalStateException("operation exceeded 10,000 extracted records"),
            )
        }
    }
}

/**
 * Evaluates validated definition expressions. Request steps execute through
 * [transport] (a policy-checked client in production, a fake in tests).
 * Helper calls flow through [invoker], wired by the workflow runner; a null
 * invoker rejects `invoke` (direct evaluator use outside a workflow run).
 */
class ExpressionEvaluator(
    val definition: ValidatedDefinition,
    val transport: SourceTransport,
    val invoker: (suspend (String, JsonObject) -> JsonElement)? = null,
    val budget: EvalBudget = EvalBudget(),
) {
    private val sourceId: String get() = definition.id
    private var operation: String? = null
    private val json = Json {}

    /** Entry point used by the runner, which sets the current operation name. */
    suspend fun evaluateInOperation(expr: JsonObject, env: EvalEnv, operation: String): EvalValue {
        val previous = this.operation
        this.operation = operation
        try {
            return evaluate(expr, env, 0)
        } finally {
            this.operation = previous
        }
    }

    /** Direct entry point for tests and value-only evaluation. */
    suspend fun evaluate(expr: JsonObject, env: EvalEnv): EvalValue = evaluate(expr, env, 0)

    suspend fun evaluateRequest(request: RequestSpec, env: EvalEnv): SourceResponse {
        val url = stringOf(evaluateObject(request.url, env), "request url")
        if (url.isBlank()) invalid("request url must be nonblank")
        val query = LinkedHashMap<String, String>()
        for ((k, e) in request.query) scalarOrNull(evaluateObject(e, env), "query $k")?.let { query[k] = it }
        val form = LinkedHashMap<String, String>()
        for ((k, e) in request.form) scalarOrNull(evaluateObject(e, env), "form $k")?.let { form[k] = it }
        val headers = LinkedHashMap<String, String>()
        for ((k, e) in request.headers) {
            val v = scalarOrNull(evaluateObject(e, env), "header $k") ?: continue
            if (v.contains('\r') || v.contains('\n')) invalid("CR/LF in header $k")
            headers[k] = v
        }
        val purpose = when (request.purpose) {
            "catalog" -> NetworkPurpose.CATALOG
            "download" -> NetworkPurpose.DOWNLOAD
            "artwork" -> NetworkPurpose.ARTWORK
            "assist" -> NetworkPurpose.ASSIST
            else -> invalid("unknown purpose ${request.purpose}")
        }
        return transport.execute(EvaluatedRequest(request.method, purpose, url, query, form, headers))
    }

    private suspend fun evaluateObject(expr: JsonObject, env: EvalEnv): EvalValue = evaluate(expr, env, 0)

    private suspend fun evaluate(expr: JsonObject, env: EvalEnv, depth: Int): EvalValue {
        if (depth > DefinitionValidator.MAX_EXPRESSION_DEPTH) invalid("expression nesting exceeds 64")
        val op = (expr["op"] as? JsonPrimitive)?.contentOrNull ?: invalid("expression needs a string op")
        suspend fun ev(name: String): EvalValue {
            val e = expr[name] as? JsonObject ?: invalid("$op needs object field $name")
            return evaluate(e, env, depth + 1)
        }
        suspend fun evList(name: String): List<JsonObject> {
            val arr = expr[name] as? JsonArray ?: invalid("$op needs array field $name")
            return arr.map { it as? JsonObject ?: invalid("$op.$name entries must be objects") }
        }
        fun lit(name: String): String =
            (expr[name] as? JsonPrimitive)?.contentOrNull ?: invalid("$op needs string field $name")
        fun litBool(name: String): Boolean =
            (expr[name] as? JsonPrimitive)?.booleanOrNull ?: invalid("$op needs boolean field $name")
        fun segArray(name: String): List<Any> = pathSegments(expr[name] ?: invalid("$op needs field $name"))
        return when (op) {
            "literal" -> EvalValue.Json(expr["value"] ?: JsonNull)
            "ref" -> resolveRef(segArray("path"), env)
            "object" -> {
                val fields = expr["fields"] as? JsonObject ?: invalid("object needs fields")
                val out = LinkedHashMap<String, JsonElement>()
                for ((k, v) in fields) {
                    val e = v as? JsonObject ?: invalid("object field $k must be an expression")
                    out[k] = toJson(evaluate(e, env, depth + 1))
                }
                EvalValue.Json(JsonObject(out))
            }
            "array" -> EvalValue.Json(JsonArray(evList("items").map { toJson(evaluate(it, env, depth + 1)) }))
            "json" -> EvalValue.Json(parseJson(ev("value")))
            "get" -> traverse(ev("value"), segArray("path"))
            "html" -> EvalValue.Node(parseHtml(ev("value")))
            "select" -> {
                val css = lit("css")
                EvalValue.Nodes(selectNodes(ev("value"), css))
            }
            "text" -> EvalValue.Json(textOf(ev("value")))
            "attr" -> {
                val name = lit("name")
                EvalValue.Json(attrOf(ev("value"), name, litBool("absolute")))
            }
            "map" -> {
                val alias = lit("as")
                val items = mapItems(ev("value"))
                budget.charge(items.size, sourceId, operation)
                val body = expr["body"] as? JsonObject ?: invalid("map needs body")
                EvalValue.Json(JsonArray(items.map { toJson(evaluate(body, env.withBinding(alias, it), depth + 1)) }))
            }
            "filter" -> {
                val alias = lit("as")
                val raw = ev("value")
                val items = mapItems(raw)
                budget.charge(items.size, sourceId, operation)
                val pred = expr["predicate"] as? JsonObject ?: invalid("filter needs predicate")
                val kept = items.filter { isTrue(evaluate(pred, env.withBinding(alias, it), depth + 1)) }
                // Node lists stay node lists so callers can keep selecting within them.
                if (raw is EvalValue.Nodes) {
                    EvalValue.Nodes(kept.map { (it as EvalValue.Node).element })
                } else {
                    EvalValue.Json(JsonArray(kept.map { toJson(it) }))
                }
            }
            "first" -> firstOf(ev("value"))
            "coalesce" -> {
                var result: EvalValue = EvalValue.Json(JsonNull)
                for (e in evList("values")) {
                    val v = evaluate(e, env, depth + 1)
                    if (isPresent(v)) {
                        result = v
                        break
                    }
                }
                result
            }
            "if" -> {
                val cond = expr["condition"] as? JsonObject ?: invalid("if needs condition")
                val branch = expr[if (isTrue(evaluate(cond, env, depth + 1))) "then" else "else"]
                    as? JsonObject ?: invalid("if needs then/else")
                evaluate(branch, env, depth + 1)
            }
            "eq" -> EvalValue.Json(JsonPrimitive(eqValues(ev("left"), ev("right"), flexBool(expr, "ignoreCase"))))
            "lt" -> EvalValue.Json(JsonPrimitive(compareNumbers(ev("left"), ev("right")) < 0))
            "not" -> EvalValue.Json(JsonPrimitive(!isTrue(ev("value"))))
            "contains" -> EvalValue.Json(JsonPrimitive(containsText(ev("value"), ev("needle"), litBool("ignoreCase"))))
            "concat" -> {
                val parts = ArrayList<String>()
                for (e in evList("values")) parts.add(concatPart(e, env, depth))
                EvalValue.Json(JsonPrimitive(parts.joinToString("")))
            }
            "trim" -> EvalValue.Json(trimText(ev("value")) { it.trim() })
            "trimEnd" -> {
                val chars = lit("chars").toCharArray()
                EvalValue.Json(trimText(ev("value")) { it.trimEnd(*chars) })
            }
            "htmlDecode" -> EvalValue.Json(trimText(ev("value")) { Parser.unescapeEntities(it, false) })
            "replace" -> {
                val old = lit("old")
                val new = lit("new")
                EvalValue.Json(trimText(ev("value")) { it.replace(old, new) })
            }
            "before" -> EvalValue.Json(trimText(ev("value")) { it.substringBefore(lit("delimiter")) })
            "after" -> EvalValue.Json(trimText(ev("value")) { it.substringAfter(lit("delimiter")) })
            "beforeLast" -> EvalValue.Json(trimText(ev("value")) { it.substringBeforeLast(lit("delimiter")) })
            "afterLast" -> EvalValue.Json(trimText(ev("value")) { it.substringAfterLast(lit("delimiter")) })
            "int" -> EvalValue.Json(parseInt(ev("value")))
            "double" -> EvalValue.Json(parseDouble(ev("value")))
            "digits" -> EvalValue.Json(digitsOf(ev("value")))
            "add" -> EvalValue.Json(JsonPrimitive(addLongs(ev("left"), ev("right"))))
            "size" -> EvalValue.Json(sizeOf(ev("value")))
            "distinct" -> distinctOf(ev("value"))
            "sort" -> EvalValue.Json(sortArray(ev("value"), lit("field"), litBool("descending")))
            "platform" -> EvalValue.Json(platformOf(ev("value")))
            "lookup" -> lookupFirst(ev("value"), lit("field"), ev("key"))
            "invoke" -> {
                val target = lit("operation")
                val input = ev("input")
                val obj = (input as? EvalValue.Json)?.element as? JsonObject
                    ?: invalid("invoke input must be an object")
                val fn = invoker ?: invalid("invoke is only available inside a workflow run")
                EvalValue.Json(fn(target, obj))
            }
            "pathSegment" -> EvalValue.Json(pathSegmentOf(ev("value")))
            else -> invalid("unknown operator $op")
        }
    }

    private fun EvalEnv.withBinding(alias: String, value: EvalValue): EvalEnv =
        copy(bindings = bindings + (alias to value))

    private fun invalid(msg: String): Nothing {
        throw SourceException(SourceFailure.INVALID_RESPONSE, sourceId, operation, cause = IllegalStateException(msg))
    }

    private fun flexBool(expr: JsonObject, name: String): Boolean =
        (expr[name] as? JsonPrimitive)?.booleanOrNull == true

    private fun pathSegments(e: JsonElement): List<Any> {
        val arr = e as? JsonArray ?: invalid("path must be an array")
        return arr.map { seg ->
            val p = seg as? JsonPrimitive ?: invalid("path segments must be strings or integers")
            if (p.isString) p.content else p.content.toLongOrNull()?.takeIf { it >= 0 }
                ?: invalid("path segments must be strings or nonnegative integers")
        }
    }

    private fun resolveRef(path: List<Any>, env: EvalEnv): EvalValue {
        if (path.isEmpty()) invalid("ref.path must not be empty")
        val root = path[0] as? String ?: invalid("ref root must be a string")
        val rest = path.drop(1)
        return when (root) {
            "input" -> traverse(EvalValue.Json(env.input), rest)
            "source" -> traverse(EvalValue.Json(sourceObject()), rest)
            "steps" -> {
                if (rest.isEmpty()) invalid("steps ref needs a step name")
                val name = rest[0] as? String ?: invalid("step name must be a string")
                val step = env.steps[name] ?: invalid("unknown step $name")
                traverse(step, rest.drop(1))
            }
            else -> {
                val bound = env.bindings[root] ?: invalid("unknown ref root $root")
                traverse(bound, rest)
            }
        }
    }

    private fun sourceObject(): JsonObject = buildJsonObject {
        put("id", JsonPrimitive(definition.def.id))
        put("name", JsonPrimitive(definition.def.name))
        put(
            "platforms",
            JsonObject(definition.platforms.mapValues { JsonPrimitive(it.value) }),
        )
        put(
            "headers",
            JsonObject(definition.def.defaults.headers.mapValues { JsonPrimitive(it.value) }),
        )
    }

    private fun responseObject(r: SourceResponse): JsonObject = buildJsonObject {
        put("status", JsonPrimitive(r.status))
        put(
            "headers",
            JsonObject(r.headers.mapValues { (_, v) -> JsonArray(v.map { JsonPrimitive(it) }) }),
        )
        put("body", JsonPrimitive(r.body))
        put("url", JsonPrimitive(r.url))
    }

    private fun traverse(value: EvalValue, segments: List<Any>): EvalValue {
        var cur = value
        for (seg in segments) {
            cur = when (cur) {
                is EvalValue.Json -> when (val e = cur.element) {
                    is JsonObject -> {
                        if (seg !is String) return EvalValue.Json(JsonNull)
                        EvalValue.Json(e[seg] ?: JsonNull)
                    }
                    is JsonArray -> {
                        val i = (seg as? Long)?.toInt() ?: return EvalValue.Json(JsonNull)
                        EvalValue.Json(e.getOrNull(i) ?: JsonNull)
                    }
                    else -> return EvalValue.Json(JsonNull)
                }
                is EvalValue.Resp -> traverse(EvalValue.Json(responseObject(cur.response)), listOf(seg))
                is EvalValue.Nodes -> {
                    val i = (seg as? Long)?.toInt() ?: return EvalValue.Json(JsonNull)
                    val node = cur.elements.getOrNull(i) ?: return EvalValue.Json(JsonNull)
                    EvalValue.Node(node)
                }
                is EvalValue.Node -> return EvalValue.Json(JsonNull)
            }
        }
        return cur
    }

    private fun toJson(v: EvalValue): JsonElement = when (v) {
        is EvalValue.Json -> v.element
        else -> invalid("response/HTML values must not escape into JSON output")
    }

    private fun parseJson(v: EvalValue): JsonElement = when (v) {
        is EvalValue.Json -> when (val e = v.element) {
            is JsonObject, is JsonArray -> e
            is JsonPrimitive -> if (e.isString) parseJsonText(e.content) else invalid("json needs a response, body, or string")
            else -> invalid("json needs a response, body, or string")
        }
        is EvalValue.Resp -> parseJsonText(v.response.body)
        else -> invalid("json needs a response, body, or string")
    }

    private fun parseJsonText(text: String): JsonElement = try {
        json.parseToJsonElement(text)
    } catch (e: IllegalArgumentException) {
        throw SourceException(SourceFailure.INVALID_RESPONSE, sourceId, operation, cause = e)
    }

    private fun parseHtml(v: EvalValue): Element = when (v) {
        is EvalValue.Resp -> Jsoup.parse(v.response.body, v.response.url)
        is EvalValue.Json -> {
            val s = (v.element as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: invalid("html needs a response or string")
            Jsoup.parse(s, "")
        }
        is EvalValue.Node -> v.element
        is EvalValue.Nodes -> invalid("html needs a single response or string")
    }

    private fun selectNodes(v: EvalValue, css: String): List<Element> {
        val roots: List<Element> = when (v) {
            is EvalValue.Node -> listOf(v.element)
            is EvalValue.Nodes -> v.elements
            is EvalValue.Resp -> listOf(Jsoup.parse(v.response.body, v.response.url))
            is EvalValue.Json -> {
                val e = v.element
                if (e is JsonNull) return emptyList()
                val s = (e as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: invalid("select needs HTML, a response, or a string")
                listOf(Jsoup.parse(s, ""))
            }
        }
        val out = ArrayList<Element>()
        for (root in roots) out.addAll(root.select(css))
        budget.charge(out.size, sourceId, operation)
        return out
    }

    private fun textOf(v: EvalValue): JsonElement = when (v) {
        is EvalValue.Node -> JsonPrimitive(v.element.text())
        is EvalValue.Json -> when (val e = v.element) {
            is JsonNull -> JsonNull
            is JsonPrimitive -> JsonPrimitive(e.content)
            else -> invalid("text needs a node or scalar")
        }
        is EvalValue.Nodes -> invalid("text needs a single node or scalar")
        is EvalValue.Resp -> invalid("text needs a single node or scalar")
    }

    private fun attrOf(v: EvalValue, name: String, absolute: Boolean): JsonElement = when (v) {
        is EvalValue.Node -> {
            val raw = v.element.attr(name)
            JsonPrimitive(if (absolute) v.element.absUrl(name).ifEmpty { raw } else raw)
        }
        is EvalValue.Json -> if (v.element is JsonNull) JsonNull else invalid("attr needs a node")
        else -> invalid("attr needs a node")
    }

    private fun mapItems(v: EvalValue): List<EvalValue> = when (v) {
        is EvalValue.Json -> when (val e = v.element) {
            is JsonArray -> e.map { EvalValue.Json(it) }
            is JsonNull -> emptyList()
            else -> invalid("map/filter needs an array or node list")
        }
        is EvalValue.Nodes -> v.elements.map { EvalValue.Node(it) }
        else -> invalid("map/filter needs an array or node list")
    }

    private fun firstOf(v: EvalValue): EvalValue = when (v) {
        is EvalValue.Json -> when (val e = v.element) {
            is JsonArray -> EvalValue.Json(e.firstOrNull() ?: JsonNull)
            is JsonNull -> EvalValue.Json(JsonNull)
            else -> invalid("first needs a list")
        }
        is EvalValue.Nodes -> v.elements.firstOrNull()?.let { EvalValue.Node(it) } ?: EvalValue.Json(JsonNull)
        else -> invalid("first needs a list")
    }

    private fun isPresent(v: EvalValue): Boolean = when (v) {
        is EvalValue.Json -> when (val e = v.element) {
            is JsonNull -> false
            is JsonPrimitive -> !(e.isString && e.content.isBlank())
            is JsonArray -> e.isNotEmpty()
            else -> true
        }
        is EvalValue.Nodes -> v.elements.isNotEmpty()
        else -> true
    }

    private fun isTrue(v: EvalValue): Boolean {
        val e = (v as? EvalValue.Json)?.element as? JsonPrimitive
        return e?.booleanOrNull ?: invalid("condition must be a boolean")
    }

    private fun eqValues(left: EvalValue, right: EvalValue, ignoreCase: Boolean): Boolean {
        val l = (left as? EvalValue.Json)?.element ?: return false
        val r = (right as? EvalValue.Json)?.element ?: return false
        if (l is JsonNull || r is JsonNull) return l is JsonNull && r is JsonNull
        if (l is JsonPrimitive && r is JsonPrimitive) {
            if (l.isString && r.isString) return l.content.equals(r.content, ignoreCase)
            if (l.isString != r.isString) return false
            val lb = l.booleanOrNull
            val rb = r.booleanOrNull
            if (lb != null || rb != null) return lb == rb
            return compareNumbers(left, right) == 0
        }
        return l == r
    }

    private fun numberOf(v: EvalValue): BigDecimal {
        val p = ((v as? EvalValue.Json)?.element as? JsonPrimitive)?.takeIf { !it.isString }
            ?: invalid("numeric operator needs two numbers")
        return try {
            BigDecimal(p.content)
        } catch (_: NumberFormatException) {
            invalid("numeric operator needs two numbers")
        }
    }

    private fun compareNumbers(left: EvalValue, right: EvalValue): Int =
        numberOf(left).compareTo(numberOf(right))

    private fun containsText(v: EvalValue, needle: EvalValue, ignoreCase: Boolean): Boolean {
        val hay = ((v as? EvalValue.Json)?.element as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: return false
        val n = ((needle as? EvalValue.Json)?.element as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: return false
        return hay.contains(n, ignoreCase)
    }

    private suspend fun concatPart(e: JsonObject, env: EvalEnv, depth: Int): String {
        val v = evaluate(e, env, depth + 1)
        val el = (v as? EvalValue.Json)?.element ?: invalid("concat needs scalar strings")
        return if (el is JsonNull) "" else (el as? JsonPrimitive)?.content
            ?: invalid("concat needs scalar strings")
    }

    private fun trimText(v: EvalValue, f: (String) -> String): JsonElement {
        val e = (v as? EvalValue.Json)?.element ?: invalid("string operator needs a string")
        if (e is JsonNull) return JsonNull
        val s = (e as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalid("string operator needs a string")
        return JsonPrimitive(f(s))
    }

    private fun parseInt(v: EvalValue): JsonElement {
        val e = (v as? EvalValue.Json)?.element ?: return JsonNull
        if (e is JsonNull) return JsonNull
        val s = (e as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim() ?: return JsonNull
        return s.toIntOrNull()?.let { JsonPrimitive(it) } ?: JsonNull
    }

    private fun parseDouble(v: EvalValue): JsonElement {
        val e = (v as? EvalValue.Json)?.element ?: return JsonNull
        if (e is JsonNull) return JsonNull
        val s = (e as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim() ?: return JsonNull
        return s.toDoubleOrNull()?.let { JsonPrimitive(it) } ?: JsonNull
    }

    private fun digitsOf(v: EvalValue): JsonElement {
        val e = (v as? EvalValue.Json)?.element ?: return JsonNull
        if (e is JsonNull) return JsonNull
        val s = (e as? JsonPrimitive)?.content ?: return JsonNull
        return JsonPrimitive(s.filter { it in '0'..'9' })
    }

    private fun addLongs(left: EvalValue, right: EvalValue): Long {
        fun part(v: EvalValue): Long {
            val p = ((v as? EvalValue.Json)?.element as? JsonPrimitive)?.takeIf { !it.isString }
                ?: invalid("add needs integral values")
            return p.content.toLongOrNull() ?: invalid("add needs integral values")
        }
        return try {
            Math.addExact(part(left), part(right))
        } catch (_: ArithmeticException) {
            invalid("add overflow")
        }
    }

    private fun sizeOf(v: EvalValue): JsonElement = when (v) {
        is EvalValue.Json -> when (val e = v.element) {
            is JsonArray -> JsonPrimitive(e.size)
            is JsonNull -> JsonNull
            else -> invalid("size needs a list")
        }
        is EvalValue.Nodes -> JsonPrimitive(v.elements.size)
        else -> invalid("size needs a list")
    }

    private fun distinctOf(v: EvalValue): EvalValue = when (v) {
        is EvalValue.Json -> when (val e = v.element) {
            is JsonArray -> EvalValue.Json(JsonArray(e.distinct()))
            is JsonNull -> v
            else -> invalid("distinct needs a list")
        }
        is EvalValue.Nodes -> {
            val seen = HashSet<String>()
            EvalValue.Nodes(v.elements.filter { seen.add(it.outerHtml()) })
        }
        else -> invalid("distinct needs a list")
    }

    private fun sortArray(v: EvalValue, field: String, descending: Boolean): JsonElement {
        val e = (v as? EvalValue.Json)?.element ?: invalid("sort needs an array")
        if (e is JsonNull) return JsonNull
        val arr = e as? JsonArray ?: invalid("sort needs an array")
        fun keyOf(el: JsonElement): JsonElement? {
            val o = el as? JsonObject ?: return null
            val f = o[field] ?: return null
            return if (f is JsonNull) null else f
        }
        fun cmp(a: JsonElement, b: JsonElement): Int {
            if (a is JsonPrimitive && b is JsonPrimitive && !a.isString && !b.isString) {
                return try {
                    BigDecimal(a.content).compareTo(BigDecimal(b.content))
                } catch (_: NumberFormatException) {
                    a.content.compareTo(b.content)
                }
            }
            val sa = if (a is JsonPrimitive) a.content else a.toString()
            val sb = if (b is JsonPrimitive) b.content else b.toString()
            return sa.compareTo(sb)
        }
        val indexed = arr.withIndex().partition { keyOf(it.value) != null }
        val sorted = indexed.first.sortedWith { x, y ->
            val c = cmp(keyOf(x.value)!!, keyOf(y.value)!!)
            if (c != 0) (if (descending) -c else c) else x.index.compareTo(y.index)
        }
        return JsonArray((sorted + indexed.second).map { it.value })
    }

    private fun platformOf(v: EvalValue): JsonElement {
        val e = (v as? EvalValue.Json)?.element ?: return JsonNull
        if (e is JsonNull) return JsonNull
        val alias = (e as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return JsonNull
        return definition.platforms[alias]?.let { JsonPrimitive(it) } ?: JsonNull
    }

    private fun lookupFirst(list: EvalValue, field: String, key: EvalValue): EvalValue {
        val e = (list as? EvalValue.Json)?.element ?: invalid("lookup needs a list")
        if (e is JsonNull) return EvalValue.Json(JsonNull)
        val arr = e as? JsonArray ?: invalid("lookup needs a list")
        for (item in arr) {
            val o = item as? JsonObject ?: continue
            if (eqValues(EvalValue.Json(o[field] ?: JsonNull), key, false)) return EvalValue.Json(item)
        }
        return EvalValue.Json(JsonNull)
    }

    private fun pathSegmentOf(v: EvalValue): JsonElement {
        val e = (v as? EvalValue.Json)?.element ?: invalid("pathSegment needs a string")
        if (e is JsonNull) return JsonNull
        val s = (e as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: invalid("pathSegment needs a string")
        val encoded = "https://example.invalid/".toHttpUrl().newBuilder()
            .addPathSegment(s).build().encodedPath.substring(1)
        return JsonPrimitive(encoded)
    }

    private fun stringOf(v: EvalValue, what: String): String {
        val e = (v as? EvalValue.Json)?.element as? JsonPrimitive
        return if (e != null && e.isString) e.content else invalid("$what must be a string")
    }

    private fun scalarOrNull(v: EvalValue, what: String): String? {
        val e = (v as? EvalValue.Json)?.element ?: invalid("$what produced a non-JSON value")
        if (e is JsonNull) return null
        return (e as? JsonPrimitive)?.content ?: invalid("$what must be a scalar")
    }
}
