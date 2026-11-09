package com.gamebex.client.stream.guest

import org.junit.Assert.assertEquals
import org.junit.Test

class DecoderFormatKeysTest {
    @Test fun debugHintsAcceptIntegerKeysAndSkipMalformedEntries() {
        assertEquals(mapOf("frame-rate" to 240, "operating-rate" to 120),
            decoderFormatKeys(true) { "frame-rate=240;operating-rate=120;bad;=4;latency=oops" })
        assertEquals(emptyMap<String, Int>(), decoderFormatKeys(false) { error("release must not read props") })
    }
}
