package com.gamebex.client.together.session

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

internal object StreamResumeGuard {
    data class Bound(
        val sessionId: String,
        val round: Int,
        val token: String,
        val deviceId: String,
        val address: String,
        val audioTimestampOrigin: String,
    )

    private val tokenPattern = Regex("[0-9a-f]{32}")

    fun validAttempt(attempt: String): Boolean = runCatching {
        UUID.fromString(attempt).toString() == attempt
    }.getOrDefault(false)

    fun matches(bound: Bound, sessionId: String, round: Int, token: String, deviceId: String, address: String): Boolean {
        if (round <= 0 || sessionId != bound.sessionId || round != bound.round ||
            deviceId != bound.deviceId || address != bound.address || !tokenPattern.matches(token)
        ) return false
        return MessageDigest.isEqual(
            bound.token.toByteArray(StandardCharsets.US_ASCII),
            token.toByteArray(StandardCharsets.US_ASCII),
        )
    }

    fun delayMs(attempt: Int): Long = when (attempt) {
        0 -> 250L
        1 -> 500L
        2 -> 1_000L
        else -> 2_000L
    }
}
