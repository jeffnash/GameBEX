package com.gamebex.client.together.net

import java.net.ServerSocket
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TcpProbeTest {
    @Test fun openLoopbackServerIsReachable() = runBlocking {
        ServerSocket(0).use { server ->
            assertTrue(TcpProbe.tcpOpen("127.0.0.1", server.localPort, 500))
        }
    }

    @Test fun closedPortReturnsFalseWithinTimeout() = runBlocking {
        ServerSocket(0).use { server ->
            val port = server.localPort
            server.close()
            assertFalse(TcpProbe.tcpOpen("127.0.0.1", port, 100))
        }
    }
}
