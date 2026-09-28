package com.gamebex.client.together.session

import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Emulator launches that a session end must wait for.
 *
 * Step cancellation and [enter] share one lock, so an end that cancels the
 * step jobs either sees a launch's marker or that launch never starts. Each
 * launch removes only its own marker, so overlapping launches keep each other's.
 */
internal class CriticalLaunches {
    private val lock = Any()
    private val active = mutableSetOf<CompletableJob>()

    /** Cancels [jobs] under the entry lock. */
    fun cancel(jobs: Collection<Job>) {
        synchronized(lock) { jobs.forEach { it.cancel() } }
    }

    /** Registers a marker for [step], or throws CancellationException if [step] is already cancelled. */
    fun enter(step: Job): CompletableJob = synchronized(lock) {
        step.ensureActive()
        Job().also { active += it }
    }

    fun exit(marker: CompletableJob) {
        synchronized(lock) { active -= marker }
        marker.complete()
    }

    /** Waits up to [timeoutMs] for every launch registered now. */
    suspend fun join(timeoutMs: Long) {
        val pending = synchronized(lock) { active.toList() }
        withTimeoutOrNull(timeoutMs) { pending.forEach { it.join() } }
    }
}
