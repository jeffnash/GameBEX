package com.gamebex.client.stream.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class RSoundBridgeTest {
    @Test fun reattachmentReusesListenerAndDetachedRawKeepsPlaying() {
        val source = FakeSource()
        val factories = AtomicInteger()
        val probes = AtomicInteger()
        val bridge = RSoundBridge({ factories.incrementAndGet(); source }, { probes.incrementAndGet(); true }, pollMs = 5)
        val firstChunks = CopyOnWriteArrayList<Int>()
        val nextChunks = CopyOnWriteArrayList<Int>()
        try {
            val first = bridge.acquire()
            assertTrue(first.start(AudioSink { firstChunks.add(it.seq) }))
            first.gameLaunched()
            await { probes.get() > 0 }
            source.mode = "rsound"
            source.emit(1)
            first.stop()
            source.emit(2)
            assertTrue(source.running)
            assertTrue(bridge.retained)
            assertEquals(listOf(1), firstChunks.toList())
            assertEquals(0, source.stops.get())
            val next = bridge.acquire()
            assertTrue(next.start(AudioSink { nextChunks.add(it.seq) }))
            source.emit(3)
            assertEquals(listOf(3), nextChunks.toList())
            assertEquals(1, source.starts.get())
            assertEquals(1, factories.get())
            assertTrue(next.rsoundReady)
            assertEquals("rsound", next.audioMode)
            assertTrue(source.flushes.get() >= 3)
        } finally {
            bridge.close()
        }
    }

    @Test fun staleLeaseStopAndLaunchCannotDetachNewLease() {
        val source = FakeSource()
        val bridge = bridge(source)
        val chunks = CopyOnWriteArrayList<Int>()
        try {
            val first = bridge.acquire()
            assertTrue(first.start(AudioSink {}))
            first.gameLaunched()
            val next = bridge.acquire()
            assertTrue(next.start(AudioSink { chunks.add(it.seq) }))
            val flushes = source.flushes.get()
            first.stop()
            first.gameLaunched()
            first.flushGuestAudio()
            source.emit(4)
            assertEquals(listOf(4), chunks.toList())
            assertEquals(flushes, source.flushes.get())
            assertEquals(1, source.launches.get())
            assertEquals(0, source.stops.get())
            assertFalse(first.start(AudioSink {}))
        } finally {
            bridge.close()
        }
    }

    @Test fun processExitClosesDetachedBridgeAndNotifiesIdleOnce() {
        val source = FakeSource()
        val alive = AtomicBoolean(true)
        val probes = AtomicInteger()
        val idle = AtomicInteger()
        val bridge = RSoundBridge({ source }, { probes.incrementAndGet(); alive.get() }, { idle.incrementAndGet() }, 5, 60, 200)
        try {
            val lease = bridge.acquire()
            assertTrue(lease.start(AudioSink {}))
            lease.gameLaunched()
            await { probes.get() > 0 }
            lease.stop()
            assertEquals(0, idle.get())
            alive.set(false)
            await { idle.get() == 1 }
            assertEquals(1, source.stops.get())
            assertFalse(bridge.retained)
            assertEquals("emulator-exit", bridge.failureKind)
            assertEquals("emulator-exit", lease.audioFailureKind)
            lease.stop()
            assertEquals(1, idle.get())
        } finally {
            bridge.close()
        }
        assertEquals(1, source.stops.get())
    }

    @Test fun processExitWhileLeaseActiveDefersIdleUntilLeaseStops() {
        val source = FakeSource()
        val alive = AtomicBoolean(true)
        val probes = AtomicInteger()
        val idle = AtomicInteger()
        val bridge = RSoundBridge({ source }, { probes.incrementAndGet(); alive.get() }, { idle.incrementAndGet() }, 5, 40, 200)
        try {
            val lease = bridge.acquire()
            assertTrue(lease.start(AudioSink {}))
            lease.gameLaunched()
            await { probes.get() > 0 }
            alive.set(false)
            await { source.stops.get() == 1 && !bridge.retained }
            assertEquals(0, idle.get())
            lease.stop()
            await { idle.get() == 1 }
            lease.stop()
            assertEquals(1, idle.get())
        } finally {
            bridge.close()
        }
    }

    @Test fun preflightWithoutLaunchOrConnectionReleasesRaw() {
        val source = FakeSource()
        val idle = AtomicInteger()
        val bridge = RSoundBridge({ source }, { true }, { idle.incrementAndGet() })
        try {
            val lease = bridge.acquire()
            assertTrue(lease.start(AudioSink {}))
            assertTrue(bridge.retained)
            assertEquals("rsound-waiting", lease.audioMode)
            lease.stop()
            assertEquals(1, source.stops.get())
            assertEquals(1, idle.get())
            assertFalse(bridge.retained)
            assertFalse(source.running)
        } finally {
            bridge.close()
        }
    }

    @Test fun connectedClientWithoutLaunchNotificationRetainsRawAndArmsWatch() {
        val source = FakeSource().apply { mode = "rsound" }
        val alive = AtomicBoolean(true)
        val probes = AtomicInteger()
        val bridge = RSoundBridge({ source }, { probes.incrementAndGet(); alive.get() }, pollMs = 5, exitGraceMs = 40)
        try {
            val lease = bridge.acquire()
            assertTrue(lease.start(AudioSink {}))
            lease.stop()
            await { probes.get() > 0 }
            assertTrue(bridge.retained)
            assertEquals(0, source.stops.get())
            alive.set(false)
            await { source.stops.get() == 1 }
        } finally {
            bridge.close()
        }
    }

    @Test fun setupFallbackCaptureStopsWithLeaseDespiteLiveEmulator() {
        val source = FakeSource().apply {
            ready = false
            mode = "opensl"
            reason = "bind collision"
            failure = "bind"
            drops = 9
        }
        val bridge = bridge(source)
        try {
            val lease = bridge.acquire()
            assertTrue(lease.start(AudioSink {}))
            lease.gameLaunched()
            assertFalse(bridge.retained)
            assertFalse(lease.rsoundReady)
            assertEquals("opensl", lease.audioMode)
            assertEquals("bind collision", lease.fallbackReason)
            assertEquals("bind", lease.audioFailureKind)
            assertEquals(9L, lease.guestAudioDrops)
            lease.stop()
            assertEquals(1, source.stops.get())
            assertFalse(source.running)
        } finally {
            bridge.close()
        }
    }

    @Test fun launchFallbackWithListenerStillReadyRetainsUntilEmulatorExit() {
        val source = FakeSource()
        val bridge = bridge(source)
        try {
            val lease = bridge.acquire()
            assertTrue(lease.start(AudioSink {}))
            lease.gameLaunched()
            source.mode = "opensl"
            source.reason = "no connection within 5000ms"
            lease.stop()
            assertTrue(bridge.retained)
            assertEquals(0, source.stops.get())
            assertEquals("no connection within 5000ms", lease.fallbackReason)
        } finally {
            bridge.close()
        }
    }

    @Test fun transientAbsenceAndRepeatedLaunchDoNotRestartRawOrWatcher() {
        val source = FakeSource()
        val healthy = CountDownLatch(1)
        val probingAbsent = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val phase = AtomicInteger()
        val bridge = RSoundBridge({ source }, {
            when (phase.get()) {
                1 -> { probingAbsent.countDown(); false }
                2 -> { returned.countDown(); true }
                else -> { healthy.countDown(); true }
            }
        }, pollMs = 5, exitGraceMs = 120)
        try {
            val lease = bridge.acquire()
            assertTrue(lease.start(AudioSink {}))
            lease.gameLaunched()
            assertTrue(healthy.await(2, TimeUnit.SECONDS))
            phase.set(1)
            assertTrue(probingAbsent.await(2, TimeUnit.SECONDS))
            phase.set(2)
            assertTrue(returned.await(2, TimeUnit.SECONDS))
            val next = bridge.acquire()
            assertTrue(next.start(AudioSink {}))
            next.gameLaunched()
            lease.stop()
            assertEquals(1, source.starts.get())
            assertEquals(0, source.stops.get())
            assertTrue(bridge.retained)
        } finally {
            bridge.close()
        }
    }

    @Test fun throwingEmulatorProbeLogsAndDoesNotKillPlayingBridge() {
        val source = FakeSource()
        val phase = AtomicInteger()
        val healthy = CountDownLatch(1)
        val recovered = CountDownLatch(1)
        val logs = CopyOnWriteArrayList<String>()
        val bridge = RSoundBridge({ source }, {
            when (phase.get()) {
                0 -> { healthy.countDown(); true }
                1 -> throw IllegalStateException("pidof unavailable")
                2 -> { recovered.countDown(); true }
                else -> false
            }
        }, pollMs = 5, exitGraceMs = 40, launchWaitMs = 100, logger = { logs.add(it) })
        try {
            val lease = bridge.acquire()
            assertTrue(lease.start(AudioSink {}))
            lease.gameLaunched()
            assertTrue(healthy.await(2, TimeUnit.SECONDS))
            phase.set(1)
            await { logs.any { it.contains("pidof unavailable") } }
            Thread.sleep(80)
            assertEquals(0, source.stops.get())
            assertTrue(bridge.retained)
            phase.set(2)
            assertTrue(recovered.await(2, TimeUnit.SECONDS))
            phase.set(3)
            await { source.stops.get() == 1 }
            assertEquals("emulator-exit", bridge.failureKind)
        } finally {
            bridge.close()
        }
    }

    @Test fun noProcessAfterLaunchClosesAfterLaunchWaitNotExitGrace() {
        val source = FakeSource()
        val probes = AtomicInteger()
        val idle = AtomicInteger()
        val bridge = RSoundBridge({ source }, { probes.incrementAndGet(); false }, { idle.incrementAndGet() }, 5, 0, 120)
        try {
            val lease = bridge.acquire()
            assertTrue(lease.start(AudioSink {}))
            lease.gameLaunched()
            lease.stop()
            await { probes.get() > 0 }
            assertEquals(0, source.stops.get())
            assertTrue(bridge.retained)
            await { idle.get() == 1 }
            assertEquals("emulator-exit", bridge.failureKind)
            assertEquals(1, source.stops.get())
        } finally {
            bridge.close()
        }
    }

    @Test fun explicitCloseStopsActiveRawRejectsNewLeaseAndSuppressesIdle() {
        val source = FakeSource()
        val idle = AtomicInteger()
        val bridge = RSoundBridge({ source }, { true }, { idle.incrementAndGet() }, pollMs = 5)
        val chunks = AtomicInteger()
        val lease = bridge.acquire()
        assertTrue(lease.start(AudioSink { chunks.incrementAndGet() }))
        lease.gameLaunched()
        bridge.close()
        bridge.close()
        lease.stop()
        source.emit(1)
        assertEquals(1, source.stops.get())
        assertEquals(0, chunks.get())
        assertEquals(0, idle.get())
        assertFalse(bridge.retained)
        assertFalse(lease.start(AudioSink {}))
        assertThrows(IllegalStateException::class.java) { bridge.acquire() }
    }

    @Test fun failedAndThrowingStartsReleaseRawAndPermitFreshLease() {
        for (throwing in listOf(false, true)) {
            val failed = FakeSource().apply {
                succeeds = false
                if (throwing) onStart = { throw IllegalStateException("start failed") }
            }
            val next = FakeSource()
            val factories = AtomicInteger()
            val bridge = RSoundBridge({ if (factories.incrementAndGet() == 1) failed else next }, { true })
            try {
                val lease = bridge.acquire()
                assertFalse(lease.start(AudioSink {}))
                assertEquals(1, failed.stops.get())
                assertFalse(bridge.retained)
                lease.stop()
                val replacement = bridge.acquire()
                assertTrue(replacement.start(AudioSink {}))
                assertEquals(1, next.starts.get())
                assertTrue(bridge.retained)
                assertNull(bridge.failureKind)
            } finally {
                bridge.close()
            }
        }
    }

    @Test fun lateCallbackFromDestroyedRawCannotReachReplacementLease() {
        val first = FakeSource()
        val next = FakeSource()
        val factories = AtomicInteger()
        val chunks = CopyOnWriteArrayList<Int>()
        val bridge = RSoundBridge({ if (factories.incrementAndGet() == 1) first else next }, { true })
        try {
            val oldLease = bridge.acquire()
            assertTrue(oldLease.start(AudioSink {}))
            val oldForwarder = first.sink!!
            oldLease.stop()
            val newLease = bridge.acquire()
            assertTrue(newLease.start(AudioSink { chunks.add(it.seq) }))
            oldForwarder.onPcm(chunk(1))
            oldLease.stop()
            next.emit(2)
            assertEquals(listOf(2), chunks.toList())
        } finally {
            bridge.close()
        }
    }

    @Test fun rawStartAndGuestCallbacksNeverHoldManagerLock() {
        val source = FakeSource()
        val bridge = bridge(source)
        val completed = CountDownLatch(1)
        val observations = CopyOnWriteArrayList<Boolean>()
        source.onStart = { source.emit(1) }
        try {
            val lease = bridge.acquire()
            assertTrue(lease.start(AudioSink {
                val worker = Thread {
                    observations.add(bridge.retained)
                    completed.countDown()
                }
                worker.start()
                assertTrue(completed.await(2, TimeUnit.SECONDS))
                worker.join(1_000)
            }))
            assertEquals(listOf(false), observations.toList())
        } finally {
            bridge.close()
        }
    }

    @Test fun closeDuringStartCancelsAndCleansLateStartupWithoutDeadlock() {
        val source = FakeSource()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val result = AtomicBoolean(true)
        source.onStart = {
            entered.countDown()
            assertTrue(release.await(2, TimeUnit.SECONDS))
        }
        source.onStop = { release.countDown() }
        val bridge = bridge(source)
        val lease = bridge.acquire()
        val worker = Thread { result.set(lease.start(AudioSink {})) }
        try {
            worker.start()
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            bridge.close()
            worker.join(2_000)
            assertFalse(worker.isAlive)
            assertFalse(result.get())
            assertFalse(bridge.retained)
            assertFalse(source.running)
            assertTrue(source.stops.get() >= 1)
        } finally {
            release.countDown()
            bridge.close()
            worker.join(2_000)
        }
    }

    @Test fun closeInterruptsBlockedProbeAndJoinsOutsideManagerLock() {
        val source = FakeSource()
        val entered = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val stoppedCallback = CountDownLatch(1)
        val bridge = RSoundBridge({ source }, {
            entered.countDown()
            try {
                CountDownLatch(1).await()
            } catch (_: InterruptedException) {
                interrupted.countDown()
            }
            true
        }, pollMs = 5)
        source.onStop = {
            val worker = Thread { bridge.retained; stoppedCallback.countDown() }
            worker.start()
            assertTrue(stoppedCallback.await(2, TimeUnit.SECONDS))
            worker.join(1_000)
        }
        try {
            val lease = bridge.acquire()
            assertTrue(lease.start(AudioSink {}))
            lease.gameLaunched()
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            bridge.close()
            assertTrue(interrupted.await(2, TimeUnit.SECONDS))
            assertEquals(1, source.stops.get())
            assertFalse(bridge.retained)
        } finally {
            bridge.close()
        }
    }

    private fun bridge(source: FakeSource) = RSoundBridge({ source }, { true }, pollMs = 5)

    private fun await(condition: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (!condition()) {
            if (System.nanoTime() >= end) throw AssertionError("RSound bridge condition timed out")
            Thread.sleep(2)
        }
    }

    private class FakeSource : AudioSource {
        val starts = AtomicInteger()
        val stops = AtomicInteger()
        val launches = AtomicInteger()
        val flushes = AtomicInteger()
        @Volatile var running = false
        @Volatile var ready = true
        @Volatile var mode = "rsound-waiting"
        @Volatile var reason: String? = null
        @Volatile var failure: String? = null
        @Volatile var drops = 0L
        @Volatile var sink: AudioSink? = null
        var succeeds = true
        var onStart: () -> Unit = {}
        var onStop: () -> Unit = {}
        override val rsoundReady: Boolean get() = running && ready
        override val audioMode: String get() = mode
        override val fallbackReason: String? get() = reason
        override val audioFailureKind: String? get() = failure
        override val guestAudioDrops: Long get() = drops
        override fun start(sink: AudioSink): Boolean {
            this.sink = sink
            starts.incrementAndGet()
            running = true
            onStart()
            return succeeds
        }
        override fun gameLaunched() { launches.incrementAndGet() }
        override fun flushGuestAudio() { flushes.incrementAndGet() }
        override fun stop() {
            running = false
            stops.incrementAndGet()
            onStop()
        }
        fun emit(seq: Int) { sink?.onPcm(chunk(seq)) }
    }

    companion object {
        private fun chunk(seq: Int) = PcmChunk(seq, System.nanoTime(), 1, byteArrayOf(0, 0, 0, 0))
    }
}
