package com.gamebex.client

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * ViewModel tests run viewModelScope on a real dispatcher and poll real
 * state: fakes answer in milliseconds, so polling with a deadline is
 * deterministic without virtual-time control of Dispatchers.IO.
 */
class MainDispatcherRule : TestWatcher() {
    override fun starting(description: Description) {
        // Once per JVM: re-setting Main while a previous test's leftover
        // viewModelScope work is dispatching throws "Dispatchers.Main is
        // used concurrently with setting it".
        if (installed.compareAndSet(false, true)) Dispatchers.setMain(Dispatchers.Default)
    }

    private companion object {
        val installed = java.util.concurrent.atomic.AtomicBoolean(false)
    }

    // Deliberately no resetMain(): ViewModels built in a test are never
    // cleared, so their viewModelScope work can still dispatch to Main
    // after the test ends. Resetting Main made that leftover work fail
    // inside whichever test ran next ("Main dispatcher failed to
    // initialize" flakes in AutoPrepTest/QuickDownloadTest). Keeping Main
    // on Default for the whole JVM run is harmless for these tests.
}

suspend fun awaitTrue(timeoutMs: Long = 5_000, cond: () -> Boolean) {
    val end = System.currentTimeMillis() + timeoutMs
    while (!cond()) {
        if (System.currentTimeMillis() > end) error("timed out waiting for condition")
        delay(10)
    }
}
