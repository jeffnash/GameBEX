package com.gamebex.client.stream.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class RetroArchActivityProbeTest {
    @Test fun foregroundAndBackgroundActivitiesRemainAlive() {
        for (state in listOf("RESUMED", "PAUSED", "STOPPING", "STOPPED")) {
            assertTrue(state, RetroArchActivityProbe.parseSnapshot(snapshot(activity(state = state))))
        }
    }

    @Test fun cachedProviderAndRootTaskSummariesDoNotCountAsActivity() {
        val providerOnly = """
              * Task{abc #1528 type=standard A=10496:com.retroarch.aarch64 sz=0}
                intent={cmp=$COMPONENT}
                topResumedActivity=ActivityRecord{abc u0 $COMPONENT t1528}
                app=ProcessRecord{490ad65 25968:com.retroarch.aarch64/u0a496}
                provider=com.retroarch.browser.retroactivity.RetroDocumentsProvider
                mActivityComponent=$COMPONENT
                state=RESUMED delayedResume=false finishing=false
        """.trimIndent()
        assertFalse(RetroArchActivityProbe.parseSnapshot(snapshot(providerOnly)))
    }

    @Test fun completeSnapshotWithoutActivitiesIsAbsent() {
        assertFalse(RetroArchActivityProbe.parseSnapshot(snapshot()))
    }

    @Test fun onlyExactComponentCounts() {
        for (component in listOf(
            "com.retroarch.aarch64/com.retroarch.browser.retroactivity.RetroActivityPast",
            "${COMPONENT}Other",
            "com.other/com.retroarch.browser.retroactivity.RetroActivityFuture",
        )) {
            assertFalse(component, RetroArchActivityProbe.parseSnapshot(snapshot(activity(component = component))))
        }
    }

    @Test fun finishingDestroyedAndMissingProcessActivitiesAreAbsent() {
        assertFalse(RetroArchActivityProbe.parseSnapshot(snapshot(activity(finishing = true))))
        for (state in listOf("FINISHING", "DESTROYING", "DESTROYED")) {
            assertFalse(state, RetroArchActivityProbe.parseSnapshot(snapshot(activity(state = state))))
        }
        for (app in listOf("null", "ProcessRecord{490ad65 0:com.retroarch.aarch64/u0a496}", "ProcessRecord{490ad65 -1:com.retroarch.aarch64/u0a496}")) {
            assertFalse(app, RetroArchActivityProbe.parseSnapshot(snapshot(activity(app = app))))
        }
    }

    @Test fun destroyedExitingRecordWithDumpSuffixIsAbsent() {
        val exiting = activity(state = "DESTROYED", finishing = true, app = "null")
            .replace("ActivityRecord{51382743 u0 $COMPONENT t1528}",
                "ActivityRecord{252162089 u0 $COMPONENT t1540 f} isExiting}")
            .replace("keysPaused=false inHistory=true idle=true", "keysPaused=false inHistory=false idle=false\n      isExiting=true")
        val otherActivity = activity(component = "com.gamebex.client/.MainActivity")
        assertFalse(RetroArchActivityProbe.parseSnapshot(snapshot(otherActivity + "\n" + exiting)))
    }

    @Test fun malformedAndTruncatedExitingRecordsRemainUncertain() {
        val header = "ActivityRecord{51382743 u0 $COMPONENT t1528}"
        val exiting = activity(state = "DESTROYED", finishing = true, app = "null")
            .replace(header, "$header isExiting}")
        val variants = listOf(
            exiting.replace("} isExiting}", "} isExiting"),
            exiting.replace("} isExiting}", "} isExiting}}"),
            exiting.replace("} isExiting}", "} isExitin}"),
            exiting.replace("} isExiting}", "} isExiting} unexpected"),
            exiting.replace("} isExiting}", " isExiting"),
            exiting.replace("      app=null\n", ""),
            exiting.replace("      mActivityComponent=$COMPONENT\n", ""),
            exiting.replace("state=DESTROYED", "state=UNKNOWN"),
            exiting.replace("finishing=true", "finishing=unknown"),
        )
        for (variant in variants) {
            assertThrows(IOException::class.java) { RetroArchActivityProbe.parseSnapshot(snapshot(activity() + "\n" + variant)) }
        }
        val complete = snapshot(exiting)
        assertThrows(IOException::class.java) { RetroArchActivityProbe.parseSnapshot(complete.removeSuffix("\n")) }
        assertThrows(IOException::class.java) { RetroArchActivityProbe.parseSnapshot(complete.substringBefore("ActivityTaskSupervisor state:")) }
    }

    @Test fun anyLiveHistRecordWinsOverDeadRecordsAndOtherDisplays() {
        val records = activity(app = "null") + "\n" + activity(state = "STOPPED") + "\n" +
            "Display #17 (activities from top to bottom):\n" + activity(finishing = true)
        assertTrue(RetroArchActivityProbe.parseSnapshot(snapshot(records)))
    }

    @Test fun fieldsFromAnotherRecordOrNestedSummaryCannotMakeActivityLive() {
        val missingApp = activity().replace("      app=ProcessRecord{490ad65 25968:com.retroarch.aarch64/u0a496}\n", "")
        val nestedApp = missingApp.replace("      packageName=", "        app=ProcessRecord{490ad65 25968:com.retroarch.aarch64/u0a496}\n      packageName=")
        for (records in listOf(missingApp + "\n" + activity(), nestedApp)) {
            assertThrows(IOException::class.java) { RetroArchActivityProbe.parseSnapshot(snapshot(records)) }
        }
    }

    @Test fun unknownAndTransitionalStatesRemainUncertain() {
        for (state in listOf("UNKNOWN", "INITIALIZING", "STARTED", "PAUSING", "RESTARTING_PROCESS")) {
            assertThrows(IOException::class.java) { RetroArchActivityProbe.parseSnapshot(snapshot(activity(state = state))) }
        }
    }

    @Test fun missingDuplicateAndMalformedFieldsRemainUncertain() {
        val record = activity()
        val variants = listOf(
            record.replace("app=ProcessRecord{490ad65 25968:com.retroarch.aarch64/u0a496}", "app=ProcessRecord{broken}"),
            record.replace("25968:com.retroarch", "999999999999999:com.retroarch"),
            record.replace("finishing=false", "finishing=unknown"),
            record.replace("finishing=false", "finishing=false finishing=true"),
            record.replace("state=RESUMED", "state=RESUMED state=STOPPED"),
            record.replace(" delayedResume=false finishing=false", ""),
            record.replace("mActivityComponent=$COMPONENT", "mActivityComponent=$COMPONENT\n      mActivityComponent=$COMPONENT"),
            record.replace("mActivityComponent=$COMPONENT", "mActivityComponent=com.other/.MainActivity"),
            record.replace("* Hist  #0: ActivityRecord{51382743", "* Hist  #0: ActivityRecord{broken{"),
            record.replace("      state=RESUMED delayedResume=false finishing=false\n", ""),
        )
        for (variant in variants) {
            assertThrows(IOException::class.java) { RetroArchActivityProbe.parseSnapshot(snapshot(variant)) }
        }
    }

    @Test fun deniedUnknownAndTruncatedSnapshotsRemainUncertain() {
        val complete = snapshot(activity())
        for (dump in listOf(
            "",
            "Permission Denial: can't dump ActivityManager\n",
            "java.lang.SecurityException: denied\n",
            "No activities\n",
            complete.removeSuffix("\n"),
            complete.substringBefore("ActivityTaskSupervisor state:"),
            complete.replace("  mNoHistoryActivities=[]\n", ""),
            complete.replace("Display #0 (activities from top to bottom):\n", ""),
            complete + "Error: service unavailable\n",
            complete + "DUMP TIMEOUT\n",
            complete + "... truncated ...\n",
        )) {
            assertThrows(IOException::class.java) { RetroArchActivityProbe.parseSnapshot(dump) }
        }
    }

    @Test fun malformedLaterRecordCannotTurnUncertainDumpIntoAlive() {
        val records = activity() + "\n" + activity().replace("finishing=false", "finishing=?")
        assertThrows(IOException::class.java) { RetroArchActivityProbe.parseSnapshot(snapshot(records)) }
    }

    @Test fun successfulExecutionParsesSnapshotAndClosesChildStreams() {
        for (records in listOf("", activity())) {
            val process = FakeProcess(snapshot(records).byteInputStream())
            val probe = RetroArchActivityProbe(startProcess = { process })
            assertEquals(records.isNotEmpty(), probe.isAlive())
            assertTrue(process.streamsClosed.await(1, TimeUnit.SECONDS))
            assertTrue(process.destroyed.get())
        }
    }

    @Test fun startupReadAndNonzeroExitFailuresThrowInsteadOfReportingAbsence() {
        val startup = RetroArchActivityProbe(startProcess = { throw IOException("start denied") })
        assertThrows(IOException::class.java) { startup.isAlive() }
        val readFailure = object : InputStream() {
            override fun read(): Int = throw IOException("pipe failed")
        }
        for (process in listOf(
            FakeProcess(readFailure),
            FakeProcess(snapshot().byteInputStream(), exitCode = 1),
            FakeProcess("Permission Denial: can't dump ActivityManager\n".byteInputStream()),
            FakeProcess(ByteArrayInputStream(byteArrayOf(0xc3.toByte(), 0x28))),
        )) {
            assertThrows(IOException::class.java) { RetroArchActivityProbe(startProcess = { process }).isAlive() }
            assertTrue(process.destroyed.get())
        }
    }

    @Test fun outputLimitThrowsAndDestroysChild() {
        val process = FakeProcess(ByteArrayInputStream(ByteArray(257) { 'x'.code.toByte() }))
        val probe = RetroArchActivityProbe(maxOutputBytes = 256, startProcess = { process })
        assertThrows(IOException::class.java) { probe.isAlive() }
        assertTrue(process.destroyed.get())
    }

    @Test fun stalledOutputAndStalledExitAreBoundedAndCleaned() {
        val blockedRead = object : InputStream() {
            override fun read(): Int {
                CountDownLatch(1).await()
                return -1
            }
        }
        for (process in listOf(FakeProcess(blockedRead), FakeProcess(snapshot().byteInputStream(), exitStalls = true))) {
            val probe = RetroArchActivityProbe(timeoutMs = 250, startProcess = { process })
            val started = System.nanoTime()
            assertThrows(IOException::class.java) { probe.isAlive() }
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2_000)
            assertTrue(process.destroyed.get())
            assertTrue(process.streamsClosed.await(1, TimeUnit.SECONDS))
        }
    }

    @Test fun delayedStartupIsBoundedAndLateChildIsDestroyedWithoutProbeFanout() {
        val release = CountDownLatch(1)
        val startupEntered = CountDownLatch(1)
        val starts = AtomicInteger()
        val process = FakeProcess(snapshot().byteInputStream())
        val probe = RetroArchActivityProbe(timeoutMs = 250, startProcess = {
            starts.incrementAndGet()
            startupEntered.countDown()
            awaitUninterruptibly(release)
            process
        })
        try {
            assertThrows(IOException::class.java) { probe.isAlive() }
            assertTrue(startupEntered.await(1, TimeUnit.SECONDS))
            assertEquals(1, starts.get())
            assertThrows(IOException::class.java) { probe.isAlive() }
            assertEquals(1, starts.get())
        } finally {
            release.countDown()
        }
        assertTrue(process.streamsClosed.await(1, TimeUnit.SECONDS))
        assertTrue(process.destroyed.get())
    }

    @Test fun interruptionThrowsPreservesInterruptAndCleansChild() {
        val enteredRead = CountDownLatch(1)
        val process = FakeProcess(object : InputStream() {
            override fun read(): Int {
                enteredRead.countDown()
                CountDownLatch(1).await()
                return -1
            }
        })
        val probe = RetroArchActivityProbe(startProcess = { process })
        val interrupted = AtomicBoolean()
        val finished = CountDownLatch(1)
        val caller = Thread {
            try {
                assertThrows(IOException::class.java) { probe.isAlive() }
                interrupted.set(Thread.currentThread().isInterrupted)
            } finally {
                finished.countDown()
            }
        }
        caller.start()
        try {
            assertTrue(enteredRead.await(1, TimeUnit.SECONDS))
        } finally {
            caller.interrupt()
        }
        assertTrue(finished.await(1, TimeUnit.SECONDS))
        assertTrue(interrupted.get())
        assertTrue(process.destroyed.get())
        assertTrue(process.streamsClosed.await(1, TimeUnit.SECONDS))
    }

    private fun snapshot(records: String = ""): String =
        "ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)\n" +
            "Display #0 (activities from top to bottom):\n" + records + "\n" +
            "  Resumed activities in task display areas (from top to bottom):\n\n" +
            "ActivityTaskSupervisor state:\n  mNoHistoryActivities=[]\n"

    private fun activity(
        component: String = COMPONENT,
        state: String = "RESUMED",
        finishing: Boolean = false,
        app: String = "ProcessRecord{490ad65 25968:com.retroarch.aarch64/u0a496}",
    ): String = listOf(
        "  * Task{36975ad #1528 type=standard A=10496:com.retroarch.aarch64 sz=1}",
        "    * Hist  #0: ActivityRecord{51382743 u0 $component t1528}",
        "      packageName=com.retroarch.aarch64 processName=com.retroarch.aarch64",
        "      app=$app",
        "      Intent { cmp=$component (has extras) }",
        "      mActivityComponent=$component",
        "      state=$state delayedResume=false finishing=$finishing",
        "      keysPaused=false inHistory=true idle=true",
        "",
    ).joinToString("\n")

    private class FakeProcess(
        private val input: InputStream,
        private val exitCode: Int = 0,
        private val exitStalls: Boolean = false,
    ) : Process() {
        val destroyed = AtomicBoolean()
        val streamsClosed = CountDownLatch(1)
        private val output = object : ByteArrayOutputStream() {
            override fun close() {
                super.close()
                streamsClosed.countDown()
            }
        }

        override fun getOutputStream() = output
        override fun getInputStream() = input
        override fun getErrorStream() = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int = throw AssertionError("Unbounded waitFor must not be used")
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            if (!exitStalls) return true
            CountDownLatch(1).await(timeout, unit)
            return false
        }
        override fun exitValue(): Int = exitCode
        override fun destroy() { destroyed.set(true) }
        override fun destroyForcibly(): Process { destroy(); return this }
    }

    companion object {
        private const val COMPONENT = "com.retroarch.aarch64/com.retroarch.browser.retroactivity.RetroActivityFuture"

        private fun awaitUninterruptibly(latch: CountDownLatch) {
            while (latch.count > 0) {
                try {
                    latch.await()
                } catch (_: InterruptedException) {
                }
            }
        }
    }
}
