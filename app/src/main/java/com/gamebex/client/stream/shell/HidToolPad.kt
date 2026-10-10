package com.gamebex.client.stream.shell

import android.util.Log
import com.gamebex.client.stream.StreamCaps
import com.gamebex.client.stream.proto.PadHid
import com.gamebex.client.stream.proto.PadState
import java.io.File
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Fallback pad: AOSP's /system/bin/hid (frameworks/base/cmds/hid) fed
 * JSON commands on stdin, the path proven on the emulator. The tool
 * only knows the usb and bluetooth buses; usb is used. When stdin closes, the
 * tool exits and the device goes with it.
 */
class HidToolPad private constructor(private val process: Process) : VirtualPad {
    override val backend: String = BACKEND

    private val closed = AtomicBoolean(false)
    private val out = OutputStreamWriter(process.outputStream, StandardCharsets.UTF_8)

    override fun write(state: PadState): Boolean {
        if (closed.get()) return false
        return send(reportCommand(PadHid.report(state)))
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { send(reportCommand(PadHid.report(PadState.NEUTRAL))) }
        runCatching { out.close() }
        runCatching {
            if (!process.waitFor(EXIT_WAIT_MS, TimeUnit.MILLISECONDS)) process.destroy()
        }
    }

    private fun send(line: String): Boolean = try {
        synchronized(out) {
            out.write(line)
            out.write("\n")
            out.flush()
        }
        true
    } catch (e: java.io.IOException) {
        Log.w(TAG, "hid tool write failed", e)
        false
    }

    companion object {
        const val BACKEND: String = "hid-tool"
        const val PATH: String = "/system/bin/hid"
        private const val TAG = "GameBEXStream"
        private const val DEVICE_ID = 1
        private const val EXIT_WAIT_MS = 1_000L
        private const val REGISTER_WAIT_MS = 500L

        fun available(): Boolean = File(PATH).canExecute()

        fun registerCommand(name: String = StreamCaps.PAD_NAME): String =
            "{\"id\":$DEVICE_ID,\"command\":\"register\",\"name\":\"$name\"," +
                "\"vid\":${StreamCaps.PAD_VENDOR},\"pid\":${StreamCaps.PAD_PRODUCT},\"bus\":\"usb\"," +
                "\"descriptor\":${jsonArray(PadHid.DESCRIPTOR)}}"

        fun reportCommand(report: ByteArray): String =
            "{\"id\":$DEVICE_ID,\"command\":\"report\",\"report\":${jsonArray(report)}}"

        /** Unsigned byte values, as the hid tool's JSON parser expects. */
        fun jsonArray(bytes: ByteArray): String = bytes.joinToString(",", "[", "]") { (it.toInt() and 0xFF).toString() }

        fun open(name: String = StreamCaps.PAD_NAME): HidToolPad? {
            if (!available()) return null
            val process = try {
                ProcessBuilder(PATH, "-").redirectErrorStream(true).start()
            } catch (e: java.io.IOException) {
                Log.w(TAG, "start $PATH failed", e)
                return null
            }
            val pad = HidToolPad(process)
            // The tool logs to stdout; drain it so it never blocks on a full pipe.
            Thread({ runCatching { process.inputStream.copyTo(NullSink) } }, "gbx-hid-out").apply { isDaemon = true }.start()
            if (!pad.send(registerCommand(name))) {
                pad.close()
                return null
            }
            Thread.sleep(REGISTER_WAIT_MS)
            if (!process.isAlive) {
                Log.w(TAG, "$PATH exited after register")
                return null
            }
            pad.write(PadState.NEUTRAL)
            return pad
        }

        private object NullSink : java.io.OutputStream() {
            override fun write(b: Int) = Unit
            override fun write(b: ByteArray, off: Int, len: Int) = Unit
        }
    }
}
