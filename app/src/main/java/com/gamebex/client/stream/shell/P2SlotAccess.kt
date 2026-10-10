package com.gamebex.client.stream.shell

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.util.Log
import com.gamebex.client.stream.StreamCaps
import com.gamebex.client.stream.p2.P2SlotMode
import com.gamebex.client.stream.proto.PadState
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Makes device slot == user for player 2 on official RetroArch.
 * Player 2's pad is wrapped in a [GatedPad] that holds its first report until the
 * ordering is safe: [P2SlotMode.PLACEHOLDER] registers a silent pad in
 * slot 0 on the guest's first report once RetroArch is up, [P2SlotMode.GATE_REAL_PAD]
 * waits for the host pad's first input instead. Every wait is bounded;
 * on timeout the gate opens anyway and logs loudly. Pure parts
 * ([GeteventParser], the gate) are unit-tested; the process/poll parts
 * take injectable seams.
 */
class GatedPad(
    private val inner: VirtualPad,
    private val onFirstWrite: () -> Unit = {},
) : VirtualPad {
    override val backend: String get() = inner.backend

    private val lock = Any()
    private var open = false
    private var held: PadState? = null
    private var firstWriteFired = false

    val isOpen: Boolean get() = synchronized(lock) { open }

    /**
     * While closed, remembers only the latest state (coalesced hold);
     * [openGate] delivers it first, so RetroArch registers the pad on
     * the held report.
     */
    override fun write(state: PadState): Boolean = synchronized(lock) {
        if (!firstWriteFired) {
            firstWriteFired = true
            runCatching { onFirstWrite() }
        }
        if (!open) {
            held = state
            true
        } else inner.write(state)
    }

    fun openGate() = synchronized(lock) {
        if (open) return
        open = true
        held?.let { inner.write(it) }
        held = null
    }

    override fun close() = synchronized(lock) {
        open = true
        held = null
        inner.close()
    }
}

/** One /dev/input/event* node from `getevent -p`. */
data class InputNode(val path: String, val name: String, val isPad: Boolean)

/**
 * Pure `getevent` parsing. A pad is a device reporting any key in
 * 0x120..0x13f (BTN_TRIGGER_HAPPY..BTN_THUMBR_R): real gamepads always
 * have face buttons there, keyboards (0x01..) and touchscreens
 * (BTN_TOUCH 0x14a, tool codes 0x140+) never do.
 */
object GeteventParser {
    private val addDevice = Regex("^add device \\d+: (\\S+)\\s*$")
    private val nameLine = Regex("^\\s*name:\\s*\"([^\"]*)\"\\s*$")
    private val keyLine = Regex("^\\s*KEY \\([0-9a-fA-F]+\\):\\s*(.*)$")
    private val eventLine = Regex("^(\\S+):\\s+(\\S+)\\s+(\\S+)\\s+(\\S+)\\s*$")

    private const val BTN_FIRST = 0x120
    private const val BTN_LAST = 0x13F

    /** `getevent -p` output → node path → device. Lines that don't parse are ignored. */
    fun devices(output: String): Map<String, InputNode> {
        val out = LinkedHashMap<String, InputNode>()
        var path: String? = null
        var name = ""
        var pad = false
        var inKeys = false
        fun hasPadButton(codes: String): Boolean =
            codes.split(Regex("\\s+")).any { code -> code.toIntOrNull(16) in BTN_FIRST..BTN_LAST }
        fun flush() {
            path?.let { out[it] = InputNode(it, name, pad) }
            path = null
            name = ""
            pad = false
            inKeys = false
        }
        for (raw in output.lineSequence()) {
            val line = raw.trimEnd()
            val add = addDevice.matchEntire(line)
            if (add != null) {
                flush()
                path = add.groupValues[1]
                continue
            }
            if (path == null) continue
            nameLine.matchEntire(line)?.let {
                name = it.groupValues[1]
                inKeys = false
                return@let
            } ?: keyLine.matchEntire(line)?.let {
                inKeys = true
                if (hasPadButton(it.groupValues[1])) pad = true
            } ?: run {
                // A KEY section wraps onto bare indented lines; any other
                // section header ends it.
                if (line.matches(Regex("^\\s+[A-Z_]+ \\([0-9a-fA-F]+\\):.*$"))) inKeys = false
                else if (inKeys && line.matches(Regex("^\\s+[0-9a-fA-F ]+$")) && hasPadButton(line)) pad = true
            }
        }
        flush()
        return out
    }

