package com.gamebex.source

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class SourceDefinition(
    val schemaVersion: Int,
    val id: String,
    val name: String,
    val author: String,
    val version: String,
    val requires: Set<String>,
    val origins: SourceOrigins,
    val defaults: RequestDefaults = RequestDefaults(),
    val platforms: Map<String, String>,
    val assist: AssistPolicy = AssistPolicy(),
    val operations: Map<String, Workflow>,
)

@Serializable
data class SourceOrigins(
    val catalog: Set<String>,
    val download: Set<String>,
    val artwork: Set<String>,
    val assist: Set<String> = emptySet(),
)

@Serializable
data class RequestDefaults(
    val headers: Map<String, String> = emptyMap(),
    val preferIpv4: Boolean = false,
)

@Serializable
data class Workflow(
    val inputs: Set<String>,
    val steps: List<WorkflowStep>,
    val result: JsonObject,
    val fallback: WorkflowFallback? = null,
    val cache: Boolean = false,
)

@Serializable
data class WorkflowStep(
    val name: String,
    val request: RequestSpec? = null,
    val value: JsonObject? = null,
)

@Serializable
data class RequestSpec(
    val method: String = "GET",
    val purpose: String = "catalog",
    val url: JsonObject,
    val query: Map<String, JsonObject> = emptyMap(),
    val form: Map<String, JsonObject> = emptyMap(),
    val headers: Map<String, JsonObject> = emptyMap(),
)

@Serializable
data class WorkflowFallback(
    val operation: String,
    val onEmpty: Boolean = false,
    val onFailures: Set<SourceFailure> = emptySet(),
)
