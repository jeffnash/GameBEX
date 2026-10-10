package com.gamebex.client.together.proto

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/** Length-prefixed frames used by the Play together link. */
object Frames {
    const val KIND_JSON: Int = 0x4A
    const val KIND_BLOB: Int = 0x42
    /**
     * Fits one whole overlay image (HostOverlayBundle.MAX_IMAGE_BYTES as base64 plus envelope; arcade
     * buttons are ~217 KB base64), since parts never split an image. Must stay within MAX_FRAME_LENGTH.
     */
    const val MAX_JSON: Int = 262_144
    const val MAX_CHUNK: Int = 262_144

    private const val MAX_FRAME_LENGTH: Int = MAX_CHUNK + 5

    fun writeJson(out: OutputStream, json: String) {
        val bytes = json.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size > MAX_JSON) bad("JSON payload too large")
        writeLength(out, bytes.size + 1)
        out.write(KIND_JSON)
        out.write(bytes)
    }

    fun writeBlob(out: OutputStream, blobId: Int, buf: ByteArray, off: Int, len: Int) {
        if (off < 0 || len < 1 || len > MAX_CHUNK || off > buf.size - len) {
            bad("blob payload size")
        }
        writeLength(out, len + 5)
        out.write(KIND_BLOB)
        writeInt(out, blobId)
        out.write(buf, off, len)
    }

    /** Reads one frame; returns null only when EOF occurs before a frame starts. */
    fun read(input: InputStream): Frame? {
        val first = input.read()
        if (first < 0) return null
        val lengthBytes = ByteArray(4)
        lengthBytes[0] = first.toByte()
        readFully(input, lengthBytes, 1, 3)
        val length = ((lengthBytes[0].toInt() and 0xff) shl 24) or
            ((lengthBytes[1].toInt() and 0xff) shl 16) or
            ((lengthBytes[2].toInt() and 0xff) shl 8) or
            (lengthBytes[3].toInt() and 0xff)
        if (length !in 1..MAX_FRAME_LENGTH) bad("length $length")

        val body = ByteArray(length)
        readFully(input, body, 0, length)
        return when (body[0].toInt() and 0xff) {
            KIND_JSON -> {
                val payloadLength = length - 1
                if (payloadLength > MAX_JSON) bad("JSON payload too large")
                Frame.Json(String(body, 1, payloadLength, StandardCharsets.UTF_8))
            }
            KIND_BLOB -> {
                if (length < 6) bad("blob payload too short")
                val blobId = ((body[1].toInt() and 0xff) shl 24) or
                    ((body[2].toInt() and 0xff) shl 16) or
                    ((body[3].toInt() and 0xff) shl 8) or
                    (body[4].toInt() and 0xff)
                Frame.Blob(blobId, body.copyOfRange(5, length))
            }
            else -> bad("unknown kind ${body[0].toInt() and 0xff}")
        }
    }

    private fun writeLength(out: OutputStream, length: Int) {
        if (length !in 1..MAX_FRAME_LENGTH) bad("length $length")
        writeInt(out, length)
    }

    private fun writeInt(out: OutputStream, value: Int) {
        out.write((value ushr 24) and 0xff)
        out.write((value ushr 16) and 0xff)
        out.write((value ushr 8) and 0xff)
        out.write(value and 0xff)
    }

    private fun readFully(input: InputStream, bytes: ByteArray, off: Int, len: Int) {
        var pos = off
        val end = off + len
        while (pos < end) {
            val count = input.read(bytes, pos, end - pos)
            if (count < 0) bad("unexpected EOF")
            if (count == 0) continue
            pos += count
        }
    }

    private fun bad(reason: String): Nothing = throw IOException("bad frame: $reason")
}

sealed interface Frame {
    data class Json(val text: String) : Frame
    class Blob(val blobId: Int, val bytes: ByteArray) : Frame
}