    /** `getevent -l` event line → node path, or null for add-device/name/syn lines. */
    fun eventNode(line: String): String? {
        val m = eventLine.matchEntire(line.trim()) ?: return null
        if (m.groupValues[2] == "EV_SYN") return null
        return m.groupValues[1]
    }

    /** `getevent -l` add-device line → node path, or null. */
    fun addedNode(line: String): String? = addDevice.matchEntire(line.trim())?.groupValues?.get(1)
}

/**
 * Watches physical input for the host pad's first event (shell has the
 * input group). Node events are read straight from /dev/input/event*
 * (24-byte input_event structs; only readability matters), so no
 * `getevent -l` pipe buffering is involved; `getevent -p` classifies
 * nodes once at start and again when a node appears mid-session.
 */
/** Nodes with input waiting; fds stay open across polls (a fresh open misses queued events). */
interface EvdevPoll : AutoCloseable {
    fun ready(nodes: Set<String>, timeoutMs: Int): Set<String>
    override fun close()
}

class RealPadWatcher(
    private val listNodes: () -> Set<String> = RealPadWatcherDefaults::listNodes,
    private val classify: (nodes: Set<String>) -> Map<String, InputNode> = RealPadWatcherDefaults::classify,
    private val evdev: EvdevPoll = RealPadWatcherDefaults.poll(),
    private val onRealPadInput: () -> Unit,
    private val onRealPadAdded: (String) -> Unit = {},
    private val pollMs: Int = 500,
    private val rescanEvery: Int = 10,
    private val log: (String) -> Unit = { Log.i(TAG, it) },
) {
    private val stopped = AtomicBoolean(false)
    private val inputSeen = AtomicBoolean(false)
    private var thread: Thread? = null

    fun start() {
        val t = Thread({ loop() }, "gbx-p2slot-watch").apply { isDaemon = true }
        thread = t
        t.start()
    }

    fun stop() {
        stopped.set(true)
        thread?.interrupt()
        thread?.join(2_000L)
        runCatching { evdev.close() }
    }

    private fun loop() {
        var watched: Map<String, InputNode> = emptyMap()
        var known: Set<String> = emptySet()
        var primed = false
        var iter = 0
        while (!stopped.get()) {
            try {
                if (iter % rescanEvery == 0) {
                    val nodes = runCatching { listNodes() }.getOrDefault(emptySet())
                    val fresh = nodes - known
                    if (fresh.isNotEmpty() || watched.isEmpty()) {
                        val all = runCatching { classify(nodes) }.getOrDefault(emptyMap())
                        if (primed) {
                            for (node in fresh) {
                                val dev = all[node] ?: continue
                                if (dev.isPad && !isOurs(dev.name)) {
                                    log("p2slot: real pad added mid-session: ${dev.name}")
                                    onRealPadAdded(dev.name)
                                }
                            }
                        }
                        watched = all.filter { it.value.isPad && !isOurs(it.value.name) }
                        known = nodes
                        primed = true
                    }
                }
                iter++
                if (watched.isEmpty()) {
                    Thread.sleep(pollMs.toLong())
                    continue
                }
                val ready = runCatching { evdev.ready(watched.keys, pollMs) }.getOrDefault(emptySet())
                if (ready.isNotEmpty() && inputSeen.compareAndSet(false, true)) {
                    log("p2slot: host pad input seen on ${ready.sorted().joinToString()}")
                    onRealPadInput()
                }
            } catch (_: InterruptedException) {
                return
            } catch (t: Throwable) {
                if (stopped.get()) return
                log("p2slot: watcher error: $t")
                runCatching { Thread.sleep(pollMs.toLong()) }
            }
        }
    }

    companion object {
        private const val TAG = "GameBEXStream"

        fun isOurs(name: String): Boolean =
            name == StreamCaps.PAD_NAME || name == StreamCaps.PLACEHOLDER_PAD_NAME
    }
}

