package com.gamebex.client.stream.shell

import com.gamebex.client.stream.proto.PadState
import java.net.InetSocketAddress
import org.junit.Assert.*
import org.junit.Test

class InputAuthorityTest {
    private var now = 1L
    private val attempts = mutableListOf<PadState>()
    private val writes = mutableListOf<PadState>()
    private var fail: (PadState) -> Boolean = { false }
    private val authority = InputAuthority({ state ->
        attempts += state
        if (fail(state)) false else { writes += state; true }
    }, { now }, 250L)
    private val press = PadState(buttons = PadState.A)
    private val routeA = InetSocketAddress("127.0.0.1", 10001)
    private val routeB = InetSocketAddress("127.0.0.1", 10002)

    @Test fun successfulNeutralHandoverPrecedesFirstPressAndSameStateDoesNotWrite() {
        assertTrue(authority.attach(7L))
        now = 10L
        val applied = authority.apply(7L, 1, 5L, press)!!
        now = 20L
        val heartbeat = authority.apply(7L, 2, 5L, press)!!
        assertEquals(listOf(PadState.NEUTRAL, press), writes)
        assertTrue(applied.wrote)
        assertFalse(heartbeat.wrote)
        assertEquals(10L, heartbeat.appliedNs)
    }

    @Test fun failedPressHasNoAckDoesNotRememberOrAdvanceSequence() {
        authority.attach(7L)
        fail = { !it.isNeutral }
        assertNull(authority.apply(7L, 1, 2L, press))
        assertEquals(PadState.NEUTRAL, authority.state)
        assertFalse(authority.isStale(1))
        fail = { false }
        assertTrue(authority.apply(7L, 1, 2L, press)!!.wrote)
        assertEquals(listOf(PadState.NEUTRAL, press), writes)
    }

    @Test fun failedReleaseRetriesWithSameOrNewerSequence() {
        authority.attach(7L)
        authority.apply(7L, 1, 2L, press)
        fail = { it.isNeutral }
        assertNull(authority.apply(7L, 2, 3L, PadState.NEUTRAL))
        assertEquals(press, authority.state)
        fail = { false }
        assertTrue(authority.apply(7L, 3, 3L, PadState.NEUTRAL)!!.wrote)
    }

    @Test fun failedAttachWhileRememberedNeutralRetriesAndGatesInputButLearnsRoute() {
        fail = { true }
        assertFalse(authority.attach(7L))
        assertNull(authority.apply(7L, 1, 2L, press, routeA))
        assertEquals(routeA, authority.audioRoute(100L))
        authority.tick()
        assertEquals(2, attempts.size)
        fail = { false }
        authority.tick()
        assertTrue(authority.apply(7L, 1, 2L, press)!!.wrote)
        assertEquals(listOf(PadState.NEUTRAL, press), writes)
    }

    @Test fun watchdogRetriesFailedNeutralWithoutRepeatedIdleWrites() {
        authority.attach(7L)
        authority.apply(7L, 1, 2L, press)
        now = 252L
        fail = { it.isNeutral }
        repeat(3) {
            assertNull(authority.apply(8L, 500, 2L, press, routeB))
            assertNull(authority.apply(7L, 1, 2L, press))
            authority.tick()
        }
        assertEquals(press, authority.state)
        fail = { false }
        authority.tick()
        assertEquals(PadState.NEUTRAL, authority.state)
        val count = attempts.size
        repeat(10) { now++; authority.tick() }
        assertEquals(count, attempts.size)
    }

    @Test fun reopenResetsSequenceAndRouteAndOldDetachIsInert() {
        authority.attach(7L)
        authority.apply(7L, 500, 2L, press, routeA)
        assertTrue(authority.attach(8L))
        assertNull(authority.audioRoute(100L))
        assertNull(authority.apply(7L, 501, 3L, press, routeA))
        authority.detach(7L)
        assertTrue(authority.apply(8L, 1, 4L, PadState(buttons = PadState.B), routeB)!!.wrote)
        assertEquals(routeB, authority.audioRoute(100L))
        assertEquals(listOf(PadState.NEUTRAL, press, PadState.NEUTRAL, PadState(buttons = PadState.B)), writes)
    }

    @Test fun sameViewerMediaReconnectPreservesHeldPadSequenceAndRoute() {
        authority.attach(7L)
        authority.apply(7L, 500, 2L, press, routeA)
        assertTrue(authority.attach(7L))
        assertNull(authority.apply(7L, 1, 3L, PadState.NEUTRAL, routeB))
        assertEquals(routeA, authority.audioRoute(100L))
        assertEquals(listOf(PadState.NEUTRAL, press), writes)
    }

    @Test fun failedPressDoesNotBreakAudioRouteAndForeignIdsCannotRefreshIt() {
        authority.attach(7L)
        fail = { !it.isNeutral }
        assertNull(authority.apply(7L, 1, 2L, press, routeA))
        assertEquals(routeA, authority.audioRoute(100L))
        now = 150L
        assertNull(authority.apply(8L, 2, 3L, press, routeB))
        assertNull(authority.audioRoute(100L))
    }

    @Test fun failedDetachRetriesAndOldPacketsCannotReclaimOwnership() {
        authority.attach(7L)
        authority.apply(7L, 1, 2L, press, routeA)
        fail = { it.isNeutral }
        authority.detach(7L)
        assertNull(authority.audioRoute(100L))
        assertNull(authority.apply(7L, 2, 3L, press, routeA))
        fail = { false }
        authority.tick()
        val count = writes.size
        repeat(5) { authority.tick() }
        assertEquals(count, writes.size)
        assertEquals(PadState.NEUTRAL, authority.state)
    }

    @Test fun packetSequenceWrapIsNewestWinsAndReorderingCannotReapply() {
        authority.attach(7L)
        assertNotNull(authority.apply(7L, Int.MAX_VALUE, 2L, press))
        assertNotNull(authority.apply(7L, Int.MIN_VALUE, 3L, PadState.NEUTRAL))
        assertNull(authority.apply(7L, Int.MAX_VALUE, 2L, press))
        assertNull(authority.apply(7L, Int.MIN_VALUE, 3L, PadState.NEUTRAL))
        assertEquals(listOf(PadState.NEUTRAL, press, PadState.NEUTRAL), writes)
    }
}
