package com.gamebex.client.stream.lab

import com.gamebex.client.stream.StreamCaps
import com.gamebex.client.stream.shell.AvcEncoders
import com.gamebex.client.stream.shell.AvcScreenSource
import com.gamebex.client.stream.shell.EncodedFrame
import com.gamebex.client.stream.shell.ShellRuntime
import com.gamebex.client.stream.shell.StreamFailure
import com.gamebex.client.stream.shell.VideoSink
import java.io.File
import java.io.FileOutputStream
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Debug builds only, emulator only (it writes a file): checks that capture
 * and encoding work as the shell uid, without the rest of Stream.
 *
 *   CLASSPATH=$(pm path com.gamebex.client | cut -d: -f2) app_process /system/bin \
 *     com.gamebex.client.stream.lab.VideoProbeMain --level 1 --seconds 5 --out /data/local/tmp/gbx-probe.h264
 *
 * Prints the encoder list, then one JSON summary line.
 */
object VideoProbeMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val opts = args.toList().zipWithNext().filter { it.first.startsWith("--") }.associate { it.first.removePrefix("--") to it.second }
        val level = StreamCaps.LEVELS[(opts["level"]?.toIntOrNull() ?: 1).coerceIn(0, StreamCaps.LEVELS.size - 1)]
        val seconds = opts["seconds"]?.toLongOrNull() ?: 5L
        val out = File(opts["out"] ?: "/data/local/tmp/gbx-probe.h264")
        ShellRuntime.ensureMainLooper()
        AvcEncoders.list().forEach { println("{\"encoder\":\"${it.name}\",\"hardware\":${it.hardware},\"cbr\":${it.cbr},\"max\":\"${it.maxWidth}x${it.maxHeight}\"}") }
        val latencies = Collections.synchronizedList(mutableListOf<Long>())
        var frames = 0
        var keyframes = 0
        var configs = 0
        var bytes = 0L
        val failed = CountDownLatch(1)
        var failure: String? = null
        val file = FileOutputStream(out)
        val sink = object : VideoSink {
            override fun onConfig(csd: ByteArray) {
                synchronized(this) {
                    configs += 1
                    file.write(csd)
                }
            }

            override fun onFrame(frame: EncodedFrame) {
                synchronized(this) {
                    frames += 1
                    if (frame.key) keyframes += 1
                    bytes += frame.data.size
                    file.write(frame.data)
                }
                latencies += frame.encodedNs - frame.captureNs
            }

            override fun onFailure(reason: String) {
                failure = reason
                failed.countDown()
            }
        }
        val source = AvcScreenSource()
        try {
            source.start(level, level.maxBitrate, sink)
        } catch (f: StreamFailure) {
            println("{\"ok\":false,\"error\":\"${f.error}\",\"detail\":\"${f.message}\"}")
            exitProcess(1)
        }
        failed.await(seconds, TimeUnit.SECONDS)
        source.stop()
        file.close()
        val sorted = synchronized(latencies) { latencies.sorted() }
        fun pct(p: Double): Long = if (sorted.isEmpty()) 0 else sorted[((sorted.size - 1) * p).toInt()] / 1_000L
        println(
            "{\"ok\":${failure == null},\"encoder\":\"${source.encoderName}\",\"capture\":\"${source.captureApi}\"," +
                "\"size\":\"${level.width}x${level.height}\",\"fps\":${frames / seconds},\"frames\":$frames," +
                "\"keyframes\":$keyframes,\"configs\":$configs,\"kbps\":${bytes * 8 / 1_000 / seconds}," +
                "\"encodeP50Us\":${pct(0.5)},\"encodeP95Us\":${pct(0.95)},\"failure\":${failure?.let { "\"$it\"" }},\"file\":\"$out\"}",
        )
        exitProcess(if (failure == null && frames > 0) 0 else 1)
    }
}
