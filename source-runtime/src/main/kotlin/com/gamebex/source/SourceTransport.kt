package com.gamebex.source

enum class NetworkPurpose { CATALOG, DOWNLOAD, ARTWORK, ASSIST }

interface SourceTransport {
    suspend fun execute(request: EvaluatedRequest): SourceResponse
}

data class EvaluatedRequest(
    val method: String,
    val purpose: NetworkPurpose,
    val url: String,
    val query: Map<String, String>,
    val form: Map<String, String>,
    val headers: Map<String, String>,
)

data class SourceResponse(
    val status: Int,
    val headers: Map<String, List<String>>,
    val body: String,
    val url: String,
)
