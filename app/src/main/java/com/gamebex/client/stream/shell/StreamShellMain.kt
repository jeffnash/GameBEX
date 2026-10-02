package com.gamebex.client.stream.shell

import com.gamebex.client.stream.StreamCaps
import com.gamebex.client.stream.StreamConfig
import com.gamebex.client.stream.StreamJson
import com.gamebex.client.stream.StreamProbe
import com.gamebex.client.stream.StreamStarted
import com.gamebex.client.stream.StreamStatus
import com.gamebex.client.stream.proto.PadState
import kotlin.system.exitProcess

/**
 * Runs Stream's shell side without Shizuku:
 *
 *   CLASSPATH=$(pm path com.gamebex.client | cut -d: -f2) app_process /system/bin \
 *     com.gamebex.client.stream.shell.StreamShellMain --token <32 hex> --guest 127.0.0.1 --seconds 120
 *
 * `--probe` prints this phone's StreamProbe and exits. `--pad-test` creates
 * player 2's pad, taps A once a second for `--seconds` (default 20), and
 * removes it. Output is one JSON line per event.
 */
object StreamShellMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val opts = parse(args)
        ShellRuntime.ensureMainLooper()
        val seconds = opts["seconds"]?.toLongOrNull() ?: if (opts.containsKey("pad-test")) 20L else 120L
        if (opts.containsKey("pad-test")) exitProcess(padTest(seconds))
        val components = ShellWiring.components(ShellRuntime.systemContext())
        if (opts.containsKey("probe")) {
            println(StreamJson.json.encodeToString(StreamProbe.serializer(), components.probe()))
            exitProcess(0)
        }
        val config = StreamConfig(
            token = opts["token"].orEmpty(),
            guestAddress = opts["guest"] ?: "127.0.0.1",
            mediaPort = opts["media-port"]?.toIntOrNull() ?: StreamCaps.MEDIA_PORT,
            inputPort = opts["input-port"]?.toIntOrNull() ?: StreamCaps.INPUT_PORT,
            level = opts["level"]?.toIntOrNull() ?: 0,
            audio = !opts.containsKey("no-audio"),
        )
        val session = StreamHostSession(config, components, appWatchdog = false)
        val started = session.start()
        println(StreamJson.json.encodeToString(StreamStarted.serializer(), started))
        if (!started.ok) exitProcess(1)
        val end = System.nanoTime() + seconds * 1_000_000_000L
        while (!session.stopped && System.nanoTime() < end) {
            Thread.sleep(5_000L)
            println(StreamJson.json.encodeToString(StreamStatus.serializer(), session.status()))
        }
        session.stop()
        exitProcess(0)
    }

    /** Emulator check: the pad appears, presses A, and goes away. */
    private fun padTest(seconds: Long): Int {
        val pad = VirtualPads.open()
        if (pad == null) {
            println("{\"pad\":null,\"uhid\":${VirtualPads.uhidAvailable()},\"hidTool\":${VirtualPads.hidToolAvailable()}}")
            return 1
        }
        println("{\"pad\":\"${pad.backend}\"}")
        try {
            for (i in 0 until seconds) {
                val pressed = i % 2 == 0L
                val ok = pad.write(if (pressed) PadState(buttons = PadState.A) else PadState.NEUTRAL)
                println("{\"t\":$i,\"a\":$pressed,\"ok\":$ok}")
                Thread.sleep(1_000L)
            }
        } finally {
            pad.close()
            println("{\"pad\":\"closed\"}")
        }
        return 0
    }

    /** "--key value" and bare "--flag" arguments. */
    fun parse(args: Array<String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var i = 0
        while (i < args.size) {
            val a = args[i]
            if (a.startsWith("--")) {
                val key = a.removePrefix("--")
                val next = args.getOrNull(i + 1)
                if (next != null && !next.startsWith("--")) {
                    out[key] = next
                    i += 2
                    continue
                }
                out[key] = ""
            }
            i += 1
        }
        return out
    }
}
