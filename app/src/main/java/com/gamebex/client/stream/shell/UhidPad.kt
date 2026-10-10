package com.gamebex.client.stream.shell

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.util.Log
import com.gamebex.client.stream.StreamCaps
import com.gamebex.client.stream.proto.PadHid
import com.gamebex.client.stream.proto.PadState
import java.io.FileDescriptor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Player 2 as a /dev/uhid device, written by the shell uid (group uhid).
 * Closing the descriptor destroys the device, so a crashed or killed process
 * never leaves the pad behind (Linux Documentation/hid/uhid.rst).
 */
class UhidPad private constructor(private val fd: FileDescriptor) : VirtualPad {
    override val backend: String = BACKEND

    private val closed = AtomicBoolean(false)
    private val started = CountDownLatch(1)
    private val writeLock = Any()
    private val reader = Thread({ readLoop() }, "gbx-uhid-read").apply { isDaemon = true }

    override fun write(state: PadState): Boolean {
        if (closed.get()) return false
        val event = UhidEvents.input2(PadHid.report(state))
        return try {
            synchronized(writeLock) { Os.write(fd, event, 0, event.size) }
            true
        } catch (e: ErrnoException) {
            Log.w(TAG, "uhid write failed", e)
            false
        } catch (e: java.io.InterruptedIOException) {
            false
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching {
            val release = UhidEvents.input2(PadHid.report(PadState.NEUTRAL))
            synchronized(writeLock) { Os.write(fd, release, 0, release.size) }
        }
        runCatching {
            val destroy = UhidEvents.destroy()
            synchronized(writeLock) { Os.write(fd, destroy, 0, destroy.size) }
        }
        runCatching { Os.close(fd) }
    }

    /**
     * Drains kernel events so the queue never fills, and answers report requests
     * at once so the kernel never waits its 5 s timeout on them.
     */
    private fun readLoop() {
        val buffer = ByteArray(UhidEvents.EVENT_SIZE)
        val poll = StructPollfd().apply {
            fd = this@UhidPad.fd
            events = OsConstants.POLLIN.toShort()
        }
        while (!closed.get()) {
            try {
                if (Os.poll(arrayOf(poll), POLL_MS) <= 0) continue
                val n = Os.read(fd, buffer, 0, buffer.size)
                if (n <= 0) continue
                when (UhidEvents.typeOf(buffer, n)) {
                    UhidEvents.UHID_START -> started.countDown()
                    UhidEvents.UHID_GET_REPORT -> reply(UhidEvents.getReportReply(UhidEvents.requestId(buffer, n), UhidEvents.EIO))
                    UhidEvents.UHID_SET_REPORT -> reply(UhidEvents.setReportReply(UhidEvents.requestId(buffer, n), UhidEvents.EIO))
                    else -> Unit
                }
            } catch (e: ErrnoException) {
                if (closed.get()) return
                if (e.errno != OsConstants.EINTR && e.errno != OsConstants.EAGAIN) {
                    Log.w(TAG, "uhid read failed", e)
                    return
                }
            } catch (e: java.io.InterruptedIOException) {
                return
            }
        }
    }

    private fun reply(bytes: ByteArray) {
        runCatching { synchronized(writeLock) { Os.write(fd, bytes, 0, bytes.size) } }
    }

    companion object {
        const val BACKEND: String = "uhid"
        const val PATH: String = "/dev/uhid"
        private const val TAG = "GameBEXStream"
        private const val POLL_MS = 200
        private const val START_WAIT_MS = 2_000L

        /** True when this process may open /dev/uhid read-write (shell is in group uhid). */
        fun available(): Boolean = runCatching { Os.access(PATH, OsConstants.R_OK or OsConstants.W_OK) }.getOrDefault(false)

        /**
         * Creates the device, or null when /dev/uhid can't be opened or refuses it.
         * (NewApi: O_CLOEXEC is API 27, but this runs only in the shell process of a host
         * at [StreamCaps.MIN_HOST_SDK] or later.)
         */
        @android.annotation.SuppressLint("NewApi")
        fun open(
            name: String = StreamCaps.PAD_NAME,
            uniq: String = StreamCaps.PAD_UNIQ,
        ): UhidPad? {
            val fd = try {
                Os.open(PATH, OsConstants.O_RDWR or OsConstants.O_CLOEXEC, 0)
            } catch (e: ErrnoException) {
                Log.w(TAG, "open $PATH failed", e)
                return null
            }
            return try {
                val create = UhidEvents.create2(
                    name = name,
                    phys = StreamCaps.PAD_PHYS,
                    uniq = uniq,
                    bus = StreamCaps.PAD_BUS,
                    vendor = StreamCaps.PAD_VENDOR,
                    product = StreamCaps.PAD_PRODUCT,
                    version = StreamCaps.PAD_VERSION,
                    descriptor = PadHid.DESCRIPTOR,
                )
                Os.write(fd, create, 0, create.size)
                UhidPad(fd).also { pad ->
                    pad.reader.start()
                    // The kernel refuses input until the HID driver has bound (UHID_START).
                    if (!pad.started.await(START_WAIT_MS, TimeUnit.MILLISECONDS)) Log.w(TAG, "no UHID_START after ${START_WAIT_MS} ms")
                    pad.write(PadState.NEUTRAL)
                }
            } catch (e: ErrnoException) {
                Log.w(TAG, "UHID_CREATE2 failed", e)
                runCatching { Os.close(fd) }
                null
            }
        }
    }
}
