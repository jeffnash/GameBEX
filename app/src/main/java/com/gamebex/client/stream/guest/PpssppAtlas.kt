package com.gamebex.client.stream.guest

import com.github.luben.zstd.Zstd
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

internal data class PpssppAtlasImage(val width: Int, val height: Int, val u0: Float, val v0: Float, val u1: Float, val v1: Float)

internal fun parsePpssppAtlas(data: ByteArray): Map<String, PpssppAtlasImage>? = runCatching {
    if (data.size !in 16..HostOverlayBundle.MAX_IMAGE_BYTES) return null
    val header = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
    if (header.int != 1095521345) return null
    val version = header.int
    val fonts = header.int
    val images = header.int
    if (version !in 0..1 || fonts !in 0..HostOverlayBundle.MAX_IMAGE_BYTES || images <= 0) return null
    val expected = images.toLong() * 56
    if (expected > HostOverlayBundle.MAX_IMAGE_BYTES) return null
    val records = if (version == 0) {
        if (header.remaining() < expected) return null
        data.copyOfRange(16, 16 + expected.toInt())
    } else {
        if (header.remaining() < 4) return null
        val compressed = header.int
        if (compressed <= 0 || compressed > header.remaining()) return null
        Zstd.decompress(data.copyOfRange(20, 20 + compressed), expected.toInt()).takeIf { it.size == expected.toInt() } ?: return null
    }
    val bytes = ByteBuffer.wrap(records).order(ByteOrder.LITTLE_ENDIAN)
    val out = linkedMapOf<String, PpssppAtlasImage>()
    repeat(images) {
        val u0 = bytes.float; val v0 = bytes.float; val u1 = bytes.float; val v1 = bytes.float
        val width = bytes.int; val height = bytes.int
        val nameBytes = ByteArray(32).also(bytes::get)
        val end = nameBytes.indexOf(0)
        if (width <= 0 || height <= 0 || end <= 0 || nameBytes.take(end).any { it < 32 || it > 126 } ||
            listOf(u0, v0, u1, v1).any { !it.isFinite() || it !in 0f..1f } || u1 < u0 || v1 < v0) return null
        val name = String(nameBytes, 0, end, StandardCharsets.US_ASCII)
        if (out.put(name, PpssppAtlasImage(width, height, u0, v0, u1, v1)) != null) return null
    }
    out
}.getOrNull()
