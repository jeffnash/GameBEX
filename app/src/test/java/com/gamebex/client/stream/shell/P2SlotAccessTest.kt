package com.gamebex.client.stream.shell

import com.gamebex.client.stream.StreamCaps
import com.gamebex.client.stream.fakes.FakeVirtualPad
import com.gamebex.client.stream.p2.P2SlotMode
import com.gamebex.client.stream.proto.PadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** P2 slot shell gate: ordering, bounded waits, cleanup. */
class P2SlotGateTest {
    private val instant = P2SlotSession.Timing(
        raUpTimeoutMs = 5_000L, raPollMs = 1L, pressHoldMs = 0L, registerWaitMs = 0L, realPadTimeoutMs = 5_000L,
        blipSettleMs = 0L,
    )

    private fun pads(): Pair<MutableList<String>, (String, String) -> FakeVirtualPad?> {
        val opened = mutableListOf<String>()
        val pads = mutableMapOf<String, FakeVirtualPad>()
        return opened to { name, _ ->
            opened += name
            FakeVirtualPad().also { pads[name] = it }
        }
    }

    private fun awaitOpen(session: P2SlotSession?): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        val gated = session?.let {
            val f = P2SlotSession::class.java.getDeclaredField("gated").apply { isAccessible = true }
            f.get(it) as GatedPad
        } ?: return false
        while (System.nanoTime() < deadline) {
            if (gated.isOpen) return true
            Thread.sleep(10L)
        }
        return false
    }

    @Test fun offPassesStraightThrough() {
        val (_, open) = pads()
        val logs = mutableListOf<String>()
        val session = P2SlotSession.open(P2SlotMode.OFF, open, timing = instant, log = logs::add)!!
        try {
            assertTrue(awaitOpen(session))
            assertTrue(session.write(PadState(buttons = PadState.A)))
        } finally {
            session.close()
        }
    }

    @Test fun placeholderRegistersBeforePlayerTwo() {
        val (opened, open) = pads()
        val byName = mutableMapOf<String, FakeVirtualPad>()
        val capturing: (String, String) -> FakeVirtualPad? = { name, uniq ->
            open(name, uniq)?.also { byName[name] = it }
        }
        var resumed = false
        val logs = mutableListOf<String>()
        val session = P2SlotSession.open(
            P2SlotMode.PLACEHOLDER, capturing,
            isRaResumed = { resumed },
            timing = instant.copy(registerWaitMs = 50L),
            sleep = { Thread.sleep(it) },
            log = logs::add,
        )!!
        try {
            // Placeholder first, P2 second.
            assertEquals(listOf(StreamCaps.PLACEHOLDER_PAD_NAME, StreamCaps.PAD_NAME), opened)
            // Nothing sent while RetroArch is down, even when the guest plays.
            assertTrue(session.write(PadState(buttons = PadState.A)))
            Thread.sleep(100L)
            assertTrue(byName.getValue(StreamCaps.PLACEHOLDER_PAD_NAME).writes.isEmpty())
            assertTrue(byName.getValue(StreamCaps.PAD_NAME).writes.isEmpty())
            // RetroArch foregrounds: press+release registers slot 0, then the held P2 report goes.
            resumed = true
            assertTrue(awaitOpen(session))
            val placeholderWrites = byName.getValue(StreamCaps.PLACEHOLDER_PAD_NAME).writes
            assertEquals(listOf(PadState(buttons = PadState.SELECT), PadState.NEUTRAL), placeholderWrites)
            val p2Writes = byName.getValue(StreamCaps.PAD_NAME).writes
            assertEquals(listOf(PadState(buttons = PadState.A)), p2Writes)
            assertTrue(logs.any { it.contains("placeholder registered") })
            // Afterwards the pad is live.
            assertTrue(session.write(PadState.NEUTRAL))
            assertEquals(2, byName.getValue(StreamCaps.PAD_NAME).writes.size)
        } finally {
            session.close()
        }
    }

    @Test fun placeholderWaitsForGuestDemand() {
        val (_, open) = pads()
        val byName = mutableMapOf<String, FakeVirtualPad>()
        val capturing: (String, String) -> FakeVirtualPad? = { name, uniq ->
            open(name, uniq)?.also { byName[name] = it }
        }
        val session = P2SlotSession.open(
            P2SlotMode.PLACEHOLDER, capturing,
            isRaResumed = { true },
            timing = instant,
            sleep = {},
            log = {},
        )!!
        try {
            // Resumed but silent: no blip, gate closed, nothing delivered.
            Thread.sleep(150L)
            assertTrue(byName.getValue(StreamCaps.PLACEHOLDER_PAD_NAME).writes.isEmpty())
            assertTrue(byName.getValue(StreamCaps.PAD_NAME).writes.isEmpty())
            // The guest's first report fires the blip, then delivers it.
            assertTrue(session.write(PadState(buttons = PadState.B)))
            assertTrue(awaitOpen(session))
            assertEquals(
                listOf(PadState(buttons = PadState.SELECT), PadState.NEUTRAL),
                byName.getValue(StreamCaps.PLACEHOLDER_PAD_NAME).writes,
            )
            assertEquals(listOf(PadState(buttons = PadState.B)), byName.getValue(StreamCaps.PAD_NAME).writes)
        } finally {
            session.close()
        }
    }

    @Test fun placeholderSettleWaitsOutAFreshResume() {
        val (_, open) = pads()
        val sleeps = Collections.synchronizedList(mutableListOf<Long>())
        val session = P2SlotSession.open(
            P2SlotMode.PLACEHOLDER, open,
            isRaResumed = { true },
            timing = instant.copy(blipSettleMs = 5_000L),
            sleep = { sleeps += it },
            nanos = { 0L },
            log = {},
        )!!
        try {
            assertTrue(session.write(PadState.NEUTRAL))
            assertTrue(awaitOpen(session))
            assertTrue(sleeps.contains(5_000L))
        } finally {
            session.close()
        }
    }

    @Test fun placeholderSettleSkipsWhenRaResumedLongAgo() {
        val (_, open) = pads()
        val sleeps = Collections.synchronizedList(mutableListOf<Long>())
        // Deadline, resume-poll, resumed-at, post-demand: RA resumed at t=0,
        // the guest's demand lands 10 s later.
        val ticks = ArrayDeque(listOf(0L, 0L, 0L, 10_000_000_000L))
        val session = P2SlotSession.open(
            P2SlotMode.PLACEHOLDER, open,
            isRaResumed = { true },
            timing = instant.copy(blipSettleMs = 5_000L),
            sleep = { sleeps += it },
            nanos = { synchronized(ticks) { if (ticks.size > 1) ticks.removeFirst() else ticks.first() } },
            log = {},
        )!!
        try {
            assertTrue(session.write(PadState.NEUTRAL))
            assertTrue(awaitOpen(session))
            assertTrue(sleeps.none { it == 5_000L })
        } finally {
            session.close()
        }
    }

    @Test fun closeUnblocksADemandParkedWorker() {
        val (_, open) = pads()
        val session = P2SlotSession.open(
            P2SlotMode.PLACEHOLDER, open,
            isRaResumed = { true },
            timing = instant,
            sleep = {},
            log = {},
        )!!
        // No guest input ever: close still returns (the parked worker dies).
        session.close()
        assertTrue(awaitOpen(session))
    }

    @Test fun placeholderTimeoutOpensTheGateAndLogs() {
        val (_, open) = pads()
        val logs = mutableListOf<String>()
        val session = P2SlotSession.open(
            P2SlotMode.PLACEHOLDER, open,
            isRaResumed = { false },
            timing = instant.copy(raUpTimeoutMs = 0L),
            sleep = {},
            log = logs::add,
        )!!
        try {
            assertTrue(awaitOpen(session))
            assertTrue(session.write(PadState.NEUTRAL))
            assertTrue(logs.any { it.contains("never foregrounded") && it.contains("without slot guarantee") })
        } finally {
            session.close()
        }
    }

    @Test fun placeholderFailureDegradesLoudly() {
        val logs = mutableListOf<String>()
        val session = P2SlotSession.open(
            P2SlotMode.PLACEHOLDER,
            openNamedPad = { name, _ -> if (name == StreamCaps.PAD_NAME) FakeVirtualPad() else null },
            timing = instant,
            log = logs::add,
        )!!
        try {
            assertTrue(awaitOpen(session))
            assertTrue(logs.any { it.contains("placeholder failed to open") })
        } finally {
            session.close()
        }
    }

    @Test fun noPlayerTwoPadFailsLikeBefore() {
        assertNull(P2SlotSession.open(P2SlotMode.PLACEHOLDER, openNamedPad = { _, _ -> null }, timing = instant))
    }

    @Test fun gateOpensOnHostPadInput() {
        val (_, open) = pads()
        val node = "/dev/input/event5"
        val evdev = object : EvdevPoll {
            private var reads = 0
            override fun ready(nodes: Set<String>, timeoutMs: Int): Set<String> {
                reads++
                return if (reads >= 3) setOf(node) else emptySet()
            }
            override fun close() = Unit
        }
        val added = mutableListOf<String>()
        val logs = mutableListOf<String>()
        val session = P2SlotSession.open(
            P2SlotMode.GATE_REAL_PAD, open,
            watcherFactory = { onInput, onAdded ->
                RealPadWatcher(
                    listNodes = { setOf(node) },
                    classify = { mapOf(node to InputNode(node, "GameSir-X5s", isPad = true)) },
                    evdev = evdev,
                    onRealPadInput = onInput,
                    onRealPadAdded = { onAdded(it); added += it },
                    pollMs = 5,
                    rescanEvery = 1,
                    log = {},
                )
            },
            timing = instant,
            log = logs::add,
        )!!
        try {
            assertTrue(awaitOpen(session))
            // Present at start: watched, but not a mid-session add.
            assertTrue(added.isEmpty())
            assertTrue(logs.any { it.contains("host pad first") })
            // The held first report goes as soon as the gate opens.
            assertTrue(session.write(PadState(buttons = PadState.A)))
        } finally {
            session.close()
        }
    }

    @Test fun midSessionPadConnectIsWatched() {
        val first = "/dev/input/event5"
        val late = "/dev/input/event6"
        var nodes = setOf(first)
        val byNode = mapOf(
            first to InputNode(first, "GameSir-X5s", isPad = true),
            late to InputNode(late, "8BitDo Pro 2", isPad = true),
        )
        val evdev = object : EvdevPoll {
            override fun ready(nodes: Set<String>, timeoutMs: Int): Set<String> =
                if (late in nodes) setOf(late) else emptySet()
            override fun close() = Unit
        }
        val added = mutableListOf<String>()
        val gateOpened = CountDownLatch(1)
        val watcher = RealPadWatcher(
            listNodes = { nodes },
            classify = { byNode.filterKeys { it in nodes } },
            evdev = evdev,
            onRealPadInput = { gateOpened.countDown() },
            onRealPadAdded = { added += it },
            pollMs = 5,
            rescanEvery = 1,
            log = {},
        )
        watcher.start()
        try {
            Thread.sleep(50L)
            nodes = setOf(first, late)
            assertTrue(gateOpened.await(10L, TimeUnit.SECONDS))
            assertEquals(listOf("8BitDo Pro 2"), added)
        } finally {
            watcher.stop()
        }
    }

    @Test fun gateTimeoutOpensAndKeepsWatching() {
        val (_, open) = pads()
        val inputFired = AtomicBoolean(false)
        val logs = mutableListOf<String>()
        val session = P2SlotSession.open(
            P2SlotMode.GATE_REAL_PAD, open,
            watcherFactory = { onInput, _ ->
                RealPadWatcher(
                    listNodes = { emptySet() },
                    classify = { emptyMap() },
                    evdev = object : EvdevPoll {
                        override fun ready(nodes: Set<String>, timeoutMs: Int): Set<String> = emptySet()
                        override fun close() = Unit
                    },
                    onRealPadInput = {
                        inputFired.set(true)
                        onInput()
                    },
                    pollMs = 5,
                )
            },
            timing = instant.copy(realPadTimeoutMs = 50L),
            log = logs::add,
        )!!
        try {
            assertTrue(awaitOpen(session))
            assertTrue(logs.any { it.contains("no host-pad input") && it.contains("without slot guarantee") })
        } finally {
            session.close()
        }
    }

    @Test fun closeRemovesBothPads() {
        val byName = mutableMapOf<String, FakeVirtualPad>()
        val session = P2SlotSession.open(
            P2SlotMode.PLACEHOLDER,
            openNamedPad = { name, _ -> FakeVirtualPad().also { byName[name] = it } },
            isRaResumed = { true },
            timing = instant,
            sleep = {},
            log = {},
        )!!
        assertTrue(session.write(PadState.NEUTRAL))
        assertTrue(awaitOpen(session))
        session.close()
        assertTrue(byName.getValue(StreamCaps.PAD_NAME).closed)
        assertTrue(byName.getValue(StreamCaps.PLACEHOLDER_PAD_NAME).closed)
    }

    @Test fun gatedPadCoalescesTheHold() {
        val inner = FakeVirtualPad()
        val gated = GatedPad(inner)
        assertFalse(gated.isOpen)
        gated.write(PadState(buttons = PadState.A))
        gated.write(PadState(buttons = PadState.B))
        assertTrue(inner.writes.isEmpty())
        gated.openGate()
        assertTrue(gated.isOpen)
        // Only the latest held state is delivered, then live writes flow.
        assertEquals(listOf(PadState(buttons = PadState.B)), inner.writes)
        gated.write(PadState.NEUTRAL)
        assertEquals(2, inner.writes.size)
        gated.openGate()
        assertEquals(2, inner.writes.size)
    }

    @Test fun resumedProbeMatchesOnlyTheTopActivity() {
        val resumed = "  topResumedActivity=ActivityRecord{abc u0 com.retroarch.aarch64/com.retroarch.browser.retroactivity.RetroActivityFuture t42}\n"
        assertTrue(RaForeground.isResumed(resumed))
        val resumed32 = "  topResumedActivity=ActivityRecord{abc u0 com.retroarch/com.retroarch.browser.retroactivity.RetroActivityFuture t42}\n"
        assertTrue(RaForeground.isResumed(resumed32))
        assertFalse(RaForeground.isResumed("  topResumedActivity=ActivityRecord{abc u0 com.gamebex.client/.MainActivity t42}\n"))
        assertFalse(RaForeground.isResumed(""))
        // A paused record elsewhere is not resumed.
        assertFalse(RaForeground.isResumed("  * Hist #0: ActivityRecord{abc u0 com.retroarch.aarch64/.X t42}\n"))
    }
}
