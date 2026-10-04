package com.gamebex.client.stream.shell

import java.io.EOFException
import java.io.InputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object RSoundProtocol {
    const val RATE = 48_000
    const val CHANNELS = 2
    const val FRAME_BYTES = 4
    const val CHUNK_FRAMES = 240
    const val CHUNK_BYTES = CHUNK_FRAMES * FRAME_BYTES
    const val HEADER_BYTES = 44
    const val MAX_CONTROL_BYTES = 256

    fun validateHeader(bytes: ByteArray) {
        require(bytes.size == HEADER_BYTES) { "RSound header must be 44 bytes" }
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun word(offset: Int) = b.getShort(offset).toInt() and 0xffff
        fun magic(offset: Int, text: String) =
            bytes.copyOfRange(offset, offset + text.length).contentEquals(text.toByteArray(Charsets.US_ASCII))
        require(magic(0, "RIFF") && magic(8, "WAVE") && magic(12, "fmt ") && magic(36, "data")) {
            "RSound requires RIFF/WAVE/fmt/data header"
        }
        require(b.getInt(16) == 16 && word(20) in 0..1 && word(42) == 1) {
            "RSound requires resolved S16_LE format at offset 42"
        }
        require(word(22) == CHANNELS && b.getInt(24) == RATE && word(34) == 16) {
            "RSound requires stereo 48000 Hz s16"
        }
        require(word(32) == FRAME_BYTES && b.getInt(28) == RATE * FRAME_BYTES) {
            "RSound PCM byte rate or block alignment mismatch"
        }
    }

    fun backendReply(
        latencyBytes: Int = 0,
        chunkBytes: Int = 512,
        useControlProtocol: Boolean = false,
    ): ByteArray {
        require(latencyBytes >= 0)
        require(chunkBytes in 1..1024 && chunkBytes % FRAME_BYTES == 0)
        val reply = ByteBuffer.allocate(if (useControlProtocol) 16 else 8).order(ByteOrder.BIG_ENDIAN)
            .putInt(latencyBytes).putInt(chunkBytes)
        if (useControlProtocol) reply.putLong(0)
        return reply.array()
    }

    sealed interface Control {
        data class Info(val clientBytes: Long) : Control
        data object Identity : Control
        data object Close : Control
        data object Stop : Control
        data object Unknown : Control
    }

    fun readControl(input: InputStream, firstByte: Int? = null, beforeRead: () -> Unit = {}): Control {
        val header = if (firstByte == null) {
            readExactly(input, 8, beforeRead)
        } else {
            byteArrayOf(firstByte.toByte()) + readExactly(input, 7, beforeRead)
        }
        require(header.copyOfRange(0, 3).contentEquals("RSD".toByteArray(Charsets.US_ASCII))) {
            "Invalid RSound control prefix"
        }
        val sizeText = String(header, 3, 5, Charsets.US_ASCII)
        require(sizeText.matches(Regex(" *[0-9]+"))) { "Invalid RSound control size" }
        val size = sizeText.trim().toInt()
        require(size in 1..MAX_CONTROL_BYTES) { "RSound control body exceeds bound" }
        val body = String(readExactly(input, size, beforeRead), Charsets.US_ASCII)
        require(body.startsWith(' ')) { "RSound control body requires leading space" }
        val parts = body.trim().split(Regex(" +"), limit = 3)
        return when (parts.firstOrNull()) {
            "INFO" -> {
                require(parts.size == 2) { "Invalid RSound INFO arguments" }
                val pointer = parts[1].toLongOrNull()
                require(pointer != null && pointer >= 0) { "Invalid RSound INFO pointer" }
                Control.Info(pointer)
            }
            "IDENTITY" -> Control.Identity
            "CLOSECTL" -> Control.Close
            "STOP" -> Control.Stop
            else -> Control.Unknown
        }
    }

    fun controlReply(body: String): ByteArray {
        val payload = body.toByteArray(Charsets.US_ASCII)
        require(body.startsWith(' ') && payload.size in 1..MAX_CONTROL_BYTES)
        val size = payload.size.toString().padStart(5, ' ')
        return "RSD$size".toByteArray(Charsets.US_ASCII) + payload
    }

    fun readExactly(input: InputStream, size: Int, beforeRead: () -> Unit = {}): ByteArray {
        val bytes = ByteArray(size)
        var offset = 0
        while (offset < size) {
            beforeRead()
            val count = input.read(bytes, offset, size - offset)
            if (count < 0) throw EOFException("RSound truncated packet")
            if (count == 0) throw IOException("RSound input made no progress")
            offset += count
        }
        return bytes
    }
}

internal class RSoundChunker(private val emit: (ByteArray, Long) -> Unit) {
    private var chunk = ByteArray(RSoundProtocol.CHUNK_BYTES)
    private var filled = 0
    private var firstByteNs = 0L

    fun accept(bytes: ByteArray, offset: Int, count: Int, arrivalNs: Long) {
        require(offset >= 0 && count >= 0 && offset <= bytes.size - count)
        var position = offset
        val end = offset + count
        while (position < end) {
            if (filled == 0) firstByteNs = arrivalNs
            val size = minOf(end - position, chunk.size - filled)
            bytes.copyInto(chunk, filled, position, position + size)
            position += size
            filled += size
            if (filled == chunk.size) {
                val complete = chunk
                val captureNs = firstByteNs
                chunk = ByteArray(RSoundProtocol.CHUNK_BYTES)
                filled = 0
                emit(complete, captureNs)
            }
        }
    }
}
