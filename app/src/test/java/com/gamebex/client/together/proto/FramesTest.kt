package com.gamebex.client.together.proto

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FramesTest {
    @Test fun jsonRoundTrips() {
        val out = ByteArrayOutputStream()
        Frames.writeJson(out, "{\"t\":\"ping\",\"n\":7}")
        val frame = Frames.read(ByteArrayInputStream(out.toByteArray())) as Frame.Json
        assertEquals("{\"t\":\"ping\",\"n\":7}", frame.text)
    }

    @Test fun blobRoundTripsAtMinimumAndMaximum() {
        for (length in listOf(1, Frames.MAX_CHUNK)) {
            val data = ByteArray(length) { (it * 31).toByte() }
            val out = ByteArrayOutputStream()
            Frames.writeBlob(out, 0x12345678, data, 0, data.size)
            val frame = Frames.read(ByteArrayInputStream(out.toByteArray())) as Frame.Blob
            assertEquals(0x12345678, frame.blobId)
            assertArrayEquals(data, frame.bytes)
        }
    }

    @Test fun invalidLengthFails() {
        assertBadFrame(byteArrayOf(0, 0, 0, 0))
        assertBadFrame(byteArrayOf(0, 4, 0, 6))
    }

    @Test fun blobWithoutPayloadFails() {
        assertBadFrame(byteArrayOf(0, 0, 0, 5, Frames.KIND_BLOB.toByte(), 0, 0, 0, 7))
    }

    @Test fun unknownKindFails() {
        assertBadFrame(byteArrayOf(0, 0, 0, 1, 0x01))
    }

    @Test fun oversizedJsonFails() {
        val out = ByteArrayOutputStream()
        try {
            Frames.writeJson(out, "x".repeat(Frames.MAX_JSON + 1))
            throw AssertionError("expected IOException")
        } catch (_: IOException) {
        }
    }

    @Test fun readRejectsOversizedJson() {
        // length counts the kind byte: payload is MAX_JSON + 1.
        val length = Frames.MAX_JSON + 2
        val frame = ByteArray(4 + length)
        frame[0] = ((length shr 24) and 0xff).toByte()
        frame[1] = ((length shr 16) and 0xff).toByte()
        frame[2] = ((length shr 8) and 0xff).toByte()
        frame[3] = (length and 0xff).toByte()
        frame[4] = Frames.KIND_JSON.toByte()
        assertBadFrame(frame)
    }

    @Test fun jsonRoundTripsNearTheCeiling() {
        // Whole overlay images ride one part (arcade buttons ~217 KB base64).
        val text = "x".repeat(220_000)
        val out = ByteArrayOutputStream()
        Frames.writeJson(out, text)
        val frame = Frames.read(ByteArrayInputStream(out.toByteArray())) as Frame.Json
        assertEquals(text, frame.text)
    }

    @Test fun cleanEofAtFrameBoundaryReturnsNull() {
        assertNull(Frames.read(ByteArrayInputStream(ByteArray(0))))
    }

    @Test fun eofMidFrameFails() {
        assertBadFrame(byteArrayOf(0, 0, 0, 2, Frames.KIND_JSON.toByte()))
    }

    private fun assertBadFrame(bytes: ByteArray) {
        try {
            Frames.read(ByteArrayInputStream(bytes))
            throw AssertionError("expected IOException")
        } catch (_: IOException) {
        }
    }
}
