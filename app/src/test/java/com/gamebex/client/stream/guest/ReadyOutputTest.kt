package com.gamebex.client.stream.guest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadyOutputTest {
    private class Turn {
        val tasks = java.util.ArrayDeque<() -> Unit>()
        val released = mutableListOf<Pair<ReadyOutput<Any>, Boolean>>()
        var codec = Any()
        var generation = 1L
        fun queue(latest: Boolean = true, capacity: Int = 120) = ReadyOutputs<Any>(
            latest,
            post = { tasks.addLast(it); Unit },
            isCurrent = { it.codec === codec && it.generation == generation },
            finish = { output, render -> released += output to render },
            capacity = capacity,
        )
        fun output(index: Int, size: Int = 10, flags: Int = 0) =
            ReadyOutput(codec, generation, index, index.toLong(), size, flags, 100L)
        fun runTurn() {
            val batch = tasks.toList()
            tasks.clear()
            batch.forEach { it() }
        }
    }

    @Test
    fun singleOutputNeverWaitsForNextFrame() {
        val turn = Turn()
        val queue = turn.queue()
        queue.offer(turn.output(1))
        assertEquals(1, turn.tasks.size)
        assertTrue(turn.released.isEmpty())
        turn.runTurn()
        assertEquals(listOf(1 to true), turn.released.map { it.first.index to it.second })
    }

    @Test
    fun defaultOffReleasesImmediatelyWithoutAnotherHandlerTurn() {
        val turn = Turn()
        val queue = turn.queue(latest = false)
        queue.offer(turn.output(1))
        queue.offer(turn.output(2))
        assertTrue(turn.tasks.isEmpty())
        assertEquals(listOf(1 to true, 2 to true), turn.released.map { it.first.index to it.second })
    }

    @Test
    fun batchOnlyLastNonemptyPictureRenders() {
        val turn = Turn()
        val queue = turn.queue()
        queue.offer(turn.output(1))
        queue.offer(turn.output(2))
        queue.offer(turn.output(3, size = 0, flags = 4))
        queue.offer(turn.output(4, flags = 2))
        assertEquals(1, turn.tasks.size)
        turn.runTurn()
        assertEquals(listOf(1 to false, 2 to true, 3 to false, 4 to false), turn.released.map { it.first.index to it.second })
    }

    @Test
    fun bufferInfoFieldsAreCopiedBeforeCallbackReuse() {
        val turn = Turn()
        val queue = turn.queue()
        var pts = 7L
        var size = 10
        var flags = 0
        val output = ReadyOutput(turn.codec, turn.generation, 1, pts, size, flags, 100L)
        queue.offer(output)
        pts = 9
        size = 0
        flags = 2
        turn.runTurn()
        assertEquals(7L, turn.released.single().first.pts)
        assertEquals(10, turn.released.single().first.size)
        assertEquals(0, turn.released.single().first.flags)
        assertTrue(turn.released.single().second)
        assertEquals(9L, pts)
        assertEquals(0, size)
        assertEquals(2, flags)
    }

    @Test
    fun zeroSizeEosIsNotAPictureAndNeverOpensVideoGate() {
        val turn = Turn()
        val queue = turn.queue()
        queue.offer(turn.output(1, size = 0, flags = 4))
        turn.runTurn()
        assertFalse(turn.released.single().second)
    }

    @Test
    fun nonemptyEosCanStillContainTheLastPicture() {
        val turn = Turn()
        val queue = turn.queue()
        queue.offer(turn.output(1, flags = 4))
        turn.runTurn()
        assertTrue(turn.released.single().second)
    }

    @Test
    fun stoppedCodecOrGenerationNeverRendersIntoCurrentSurface() {
        val turn = Turn()
        val queue = turn.queue()
        val stale = turn.output(1)
        queue.offer(stale)
        turn.codec = Any()
        turn.generation += 1
        val current = turn.output(1)
        queue.offer(current)
        turn.runTurn()
        assertEquals(listOf(stale to false, current to true), turn.released)
    }

    @Test
    fun eachIndexIsReturnedExactlyOnceAcrossClearAndDeferredDrain() {
        val turn = Turn()
        val queue = turn.queue()
        repeat(10) { queue.offer(turn.output(it)) }
        queue.clear()
        queue.clear()
        turn.runTurn()
        assertEquals(10, turn.released.size)
        assertEquals((0..9).toList(), turn.released.map { it.first.index })
        assertTrue(turn.released.all { !it.second })
    }

    @Test
    fun pendingDrainAfterCloseIsInertAndCannotConsumeNewGeneration() {
        val turn = Turn()
        val queue = turn.queue()
        queue.offer(turn.output(1))
        queue.clear()
        val oldDrain = turn.tasks.removeFirst()
        turn.codec = Any()
        turn.generation += 1
        queue.offer(turn.output(2))
        oldDrain()
        assertEquals(listOf(1 to false), turn.released.map { it.first.index to it.second })
        turn.runTurn()
        assertEquals(listOf(1 to false, 2 to true), turn.released.map { it.first.index to it.second })
    }

    @Test
    fun boundedQueueReturnsEveryEvictedIndexWithoutRendering() {
        val turn = Turn()
        val queue = turn.queue(capacity = 2)
        repeat(5) { queue.offer(turn.output(it)) }
        turn.runTurn()
        assertEquals(listOf(0 to false, 1 to false, 2 to false, 3 to false, 4 to true), turn.released.map { it.first.index to it.second })
    }

    @Test
    fun discardedOutputsNeverCountAsRenderedOrEnableInput() {
        val turn = Turn()
        var rendered = 0
        var ready = 0
        val queue = ReadyOutputs<Any>(
            latest = true,
            post = { turn.tasks.addLast(it); Unit },
            isCurrent = { it.codec === turn.codec },
            finish = { output, render ->
                turn.released += output to render
                if (render) {
                    rendered += 1
                    ready += 1
                }
            },
        )
        queue.offer(turn.output(1))
        queue.offer(turn.output(2))
        turn.runTurn()
        assertEquals(1, rendered)
        assertEquals(1, ready)
    }
}
