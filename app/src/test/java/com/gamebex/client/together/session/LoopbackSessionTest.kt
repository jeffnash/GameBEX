package com.gamebex.client.together.session

import com.gamebex.client.together.GuestStep
import com.gamebex.client.together.HostStep
import com.gamebex.client.together.Link
import com.gamebex.client.together.LinkEvent
import com.gamebex.client.together.LinkListener
import com.gamebex.client.together.LinkMsg
import com.gamebex.client.together.LinkTransport
import com.gamebex.client.together.NearbyHost
import com.gamebex.client.together.TogetherTiming
import com.gamebex.client.together.proto.SocketTransport
import com.gamebex.client.together.session.fakes.EngineTestProbes
import com.gamebex.client.together.session.fakes.FakeDiscovery
import com.gamebex.client.together.session.fakes.FakeEmulators
import com.gamebex.client.together.session.fakes.FakeLibrary
import com.gamebex.client.together.session.fakes.FakePlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.ChannelIterator
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.util.Collections

class LoopbackSessionTest {
    @Test
    fun hostAndGuestReachInGameOverRealLoopbackSocket() = runBlocking {
        val timing = TogetherTiming(
            helloMs = 2_000L,
            offerMs = 2_000L,
            launchWaitMs = 250L,
            watchIntervalMs = 25L,
            countdownS = 0,
        )
        val trace = Trace()
        val hostScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val guestScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val hostDiscovery = FakeDiscovery()
        val hostEmulators = FakeEmulators()
        val guestEmulators = FakeEmulators()
        val host = TogetherEngine(
            transport = RecordingTransport("host", SocketTransport(timing), trace),
            probes = EngineTestProbes(),
            emulators = hostEmulators,
            library = FakeLibrary(),
            discovery = hostDiscovery,
            platform = FakePlatform(name = "Host", address = "127.0.0.1"),
            timing = timing,
            scope = hostScope,
        )
        val guest = TogetherEngine(
            transport = RecordingTransport("guest", SocketTransport(timing), trace),
            probes = EngineTestProbes(),
            emulators = guestEmulators,
            library = FakeLibrary(),
            discovery = FakeDiscovery(),
            platform = FakePlatform(name = "Guest", address = "127.0.0.1"),
            timing = timing,
            scope = guestScope,
        )

        val states = { "host=${host.host.value} guest=${guest.guest.value} trace=$trace" }

        try {
            host.hostGame(1L)
            awaitCondition("host waiting", states) { host.host.value?.step == HostStep.WAITING }
            val ad = hostDiscovery.advertisements.single()
            guest.join(
                NearbyHost(
                    deviceId = ad.deviceId,
                    sessionId = ad.sessionId,
                    name = ad.name,
                    gameTitle = ad.gameTitle,
                    folder = ad.folder,
                    mode = ad.mode,
                    address = "127.0.0.1",
                    port = ad.port,
                ),
            )
            awaitCondition("host can start", states) { host.host.value?.canStart == true }
            host.hostStart()
            awaitCondition("both in game", states) {
                host.host.value?.step == HostStep.IN_GAME &&
                    guest.guest.value?.step == GuestStep.IN_GAME
            }

            assertEquals(HostStep.IN_GAME, host.host.value?.step)
            assertEquals(GuestStep.IN_GAME, guest.guest.value?.step)
            assertNotNull(hostEmulators.launches.singleOrNull())
            assertNotNull(guestEmulators.launches.singleOrNull())
        } finally {
            guest.end()
            host.end()
            runCatching {
                awaitCondition("both ended", states, 2_000L) {
                    host.host.value?.step == HostStep.ENDED && guest.guest.value?.step == GuestStep.ENDED
                }
            }
            hostScope.cancel()
            guestScope.cancel()
        }
    }

    /** Fails with both engines' views, so a stall shows which step each side reached. */
    private suspend fun awaitCondition(
        what: String,
        states: () -> String,
        timeoutMs: Long = 5_000L,
        condition: () -> Boolean,
    ) {
        withTimeoutOrNull(timeoutMs) {
            while (!condition()) delay(10L)
        } ?: throw AssertionError("timed out after $timeoutMs ms waiting for $what: ${states()}")
    }

    /** Every link message both engines send and receive, in order, for the failure report. */
    private class Trace {
        private val start = System.nanoTime()
        private val lines = Collections.synchronizedList(mutableListOf<String>())

        fun note(line: String) {
            lines += "${(System.nanoTime() - start) / 1_000_000}ms $line"
        }

        override fun toString(): String = synchronized(lines) { lines.joinToString(" | ") }
    }

    private class RecordingTransport(
        private val side: String,
        private val inner: LinkTransport,
        private val trace: Trace,
    ) : LinkTransport {
        override suspend fun listen(preferredPort: Int): LinkListener {
            val listener = inner.listen(preferredPort)
            trace.note("$side listen ${listener.port}")
            return object : LinkListener by listener {
                override suspend fun accept(): Link = record(listener.accept(), "accepted")
            }
        }

        override suspend fun connect(address: String, port: Int, timeoutMs: Long): Link =
            record(inner.connect(address, port, timeoutMs), "connected $port")

        private fun record(link: Link, how: String): Link {
            trace.note("$side $how")
            val events = link.events
            return object : Link by link {
                override val events: ReceiveChannel<LinkEvent> = object : ReceiveChannel<LinkEvent> by events {
                    override fun iterator(): ChannelIterator<LinkEvent> {
                        val inner = events.iterator()
                        return object : ChannelIterator<LinkEvent> {
                            override suspend fun hasNext(): Boolean = inner.hasNext()
                            override fun next(): LinkEvent = inner.next().also { trace.note("$side <- ${describe(it)}") }
                        }
                    }
                }

                override suspend fun send(msg: LinkMsg) {
                    trace.note("$side -> ${describe(msg)}")
                    link.send(msg)
                }
            }
        }

        private fun describe(event: LinkEvent): String = when (event) {
            is LinkEvent.Message -> describe(event.msg)
            is LinkEvent.Blob -> "Blob(${event.blobId})"
            is LinkEvent.Closed -> "Closed(${event.cause})"
        }

        private fun describe(msg: LinkMsg): String = when (msg) {
            is LinkMsg.GuestState -> "GuestState(${msg.phase}, session=${msg.sessionId})"
            is LinkMsg.Offer -> "Offer(session=${msg.sessionId})"
            else -> msg::class.simpleName.orEmpty()
        }
    }
}
