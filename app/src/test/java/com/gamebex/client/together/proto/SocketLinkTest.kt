package com.gamebex.client.together.proto

import com.gamebex.client.together.ByeReason
import com.gamebex.client.together.Link
import com.gamebex.client.together.LinkEvent
import com.gamebex.client.together.LinkMsg
import com.gamebex.client.together.OfferedGame
import com.gamebex.client.together.Role
import com.gamebex.client.together.TOGETHER_PROTO
import com.gamebex.client.together.TogetherMode
import com.gamebex.client.together.TogetherTiming
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SocketLinkTest {
    @Test fun messagesArriveInOrder() = runBlocking {
        val timing = TogetherTiming(pingMs = 20, silenceMs = 500)
        val (guest, host) = connected(timing)
        try {
            guest.send(LinkMsg.Hello(TOGETHER_PROTO, Role.GUEST, "guest", "Guest", "1.0"))
            guest.send(LinkMsg.Offer("s", TogetherMode.GB_LINK, OfferedGame("g", "gb", "g.gb", 1), null, "127.0.0.1"))
            guest.send(LinkMsg.Bye(ByeReason.LEFT))
            assertEquals(LinkMsg.Hello(TOGETHER_PROTO, Role.GUEST, "guest", "Guest", "1.0"), receiveMessage(host))
            assertEquals(LinkMsg.Offer("s", TogetherMode.GB_LINK, OfferedGame("g", "gb", "g.gb", 1), null, "127.0.0.1"), receiveMessage(host))
            assertEquals(LinkMsg.Bye(ByeReason.LEFT), receiveMessage(host))
        } finally {
            guest.close(); host.close()
        }
    }

    @Test fun pingAndPongAreNotDelivered() = runBlocking {
        val timing = TogetherTiming(pingMs = 20, silenceMs = 500)
        val (left, right) = connected(timing)
        try {
            assertNull(withTimeoutOrNull(75) { left.events.receive() })
            assertNull(withTimeoutOrNull(75) { right.events.receive() })
        } finally {
            left.close(); right.close()
        }
    }

    @Test fun silentPeerTimesOut() = runBlocking {
        val server = ServerSocket(0)
        val rawPeer = async(Dispatchers.IO) { server.accept() }
        val link = SocketTransport(TogetherTiming(pingMs = 15, silenceMs = 60)).connect(
            "127.0.0.1", server.localPort, 1_000
        )
        val socket = rawPeer.await()
        try {
            val event = withTimeout(1_000) { link.events.receive() }
            assertTrue(event is LinkEvent.Closed)
            assertTrue((event as LinkEvent.Closed).cause is com.gamebex.client.together.LinkTimeoutException)
        } finally {
            link.close(); socket.close(); server.close()
        }
    }

    @Test fun peerStopsReadingMidBlobEndsWithLinkTimeout() = runBlocking {
        val source = kotlin.io.path.createTempFile("socket-link-stall", ".bin").toFile()
        source.writeBytes(ByteArray(8 * 1024 * 1024))
        val server = ServerSocket(0)
        server.receiveBufferSize = 4 * 1024
        val rawPeer = async(Dispatchers.IO) { server.accept() }
        val link = SocketTransport(TogetherTiming(pingMs = 50, silenceMs = 400)).connect(
            "127.0.0.1", server.localPort, 1_000,
        )
        val socket = rawPeer.await()
        socket.receiveBufferSize = 4 * 1024
        val sendJob = launch(Dispatchers.IO) {
            try {
                link.sendBlob(1, source)
            } catch (_: java.io.IOException) {
            }
        }
        try {
            val event = withTimeout(2_000) {
                var closed: LinkEvent.Closed? = null
                while (closed == null) {
                    when (val received = link.events.receive()) {
                        is LinkEvent.Closed -> closed = received
                        else -> Unit
                    }
                }
                checkNotNull(closed)
            }
            assertTrue(event.cause is com.gamebex.client.together.LinkTimeoutException)
        } finally {
            link.close()
            socket.close()
            server.close()
            sendJob.cancel()
            source.delete()
        }
    }

    @Test fun threeMiBBlobArrivesIntactThroughBlobSink() = runBlocking {
        val (sender, receiver) = connected(TogetherTiming(pingMs = 20, silenceMs = 5_000))
        val source = kotlin.io.path.createTempFile("socket-link", ".bin").toFile()
        val data = ByteArray(3 * 1024 * 1024).also { Random(7).nextBytes(it) }
        source.writeBytes(data)
        val destination = kotlin.io.path.createTempFile("socket-link-dest", ".bin").toFile().also { it.delete() }
        val expectedHash = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
        val sink = BlobSink(destination, com.gamebex.client.together.CoreBuild("test", expectedHash, data.size.toLong()))
        try {
            sender.sendBlob(4, source)
            var received = 0
            while (received < data.size) {
                val event = withTimeout(2_000) { receiver.events.receive() }
                assertTrue(event is LinkEvent.Blob)
                val blob = event as LinkEvent.Blob
                assertEquals(4, blob.blobId)
                sink.write(blob.bytes)
                received += blob.bytes.size
            }
            assertTrue(sink.finish())
            assertArrayEquals(data, destination.readBytes())
        } finally {
            sender.close(); receiver.close(); source.delete(); destination.delete()
        }
    }

    @Test fun closingOneSideClosesTheOtherWithNullCause() = runBlocking {
        val (left, right) = connected(TogetherTiming(pingMs = 20, silenceMs = 500))
        try {
            left.close()
            val event = withTimeout(1_000) { right.events.receive() }
            assertEquals(LinkEvent.Closed(null), event)
        } finally {
            right.close()
        }
    }

    @Test fun closingPreservesQueuedMessagesBeforeClosed() = runBlocking {
        val (sender, receiver) = connected(TogetherTiming(pingMs = 10_000, silenceMs = 20_000))
        try {
            val count = 100
            repeat(count) { index ->
                sender.send(LinkMsg.Hello(index, Role.GUEST, "guest", "Guest", "1.0"))
            }
            sender.close()

            val received = mutableListOf<Int>()
            var closed = false
            withTimeout(1_000) {
                for (event in receiver.events) {
                    when (event) {
                        is LinkEvent.Message -> {
                            assertTrue(event.msg is LinkMsg.Hello)
                            received += (event.msg as LinkMsg.Hello).proto
                        }
                        is LinkEvent.Closed -> {
                            closed = true
                            break
                        }
                        is LinkEvent.Blob -> throw AssertionError("unexpected blob")
                    }
                }
            }
            assertEquals(count, received.size)
            assertEquals((0 until count).toList(), received)
            assertTrue(closed)
        } finally {
            sender.close()
            receiver.close()
        }
    }

    @Test fun occupiedPreferredPortFallsBackToEphemeral() = runBlocking {
        val occupied = ServerSocket(0)
        val listener = try {
            SocketTransport().listen(occupied.localPort)
        } finally {
            occupied.close()
        }
        try {
            assertTrue(listener.port != occupied.localPort)
            assertTrue(listener.port > 0)
        } finally {
            listener.close()
        }
    }

    private suspend fun connected(timing: TogetherTiming): Pair<Link, Link> = coroutineScope {
        val transport = SocketTransport(timing)
        val listener = transport.listen(0)
        val client = async(Dispatchers.IO) { transport.connect("127.0.0.1", listener.port, 1_000) }
        val host = listener.accept()
        listener.close()
        client.await() to host
    }

    private suspend fun receiveMessage(link: Link): LinkMsg {
        while (true) {
            when (val event = withTimeout(1_000) { link.events.receive() }) {
                is LinkEvent.Message -> return event.msg
                is LinkEvent.Closed -> throw AssertionError("link closed: ${event.cause}")
                is LinkEvent.Blob -> Unit
            }
        }
    }
}
