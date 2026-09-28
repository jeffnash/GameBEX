package com.gamebex.client.together.proto

import android.util.Log
import com.gamebex.client.together.LinkMsg
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object LinkJson {
    private const val TAG = "GameBEXTogether"

    @OptIn(ExperimentalSerializationApi::class)
    val json = Json {
        classDiscriminator = "t"
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    fun encode(msg: LinkMsg): String = json.encodeToString(LinkMsg.serializer(), msg)

    /** Unknown message types and malformed payloads are ignored, not link-fatal. */
    fun decode(text: String): LinkMsg? = try {
        json.decodeFromString(LinkMsg.serializer(), text)
    } catch (e: Exception) {
        runCatching { Log.d(TAG, "dropping invalid link message", e) }
        null
    }
}
