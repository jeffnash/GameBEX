package com.gamebex.client.stream.shell

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal class RetroArchActivityProbe(
    private val timeoutMs: Long = 1_000,
    private val maxOutputBytes: Int = 256 * 1024,
    private val startProcess: () -> Process = {
        ProcessBuilder("dumpsys", "activity", "activities", PACKAGE).redirectErrorStream(true).start()
    },
) {
    private val running = AtomicBoolean()

    init {
        require(timeoutMs in 1..10_000)
        require(maxOutputBytes in 1..1024 * 1024)
    }

    fun isAlive(): Boolean {
        if (!running.compareAndSet(false, true)) throw IOException("RetroArch Activity probe still running")
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        val stopped = AtomicBoolean()
        val child = AtomicReference<Process?>()
        val task = FutureTask {
            val process = startProcess()
            child.set(process)
            try {
                if (stopped.get()) throw IOException("RetroArch Activity probe cancelled")
                val output = ByteArrayOutputStream(minOf(maxOutputBytes, 8192))
                val buffer = ByteArray(8192)
                val input = process.inputStream
                while (true) {
                    if (stopped.get() || System.nanoTime() >= deadline) {
                        throw IOException("RetroArch Activity probe timed out")
                    }
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count > maxOutputBytes - output.size()) {
                        throw IOException("RetroArch Activity probe output limit exceeded")
                    }
                    output.write(buffer, 0, count)
                }
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0 || !process.waitFor(remaining, TimeUnit.NANOSECONDS)) {
                    throw IOException("RetroArch Activity probe timed out")
                }
                if (process.exitValue() != 0) throw IOException("RetroArch Activity dumpsys failed: ${process.exitValue()}")
                val snapshot = Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(output.toByteArray())).toString()
                parseSnapshot(snapshot)
            } finally {
                runCatching { process.destroyForcibly() }
                runCatching { process.inputStream.close() }
                runCatching { process.errorStream.close() }
                runCatching { process.outputStream.close() }
            }
        }
        val worker = Thread({
            try {
                task.run()
            } finally {
                running.set(false)
            }
        }, "RetroArchActivityProbe").apply { isDaemon = true }
        try {
            worker.start()
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) throw TimeoutException()
            return task.get(remaining, TimeUnit.NANOSECONDS)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("RetroArch Activity probe interrupted", error)
        } catch (error: TimeoutException) {
            throw IOException("RetroArch Activity probe timed out", error)
        } catch (error: ExecutionException) {
            throw IOException("RetroArch Activity probe failed", error.cause)
        } finally {
            stopped.set(true)
            runCatching { child.get()?.destroyForcibly() }
            task.cancel(true)
            if (worker.state == Thread.State.NEW) running.set(false)
        }
    }

    companion object {
        private const val PACKAGE = "com.retroarch.aarch64"
        private const val COMPONENT = "$PACKAGE/com.retroarch.browser.retroactivity.RetroActivityFuture"
        private const val HEADER = "ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)"
        private const val SUPERVISOR = "ActivityTaskSupervisor state:"
        private val displayHeader = Regex("Display #\\d+ \\(activities from top to bottom\\):")
        private val historyStart = Regex("\\*\\s+Hist\\b.*")
        private val historyHeader = Regex("\\*\\s+Hist\\s+#\\d+:\\s+ActivityRecord\\{[^\\s{}]+\\s+u\\d+\\s+([\\w.$]+/[\\w.$]+)\\s+t\\d+(?:\\s+[^{}]*)?\\}(?: isExiting\\})?")
        private val processRecord = Regex("ProcessRecord\\{[^\\s{}]+\\s+(-?\\d+):[^/\\s{}]+/[^\\s{}]+\\}")
        private val stateField = Regex("(?:^|\\s)state=([A-Z_]+)(?=\\s|$)")
        private val finishingField = Regex("(?:^|\\s)finishing=(true|false)(?=\\s|$)")
        private val snapshotTail = Regex("mNoHistoryActivities=\\[.*\\]")
        private val failure = Regex("permission (?:denial|denied)|securityexception|timeout|timed out|truncat|^\\s*(?:error|exception):", RegexOption.IGNORE_CASE)
        private val liveStates = setOf("RESUMED", "PAUSED", "STOPPING", "STOPPED")
        private val deadStates = setOf("FINISHING", "DESTROYING", "DESTROYED")
        private val knownStates = liveStates + deadStates + setOf("INITIALIZING", "STARTED", "PAUSING", "RESTARTING_PROCESS")

        internal fun parseSnapshot(snapshot: String): Boolean {
            if (!snapshot.endsWith('\n')) throw IOException("Incomplete RetroArch Activity snapshot")
            val lines = snapshot.lineSequence().toList()
            if (lines.firstOrNull { it.isNotBlank() } != HEADER || lines.count { it.trim() == HEADER } != 1) {
                throw IOException("Unrecognized RetroArch Activity snapshot")
            }
            if (lines.any { failure.containsMatchIn(it) }) throw IOException("RetroArch Activity dump denied or incomplete")
            val supervisor = lines.indexOfFirst { it.trim() == SUPERVISOR }
            if (supervisor < 0 || lines.count { it.trim() == SUPERVISOR } != 1 ||
                lines.drop(supervisor + 1).none { snapshotTail.matches(it.trim()) }) {
                throw IOException("Incomplete RetroArch Activity snapshot")
            }
            var displaySeen = false
            var record: ActivityRecord? = null
            var alive = false
            fun finishRecord() {
                record?.let { if (it.isAlive()) alive = true }
                record = null
            }
            for (line in lines.take(supervisor)) {
                val text = line.trim()
                if (text.isEmpty()) continue
                val indent = line.indexOfFirst { !it.isWhitespace() }
                val current = record
                if (current != null && indent <= current.indent) finishRecord()
                if (displayHeader.matches(text)) displaySeen = true
                if (historyStart.matches(text)) {
                    if (!displaySeen) throw IOException("Activity history outside display")
                    val match = historyHeader.matchEntire(text) ?: throw IOException("Malformed Activity history")
                    if (record != null) throw IOException("Nested Activity history")
                    record = ActivityRecord(indent, match.groupValues[1])
                } else {
                    record?.let { if (indent == it.indent + 2) it.readField(text) }
                }
            }
            finishRecord()
            if (!displaySeen) throw IOException("Missing Activity display snapshot")
            return alive
        }

        private class ActivityRecord(val indent: Int, private val headerComponent: String) {
            private var component: String? = null
            private var pid: Int? = null
            private var state: String? = null
            private var finishing: Boolean? = null

            fun readField(text: String) {
                when {
                    text.startsWith("mActivityComponent=") -> {
                        if (component != null) throw IOException("Duplicate Activity component")
                        component = text.removePrefix("mActivityComponent=")
                    }
                    text.startsWith("app=") -> {
                        if (pid != null) throw IOException("Duplicate Activity process")
                        val app = text.removePrefix("app=")
                        pid = if (app == "null") 0 else {
                            val match = processRecord.matchEntire(app) ?: throw IOException("Malformed Activity process")
                            match.groupValues[1].toIntOrNull() ?: throw IOException("Malformed Activity PID")
                        }
                    }
                    text.startsWith("state=") -> {
                        if (state != null) throw IOException("Duplicate Activity state")
                        state = stateField.findAll(text).singleOrNull()?.groupValues?.get(1)
                            ?: throw IOException("Malformed Activity state")
                        finishing = finishingField.findAll(text).singleOrNull()?.groupValues?.get(1)?.toBooleanStrictOrNull()
                            ?: throw IOException("Malformed Activity finishing state")
                    }
                }
            }

            fun isAlive(): Boolean {
                val activity = component ?: throw IOException("Missing Activity component")
                val appPid = pid ?: throw IOException("Missing Activity process")
                val lifecycle = state ?: throw IOException("Missing Activity state")
                val isFinishing = finishing ?: throw IOException("Missing Activity finishing state")
                if (activity != headerComponent || lifecycle !in knownStates) throw IOException("Inconsistent Activity record")
                if (activity != COMPONENT || isFinishing || appPid <= 0 || lifecycle in deadStates) return false
                if (lifecycle !in liveStates) throw IOException("Unsettled RetroArch Activity state: $lifecycle")
                return true
            }
        }
    }
}
