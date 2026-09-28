package com.gamebex.source

enum class SourceFailure {
    UNAVAILABLE, INCOMPATIBLE, INVALID_DEFINITION, INVALID_RESPONSE,
    NETWORK, HTTP, POLICY, UNSUPPORTED,
}

class SourceException(
    val failure: SourceFailure,
    val sourceId: String?,
    val operation: String?,
    val statusCode: Int? = null,
    val fieldPath: String? = null,
    cause: Throwable? = null,
) : Exception(listOfNotNull(failure.name, sourceId, operation, fieldPath).joinToString(":"), cause)
