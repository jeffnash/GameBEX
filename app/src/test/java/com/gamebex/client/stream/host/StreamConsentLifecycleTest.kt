package com.gamebex.client.stream.host

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StreamConsentLifecycleTest {
    private class Prompt {
        var launches = 0
        var closes = 0
        val close: () -> Unit = { closes++ }
        val launch: () -> Unit = { launches++ }
    }

    private fun StreamConsentLifecycle<String>.attach(request: StreamConsentLifecycle.Request<String>, prompt: Prompt) =
        attach(request.id, prompt, prompt.close, prompt.launch)

    @Test
    fun stopCancelsAnUnansweredRequestAndClosesItsPromptOnce() = runTest {
        val lifecycle = StreamConsentLifecycle<String>()
        val request = lifecycle.open()
        val prompt = Prompt()
        assertTrue(lifecycle.attach(request, prompt))
        val waiting = async { lifecycle.await(request, 60_000L) }
        runCurrent()

        lifecycle.stop()
        lifecycle.stop()
        runCurrent()

        assertTrue(request.result.isCancelled)
        assertTrue(waiting.isCancelled)
        assertEquals(1, prompt.launches)
        assertEquals(1, prompt.closes)
        assertFalse(lifecycle.answer(request.id, prompt, "late"))
    }

    @Test
    fun timeoutClosesTheOwnerAndRejectsLateConsent() = runTest {
        val lifecycle = StreamConsentLifecycle<String>()
        val request = lifecycle.open()
        val prompt = Prompt()
        lifecycle.attach(request, prompt)
        val waiting = async { lifecycle.await(request, 60_000L) }
        runCurrent()

        advanceTimeBy(59_999L)
        runCurrent()
        assertFalse(waiting.isCompleted)
        assertEquals(0, prompt.closes)
        advanceTimeBy(1L)
        runCurrent()

        assertNull(waiting.await())
        assertTrue(request.result.isCancelled)
        assertEquals(1, prompt.closes)
        assertFalse(lifecycle.answer(request.id, prompt, "late"))
        assertNull(lifecycle.runIfCurrent(request) { "capture" })
    }

    @Test
    fun coroutineCancellationClosesThePromptAndCancelsConsent() = runTest {
        val lifecycle = StreamConsentLifecycle<String>()
        val request = lifecycle.open()
        val prompt = Prompt()
        lifecycle.attach(request, prompt)
        val waiting = async { lifecycle.await(request, 60_000L) }
        runCurrent()

        waiting.cancel()
        runCurrent()
        lifecycle.cancel(request)

        assertTrue(request.result.isCancelled)
        assertEquals(1, prompt.closes)
        assertFalse(lifecycle.answer(request.id, prompt, "late"))
    }

    @Test
    fun newSessionCancelsTheOldOneAndIgnoresItsLateAnswerAndCleanup() = runTest {
        val lifecycle = StreamConsentLifecycle<String>()
        val old = lifecycle.open()
        val oldPrompt = Prompt()
        lifecycle.attach(old, oldPrompt)
        val current = lifecycle.open()
        val currentPrompt = Prompt()
        lifecycle.attach(current, currentPrompt)

        assertNotEquals(old.id, current.id)
        assertTrue(old.result.isCancelled)
        assertEquals(1, oldPrompt.closes)
        assertFalse(lifecycle.answer(old.id, oldPrompt, "old"))
        assertFalse(lifecycle.answer(current.id, oldPrompt, "wrong owner"))
        lifecycle.cancel(old)
        lifecycle.detach(old.id, oldPrompt, true)
        assertFalse(current.result.isCompleted)
        assertEquals(0, currentPrompt.closes)

        assertTrue(lifecycle.answer(current.id, currentPrompt, "accepted"))
        assertEquals("accepted", lifecycle.await(current, 60_000L))
        assertEquals(1, currentPrompt.closes)
        lifecycle.cancel(current)
        assertEquals(1, currentPrompt.closes)
    }

    @Test
    fun recreationRebindsTheOwnerWithoutLaunchingAnotherSystemPrompt() = runTest {
        val lifecycle = StreamConsentLifecycle<String>()
        val request = lifecycle.open()
        val original = Prompt()
        lifecycle.attach(request, original)
        lifecycle.detach(request.id, original, false)
        assertFalse(request.result.isCompleted)
        val recreated = Prompt()
        lifecycle.attach(request, recreated)
        lifecycle.attach(request, recreated)

        assertEquals(1, original.launches)
        assertEquals(0, recreated.launches)
        assertFalse(lifecycle.answer(request.id, original, "old instance"))
        lifecycle.detach(request.id, original, true)
        assertFalse(request.result.isCompleted)
        assertTrue(lifecycle.answer(request.id, recreated, "accepted"))
        assertEquals("accepted", lifecycle.await(request, 60_000L))
        assertEquals(0, original.closes)
        assertEquals(1, recreated.closes)
    }

    @Test
    fun staleOwnerDestructionCannotDetachTheRecreatedOwner() {
        val lifecycle = StreamConsentLifecycle<String>()
        val request = lifecycle.open()
        val original = Prompt()
        val recreated = Prompt()
        lifecycle.attach(request, original)
        lifecycle.attach(request, recreated)
        lifecycle.detach(request.id, original, false)
        lifecycle.detach(request.id, original, true)

        lifecycle.stop()

        assertTrue(request.result.isCancelled)
        assertEquals(0, original.closes)
        assertEquals(1, recreated.closes)
        assertEquals(0, recreated.launches)
    }

    @Test
    fun stopBetweenInstancesRejectsRecreationAndClosesItsInheritedPrompt() {
        val lifecycle = StreamConsentLifecycle<String>()
        val request = lifecycle.open()
        val original = Prompt()
        lifecycle.attach(request, original)
        lifecycle.detach(request.id, original, false)
        lifecycle.stop()
        val recreated = Prompt()

        assertFalse(lifecycle.attach(request, recreated))
        assertEquals(0, recreated.launches)
        assertEquals(1, recreated.closes)
        assertFalse(lifecycle.answer(request.id, recreated, "late"))
    }

    @Test
    fun anActivityRestoredAfterProcessDeathCannotAnswerAnotherSessionsConsent() {
        val priorProcess = StreamConsentLifecycle<String>()
        val stale = priorProcess.open()
        val restored = Prompt()
        val lifecycle = StreamConsentLifecycle<String>()
        val current = lifecycle.open()

        assertNotEquals(stale.id, current.id)
        assertFalse(lifecycle.attach(stale, restored))
        assertEquals(0, restored.launches)
        assertEquals(1, restored.closes)
        assertFalse(lifecycle.answer(stale.id, restored, "stale"))
        assertFalse(current.result.isCompleted)
    }

    @Test
    fun stopBeforeQueuedLaunchPreventsBothTheOwnerAndSystemPromptStarting() {
        val lifecycle = StreamConsentLifecycle<String>()
        val request = lifecycle.open()
        var ownerLaunches = 0
        lifecycle.stop()

        assertNull(lifecycle.runIfCurrent(request) { ownerLaunches++; true })
        val lateOwner = Prompt()
        assertFalse(lifecycle.attach(request, lateOwner))
        assertEquals(0, ownerLaunches)
        assertEquals(0, lateOwner.launches)
        assertEquals(1, lateOwner.closes)
    }

    @Test
    fun stopAfterAnAnswerPreventsQueuedCaptureStarting() {
        val lifecycle = StreamConsentLifecycle<String>()
        val request = lifecycle.open()
        val prompt = Prompt()
        lifecycle.attach(request, prompt)
        assertTrue(lifecycle.answer(request.id, prompt, "accepted"))
        lifecycle.stop()
        var captures = 0

        assertNull(lifecycle.runIfCurrent(request) { captures++; true })
        assertEquals(0, captures)
        assertEquals(1, prompt.closes)
    }

    @Test
    fun finishingAnUnansweredOwnerCancelsConsentButRotationDoesNot() {
        val lifecycle = StreamConsentLifecycle<String>()
        val request = lifecycle.open()
        val prompt = Prompt()
        lifecycle.attach(request, prompt)

        lifecycle.detach(request.id, prompt, true)
        lifecycle.stop()

        assertTrue(request.result.isCancelled)
        assertEquals(1, prompt.closes)
    }

    @Test
    fun duplicateResultsCannotReplaceTheAcceptedAnswer() = runTest {
        val lifecycle = StreamConsentLifecycle<String>()
        val request = lifecycle.open()
        val prompt = Prompt()
        lifecycle.attach(request, prompt)

        assertTrue(lifecycle.answer(request.id, prompt, "first"))
        assertFalse(lifecycle.answer(request.id, prompt, "second"))
        assertEquals("first", lifecycle.await(request, 60_000L))
        assertEquals(1, prompt.closes)
        lifecycle.cancel(request)
        assertEquals(1, prompt.closes)
    }

    @Test
    fun rejectedLateStartDoesNotReplaceACurrentRequest() {
        val lifecycle = StreamConsentLifecycle<String>()
        val current = lifecycle.open()
        val prompt = Prompt()
        lifecycle.attach(current, prompt)

        assertNull(lifecycle.openIfAllowed { false })
        assertFalse(current.result.isCompleted)
        assertEquals(0, prompt.closes)
        assertTrue(lifecycle.answer(current.id, prompt, "current"))
    }

    @Test
    fun invalidatedSessionCannotLaunchAPromptBeforeQueuedStopRuns() {
        val lifecycle = StreamConsentLifecycle<String>()
        var allowed = true
        val request = lifecycle.openIfAllowed { allowed }!!
        allowed = false
        val prompt = Prompt()

        assertFalse(lifecycle.attach(request, prompt))
        assertEquals(0, prompt.launches)
        assertEquals(1, prompt.closes)
        assertTrue(request.result.isCancelled)
    }

    @Test
    fun invalidatedSessionCannotStartQueuedOwnerActivity() {
        val lifecycle = StreamConsentLifecycle<String>()
        var allowed = true
        val request = lifecycle.openIfAllowed { allowed }!!
        allowed = false
        var launches = 0

        assertNull(lifecycle.runIfCurrent(request) { launches++; true })
        assertEquals(0, launches)
        assertTrue(request.result.isCancelled)
    }

    @Test
    fun invalidatedSessionCannotAcceptAnAnswerBeforeQueuedStopRuns() {
        val lifecycle = StreamConsentLifecycle<String>()
        var allowed = true
        val request = lifecycle.openIfAllowed { allowed }!!
        val prompt = Prompt()
        lifecycle.attach(request, prompt)
        allowed = false

        assertFalse(lifecycle.answer(request.id, prompt, "late"))
        assertTrue(request.result.isCancelled)
        assertEquals(1, prompt.closes)
    }

    @Test
    fun invalidatedSessionCannotCommitAnAlreadyAcceptedCapture() {
        val lifecycle = StreamConsentLifecycle<String>()
        var allowed = true
        val request = lifecycle.openIfAllowed { allowed }!!
        val prompt = Prompt()
        lifecycle.attach(request, prompt)
        assertTrue(lifecycle.answer(request.id, prompt, "accepted"))
        allowed = false
        var captures = 0

        assertNull(lifecycle.runIfCurrent(request) { captures++; true })
        assertEquals(0, captures)
        assertEquals(1, prompt.closes)
    }

    @Test
    fun recreationGapRetainsTheCloseHookButRejectsOldInstanceAnswers() {
        val lifecycle = StreamConsentLifecycle<String>()
        val request = lifecycle.open()
        val prompt = Prompt()
        lifecycle.attach(request, prompt)
        lifecycle.detach(request.id, prompt, false)
        assertEquals(0, prompt.closes)
        assertFalse(lifecycle.answer(request.id, prompt, "destroyed owner"))

        lifecycle.stop()

        assertEquals(1, prompt.closes)
        assertTrue(request.result.isCancelled)
    }

    @Test
    fun stopSerializesWithTheActualSystemPromptLaunch() {
        val lifecycle = StreamConsentLifecycle<String>()
        val request = lifecycle.open()
        val prompt = Prompt()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val attemptedStop = CountDownLatch(1)
        val threads = Executors.newFixedThreadPool(2)
        try {
            val launching = threads.submit<Boolean> {
                lifecycle.attach(request.id, prompt, prompt.close) {
                    entered.countDown()
                    assertTrue(release.await(5, TimeUnit.SECONDS))
                    prompt.launch()
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val stopping = threads.submit {
                attemptedStop.countDown()
                lifecycle.stop()
            }
            assertTrue(attemptedStop.await(5, TimeUnit.SECONDS))
            assertFalse(stopping.isDone)
            release.countDown()
            assertTrue(launching.get(5, TimeUnit.SECONDS))
            stopping.get(5, TimeUnit.SECONDS)

            assertEquals(1, prompt.launches)
            assertEquals(1, prompt.closes)
            assertTrue(request.result.isCancelled)
            val lateOwner = Prompt()
            assertFalse(lifecycle.attach(request, lateOwner))
            assertEquals(0, lateOwner.launches)
        } finally {
            release.countDown()
            threads.shutdownNow()
        }
    }
}
