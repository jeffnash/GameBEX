package com.gamebex.client.stream.guest

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class AudioOutputLifecycleTest {
    private class Output : AudioOutputHandle {
        val plays = AtomicInteger()
        val releases = AtomicInteger()
        val released = CountDownLatch(1)
        var onPlay: () -> Unit = {}
        override fun play() { onPlay(); plays.incrementAndGet() }
        override fun stop() = Unit
        override fun setMuted(muted: Boolean) = Unit
        override fun release() {
            assertEquals(1, releases.incrementAndGet())
            released.countDown()
        }
    }

    private fun await(latch: CountDownLatch) { assertTrue(latch.await(2, TimeUnit.SECONDS)) }

    @Test fun stopDuringFactoryCancelsUnpublishedOutputWithoutWaitingOrWriting() {
        val entered = CountDownLatch(1)
        val finishBuild = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val output = Output()
        val writes = AtomicInteger()
        val failures = AtomicInteger()
        var started = true
        val lifecycle = AudioOutputLifecycle(
            factory = { entered.countDown(); await(finishBuild); output },
            write = { _: Output, _: () -> Boolean -> writes.incrementAndGet(); Unit },
            interruptOutput = true,
        )
        val starter = Thread({
            try { started = lifecycle.start { failures.incrementAndGet() } }
            finally { finished.countDown() }
        }).apply { start() }
        try {
            await(entered)
            lifecycle.stop() // Returns while build is still held, not a UI-style join.
            assertFalse(lifecycle.isActive)
            assertFalse(lifecycle.start { failures.incrementAndGet() })
            assertEquals(0, output.releases.get())
        } finally { finishBuild.countDown() }
        await(finished)
        starter.join(2_000L)
        assertFalse(started)
        assertFalse(starter.isAlive)
        assertEquals(0, writes.get())
        assertEquals(0, output.plays.get())
        assertEquals(1, output.releases.get())
        assertEquals(0, failures.get())
        assertFalse(lifecycle.isActive)
    }

    @Test fun terminalCloseDuringPlayNeverPublishesWriterOrRevivesAfterEnded() {
        val entered = CountDownLatch(1)
        val finishPlay = CountDownLatch(1)
        val output = Output().apply { onPlay = { entered.countDown(); await(finishPlay) } }
        val writes = AtomicInteger()
        val failures = AtomicInteger()
        var started = true
        val lifecycle = AudioOutputLifecycle(
            factory = { output },
            write = { _: Output, _: () -> Boolean -> writes.incrementAndGet(); Unit },
            interruptOutput = true,
        )
        val starter = Thread { started = lifecycle.start { failures.incrementAndGet() } }.apply { start() }
        try {
            await(entered)
            lifecycle.close()
            lifecycle.stop()
            assertFalse(lifecycle.start { failures.incrementAndGet() })
            assertEquals(0, writes.get())
        } finally { finishPlay.countDown() }
        starter.join(2_000L)
        assertFalse(starter.isAlive)
        assertFalse(started)
        assertEquals(1, output.releases.get())
        assertEquals(0, failures.get())
        assertFalse(lifecycle.start { failures.incrementAndGet() })
    }

    @Test fun realFactoryAndPlayFailuresReclaimOwnershipBeforeFailureCallback() {
        val output = Output().apply { onPlay = { throw IllegalStateException("native play") } }
        val builds = AtomicInteger()
        val failures = mutableListOf<String>()
        val lifecycle = AudioOutputLifecycle(
            factory = {
                if (builds.incrementAndGet() == 1) throw IllegalArgumentException("native build")
                output
            },
            write = { _: Output, _: () -> Boolean -> fail("failed startup published writer") },
            interruptOutput = true,
        )
        repeat(2) {
            assertFalse(lifecycle.start { reason ->
                assertFalse(lifecycle.isActive)
                failures += reason
            })
        }
        assertEquals(listOf("IllegalArgumentException", "IllegalStateException"), failures)
        assertEquals(1, output.releases.get())
    }

    @Test fun failedStartupRetriesOnceThenReconnectStartsExactlyOnceAndEndRejectsQueuedRecovery() {
        val recovery = AudioOutputRecovery()
        val outputs = mutableListOf<Output>()
        val builds = AtomicInteger()
        val queued = ArrayDeque<AudioOutputRecovery.Attempt>()
        val dispositions = mutableListOf<AudioOutputRecovery.Failure>()
        val lifecycle = AudioOutputLifecycle(
            factory = {
                builds.incrementAndGet()
                Output().also { outputs += it }.apply { onPlay = { throw IllegalStateException("native play") } }
            },
            write = { _: Output, _: () -> Boolean -> fail("failed startup published writer") },
            interruptOutput = true,
        )
        var epoch = 1L
        fun connect() { recovery.connected(epoch, lifecycle.isActive)?.let { queued.addLast(it) } }
        fun runNext() {
            val attempt = queued.removeFirst()
            assertTrue(recovery.claim(attempt))
            lifecycle.stop()
            assertFalse(lifecycle.start {
                val failure = recovery.failed(attempt, epoch)
                dispositions += failure
                if (failure is AudioOutputRecovery.Failure.Retry) queued.addLast(failure.attempt)
            })
        }
        connect(); connect()
        assertEquals(1, queued.size)
        runNext(); runNext()
        assertEquals(2, builds.get())
        assertSame(AudioOutputRecovery.Failure.Disabled, dispositions.last())
        assertFalse(recovery.accepting)
        connect()
        assertTrue(queued.isEmpty())
        recovery.reconnecting()
        assertFalse(recovery.accepting)
        epoch++
        connect(); connect()
        assertEquals(1, queued.size)
        runNext(); runNext()
        assertEquals(4, builds.get())
        assertEquals(listOf(1, 1, 1, 1), outputs.map { it.releases.get() })
        recovery.reconnecting()
        epoch++
        connect()
        val stale = queued.removeFirst()
        recovery.close(); lifecycle.close()
        assertFalse(recovery.claim(stale))
        assertFalse(lifecycle.start { fail("ended callback") })
        assertNull(recovery.connected(++epoch, false))
        assertSame(AudioOutputRecovery.Failure.Ignored, recovery.failed(stale, epoch))
        assertFalse(recovery.accepting)
        assertEquals(4, builds.get())
    }

    @Test fun disabledOutputResumesARealConsumerOnNextConnectedEpochWithoutDuplicateWriter() {
        val recovery = AudioOutputRecovery()
        val consumed = CountDownLatch(1)
        val finishWrite = CountDownLatch(1)
        val output = Output()
        val builds = AtomicInteger()
        val lifecycle = AudioOutputLifecycle(
            factory = {
                when (builds.incrementAndGet()) {
                    1 -> throw IllegalArgumentException("build failed")
                    2 -> Output().apply { onPlay = { throw IllegalStateException("play failed") } }
                    else -> output
                }
            },
            write = { _: Output, keepRunning: () -> Boolean ->
                assertTrue(keepRunning())
                consumed.countDown()
                await(finishWrite)
            },
            interruptOutput = false,
        )
        val first = recovery.connected(1L, lifecycle.isActive)!!
        assertTrue(recovery.claim(first))
        var retry: AudioOutputRecovery.Attempt? = null
        assertFalse(lifecycle.start {
            retry = (recovery.failed(first, 1L) as AudioOutputRecovery.Failure.Retry).attempt
        })
        assertTrue(recovery.claim(retry!!))
        assertFalse(lifecycle.start {
            assertSame(AudioOutputRecovery.Failure.Disabled, recovery.failed(retry!!, 1L))
        })
        assertFalse(recovery.accepting)
        recovery.reconnecting()
        val healed = recovery.connected(2L, lifecycle.isActive)!!
        assertTrue(recovery.claim(healed))
        assertNull(recovery.connected(2L, false)) // Reservation closes coroutine-to-factory gap.
        assertTrue(lifecycle.start { fail("healthy output failed: $it") })
        recovery.started(healed)
        try {
            await(consumed)
            assertTrue(recovery.accepting)
            assertTrue(lifecycle.isActive)
            assertNull(recovery.connected(2L, lifecycle.isActive))
            assertEquals(3, builds.get())
        } finally {
            recovery.close()
            lifecycle.close()
            finishWrite.countDown()
            lifecycle.stop()
        }
        await(output.released)
        assertEquals(1, output.plays.get())
        assertEquals(1, output.releases.get())
        assertFalse(lifecycle.isActive)
    }

    @Test fun healthyReconnectRetainsFailureTokenAndBudgetResetsOnlyForNewSourceEpoch() {
        val recovery = AudioOutputRecovery()
        val first = recovery.connected(7L, false)!!
        assertTrue(recovery.claim(first))
        recovery.reconnecting()
        assertNull(recovery.connected(8L, true)) // Existing healthy writer is not started twice.
        val retry = recovery.failed(first, 8L) as AudioOutputRecovery.Failure.Retry
        assertTrue(recovery.claim(retry.attempt))
        assertSame(AudioOutputRecovery.Failure.Ignored, recovery.failed(first, 8L))
        assertSame(AudioOutputRecovery.Failure.Disabled, recovery.failed(retry.attempt, 8L))
        assertNull(recovery.connected(8L, false))
        recovery.reconnecting()
        val next = recovery.connected(9L, false)!!
        assertTrue(recovery.claim(next))
        assertFalse(recovery.claim(next))
        assertNull(recovery.connected(9L, true))
    }
}
