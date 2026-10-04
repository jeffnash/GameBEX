package com.gamebex.client.stream.shell

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RSoundProtocolTest {
    @Test fun acceptsExactRetroArchNativeAndExplicitLittleEndianHeaders() {
        RSoundProtocol.validateHeader(rsoundHeader())
        RSoundProtocol.validateHeader(rsoundHeader(wavFormat = 1))
        val fragmented = fragmented(rsoundHeader())
        assertArrayEquals(rsoundHeader(), RSoundProtocol.readExactly(fragmented, 44))
    }

    @Test fun rejectsWrongMagicRateChannelsBitsAndResolvedCustomFormat() {
        val edits = listOf<Pair<Int, ByteArray>>(
            0 to byteArrayOf('X'.code.toByte()),
            24 to ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(44_100).array(),
            22 to byteArrayOf(1, 0),
            34 to byteArrayOf(32, 0),
            42 to byteArrayOf(2, 0),
            42 to byteArrayOf(64, 0),
            20 to byteArrayOf(3, 0),
            32 to byteArrayOf(8, 0),
        )
        for ((offset, replacement) in edits) {
            val header = rsoundHeader()
            replacement.copyInto(header, offset)
            assertThrows(IllegalArgumentException::class.java) { RSoundProtocol.validateHeader(header) }
        }
        assertThrows(IllegalArgumentException::class.java) { RSoundProtocol.validateHeader(ByteArray(43)) }
    }

    @Test fun defaultReplyIsEightBigEndianBytesWithZeroLatencyAnd512ByteTransportChunk() {
        assertArrayEquals(byteArrayOf(0, 0, 0, 0, 0, 0, 2, 0), RSoundProtocol.backendReply())
    }

    @Test fun optionalControlReplyPreservesLatency960ByteTransportChunkAndImmediateInfoExtension() {
        assertArrayEquals(
            byteArrayOf(0, 0, 7, -128, 0, 0, 3, -64, 0, 0, 0, 0, 0, 0, 0, 0),
            RSoundProtocol.backendReply(1920, chunkBytes = 960, useControlProtocol = true),
        )
        assertThrows(IllegalArgumentException::class.java) { RSoundProtocol.backendReply(-1) }
    }

    @Test fun transportChunkSizesAreBoundedAndFrameAlignedIndependentlyOfPcmChunks() {
        for (chunkBytes in listOf(4, 512, 960, 1024)) {
            val reply = RSoundProtocol.backendReply(chunkBytes = chunkBytes)
            assertEquals(8, reply.size)
            assertEquals(chunkBytes, ByteBuffer.wrap(reply).order(ByteOrder.BIG_ENDIAN).getInt(4))
        }
        for (chunkBytes in listOf(-4, 0, 1, 3, 513, 1023, 1025, 1028)) {
            assertThrows(IllegalArgumentException::class.java) {
                RSoundProtocol.backendReply(chunkBytes = chunkBytes)
            }
        }
        assertEquals(240, RSoundProtocol.CHUNK_FRAMES)
        assertEquals(960, RSoundProtocol.CHUNK_BYTES)
    }

    @Test fun readsFragmentedConcatenatedInfoIdentityCloseAndStopControls() {
        val input = fragmented(
            RSoundProtocol.controlReply(" INFO 123456789012") +
                RSoundProtocol.controlReply(" IDENTITY RetroArch") +
                "RSD    9 CLOSECTL".toByteArray() +
                RSoundProtocol.controlReply(" STOP"),
        )
        assertEquals(RSoundProtocol.Control.Info(123456789012L), RSoundProtocol.readControl(input))
        assertEquals(RSoundProtocol.Control.Identity, RSoundProtocol.readControl(input))
        assertEquals(RSoundProtocol.Control.Close, RSoundProtocol.readControl(input))
        assertEquals(RSoundProtocol.Control.Stop, RSoundProtocol.readControl(input))
        assertArrayEquals("RSD   12 CLOSECTL OK".toByteArray(), RSoundProtocol.controlReply(" CLOSECTL OK"))
        assertArrayEquals("RSD   13 INFO 960 480".toByteArray(), RSoundProtocol.controlReply(" INFO 960 480"))
    }

    @Test fun controlLengthsPointersPrefixesAndTruncationAreBounded() {
        val invalid = listOf(
            "RSD  257", "RSD99999", "RSD    0", "RSD   -1", "BAD    4 STOP", "RSD0x010",
            "RSD   99", "RSD    8 INFO -1", "RSD    9 INFO 1 2", "RSD    4STOP",
        )
        for (message in invalid) {
            assertThrows(Exception::class.java) {
                RSoundProtocol.readControl(ByteArrayInputStream(message.toByteArray()))
            }
        }
        assertThrows(EOFException::class.java) {
            RSoundProtocol.readExactly(ByteArrayInputStream(byteArrayOf(1)), 44)
        }
    }

    @Test fun chunksRetainFirstByteArrivalTimestampAcrossPartialAndOversizedReads() {
        val emitted = mutableListOf<Pair<ByteArray, Long>>()
        val chunker = RSoundChunker { bytes, ns -> emitted.add(bytes to ns) }
        val pcm = ByteArray(2880) { (it % 127).toByte() }
        chunker.accept(pcm, 0, 3, 10)
        chunker.accept(pcm, 3, 0, 15)
        chunker.accept(pcm, 3, 1000, 20)
        chunker.accept(pcm, 1003, 1877, 30)
        assertEquals(listOf(10L, 20L, 30L), emitted.map { it.second })
        assertEquals(listOf(960, 960, 960), emitted.map { it.first.size })
        assertArrayEquals(pcm, emitted.flatMap { it.first.asIterable() }.toByteArray())
    }

    private fun fragmented(bytes: ByteArray): InputStream = object : ByteArrayInputStream(bytes) {
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            super.read(buffer, offset, minOf(1, length))
    }
}

internal fun rsoundHeader(wavFormat: Int = 0): ByteArray = ByteBuffer.allocate(44)
    .order(ByteOrder.LITTLE_ENDIAN)
    .put("RIFF".toByteArray()).putInt(0).put("WAVEfmt ".toByteArray()).putInt(16)
    .putShort(wavFormat.toShort()).putShort(2).putInt(48000).putInt(192000)
    .putShort(4).putShort(16).put("data".toByteArray()).putShort(0).putShort(1).array()
