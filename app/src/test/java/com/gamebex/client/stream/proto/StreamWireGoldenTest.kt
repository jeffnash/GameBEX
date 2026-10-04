package com.gamebex.client.stream.proto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class StreamWireGoldenTest {
    // Reviewed v1 bytes, calculated from field widths and integer arithmetic, not the codec.
    // Keep historical entries unchanged; a new VERSION requires a new reviewed vector set.
    // Spaces delimit fields. Integers are big-endian; PCM samples remain little-endian.
    private val goldenByVersion: Map<Int, Map<String, String>> = mapOf(
        1 to mapOf(
            "hello" to "47425853 01 00 0000 00112233445566778899aabbccddeeff",
            "welcome" to "47425853 01 02 0000 0640 02d0 0000bb80 02 000000 3132333435363738",
            "video-config" to "00000011 01 000000016742001e0000000168ce06e2",
            "video-key" to "00000026 02 01 12345678 0102030405060708 1112131415161718 " +
                "2122232425262728 00000001658899ab",
            "video-delta" to "00000026 02 00 12345679 0102030405060708 1112131415161718 " +
                "2122232425262728 0000000141aabbcc",
            "audio-frame" to "00000017 03 23456789 0102030405060708 0002 3412cced0180007f",
            "ping" to "0000000d 10 3456789a 0102030405060708",
            "pong" to "0000001d 11 3456789a 0102030405060708 1112131415161718 2122232425262728",
            "keyframe-request" to "00000001 12",
            "report" to "00000015 13 01020304 11223344 21324354 0001e240 00000102",
            "info" to "0000000a 20 03 0640 02d0 003d0900",
            "bye" to "00000002 21 02",
            "input" to "47425849 01 00 0000 1122334455667788 fedcba98 4142434445464748 " +
                "2949 81 3f c0 7e ab cd 05 00",
            "input-neutral" to "47425849 01 01 0000 1122334455667788 fedcba99 4142434445464749 " +
                "0000 00 00 00 00 00 00 08 00",
            "input-ack" to "47425841 01 00 0000 fedcba98 4142434445464748 5152535455565758",
            "audio-datagram" to "47425850 01 00 0000 1122334455667788 23456789 " +
                "0102030405060708 0002 0000 3412cced0180007f",
        ),
        2 to mapOf(
            "hello" to "47425853 02 00 0000 00112233445566778899aabbccddeeff 6162636465666768",
            "welcome" to "47425853 02 02 0000 0640 02d0 0000bb80 02 000000 3132333435363738",
            "video-config" to "00000011 01 000000016742001e0000000168ce06e2",
            "video-key" to "00000026 02 01 12345678 0102030405060708 1112131415161718 " +
                "2122232425262728 00000001658899ab",
            "video-delta" to "00000026 02 00 12345679 0102030405060708 1112131415161718 " +
                "2122232425262728 0000000141aabbcc",
            "audio-frame" to "00000017 03 23456789 0102030405060708 0002 3412cced0180007f",
            "ping" to "0000000d 10 3456789a 0102030405060708",
            "pong" to "0000001d 11 3456789a 0102030405060708 1112131415161718 2122232425262728",
            "keyframe-request" to "00000001 12",
            "delivery" to "00000039 14 0102030405060708 12345678 1112131415161718 " +
                "000000001dcd6500 0000000000010203 00000004 000000000001e240 0002 0000 fedcba98",
            "info" to "0000000a 20 03 0500 0240 002dc6c0",
            "bye" to "00000002 21 02",
            "input" to "47425849 02 00 0000 1122334455667788 6162636465666768 fedcba98 4142434445464748 " +
                "2949 81 3f c0 7e ab cd 05 00",
            "input-neutral" to "47425849 02 01 0000 1122334455667788 6162636465666768 fedcba99 4142434445464749 " +
                "0000 00 00 00 00 00 00 08 00",
            "input-ack" to "47425841 02 01 0000 6162636465666768 fedcba98 4142434445464748 5152535455565758",
            "audio-datagram" to "47425850 02 00 0000 1122334455667788 23456789 " +
                "0102030405060708 0002 0000 3412cced0180007f",
        ),
    )

    @Test fun everyEncodableMessageMatchesReviewedVersionVectors() {
        // Check the version before invoking any encoder; never fall back to an older vector set.
        val golden = goldenByVersion[StreamWire.VERSION] ?: throw AssertionError(
            "StreamWire.VERSION=${StreamWire.VERSION} has no reviewed golden vectors. " +
                "Add reviewed vectors for this version and preserve historical version entries.",
        )
        // Two stereo PCM frames: signed little-endian samples 0x1234, -0x1234, -32767, 32512.
        val pcm = hexBytes("3412cced0180007f")
        val actual = mapOf(
            "hello" to StreamHandshake.encodeHello(hexBytes("00112233445566778899aabbccddeeff"), 0x6162636465666768L),
            "welcome" to StreamHandshake.encodeWelcome(
                Welcome(
                    version = StreamWire.VERSION,
                    status = 2,
                    width = 1600,
                    height = 720,
                    audioRate = 48_000,
                    audioChannels = 2,
                    hostNowNs = 0x3132333435363738L,
                ),
            ),
            "video-config" to MediaFrames.encode(
                MediaFrame.VideoConfig(hexBytes("000000016742001e0000000168ce06e2")),
            ),
            "video-key" to MediaFrames.encode(
                MediaFrame.Video(
                    key = true,
                    seq = 0x12345678,
                    captureNs = 0x0102030405060708L,
                    encodedNs = 0x1112131415161718L,
                    sendNs = 0x2122232425262728L,
                    data = hexBytes("00000001658899ab"),
                ),
            ),
            "video-delta" to MediaFrames.encode(
                MediaFrame.Video(
                    key = false,
                    seq = 0x12345679,
                    captureNs = 0x0102030405060708L,
                    encodedNs = 0x1112131415161718L,
                    sendNs = 0x2122232425262728L,
                    data = hexBytes("0000000141aabbcc"),
                ),
            ),
            "audio-frame" to MediaFrames.encode(
                MediaFrame.Audio(seq = 0x23456789, captureNs = 0x0102030405060708L, frames = 2, pcm = pcm),
            ),
            "ping" to MediaFrames.encode(MediaFrame.Ping(id = 0x3456789a, t0 = 0x0102030405060708L)),
            "pong" to MediaFrames.encode(
                MediaFrame.Pong(
                    id = 0x3456789a,
                    t0 = 0x0102030405060708L,
                    t1 = 0x1112131415161718L,
                    t2 = 0x2122232425262728L,
                ),
            ),
            "keyframe-request" to MediaFrames.encode(MediaFrame.KeyframeRequest),
            "delivery" to MediaFrames.encode(
                MediaFrame.Delivery(DeliveryWire.Report(
                    codecEpoch = 0x0102030405060708L,
                    seq = 0x12345678,
                    endNs = 0x1112131415161718L,
                    spanNs = 500_000_000L,
                    bytes = 0x010203L,
                    frames = 4,
                    relativeP95Ns = 123_456L,
                    pending = 2,
                    lastFrameSeq = -0x01234568,
                )),
            ),
            "info" to MediaFrames.encode(MediaFrame.Info(level = 3, width = 1280, height = 576, bitrate = 3_000_000)),
            "bye" to MediaFrames.encode(MediaFrame.Bye(reason = 2)),
            "input" to InputWire.encode(
                InputPacket(
                    flags = 0,
                    key = 0x1122334455667788L,
                    attachId = 0x6162636465666768L,
                    seq = -0x01234568,
                    sampleNs = 0x4142434445464748L,
                    state = PadState(
                        buttons = 0x2949,
                        lx = -127,
                        ly = 63,
                        rx = -64,
                        ry = 126,
                        lt = 171,
                        rt = 205,
                        hat = 5,
                    ),
                ),
            ),
            "input-neutral" to InputWire.encode(
                InputPacket(
                    flags = 1,
                    key = 0x1122334455667788L,
                    attachId = 0x6162636465666768L,
                    seq = -0x01234567,
                    sampleNs = 0x4142434445464749L,
                    state = PadState(buttons = 0, lx = 0, ly = 0, rx = 0, ry = 0, lt = 0, rt = 0, hat = 8),
                ),
            ),
            "input-ack" to InputWire.encodeAck(
                InputAck(seq = -0x01234568, sampleNs = 0x4142434445464748L, appliedNs = 0x5152535455565758L, attachId = 0x6162636465666768L, flags = StreamWire.ACK_FLAG_WROTE),
            ),
            "audio-datagram" to InputWire.encodeAudio(
                key = 0x1122334455667788L,
                seq = 0x23456789,
                captureNs = 0x0102030405060708L,
                frames = 2,
                pcm = pcm,
            ),
        )
        assertEquals("Every encoding fixture needs a literal golden vector", golden.keys, actual.keys)
        for ((name, expectedHex) in golden) {
            assertArrayEquals("StreamWire v${StreamWire.VERSION}: $name", hexBytes(expectedHex), actual.getValue(name))
        }
    }

    @Test fun unknownMediaFrameIsDecodeOnly() {
        try {
            MediaFrames.encode(MediaFrame.Unknown(0x7e))
            fail("Unknown frames cannot be encoded; add reviewed vectors if encoding becomes supported")
        } catch (_: IllegalArgumentException) {
            // Unknown has no wire encoding in v1; every encodable MediaFrame is covered above.
        }
    }

    private fun hexBytes(hex: String): ByteArray {
        val compact = hex.filterNot { it.isWhitespace() }
        require(compact.length % 2 == 0) { "hex must contain complete bytes" }
        return compact.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
