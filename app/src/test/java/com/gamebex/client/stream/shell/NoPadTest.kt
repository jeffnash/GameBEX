package com.gamebex.client.stream.shell

import com.gamebex.client.stream.proto.PadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoPadTest {
    @Test fun reportsNoneBackend() {
        assertEquals("none", NoPad.backend)
    }

    @Test fun acceptsAndDropsReports() {
        assertTrue(NoPad.write(PadState.NEUTRAL))
        assertTrue(NoPad.write(PadState(buttons = PadState.A)))
    }

    @Test fun closeIsIdempotent() {
        NoPad.close()
        NoPad.close()
    }
}
