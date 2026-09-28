package com.gamebex.client.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class DownloadEtaTest {

    private class Clock(var now: Long = 0L) {
        val tick: () -> Long = { now }
    }

    @Test fun firstObservationHasNoRate() {
        val clock = Clock(now = 0)
        val eta = DownloadEta(clock = clock.tick)
        assertNull(eta.patch(id = 1, read = 0, total = 1_000).etaSec)
    }

    @Test fun etaFromSteadyRate() {
        val clock = Clock(now = 0)
        val eta = DownloadEta(clock = clock.tick)
        eta.patch(id = 1, read = 0, total = 1_000)
        clock.now = 1_000
        // 500 bytes in 1 s -> 500 B/s -> 500 left = 1 s.
        assertEquals(1L, eta.patch(id = 1, read = 500, total = 1_000).etaSec)
    }

    @Test fun samplesCloserThanOneSecondDoNotMoveTheRate() {
        val clock = Clock(now = 0)
        val eta = DownloadEta(clock = clock.tick)
        eta.patch(id = 1, read = 0, total = 10_000)
        clock.now = 1_000
        eta.patch(id = 1, read = 1_000, total = 10_000) // 1000 B/s
        clock.now = 1_500 // 500 ms later: not a sample.
        // Rate stays 1000 B/s, 9000 left -> 9 s.
        assertEquals(9L, eta.patch(id = 1, read = 1_000, total = 10_000).etaSec)
    }

    @Test fun emaBlendsOldAndNewRates() {
        val clock = Clock(now = 0)
        val eta = DownloadEta(alpha = 0.3, clock = clock.tick)
        eta.patch(id = 1, read = 0, total = 10_000)
        clock.now = 1_000
        eta.patch(id = 1, read = 1_000, total = 10_000) // rate 1000
        clock.now = 2_000
        // Second sample: 1000 bytes in 1 s -> instant 1000; ema stays 1000.
        assertEquals(8L, eta.patch(id = 1, read = 2_000, total = 10_000).etaSec)
        clock.now = 3_000
        // Third sample: 3000 bytes in 1 s -> instant 3000;
        // ema = 0.3*3000 + 0.7*1000 = 1600; 5000 left -> 3 s.
        assertEquals(3L, eta.patch(id = 1, read = 5_000, total = 10_000).etaSec)
    }

    /**
     * An out-of-order patch (shrinking read, same status) keeps the max
     * read — progress never steps backwards. Only a status change
     * (a genuine restart) re-seeds.
     */
    @Test fun outOfOrderPatchesKeepTheMaxReadUntilTheStatusChanges() {
        val clock = Clock(now = 0)
        val eta = DownloadEta(clock = clock.tick)
        assertEquals(1_000L, eta.patch(id = 1, read = 1_000, total = 10_000, status = "active").read)
        clock.now = 1_000
        assertEquals(1_000L, eta.patch(id = 1, read = 100, total = 10_000, status = "active").read)
        assertEquals(1_000L, eta.patch(id = 1, read = 900, total = 10_000, status = "active").read)
        assertEquals(100L, eta.patch(id = 1, read = 100, total = 10_000, status = "paused").read)
    }

    @Test fun unknownTotalHasNoEta() {
        val eta = DownloadEta()
        assertNull(eta.patch(id = 1, read = 5, total = 0).etaSec)
    }

    @Test fun finishedJobIsZeroSeconds() {
        val eta = DownloadEta()
        assertEquals(0L, eta.patch(id = 1, read = 100, total = 100).etaSec)
    }

    @Test fun jobsAreIndependentAndRemovable() {
        val clock = Clock(now = 0)
        val eta = DownloadEta(clock = clock.tick)
        eta.patch(id = 1, read = 0, total = 10_000)
        eta.patch(id = 2, read = 0, total = 10_000)
        clock.now = 1_000
        eta.patch(id = 1, read = 1_000, total = 10_000)
        eta.remove(id = 1)
        assertNull(eta.patch(id = 1, read = 1_000, total = 10_000).etaSec)
        // Job 2 keeps its own history: 1 s of zero progress is a 0 rate.
        assertNull(eta.patch(id = 2, read = 0, total = 10_000).etaSec)
    }

    /**
     * Patches arrive from the rebuild and the progress paths on different
     * dispatchers — concurrent patches on distinct jobs keep independent
     * histories.
     */
    @Test fun concurrentPatchesOnDistinctJobsKeepIndependentHistories() {
        val eta = DownloadEta()
        val threads = (1..8).map { t ->
            Thread {
                repeat(2_000) { i ->
                    eta.patch(id = t.toLong(), read = (i + 1).toLong(), total = 100_000, status = "active")
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        for (t in 1..8L) {
            assertEquals(
                "job $t lost progress to a race",
                2_000L,
                eta.patch(id = t, read = 0, total = 100_000, status = "active").read,
            )
        }
    }

    /**
     * Rebuild and patchProgress also race on the SAME job id. The
     * read-modify-write must be atomic per job — once a patch has returned
     * a max, no later look may see less (a stale concurrent write must
     * never win). Every worker checks that invariant right after each
     * patch (snapshot before look), and the quiescence look must still
     * return the highest read ever passed (18_000).
     */
    @Test fun concurrentSameIdPatchesNeverRegressTheStoredMax() {
        val eta = DownloadEta()
        val rounds = 2_000
        val completedMax = java.util.concurrent.atomic.AtomicLong(0)
        val failures = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val workers = (1..8).map { t ->
            Thread {
                for (i in 1..rounds) {
                    val r = eta.patch(id = 1, read = (t * rounds + i).toLong(), total = 10_000_000, status = "active")
                    completedMax.updateAndGet { cur -> maxOf(cur, r.read) }
                    // Snapshot first: every value in it completed before the look.
                    val seen = completedMax.get()
                    val look = eta.patch(id = 1, read = 0, total = 10_000_000, status = "active").read
                    if (look < seen) failures += "stored $look < completed max $seen"
                }
            }
        }
        workers.forEach { it.start() }
        workers.forEach { it.join() }
        if (failures.isNotEmpty()) fail(failures.first())
        assertEquals(
            18_000L,
            eta.patch(id = 1, read = 0, total = 10_000_000, status = "active").read,
        )
    }
}
