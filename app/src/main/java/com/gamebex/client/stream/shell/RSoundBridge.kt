package com.gamebex.client.stream.shell

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class RSoundBridge(
    private val sourceFactory: () -> AudioSource,
    private val emulatorAlive: () -> Boolean,
    private val onIdle: () -> Unit = {},
    private val pollMs: Long = 500,
    private val exitGraceMs: Long = 2_000,
    private val launchWaitMs: Long = 10_000,
    private val logger: (String) -> Unit = {},
) : AutoCloseable {
    private class Run {
        val forwarding = AtomicReference<AudioSink?>()
        val forwarder = AudioSink { chunk -> forwarding.get()?.onPcm(chunk) }
        var source: AudioSource? = null
        var starting = true
        var started = false
        var ready = false
        var stopRequested = false
        var stopping = false
        var finished = false
        var launchedAtNs: Long? = null
        var watcher: Thread? = null
    }

    private class StopWork(val source: AudioSource, val duringStart: Boolean)

    private val state = Any()
    private var current: Run? = null
    private var active: Lease? = null
    private var epoch = 0L
    private var closed = false
    private var latestFailure: String? = null
    private var idleNotified = false

    init {
        require(pollMs > 0 && exitGraceMs >= 0 && launchWaitMs > 0)
    }

    val retained: Boolean
        get() = synchronized(state) {
            current?.let { it.started && it.ready && !it.stopRequested } == true
        }

    val failureKind: String?
        get() = synchronized(state) { latestFailure }

    fun acquire(): AudioSource {
        val lease: Lease
        val source: AudioSource?
        synchronized(state) {
            check(!closed) { "RSound bridge is closed" }
            lease = Lease(++epoch)
            active = lease
            idleNotified = false
            current?.forwarding?.set(null)
            source = current?.source
        }
        flush(source)
        return lease
    }

    override fun close() {
        val run: Run?
        val watcher: Thread?
        synchronized(state) {
            if (closed) return
            closed = true
            active?.released = true
            active = null
            run = current
            run?.forwarding?.set(null)
            watcher = run?.watcher
        }
        if (run != null) stopRun(run)
        if (watcher != null && watcher !== Thread.currentThread()) {
            watcher.interrupt()
            try {
                watcher.join(1_000)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    private inner class Lease(val epoch: Long) : AudioSource {
        var run: Run? = null
        var launched = false
        var released = false

        private fun source(): AudioSource? = synchronized(state) { run?.source }

        override val rsoundReady: Boolean get() = source()?.rsoundReady ?: false
        override val audioMode: String get() = source()?.audioMode ?: "loopback"
        override val fallbackReason: String? get() = source()?.fallbackReason
        override val audioFailureKind: String? get() = failureKind ?: source()?.audioFailureKind
        override val guestAudioDrops: Long get() = source()?.guestAudioDrops ?: 0
        override fun start(sink: AudioSink): Boolean = startLease(this, sink)
        override fun gameLaunched() = launchLease(this)
        override fun flushGuestAudio() {
            val source = synchronized(state) { if (isActive(this)) run?.source else null }
            flush(source)
        }
        override fun stop() = stopLease(this)
    }

    private fun isActive(lease: Lease): Boolean =
        !closed && !lease.released && active?.epoch == lease.epoch

    private fun attach(run: Run, lease: Lease, sink: AudioSink) {
        run.forwarding.set(AudioSink { chunk ->
            val target = synchronized(state) {
                if (isActive(lease) && current === run && !run.stopRequested) sink else null
            }
            target?.onPcm(chunk)
        })
    }

    private fun startLease(lease: Lease, sink: AudioSink): Boolean {
        val run: Run
        val fresh: Boolean
        synchronized(state) {
            if (!isActive(lease)) return false
            val previous = current
            if (previous != null && (previous.starting || previous.stopRequested)) return false
            fresh = previous == null
            run = previous ?: Run().also { current = it }
            lease.run = run
            run.forwarding.set(null)
        }
        if (!fresh) {
            flush(synchronized(state) { run.source })
            return synchronized(state) {
                if (!isActive(lease) || current !== run || run.stopRequested || !run.started) false
                else {
                    attach(run, lease, sink)
                    true
                }
            }
        }

        var started = false
        var ready = false
        try {
            val source = sourceFactory()
            val allowed = synchronized(state) {
                run.source = source
                if (isActive(lease) && current === run && !run.stopRequested) {
                    attach(run, lease, sink)
                    true
                } else false
            }
            if (allowed) {
                started = source.start(run.forwarder)
                ready = started && source.rsoundReady
            }
        } catch (t: Throwable) {
            log("RSound bridge start failed: ${detail(t)}")
        }
        val attached = synchronized(state) {
            run.starting = false
            run.started = started
            run.ready = ready
            val attached = started && isActive(lease) && current === run && !run.stopRequested
            if (attached) latestFailure = null
            attached
        }
        if (!attached) stopRun(run)
        return attached
    }

    private fun launchLease(lease: Lease) {
        val run: Run
        val source: AudioSource
        synchronized(state) {
            if (!isActive(lease)) return
            run = lease.run ?: return
            if (current !== run || !run.started || run.stopRequested) return
            source = run.source ?: return
            lease.launched = true
            if (run.launchedAtNs == null) run.launchedAtNs = System.nanoTime()
        }
        runCatching { source.gameLaunched() }
            .onFailure { log("RSound launch notification failed: ${detail(it)}") }
        armWatch(run)
    }

    private fun stopLease(lease: Lease) {
        val run: Run?
        synchronized(state) {
            if (lease.released) return
            lease.released = true
            if (active?.epoch != lease.epoch) return
            active = null
            run = current
            run?.forwarding?.set(null)
        }
        if (run == null) {
            notifyIdle()
            return
        }
        val source = synchronized(state) { run.source }
        flush(source)
        val ready = source?.rsoundReady == true
        val connected = source?.audioMode == "rsound"
        val stop = synchronized(state) {
            if (current !== run || active != null || run.stopRequested) return
            (!ready || (!lease.launched && run.launchedAtNs == null && !connected)).also {
                if (it) run.stopRequested = true
            }
        }
        if (stop) stopRun(run) else armWatch(run)
    }

    private fun armWatch(run: Run) {
        val watcher = synchronized(state) {
            if (closed || current !== run || !run.started || run.stopRequested || run.watcher != null) return
            if (run.launchedAtNs == null) run.launchedAtNs = System.nanoTime()
            Thread({ watch(run) }, "gbx-rsound-emulator").apply {
                isDaemon = true
                run.watcher = this
            }
        }
        watcher.start()
    }

    private fun watching(run: Run): Boolean = synchronized(state) {
        !closed && current === run && !run.stopRequested
    }

    private fun watch(run: Run) {
        var everSeen = false
        var absentSince: Long? = synchronized(state) { run.launchedAtNs }
        while (watching(run)) {
            val alive = try {
                emulatorAlive()
            } catch (t: Throwable) {
                log("RSound emulator probe failed: ${detail(t)}")
                absentSince = null
                null
            }
            if (!watching(run)) return
            val now = System.nanoTime()
            if (alive == true) {
                everSeen = true
                absentSince = null
            } else if (alive == false) {
                val since = absentSince ?: now.also { absentSince = it }
                val waitMs = if (everSeen) exitGraceMs else launchWaitMs
                if (now - since >= TimeUnit.MILLISECONDS.toNanos(waitMs)) {
                    stopRun(run, "emulator-exit")
                    return
                }
            }
            try {
                Thread.sleep(pollMs)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    private fun stopRun(run: Run, failure: String? = null) {
        var finished = false
        val watcher: Thread?
        val work = synchronized(state) {
            if (current !== run || run.finished) return
            if (failure != null) latestFailure = failure
            run.stopRequested = true
            run.forwarding.set(null)
            watcher = run.watcher
            if (run.stopping) return
            val source = run.source
            if (source == null) {
                if (!run.starting) {
                    run.finished = true
                    current = null
                    finished = true
                }
                null
            } else {
                run.stopping = true
                StopWork(source, run.starting)
            }
        }
        if (failure != null) log("RSound bridge stopped: $failure")
        if (watcher != null && watcher !== Thread.currentThread()) watcher.interrupt()
        if (work == null) {
            if (finished) notifyIdle()
            return
        }
        runCatching { work.source.stop() }
            .onFailure { log("RSound bridge stop failed: ${detail(it)}") }
        val repeat = synchronized(state) {
            run.stopping = false
            if (work.duringStart) !run.starting
            else {
                if (!run.starting && current === run) {
                    run.finished = true
                    current = null
                    finished = true
                }
                false
            }
        }
        if (repeat) stopRun(run)
        else if (finished) notifyIdle()
    }

    private fun flush(source: AudioSource?) {
        runCatching { source?.flushGuestAudio() }
            .onFailure { log("RSound guest detach failed: ${detail(it)}") }
    }

    private fun notifyIdle() {
        val idle = synchronized(state) {
            (!closed && !idleNotified && active == null && current == null).also {
                if (it) idleNotified = true
            }
        }
        if (idle) runCatching(onIdle).onFailure { log("RSound idle notification failed: ${detail(it)}") }
    }

    private fun detail(t: Throwable): String = t.message ?: t.javaClass.simpleName
    private fun log(message: String) { runCatching { logger(message) } }
}
