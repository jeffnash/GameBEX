package com.gamebex.client.together.proto

import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test

class SocketTransportTest {
    @Test(timeout = 5_000)
    fun cancelDuringConnect_closesSocketWithoutLinkLeak() = runBlocking {
        val job = launch(Dispatchers.IO) {
            SocketTransport().connect("192.0.2.1", 9, 60_000)
        }
        delay(200)
        val started = System.nanoTime()
        job.cancelAndJoin()
        val elapsedMs = (System.nanoTime() - started) / 1_000_000L
        assertTrue("cancel took ${elapsedMs}ms", elapsedMs < 2_000)
        assertTrue(job.isCancelled)
        delay(100)
        assertNoSocketLinkThreads()
    }

    @Test(timeout = 5_000)
    fun cancelledAccept_leavesTheListenerOpenForTheNextAccept() = runBlocking {
        val listener = SocketTransport().listen(0)
        try {
            val port = listener.port
            val job = launch(Dispatchers.IO) { listener.accept() }
            delay(100)
            val started = System.nanoTime()
            job.cancelAndJoin()
            val elapsedMs = (System.nanoTime() - started) / 1_000_000L
            assertTrue("cancel took ${elapsedMs}ms", elapsedMs < 2_000)
            assertTrue(job.isCancelled)
            delay(100)
            assertNoSocketLinkThreads()

            val next = async(Dispatchers.IO) { listener.accept() }
            Socket().use { client ->
                client.connect(InetSocketAddress("127.0.0.1", port), 1_000)
                val link = withTimeout(2_000) { next.await() }
                link.close()
            }
        } finally {
            listener.close()
        }
    }

    private fun assertNoSocketLinkThreads() {
        val leaked = Thread.getAllStackTraces().filter { (_, stack) ->
            stack.any { it.className == "com.gamebex.client.together.proto.SocketLink" }
        }
        assertTrue("SocketLink still running: ${leaked.keys.map { it.name }}", leaked.isEmpty())
    }
}
