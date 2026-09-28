package com.gamebex.client.data.local

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Background prep after download: ordering, idempotence, dedup, failure handling. */
class AutoPrepTest {
    private fun game(id: Long = 1, prep: String = PrepPipeline.PREP_NONE, available: Boolean = true) =
        GameEntity(
            id = id, title = "Game $id", folder = "psx", volumeId = "internal",
            relPath = "psx/g$id.7z", fileName = "g$id.7z", sizeBytes = 1, mtimeMs = 1, ext = "7z",
            available = available, prepState = prep,
        )

    private class Harness(val db: FakeLibraryDb, var result: String = PrepPipeline.PREP_READY) {
        val prepared = mutableListOf<Long>()
        val ready = mutableListOf<Long>()
        val order = mutableListOf<String>()
        var failWith: Exception? = null

        fun auto(scope: kotlinx.coroutines.CoroutineScope) = AutoPrep(
            scope = scope,
            findGame = { db.games().findById(it) },
            prepare = { id ->
                order += "prep"
                failWith?.let { throw it }
                prepared += id
                db.games().setPrep(id, result, null, null)
                result
            },
            onReady = { ready += it.id },
        )
    }

    @Test fun seedsThenPreparesThenNotifiesReady() = runTest {
        val h = Harness(FakeLibraryDb(mutableListOf(game())))
        h.auto(this).kick(1) { h.order += "seed" }
        advanceUntilIdle()
        assertEquals(listOf("seed", "prep"), h.order)
        assertEquals(listOf(1L), h.prepared)
        assertEquals(listOf(1L), h.ready)
    }

    @Test fun activeTracksInFlightGameForDetail() = runTest {
        val h = Harness(FakeLibraryDb(mutableListOf(game(id = 42))))
        val gate = CompletableDeferred<Unit>()
        h.auto(this).kick(42) { gate.await() }
        assertTrue(42L in AutoPrep.active.value)
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(42L !in AutoPrep.active.value)
    }

    @Test fun alreadyReadySkipsPrepButStillNotifies() = runTest {
        val h = Harness(FakeLibraryDb(mutableListOf(game(prep = PrepPipeline.PREP_READY))))
        h.auto(this).kick(1)
        advanceUntilIdle()
        assertTrue(h.prepared.isEmpty())
        assertEquals(listOf(1L), h.ready)
    }

    @Test fun notReadyResultNeverNotifies() = runTest {
        val h = Harness(FakeLibraryDb(mutableListOf(game())), result = PrepPipeline.PREP_NEEDS_BIOS)
        h.auto(this).kick(1)
        advanceUntilIdle()
        assertEquals(listOf(1L), h.prepared)
        assertTrue(h.ready.isEmpty())
    }

    @Test fun missingOrUnavailableRowIsSkipped() = runTest {
        val h = Harness(FakeLibraryDb(mutableListOf(game(id = 2, available = false))))
        val auto = h.auto(this)
        auto.kick(1)
        auto.kick(2)
        advanceUntilIdle()
        assertTrue(h.prepared.isEmpty())
        assertTrue(h.ready.isEmpty())
    }

    @Test fun seedFailureStillPreparesAndPrepFailureIsContained() = runTest {
        val h = Harness(FakeLibraryDb(mutableListOf(game(1), game(2))))
        val auto = h.auto(this)
        auto.kick(1) { throw IllegalStateException("no network") }
        advanceUntilIdle()
        assertEquals(listOf(1L), h.ready)
        h.failWith = IllegalStateException("disk full")
        auto.kick(2)
        advanceUntilIdle()
        assertEquals(listOf(1L), h.ready)
    }

    @Test fun cancellationPropagates() = runTest {
        val h = Harness(FakeLibraryDb(mutableListOf(game())))
        h.failWith = CancellationException("scope gone")
        var thrown: Throwable? = null
        try {
            h.auto(this).run(1)
        } catch (e: CancellationException) {
            thrown = e
        }
        assertTrue(thrown is CancellationException)
        assertTrue(h.ready.isEmpty())
    }

    @Test fun duplicateKickWhileRunningIsDropped() {
        val scope = TestScope(StandardTestDispatcher())
        val h = Harness(FakeLibraryDb(mutableListOf(game())))
        val gate = CompletableDeferred<Unit>()
        val auto = h.auto(scope)
        val first = auto.kick(1) { gate.await() }
        scope.testScheduler.advanceUntilIdle()
        assertNull(auto.kick(1))
        gate.complete(Unit)
        scope.testScheduler.advanceUntilIdle()
        assertTrue(first!!.isCompleted)
        assertEquals(listOf(1L), h.prepared)
        // Finished: a later kick runs again (idempotent — READY skips prep).
        auto.kick(1)
        scope.testScheduler.advanceUntilIdle()
        assertEquals(listOf(1L), h.prepared)
        assertEquals(listOf(1L, 1L), h.ready)
    }
}
