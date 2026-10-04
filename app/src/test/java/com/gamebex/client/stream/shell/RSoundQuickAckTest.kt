package com.gamebex.client.stream.shell

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.Socket

class RSoundQuickAckTest {
    @Test fun defaultAdapterDoesNotTouchAndroidSocketApisOnPureJvm() {
        assertFalse(AndroidRSoundQuickAckFactory.isAndroidRuntime())
        val socket = object : Socket() {
            override fun getInputStream(): java.io.InputStream = throw AssertionError("must not inspect socket")
            override fun getOutputStream(): java.io.OutputStream = throw AssertionError("must not inspect socket")
        }
        socket.use { assertNull(AndroidRSoundQuickAckFactory.create(it)) }
    }
}
