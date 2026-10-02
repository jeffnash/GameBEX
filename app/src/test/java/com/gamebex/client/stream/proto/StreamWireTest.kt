package com.gamebex.client.stream.proto

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class StreamWireTest {
    private val token = ByteArray(16) { (it * 17).toByte() }

    @Test fun helloIs24BytesAndRoundTrips() {
        val bytes = StreamHandshake.encodeHello(token)
        assertEquals(StreamWire.HELLO_SIZE, bytes.size)
        assertArrayEquals(byteArrayOf(0x47, 0x42, 0x58, 0x53, 1, 0, 0, 0), bytes.copyOfRange(0, 8))
        val hello = StreamHandshake.decodeHello(bytes)!!
        assertEquals(StreamWire.VERSION, hello.version)
        assertArrayEquals(token, hello.token)
    }

    @Test fun helloRejectsWrongMagicAndSize() {
        val bytes = StreamHandshake.encodeHello(token)
        bytes[0] = 0
        assertNull(StreamHandshake.decodeHello(bytes))
        assertNull(StreamHandshake.decodeHello(ByteArray(23)))
    }

    @Test fun welcomeIs28BytesAndRoundTrips() {
        val w = Welcome(1, StreamWire.STATUS_OK, 1600, 720, 48_000, 2, 123_456_789_012L)
        val bytes = StreamHandshake.encodeWelcome(w)
        assertEquals(StreamWire.WELCOME_SIZE, bytes.size)
        assertEquals(w, StreamHandshake.decodeWelcome(bytes))
    }

    @Test fun everyFrameTypeRoundTrips() {
        val frames = listOf(
            MediaFrame.VideoConfig(byteArrayOf(0, 0, 0, 1, 0x67, 1, 2)),
            MediaFrame.Video(true, 7, 11L, 12L, 13L, byteArrayOf(0, 0, 0, 1, 0x65, 9)),
            MediaFrame.Audio(3, 44L, 480, ByteArray(1920) { it.toByte() }),
            MediaFrame.Ping(5, 99L),
            MediaFrame.Pong(5, 99L, 100L, 101L),
            MediaFrame.KeyframeRequest,
            MediaFrame.Report(1, 2, 3, 4, 5),
            MediaFrame.Info(1, 1280, 576, 4_000_000),
            MediaFrame.Bye(StreamWire.BYE_LEAVING),
        )
        val out = ByteArrayOutputStream()
        frames.forEach { out.write(MediaFrames.encode(it)) }
        val input = DataInputStream(ByteArrayInputStream(out.toByteArray()))
        val video = MediaFrames.read(input)
        assertTrue(video is MediaFrame.VideoConfig)
        assertArrayEquals((frames[0] as MediaFrame.VideoConfig).csd, (video as MediaFrame.VideoConfig).csd)
        val v = MediaFrames.read(input) as MediaFrame.Video
        assertTrue(v.key)
        assertEquals(7, v.seq)
        assertEquals(11L, v.captureNs)
        assertEquals(12L, v.encodedNs)
        assertEquals(13L, v.sendNs)
        assertArrayEquals((frames[1] as MediaFrame.Video).data, v.data)
        val a = MediaFrames.read(input) as MediaFrame.Audio
        assertEquals(3, a.seq)
        assertEquals(480, a.frames)
        assertEquals(1920, a.pcm.size)
        assertEquals(frames[3], MediaFrames.read(input))
        assertEquals(frames[4], MediaFrames.read(input))
        assertEquals(MediaFrame.KeyframeRequest, MediaFrames.read(input))
        assertEquals(frames[6], MediaFrames.read(input))
        assertEquals(frames[7], MediaFrames.read(input))
        assertEquals(frames[8], MediaFrames.read(input))
        assertNull(MediaFrames.read(input))
    }

    @Test fun videoFrameHeaderIs34BytesBeforeTheData() {
        val bytes = MediaFrames.encode(MediaFrame.Video(false, 1, 2L, 3L, 4L, ByteArray(10)))
        // length(4) + type(1) + flags(1) + seq(4) + 3 × time(8) + data
        assertEquals(4 + 1 + 1 + 4 + 24 + 10, bytes.size)
        assertEquals(1 + 1 + 4 + 24 + 10, java.nio.ByteBuffer.wrap(bytes).int)
        assertEquals(StreamWire.T_VIDEO_FRAME.toByte(), bytes[4])
    }

    @Test fun unknownTypeIsSkippedNotFatal() {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0, 0, 0, 3, 0x7E, 1, 2))
        out.write(MediaFrames.encode(MediaFrame.Ping(1, 2L)))
        val input = DataInputStream(ByteArrayInputStream(out.toByteArray()))
        assertEquals(MediaFrame.Unknown(0x7E), MediaFrames.read(input))
        assertEquals(MediaFrame.Ping(1, 2L), MediaFrames.read(input))
    }

    @Test fun oversizedOrEmptyLengthFails() {
        for (length in listOf(0, StreamWire.MAX_FRAME + 1)) {
            val b = java.nio.ByteBuffer.allocate(8).putInt(length).putInt(0).array()
            try {
                MediaFrames.read(DataInputStream(ByteArrayInputStream(b)))
                fail("expected IOException for length $length")
            } catch (_: IOException) {
            }
        }
    }

    @Test fun truncatedPongFails() {
        val body = byteArrayOf(StreamWire.T_PONG.toByte(), 0, 0, 0, 1)
        try {
            MediaFrames.decodeBody(body)
            fail("expected IOException")
        } catch (_: IOException) {
        }
    }

    @Test fun inputPacketIs38BytesAndRoundTrips() {
        val state = PadState(PadState.A or PadState.START, -127, 127, 5, -5, 255, 10, PadState.HAT_LEFT)
        val p = InputPacket(0, 0x0102030405060708L, Int.MAX_VALUE, 42L, state)
        val bytes = InputWire.encode(p)
        assertEquals(StreamWire.INPUT_SIZE, bytes.size)
        assertEquals(p, InputWire.decode(bytes))
    }

    @Test fun inputDecodeSanitizesAndRejectsGarbage() {
        val p = InputPacket(0, 1L, 1, 1L, PadState())
        val bytes = InputWire.encode(p)
        bytes[36] = 12
        assertEquals(PadState.HAT_CENTER, InputWire.decode(bytes)!!.state.hat)
        val bad = bytes.copyOf()
        bad[3] = 0
        assertNull(InputWire.decode(bad))
        assertNull(InputWire.decode(bytes, 37))
        val version = bytes.copyOf()
        version[4] = 2
        assertNull(InputWire.decode(version))
    }

    @Test fun inputNeverCarriesModeButton() {
        val p = InputPacket(0, 1L, 1, 1L, PadState(buttons = PadState.MODE or PadState.B))
        assertEquals(PadState.B, InputWire.decode(InputWire.encode(p))!!.state.buttons)
    }

    @Test fun ackIs28BytesAndRoundTrips() {
        val a = InputAck(9, 100L, 200L)
        val bytes = InputWire.encodeAck(a)
        assertEquals(StreamWire.ACK_SIZE, bytes.size)
        assertEquals(a, InputWire.decodeAck(bytes))
        assertNull(InputWire.decodeAck(InputWire.encode(InputPacket(0, 1L, 1, 1L, PadState()))))
    }

    @Test fun sequenceComparisonWrapsAround() {
        assertTrue(InputWire.isNewer(1, 0))
        assertTrue(InputWire.isNewer(Int.MIN_VALUE, Int.MAX_VALUE))
        assertFalse(InputWire.isNewer(5, 5))
        assertFalse(InputWire.isNewer(4, 5))
    }

    @Test
    fun audioDatagramsRoundTripAndRejectTheWrongLength() {
        val pcm = ByteArray(960) { it.toByte() }
        val bytes = InputWire.encodeAudio(0x1122334455667788L, 42, 987_654_321L, 240, pcm)
        assertEquals(StreamWire.AUDIO_UDP_HEADER + 960, bytes.size)
        val a = InputWire.decodeAudio(bytes)!!
        assertEquals(0x1122334455667788L, a.key)
        assertEquals(42, a.seq)
        assertEquals(987_654_321L, a.captureNs)
        assertEquals(240, a.frames)
        assertArrayEquals(pcm, a.pcm)
        assertNull(InputWire.decodeAudio(bytes, bytes.size - 4))
        assertNull(InputWire.decodeAck(bytes))
        assertNull(InputWire.decode(bytes))
    }
}
