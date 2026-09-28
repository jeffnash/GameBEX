package com.gamebex.source

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Executes validated workflows. One runner serves one immutable source
 * revision; cached workflow outputs live and die with this instance.
 */
class WorkflowRunner(
    val definition: ValidatedDefinition,
    val transport: SourceTransport,
) {
    private val cacheMutex = Mutex()
    private val cache = HashMap<String, JsonElement>()

    suspend fun run(name: String, input: JsonObject, nullableInputs: Set<String> = emptySet()): JsonElement {
        val wf = definition.def.operations[name]
            ?: throw SourceException(SourceFailure.INVALID_DEFINITION, definition.id, name)
        for (required in wf.inputs) {
            if (required !in input) {
                throw IllegalArgumentException("workflow $name needs input $required")
            }
            if (input[required] is JsonNull && required !in nullableInputs) {
                throw IllegalArgumentException("workflow $name input $required must not be null")
            }
        }
        if (wf.cache) {
            // The mutex serializes identical cached calls: the first computes,
            // later callers observe the stored success. Only successes cache.
            cacheMutex.withLock {
                val key = name + "\n" + input.toString()
                cache[key]?.let { return it }
                val freshBudget = EvalBudget()
                val out = runInternal(name, wf, input, HashMap(), freshBudget)
                cache[key] = out
                return out
            }
        }
        return runInternal(name, wf, input, HashMap(), EvalBudget())
    }

    private suspend fun runInternal(
        name: String,
        wf: Workflow,
        input: JsonObject,
        memo: MutableMap<String, JsonElement>,
        budget: EvalBudget,
    ): JsonElement {
        val scoped = ExpressionEvaluator(definition, transport, { op, args ->
            val key = op + "\n" + args.toString()
            memo[key] ?: runHelper(op, args, memo, budget).also { memo[key] = it }
        }, budget)
        try {
            val steps = LinkedHashMap<String, EvalValue>()
            for (step in wf.steps) {
                val env = EvalEnv(input, steps.toMap())
                val value = if (step.request != null) {
                    EvalValue.Resp(scoped.evaluateRequest(step.request, env))
                } else {
                    scoped.evaluateInOperation(step.value!!, env, name)
                }
                steps[step.name] = value
            }
            val result = scoped.evaluateInOperation(wf.result, EvalEnv(input, steps.toMap()), name)
            val json = strictJson(result, name)
            val fb = wf.fallback
            if (fb != null && fb.onEmpty && isEmptyResult(json)) {
                return runFallback(name, fb, input, memo, budget)
            }
            return json
        } catch (e: SourceException) {
            val fb = wf.fallback
            if (fb != null && e.failure in fb.onFailures) {
                return runFallback(name, fb, input, memo, budget)
            }
            throw e
        }
    }

    private suspend fun runHelper(
        name: String,
        input: JsonObject,
        memo: MutableMap<String, JsonElement>,
        budget: EvalBudget,
    ): JsonElement {
        val wf = definition.def.operations[name]
            ?: throw SourceException(SourceFailure.INVALID_DEFINITION, definition.id, name)
        for (required in wf.inputs) {
            if (required !in input) throw IllegalArgumentException("helper $name needs input $required")
        }
        if (wf.cache) {
            cacheMutex.withLock {
                val key = name + "\n" + input.toString()
                cache[key]?.let { return it }
                val out = runInternal(name, wf, input, memo, budget)
                cache[key] = out
                return out
            }
        }
        return runInternal(name, wf, input, memo, budget)
    }

    private suspend fun runFallback(
        name: String,
        fb: WorkflowFallback,
        input: JsonObject,
        memo: MutableMap<String, JsonElement>,
        budget: EvalBudget,
    ): JsonElement {
        val target = definition.def.operations[fb.operation]
            ?: throw SourceException(SourceFailure.INVALID_DEFINITION, definition.id, name)
        // Same input object, fresh step environment; helper memo stays scoped
        // to this top-level invocation.
        return runInternal(fb.operation, target, input, memo, budget)
    }

    private fun isEmptyResult(e: JsonElement): Boolean = when (e) {
        is JsonNull -> true
        is JsonArray -> e.isEmpty()
        is JsonObject -> (e["items"] as? JsonArray)?.isEmpty() == true
        else -> false
    }

    private fun strictJson(v: EvalValue, operation: String): JsonElement = when (v) {
        is EvalValue.Json -> v.element
        else -> throw SourceException(
            SourceFailure.INVALID_RESPONSE, definition.id, operation,
            cause = IllegalStateException("response/HTML values must not escape the workflow result"),
        )
    }
}
