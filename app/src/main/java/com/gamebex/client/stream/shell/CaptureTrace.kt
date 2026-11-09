package com.gamebex.client.stream.shell

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Debug `capture_trace=1` (with the GL pass in the path): splits the capture
 * timestamp → encoder output time into capture delivery (timestamp → frame
 * available), GL pass (available → drawn, after glFinish) and encoder (drawn →
 * output dequeued), logged as CAPTURE_TRACE medians every [WINDOW_NS].
 */
class CaptureTrace(
    private val now: () -> Long = System::nanoTime,
    private val log: (String) -> Unit = { Log.i(TAG, it) },
) {
    /** Capture timestamp in µs → [arrived, drawn]; written on the GL thread, read on the drain thread. */
    private val drawn = ConcurrentHashMap<Long, LongArray>()
    private val deliverMs = ArrayList<Double>()
    private val glMs = ArrayList<Double>()
    private val encMs = ArrayList<Double>()
    private val afterNextMs = ArrayList<Double>()
    private var windowStartNs = 0L
    private var missed = 0
    private var held = 0
    private var windowBytes = 0L
    private var windowFrames = 0
    private var lastKeyframePtsUs = 0L
    private var lastKeyframeOutputNs = 0L
    @Volatile private var latest = longArrayOf(0L, 0L)

    fun onDrawn(timestampNs: Long, arrivedNs: Long, drawnNs: Long) {
        latest = longArrayOf(timestampNs, drawnNs)
        drawn[timestampNs / 1_000L] = longArrayOf(timestampNs, arrivedNs, drawnNs)
        if (drawn.size > MAX_PENDING) {
            val cutoff = drawnNs - STALE_NS
            drawn.entries.removeIf { it.value[2] < cutoff }
        }
    }

    fun onOutput(ptsUs: Long, dequeuedNs: Long, bytes: Int = 0, keyframe: Boolean = false) {
        if (keyframe) {
            val ptsSpacing = if (lastKeyframePtsUs > 0L) (ptsUs - lastKeyframePtsUs) / 1e6 else null
            val outputSpacing = if (lastKeyframeOutputNs > 0L) (dequeuedNs - lastKeyframeOutputNs) / 1e9 else null
            log("CAPTURE_TRACE keyframe ptsUs=$ptsUs outputNs=$dequeuedNs spacingPtsS=$ptsSpacing spacingOutputS=$outputSpacing bytes=$bytes")
            lastKeyframePtsUs = ptsUs
            lastKeyframeOutputNs = dequeuedNs
        }
        windowBytes += bytes
        windowFrames++
        val d = drawn.remove(ptsUs)
        if (d == null) missed++ else {
            deliverMs += (d[1] - d[0]) / 1e6
            glMs += (d[2] - d[1]) / 1e6
            encMs += (dequeuedNs - d[2]) / 1e6
            // A newer frame already drawn when this one comes out: the encoder may wait for it.
            val last = latest
            if (last[0] != d[0]) {
                held++
                afterNextMs += (dequeuedNs - last[1]) / 1e6
            }
        }
        val t = now()
        if (windowStartNs == 0L) windowStartNs = t
        if (t - windowStartNs < WINDOW_NS) return
        log("CAPTURE_TRACE n=${encMs.size} missed=$missed deliver=${stat(deliverMs)} gl=${stat(glMs)} enc=${stat(encMs)} " +
            "fps=${windowFrames * 1e9 / (t - windowStartNs)} " +
            "held=$held afterNext=${stat(afterNextMs)} kbps=${windowBytes * 8 * 1_000_000L / (t - windowStartNs)}")
        deliverMs.clear(); glMs.clear(); encMs.clear(); afterNextMs.clear()
        missed = 0
        held = 0
        windowBytes = 0L
        windowFrames = 0
        windowStartNs = t
    }

    companion object {
        private const val TAG = "GameBEXStream"
        private const val WINDOW_NS = 2_000_000_000L
        private const val STALE_NS = 1_000_000_000L
        private const val MAX_PENDING = 256

        internal fun stat(values: List<Double>): String {
            if (values.isEmpty()) return "-"
            val s = values.sorted()
            fun q(p: Double) = s[((s.size - 1) * p).toInt()]
            return "%.1f/%.1f".format(q(0.5), q(0.95))
        }
    }
}