/** Default (device) seams for [RealPadWatcher]. */
object RealPadWatcherDefaults {
    fun listNodes(): Set<String> =
        File("/dev/input").listFiles()
            ?.filter { it.name.startsWith("event") }
            ?.map { it.absolutePath }
            ?.toSet() ?: emptySet()

    fun classify(nodes: Set<String>): Map<String, InputNode> {
        if (nodes.isEmpty()) return emptyMap()
        val out = runCatching {
            val p = ProcessBuilder("getevent", "-p").redirectErrorStream(true).start()
            val buf = ByteArrayOutputStream()
            p.inputStream.copyTo(buf, 256 * 1024)
            if (!p.waitFor(5L, TimeUnit.SECONDS)) {
                p.destroyForcibly()
                return emptyMap()
            }
            buf.toString(Charsets.UTF_8.name())
        }.getOrDefault("")
        return GeteventParser.devices(out).filterKeys { it in nodes }
    }

    fun poll(): EvdevPoll = EvdevPoller()

    private class EvdevPoller : EvdevPoll {
        private val lock = Any()
        private val fds = LinkedHashMap<String, StructPollfd>()

        override fun ready(nodes: Set<String>, timeoutMs: Int): Set<String> {
            val polls = synchronized(lock) {
                for (dead in fds.keys - nodes) {
                    val fd = fds.remove(dead)?.fd ?: continue
                    runCatching { Os.close(fd) }
                }
                for (path in nodes) {
                    if (path !in fds) {
                        runCatching {
                            val fd = Os.open(path, OsConstants.O_RDONLY or OsConstants.O_NONBLOCK, 0)
                            fds[path] = StructPollfd().apply {
                                this.fd = fd
                                events = OsConstants.POLLIN.toShort()
                            }
                        }
                    }
                }
                fds.filterKeys { it in nodes }.toList()
            }
            if (polls.isEmpty()) return emptySet()
            return try {
                if (Os.poll(polls.map { it.second }.toTypedArray(), timeoutMs) <= 0) return emptySet()
                polls.filter { it.second.revents.toInt() and OsConstants.POLLIN != 0 }.map { it.first }.toSet()
                    .also { lit ->
                        // Drain what lit up so the next poll sleeps again.
                        val buf = ByteArray(24 * 64)
                        for (node in lit) {
                            val fd = polls.first { it.first == node }.second.fd
                            runCatching {
                                while (Os.read(fd, buf, 0, buf.size) > 0) Unit
                            }
                        }
                    }
            } catch (_: ErrnoException) {
                emptySet()
            } catch (_: java.io.InterruptedIOException) {
                emptySet()
            }
        }

        override fun close() = synchronized(lock) {
            for ((_, poll) in fds) runCatching { Os.close(poll.fd) }
            fds.clear()
        }
    }
}

/**
 * RetroArch-foreground wait: the placeholder must register while
 * RetroArch polls input, and only the resumed app receives events.
 */
object RaForeground {
    /** 64-bit (`com.retroarch.aarch64`) and 32-bit/universal (`com.retroarch`) package ids. */
    fun isRaComponent(line: String): Boolean =
        line.contains("com.retroarch.aarch64") || line.contains("com.retroarch/")

    /** True when RA's activity is the top resumed one in a `dumpsys activity activities` dump. */
    fun isResumed(dumpsysOutput: String): Boolean =
        dumpsysOutput.lineSequence().any { line ->
            line.contains("topResumedActivity") && isRaComponent(line)
        }

