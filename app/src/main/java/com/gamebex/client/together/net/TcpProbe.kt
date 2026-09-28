package com.gamebex.client.together.net

import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object TcpProbe {
    suspend fun tcpOpen(address: String, port: Int, timeoutMs: Long = 800L): Boolean =
        withContext(Dispatchers.IO) {
            try {
                Socket().use { socket ->
                    socket.connect(
                        InetSocketAddress(address, port),
                        timeoutMs.coerceAtLeast(1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                    )
                    true
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
        }
}
