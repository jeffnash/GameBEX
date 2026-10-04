package com.gamebex.client.together

import com.gamebex.client.stream.proto.StreamWire
import com.gamebex.client.together.proto.LinkJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TogetherJsonTest {
    @Test
    fun resumeUsesTogetherTwoWithoutCapabilityNegotiation() {
        assertEquals(2, TOGETHER_PROTO)
        val request = StreamResume("room", 3, "00112233445566778899aabbccddeeff", "00000000-0000-4000-8000-000000000001")
        val hello = LinkMsg.Hello(TOGETHER_PROTO, Role.GUEST, "device", "Sam", "2", streamProto = StreamWire.VERSION, streamResume = request)
        val encoded = LinkJson.encode(hello)
        assertEquals(hello, LinkJson.decode(encoded))
        assertTrue(encoded.contains("\"proto\":2"))
        assertTrue(encoded.contains("\"streamResume\""))
        assertFalse(encoded.contains("Supported"))
    }

    @Test
    fun resumedAndReadyPreserveBridgeArrivalOrigin() {
        val resumed = LinkMsg.StreamResumed("room", 3, "00000000-0000-4000-8000-000000000001", "bridge-arrival")
        assertEquals(resumed, LinkJson.decode(LinkJson.encode(resumed)))
        assertTrue(LinkJson.encode(resumed).contains("\"t\":\"stream_resumed\""))
        val ready = LinkMsg.StreamReady("room", 3, StreamWire.VERSION, "00112233445566778899aabbccddeeff", 55441, 55442, 1280, 576, true, "bridge-arrival")
        assertEquals(ready, LinkJson.decode(LinkJson.encode(ready)))
    }

    @Test
    fun initialHelloUsesSameVersionAndNoResumeRequest() {
        val hello = LinkMsg.Hello(TOGETHER_PROTO, Role.HOST, "device", "Host", "2", streamProto = StreamWire.VERSION)
        assertEquals(hello, LinkJson.decode(LinkJson.encode(hello)))
        assertEquals(null, hello.streamResume)
        assertFalse(LinkJson.encode(hello).contains("streamResume"))
    }
}