    fun defaultIsResumed(): Boolean = runCatching {
        val p = ProcessBuilder("dumpsys", "activity", "activities").redirectErrorStream(true).start()
        val buf = ByteArrayOutputStream()
        p.inputStream.copyTo(buf, 1024 * 1024)
        if (!p.waitFor(10L, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            return false
        }
        isResumed(buf.toString(Charsets.UTF_8.name()))
    }.getOrDefault(false)
}

/**
 * Player 2's pad as RetroArch must see it: a [VirtualPad] whose first
 * report waits for slot safety. Owns the placeholder pad (created
 * first, in [open]) and the real-pad watcher; [close] removes both
 * pads and stops every thread. Null when player 2's own pad won't
 * open (the session then fails with UHID).
 */
class P2SlotSession private constructor(
    private val gated: GatedPad,
    private val placeholder: VirtualPad?,
    private val worker: Thread?,
    private val watcher: RealPadWatcher?,
    private val log: (String) -> Unit,
) : VirtualPad {
    override val backend: String get() = gated.backend
    override fun write(state: PadState): Boolean = gated.write(state)
    val gateOpen: Boolean get() = gated.isOpen

    override fun close() {
        worker?.interrupt()
        runCatching { worker?.join(2_000L) }
        runCatching { watcher?.stop() }
        runCatching { gated.close() }
        runCatching { placeholder?.close() }
        log("p2slot: session closed")
    }

    /** All waits are bounded; the lab overrides them with debug props (see callers). */
    data class Timing(
        /** How long to wait for RetroArch to foreground before proceeding anyway. */
        val raUpTimeoutMs: Long = 60_000L,
        val raPollMs: Long = 1_000L,
        /** Press length of the placeholder's registering SELECT tap. */
        val pressHoldMs: Long = 100L,
        /** RA poll + autoconfigure settle after the placeholder's tap. */
        val registerWaitMs: Long = 750L,
        /** How long P2 waits for host-pad input before proceeding anyway. */
        val realPadTimeoutMs: Long = 30_000L,
        /**
         * Post-resume input-attach window: the activity resumes seconds
         * before RetroArch polls input, so the demand-driven blip waits
         * out the remainder of this window (skipped when RA resumed long
         * ago, e.g. a guest joining mid-game).
         */
        val blipSettleMs: Long = 3_000L,
    )

    companion object {
        private const val TAG = "GameBEXStream"
        private val PRESS_SELECT = PadState(buttons = PadState.SELECT)

        fun open(
            mode: P2SlotMode,
            openNamedPad: (name: String, uniq: String) -> VirtualPad? = { name, uniq -> VirtualPads.open(name, uniq) },
            isRaResumed: () -> Boolean = RaForeground::defaultIsResumed,
            watcherFactory: (onInput: () -> Unit, onAdded: (String) -> Unit) -> RealPadWatcher? =
                { onInput, onAdded -> RealPadWatcher(onRealPadInput = onInput, onRealPadAdded = onAdded) },
            timing: Timing = Timing(),
            sleep: (Long) -> Unit = Thread::sleep,
            nanos: () -> Long = System::nanoTime,
            log: (String) -> Unit = { Log.i(TAG, it) },
        ): P2SlotSession? {
            // The placeholder exists before any P2 report can, so slot 0 is
            // taken before player 2 can claim it.
            val placeholder = if (mode == P2SlotMode.PLACEHOLDER) {
                openNamedPad(StreamCaps.PLACEHOLDER_PAD_NAME, StreamCaps.PLACEHOLDER_PAD_UNIQ)
            } else null
            val p2 = openNamedPad(StreamCaps.PAD_NAME, StreamCaps.PAD_UNIQ)
            if (p2 == null) {
                runCatching { placeholder?.close() }
                return null
            }
            // Demand signal: the guest's first report proves someone is
            // playing, which is the only proof the game (and RA input) is up.
            val firstWrite = CountDownLatch(1)
            val gated = GatedPad(p2) { firstWrite.countDown() }
            if (mode == P2SlotMode.OFF) {
                gated.openGate()
                return P2SlotSession(gated, null, null, null, log)
            }
            if (mode == P2SlotMode.GATE_REAL_PAD) {
                val opened = CountDownLatch(1)
                val watcher = watcherFactory(
                    {
                        gated.openGate()
                        opened.countDown()
                        log("p2slot: gate open (host pad first)")
                    },
                    { name -> log("p2slot: host pad added: $name") },
                )
                if (watcher == null) {
                    log("p2slot: no watcher; gate open without slot guarantee")
                    gated.openGate()
                    return P2SlotSession(gated, null, null, null, log)
                }
                watcher.start()
                val worker = Thread({
                    // Mid-session connects keep working: the watcher
                    // outlives this wait and opens the gate on the new
                    // pad's first input. Past the timeout there is no
                    // re-slot — RA reuses a removed slot by name, so a
                    // re-added P2 pad would land back in slot 0.
                    if (!opened.await(timing.realPadTimeoutMs, TimeUnit.MILLISECONDS)) {
                        log("p2slot: no host-pad input in ${timing.realPadTimeoutMs} ms; gate open without slot guarantee")
                        gated.openGate()
                    }
                }, "gbx-p2slot-gate").apply { isDaemon = true }
                worker.start()
                return P2SlotSession(gated, null, worker, watcher, log)
            }
            // Register the placeholder once RetroArch foregrounds. A
            // placeholder that won't open degrades loudly.
            if (placeholder == null) {
                log("p2slot: placeholder failed to open; gate open without slot guarantee")
                gated.openGate()
                return P2SlotSession(gated, null, null, null, log)
            }
            val worker = Thread({
                try {
                    val deadline = nanos() + TimeUnit.MILLISECONDS.toNanos(timing.raUpTimeoutMs)
                    var resumedAtNs = -1L
                    while (nanos() < deadline) {
                        if (runCatching { isRaResumed() }.getOrDefault(false)) {
                            resumedAtNs = nanos()
                            break
                        }
                        sleep(timing.raPollMs)
                    }
                    if (resumedAtNs < 0L) {
                        log("p2slot: RetroArch never foregrounded in ${timing.raUpTimeoutMs} ms; gate open without slot guarantee")
                    } else {
                        // Demand-driven: the blip waits for the guest's
                        // first report (proof the game is up and RA polls
                        // input) plus the remainder of the input-attach
                        // window. A blip on resume alone lands seconds
                        // before RA polls and slot 0 is lost (emulator
                        // proof: the blip preceded RA's config load by 4 s).
                        firstWrite.await()
                        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(nanos() - resumedAtNs)
                        val settleMs = (timing.blipSettleMs - elapsedMs).coerceAtLeast(0L)
                        if (settleMs > 0L) sleep(settleMs)
                        // A no-change report generates no Android event,
                        // so registration needs a real press+release.
                        // Lone SELECT is inert in-game (menu is SELECT+START),
                        // and the stream config leaves user 1 unbound.
                        placeholder.write(PRESS_SELECT)
                        sleep(timing.pressHoldMs)
                        placeholder.write(PadState.NEUTRAL)
                        sleep(timing.registerWaitMs)
                        log("p2slot: placeholder registered; gate open")
                    }
                } catch (_: InterruptedException) {
                    // Session stopped mid-sequence.
                } catch (t: Throwable) {
                    log("p2slot: sequencing failed ($t); gate open without slot guarantee")
                } finally {
                    gated.openGate()
                }
            }, "gbx-p2slot-seq").apply { isDaemon = true }
            worker.start()
            return P2SlotSession(gated, placeholder, worker, null, log)
        }
    }
}
