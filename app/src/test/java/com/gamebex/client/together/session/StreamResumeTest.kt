package com.gamebex.client.together.session

import com.gamebex.client.data.local.GameEntity
import com.gamebex.client.stream.P2Prep
import com.gamebex.client.stream.StreamStarted
import com.gamebex.client.stream.fakes.FakeStreamHost
import com.gamebex.client.stream.fakes.FakeStreamPlayer2
import com.gamebex.client.together.ByeReason
import com.gamebex.client.together.GuestStep
import com.gamebex.client.together.HostStep
import com.gamebex.client.together.Link
import com.gamebex.client.together.LinkEvent
import com.gamebex.client.together.LinkMsg
import com.gamebex.client.together.LinkTransport
import com.gamebex.client.together.NearbyHost
import com.gamebex.client.together.OfferedGame
import com.gamebex.client.together.Readiness
import com.gamebex.client.together.Role
import com.gamebex.client.together.StreamResume
import com.gamebex.client.together.TOGETHER_PROTO
import com.gamebex.client.together.TogetherEmulators
import com.gamebex.client.together.TogetherMode
import com.gamebex.client.together.TogetherTiming
import com.gamebex.client.together.session.fakes.FakeDiscovery
import com.gamebex.client.together.session.fakes.FakeEmulators
import com.gamebex.client.together.session.fakes.FakeLibrary
import com.gamebex.client.together.session.fakes.FakeLinkTransport
import com.gamebex.client.together.session.fakes.FakePlatform
import com.gamebex.client.together.session.fakes.GuestTestEmulators
import com.gamebex.client.together.session.fakes.GuestTestLibrary
import com.gamebex.client.together.session.fakes.GuestTestPlatform
import com.gamebex.client.together.session.fakes.GuestTestProbes
import com.gamebex.client.together.session.fakes.LinkPair
import com.gamebex.client.together.session.fakes.engineTestScope
import com.gamebex.client.stream.proto.StreamWire
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamResumeTest {
    private class Rig(scope: TestScope, var options: TogetherControlOptions = TogetherControlOptions(true)) {
        val game = GameEntity(id = 1, title = "Mario Kart 64", folder = "n64", volumeId = "v", relPath = "mk64.z64", fileName = "mk64.z64", sizeBytes = 1, mtimeMs = 1, ext = "z64")
        val offered = OfferedGame("Mario Kart 64", "n64", "mk64.z64", 1)
        val hostTransport = FakeLinkTransport()
        val hostEmulators = FakeEmulators()
        val guestEmulators = GuestTestEmulators()
        val stream = FakeStreamHost(startValue = StreamStarted(ok = true, mediaPort = 55441, inputPort = 55442, width = 1280, height = 576, audio = true, rsound = true))
        val pad = FakeStreamPlayer2(P2Prep.Written)
        val discovery = FakeDiscovery()
        val guestPlatform = GuestTestPlatform()
        val pairs = mutableListOf<LinkPair>()
        val closed = mutableListOf<AtomicBoolean>()
        val guestLinks = mutableListOf<Link>()
        val guestEvents = mutableListOf<Channel<LinkEvent>>()
        var clockNs: (() -> Long)? = null
        val dialed = mutableListOf<Pair<String, Int>>()
        val logs = mutableListOf<String>()
        var hostReads = 0
        var guestReads = 0
        var hostReadiness = 0
        var guestReadiness = 0
        var beforeConnect: (suspend (Int) -> Unit)? = null
        var ack: (LinkMsg.StreamResumed) -> LinkMsg = { it }
        val engineScope = scope.engineTestScope()
        val dispatcher = engineScope.coroutineContext[ContinuationInterceptor] as CoroutineDispatcher
        val timing = TogetherTiming(helloMs = 100, byeMs = 1000, controlResumeHelloMs = 300)
        val host = HostEngine(
            hostTransport,
            object : TogetherEmulators by hostEmulators {
                override suspend fun readiness(mode: TogetherMode, role: Role, game: GameEntity, core: String?): Readiness {
                    hostReadiness += 1
                    return hostEmulators.readiness(mode, role, game, core)
                }
            },
            FakeLibrary(gameValue = game, offerValue = offered), discovery, FakePlatform(), timing, engineScope, dispatcher,
            stream, pad, { listOf(TogetherMode.STREAM) }, { null },
            controlOptions = { hostReads += 1; options }, controlLog = { logs += it },
            monotonicNs = { clockNs?.invoke() ?: (scope.testScheduler.currentTime * 1_000_000L) },
        )
        val transport = object : LinkTransport {
            override suspend fun listen(preferredPort: Int) = error("guest does not listen")
            override suspend fun connect(address: String, port: Int, timeoutMs: Long): Link {
                dialed += address to port
                beforeConnect?.invoke(pairs.size)
                val pair = LinkPair(guestPlatform.lanAddress()!!)
                pairs += pair
                val state = AtomicBoolean()
                closed += state
                hostTransport.listener.offer(object : Link by pair.host {
                    override suspend fun send(msg: LinkMsg) {
                        pair.host.send(if (msg is LinkMsg.StreamResumed) ack(msg) else msg)
                    }
                })
                val incoming = Channel<LinkEvent>(Channel.UNLIMITED)
                guestEvents += incoming
                engineScope.launch {
                    for (event in pair.guest.events) incoming.send(event)
                }
                return object : Link by pair.guest {
                    override val events = incoming
                    override fun close() {
                        state.set(true)
                        pair.guest.close()
                    }
                }.also { guestLinks += it }
            }
        }
        val guest = GuestEngine(
            transport, GuestTestProbes(),
            object : TogetherEmulators by guestEmulators {
                override suspend fun readiness(mode: TogetherMode, role: Role, game: GameEntity, core: String?): Readiness {
                    guestReadiness += 1
                    return guestEmulators.readiness(mode, role, game, core)
                }
            },
            GuestTestLibrary(game, offered), guestPlatform, timing, engineScope, dispatcher,
            logSink = { logs += it }, controlOptions = { guestReads += 1; options }, controlLog = { logs += it },
        )
        val ready get() = pairs.first().host.sent.filterIsInstance<LinkMsg.StreamReady>().single()
    }

    private fun TestScope.start(rig: Rig) {
        rig.host.hostGame(1)
        runCurrent()
        val ad = rig.discovery.advertisements.single()
        rig.guest.join(NearbyHost(ad.deviceId, ad.sessionId, "Host", ad.gameTitle, ad.folder, ad.mode, "10.42.1.20", ad.port))
        runCurrent()
        assertEquals(GuestStep.READY, rig.guest.view.value?.step)
        rig.host.start()
        runCurrent()
        assertEquals(HostStep.IN_GAME, rig.host.view.value?.step)
        assertEquals(GuestStep.IN_GAME, rig.guest.view.value?.step)
    }

    private fun TestScope.cut(rig: Rig) {
        rig.guestLinks.last().close()
        runCurrent()
        assertTrue(rig.host.view.value?.linkLost == true)
        assertTrue(rig.guest.view.value?.linkLost == true)
    }

    private fun TestScope.retry(ms: Long = 250) {
        advanceTimeBy(ms)
        runCurrent()
    }

    private fun assertNoRelaunch(rig: Rig) {
        assertEquals(1, rig.hostEmulators.launches.size)
        assertEquals(1, rig.guestEmulators.launches.size)
        assertEquals(1, rig.stream.configs.size)
        assertEquals(1, rig.stream.gameLaunches)
        assertEquals(1, rig.stream.captureAttaches)
        assertEquals(2, rig.pad.prepared.size)
        assertEquals(1, rig.hostReadiness)
        assertEquals(1, rig.guestReadiness)
        assertTrue(rig.guestEmulators.fetchCalls.isEmpty())
        assertTrue(rig.guestEmulators.installCalls.isEmpty())
    }

    @Test
    fun resumeInGameDoesNotPrepareOrLaunchAndPreservesAudioOrigin() = runTest {
        val rig = Rig(this)
        start(rig)
        val endpoint = rig.guestEmulators.launches.single().stream
        assertEquals("bridge-arrival", endpoint?.audioTimestampOrigin)
        cut(rig)
        retry()
        assertFalse(rig.host.view.value!!.linkLost)
        assertFalse(rig.guest.view.value!!.linkLost)
        val request = rig.pairs.last().guest.sent.filterIsInstance<LinkMsg.Hello>().single().streamResume!!
        assertEquals(StreamResume(rig.ready.sessionId, rig.ready.round, rig.ready.token, request.attempt), request)
        assertEquals(endpoint, rig.guestEmulators.launches.single().stream)
        assertEquals("bridge-arrival", rig.pairs.last().host.sent.filterIsInstance<LinkMsg.StreamResumed>().single().audioTimestampOrigin)
        assertTrue(rig.pairs.last().host.sent.none { it is LinkMsg.Launch || it is LinkMsg.StreamReady || it is LinkMsg.Offer })
        assertEquals(listOf("10.42.1.20" to 55440, "10.42.1.20" to 55440), rig.dialed)
        assertNoRelaunch(rig)
        assertTrue(rig.logs.none { it.contains(rig.ready.token) || it.contains("streamResume") || it.contains("guest-device") })
        rig.guest.end()
        runCurrent()
        assertEquals(GuestStep.ENDED, rig.guest.view.value?.step)
        assertEquals(1, rig.stream.stops)
        assertTrue(rig.closed.all { it.get() })
    }

    @Test
    fun rejectsForeignIdentityAndLiveOwnerRaceWithoutEvictingMedia() = runTest {
        val rig = Rig(this)
        start(rig)
        val ready = rig.ready
        val valid = LinkMsg.Hello(TOGETHER_PROTO, Role.GUEST, rig.guestPlatform.deviceId, "Guest", "2", streamProto = StreamWire.VERSION,
            streamResume = StreamResume(ready.sessionId, ready.round, ready.token, "00000000-0000-4000-8000-000000000001"))
        val busy = rig.hostTransport.pair(rig.guestPlatform.lanAddress()!!)
        busy.guest.send(valid)
        runCurrent()
        assertEquals(ByeReason.FULL, busy.host.sent.filterIsInstance<LinkMsg.Bye>().single().reason)
        val wrongRole = rig.hostTransport.pair(rig.guestPlatform.lanAddress()!!)
        wrongRole.guest.send(valid.copy(role = Role.HOST))
        runCurrent()
        assertEquals(ByeReason.ERROR, wrongRole.host.sent.filterIsInstance<LinkMsg.Bye>().single().reason)
        cut(rig)
        val invalid = listOf(
            valid.copy(deviceId = "foreign"), valid.copy(role = Role.HOST), valid.copy(proto = TOGETHER_PROTO + 1), valid.copy(streamProto = StreamWire.VERSION + 1),
            valid.copy(streamResume = valid.streamResume!!.copy(token = "f".repeat(32))),
            valid.copy(streamResume = valid.streamResume!!.copy(round = 2)),
            valid.copy(streamResume = valid.streamResume!!.copy(sessionId = "stale")),
            valid.copy(streamResume = valid.streamResume!!.copy(attempt = "invalid")),
        )
        for (hello in invalid) {
            val pair = rig.hostTransport.pair(rig.guestPlatform.lanAddress()!!)
            pair.guest.send(hello)
            runCurrent()
            assertEquals(ByeReason.ERROR, pair.host.sent.filterIsInstance<LinkMsg.Bye>().single().reason)
        }
        val foreignAddress = rig.hostTransport.pair("10.42.1.99")
        foreignAddress.guest.send(valid)
        runCurrent()
        assertEquals(ByeReason.ERROR, foreignAddress.host.sent.filterIsInstance<LinkMsg.Bye>().single().reason)
        retry()
        assertFalse(rig.guest.view.value!!.linkLost)
        assertNoRelaunch(rig)
        assertEquals(0, rig.stream.stops)
    }

    @Test
    fun fullDuringOldClosedRaceRetriesAndMismatchedAckCannotInstall() = runTest {
        val rig = Rig(this)
        start(rig)
        var acknowledgements = 0
        rig.ack = { message ->
            when (++acknowledgements) {
                1 -> LinkMsg.Bye(ByeReason.FULL)
                2 -> message.copy(attempt = "00000000-0000-4000-8000-000000000099")
                3 -> message.copy(sessionId = "stale")
                4 -> message.copy(round = message.round + 1)
                5 -> message.copy(audioTimestampOrigin = "capture")
                else -> message
            }
        }
        cut(rig)
        retry()
        assertTrue(rig.guest.view.value!!.linkLost)
        for (wait in listOf(500L, 1000L, 2000L, 2000L)) {
            retry(wait)
            assertTrue(rig.guest.view.value!!.linkLost)
        }
        retry(2000)
        assertFalse(rig.guest.view.value!!.linkLost)
        assertEquals(7, rig.pairs.size)
        assertTrue(rig.closed.drop(1).dropLast(1).all { it.get() })
        assertNoRelaunch(rig)
    }

    @Test
    fun explicitLeaveAndLateConnectCannotResurrectSession() = runTest {
        val rig = Rig(this)
        start(rig)
        val gate = CompletableDeferred<Unit>()
        rig.beforeConnect = { if (it > 0) withContext(NonCancellable) { gate.await() } }
        cut(rig)
        retry()
        rig.guest.end()
        runCurrent()
        assertEquals(GuestStep.ENDED, rig.guest.view.value?.step)
        gate.complete(Unit)
        runCurrent()
        retry(1000)
        assertTrue(rig.closed.all { it.get() })
        assertEquals(GuestStep.ENDED, rig.guest.view.value?.step)
        assertEquals(1, rig.guestEmulators.launches.size)
    }

    @Test
    fun cancelDuringHandshakeClosesCandidateAndNeverRetriesAfterLeave() = runTest {
        val rig = Rig(this)
        start(rig)
        rig.ack = { LinkMsg.Ping(1) }
        cut(rig)
        retry()
        assertFalse(rig.closed.last().get())
        rig.guest.end()
        runCurrent()
        assertTrue(rig.closed.last().get())
        retry(35000)
        assertEquals(2, rig.pairs.size)
        assertEquals(GuestStep.ENDED, rig.guest.view.value?.step)
    }

    @Test
    fun resumeDeadlineDoesNotStopMediaOrRetryForever() = runTest {
        val rig = Rig(this)
        start(rig)
        rig.ack = { LinkMsg.Bye(ByeReason.ERROR) }
        cut(rig)
        retry(31000)
        val attempts = rig.pairs.size
        retry(31000)
        assertEquals(attempts, rig.pairs.size)
        assertTrue(rig.guest.view.value!!.linkLost)
        assertEquals(GuestStep.IN_GAME, rig.guest.view.value?.step)
        assertEquals(0, rig.stream.stops)
        assertTrue(rig.closed.all { it.get() })
        assertTrue(rig.logs.any { it.contains("event=control_resume_failed") })
        assertNoRelaunch(rig)
    }

    @Test
    fun oldClosedAndByeCannotCloseResumedLinkOrExpireItsGrace() = runTest {
        val rig = Rig(this)
        start(rig)
        val original = rig.pairs.single()
        cut(rig)
        retry()
        rig.guestEvents.first().send(LinkEvent.Message(LinkMsg.Bye(ByeReason.LEFT)))
        rig.guestEvents.first().send(LinkEvent.Closed(null))
        original.host.close()
        runCurrent()
        assertEquals(GuestStep.IN_GAME, rig.guest.view.value?.step)
        assertFalse(rig.guest.view.value!!.linkLost)
        retry(16000)
        assertFalse(rig.host.view.value!!.linkLost)
        assertFalse(rig.guest.view.value!!.linkLost)
        assertEquals(0, rig.stream.stops)
        assertNoRelaunch(rig)
    }

    @Test
    fun expiredGraceRejectsSameIdentityWhileMediaRemainsConnected() = runTest {
        val rig = Rig(this)
        start(rig)
        val ready = rig.ready
        rig.beforeConnect = { if (it > 0) throw java.io.IOException("control unavailable") }
        cut(rig)
        retry(16000)
        val candidate = rig.hostTransport.pair(rig.guestPlatform.lanAddress()!!)
        candidate.guest.send(LinkMsg.Hello(TOGETHER_PROTO, Role.GUEST, rig.guestPlatform.deviceId, "Guest", "2",
            streamProto = StreamWire.VERSION, streamResume = StreamResume(ready.sessionId, ready.round, ready.token,
                "00000000-0000-4000-8000-000000000001")))
        runCurrent()
        assertEquals(ByeReason.ERROR, candidate.host.sent.filterIsInstance<LinkMsg.Bye>().single().reason)
        assertTrue(rig.host.view.value!!.linkLost)
        assertEquals(0, rig.stream.stops)
        assertNoRelaunch(rig)
    }

    private suspend fun TestScope.queueResumeBehindBlockedActor(rig: Rig): Pair<CompletableDeferred<Unit>, LinkPair> {
        rig.beforeConnect = { if (it > 0) throw java.io.IOException("control unavailable") }
        cut(rig)
        retry(14900)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val invalid = LinkPair(rig.guestPlatform.lanAddress()!!)
        rig.hostTransport.listener.offer(object : Link by invalid.host {
            override suspend fun send(msg: LinkMsg) {
                if (msg is LinkMsg.Bye) {
                    entered.complete(Unit)
                    release.await()
                }
                invalid.host.send(msg)
            }
        })
        invalid.guest.send(LinkMsg.Hello(TOGETHER_PROTO, Role.GUEST, "foreign", "Guest", "2", streamProto = StreamWire.VERSION,
            streamResume = StreamResume(rig.ready.sessionId, rig.ready.round, rig.ready.token,
                "00000000-0000-4000-8000-000000000001")))
        runCurrent()
        assertTrue(entered.isCompleted)
        val candidate = rig.hostTransport.pair(rig.guestPlatform.lanAddress()!!)
        candidate.guest.send(LinkMsg.Hello(TOGETHER_PROTO, Role.GUEST, rig.guestPlatform.deviceId, "Guest", "2",
            streamProto = StreamWire.VERSION, streamResume = StreamResume(rig.ready.sessionId, rig.ready.round, rig.ready.token,
                "00000000-0000-4000-8000-000000000002")))
        runCurrent()
        retry(100)
        return release to candidate
    }

    @Test
    fun actorBusyPastGraceRejectsAlreadyQueuedValidResume() = runTest {
        val rig = Rig(this)
        start(rig)
        val (release, candidate) = queueResumeBehindBlockedActor(rig)
        release.complete(Unit)
        runCurrent()
        assertEquals(ByeReason.ERROR, candidate.host.sent.filterIsInstance<LinkMsg.Bye>().single().reason)
        assertTrue(candidate.host.sent.none { it is LinkMsg.StreamResumed })
        assertTrue(rig.host.view.value!!.linkLost)
        assertEquals(0, rig.stream.stops)
        assertNoRelaunch(rig)
    }

    @Test
    fun queuedOldGraceCannotExpireAcceptedReplacement() = runTest {
        val rig = Rig(this)
        start(rig)
        val (release, candidate) = queueResumeBehindBlockedActor(rig)
        rig.clockNs = { 14900L * 1_000_000L }
        release.complete(Unit)
        runCurrent()
        assertEquals(1, candidate.host.sent.filterIsInstance<LinkMsg.StreamResumed>().size)
        assertFalse(rig.host.view.value!!.linkLost)
        assertEquals(null, rig.host.view.value?.message)
        assertEquals(0, rig.stream.stops)
        assertNoRelaunch(rig)
    }

    @Test
    fun leaveConnectAndClosedInterleavingsNeverResurrect() = runTest {
        repeat(100) { iteration ->
            val rig = Rig(this)
            start(rig)
            cut(rig)
            if (iteration % 2 == 0) retry() else retry(249)
            rig.guest.end()
            runCurrent()
            retry(1000)
            assertEquals(GuestStep.ENDED, rig.guest.view.value?.step)
            assertTrue(rig.closed.all { it.get() })
            assertEquals(1, rig.guestEmulators.launches.size)
            rig.host.end()
            runCurrent()
            rig.engineScope.close()
        }
    }

    @Test
    fun switchIsSampledOncePerSessionAndOffLeavesPlaybackAlone() = runTest {
        val rig = Rig(this, TogetherControlOptions())
        start(rig)
        rig.options = TogetherControlOptions(true)
        cut(rig)
        retry(31000)
        assertEquals(1, rig.pairs.size)
        assertEquals(1, rig.hostReads)
        assertEquals(1, rig.guestReads)
        assertEquals(0, rig.stream.stops)
        assertTrue(rig.guest.view.value!!.linkLost)
        assertNoRelaunch(rig)
    }

    @Test
    fun repeatedControlCutsUseSameStreamAndDebugOptionsAreNotResampled() = runTest {
        val rig = Rig(this, TogetherControlOptions(true, 1))
        start(rig)
        repeat(5) {
            retry(1000)
            retry(250)
            assertFalse(rig.host.view.value!!.linkLost)
            assertFalse(rig.guest.view.value!!.linkLost)
        }
        assertEquals(6, rig.pairs.size)
        assertEquals(1, rig.hostReads)
        assertEquals(1, rig.guestReads)
        assertNoRelaunch(rig)
    }
}
