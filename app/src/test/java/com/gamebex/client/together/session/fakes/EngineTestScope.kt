package com.gamebex.client.together.session.fakes

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Runs engine jobs on the caller's test dispatcher without making them children
 * of runTest. The parent completion callback closes the independent job so the
 * engine actor cannot outlive its test.
 */
class EngineTestScope(parent: CoroutineScope) : CoroutineScope, AutoCloseable {
    private val job = SupervisorJob()

    override val coroutineContext: CoroutineContext =
        parent.coroutineContext.minusKey(Job) + job

    init {
        parent.coroutineContext[Job]?.invokeOnCompletion { close() }
    }

    override fun close() {
        job.cancel()
    }
}

fun CoroutineScope.engineTestScope(): EngineTestScope = EngineTestScope(this)
