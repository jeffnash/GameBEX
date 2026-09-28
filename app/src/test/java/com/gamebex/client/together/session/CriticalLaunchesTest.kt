package com.gamebex.client.together.session

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CriticalLaunchesTest {
    @Test
    fun enterAfterTheStepWasCancelledThrowsAndRegistersNothing() = runTest {
        val launches = CriticalLaunches()
        val step = Job()
        launches.cancel(listOf(step))
        try {
            launches.enter(step)
            fail("enter must refuse a cancelled step")
        } catch (_: CancellationException) {
        }
        val ended = launch { launches.join(2_000L) }
        runCurrent()
        assertTrue(ended.isCompleted)
    }

    @Test
    fun exitClearsOnlyItsOwnMarker() = runTest {
        val launches = CriticalLaunches()
        val first = launches.enter(Job())
        val second = launches.enter(Job())
        launches.exit(second)

        val ended = launch { launches.join(2_000L) }
        runCurrent()
        assertFalse(ended.isCompleted)
        launches.exit(first)
        runCurrent()
        assertTrue(ended.isCompleted)
    }

    @Test
    fun joinGivesUpAfterItsTimeout() = runTest {
        val launches = CriticalLaunches()
        launches.enter(Job())
        val ended = launch { launches.join(2_000L) }
        advanceTimeBy(1_999)
        runCurrent()
        assertFalse(ended.isCompleted)
        advanceTimeBy(1)
        runCurrent()
        assertTrue(ended.isCompleted)
    }
}
