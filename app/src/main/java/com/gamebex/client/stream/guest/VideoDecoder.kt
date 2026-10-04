package com.gamebex.client.stream.guest

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import com.gamebex.client.stream.StreamCaps
import com.gamebex.client.stream.StreamDebug
import com.gamebex.client.stream.proto.AnnexB
import com.gamebex.client.stream.proto.MediaFrame
import java.nio.ByteBuffer
import java.util.ArrayDeque

/**
 * H.264 decode straight to the viewer's Surface, asynchronous
 * MediaCodec on its own HandlerThread. Each decoded frame is released for
 * display at once; nothing waits for a presentation time.
 *
 * Low-latency keys, tried in order until configure accepts them (as
 * moonlight-android's MediaCodecHelper.setDecoderLowLatencyOptions does):
 * 1. KEY_LOW_LATENCY (Android 11+) and MediaTek's "vdec-lowlatency", KEY_PRIORITY 0;
 * 2. KEY_LOW_LATENCY and KEY_PRIORITY only;
 * 3. no optional keys.
 * The SPS is rewritten by [SpsFixup] first when [StreamCaps.SPS_FIXUP] is on.
 */
class VideoDecoder(
    private val surface: Surface,
    private val stats: LatencyStats,
    private val clock: ClockSync,
    private val onVideoReady: () -> Unit = {},
    private val onNeedKeyframe: () -> Unit,
) {
    private class Times(val captureNs: Long, val sendNs: Long, val receivedNs: Long)

    private val thread = HandlerThread("gbx-video-decode", android.os.Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    private val handler = Handler(thread.looper)
    private val lock = Any()
    private var codec: MediaCodec? = null
    private var csd: ByteArray? = null
    private var width = 0
    private var height = 0
    private val pending = ArrayDeque<MediaFrame.Video>()
    private val freeInputs = ArrayDeque<Int>()
    private val times = HashMap<Long, Times>()
    private val released = HashMap<Long, Long>()
    private var waitingForKey = true
    @Volatile private var closed = false

 /** The decoder in use, for the stats log. */
    @Volatile var decoderName: String? = null
        private set

 /** New stream parameters: (re)creates the decoder. Called on the network thread. */
    fun configure(csdIn: ByteArray, w: Int, h: Int) {
        val fixed = if (StreamCaps.SPS_FIXUP) SpsFixup.fixCsd(csdIn) else csdIn
        synchronized(lock) {
            csd = fixed
            width = w
            height = h
            pending.clear()
            waitingForKey = true
        }
        handler.post { recreate() }
    }

 /** One received frame. Called on the network thread. */
    fun offer(frame: MediaFrame.Video, receivedNs: Long) {
        synchronized(lock) {
            if (closed) return
            if (waitingForKey && !frame.key) {
                stats.frameDropped()
                return
            }
            waitingForKey = false
            if (pending.size >= MAX_PENDING) {
 // The decoder fell behind: skip to the next keyframe rather than build latency.
                pending.clear()
                waitingForKey = !frame.key
                stats.frameDropped()
                if (!frame.key) {
                    onNeedKeyframe()
                    return
                }
            }
 // A keyframe can repeat the SPS in-band; patch it like the config one so the decoder keeps output order.
            pending.addLast(if (frame.key && StreamCaps.SPS_FIXUP) withFixedSps(frame) else frame)
            times[frame.seq.toLong()] = Times(frame.captureNs, frame.sendNs, receivedNs)
            if (times.size > MAX_TIMES) times.keys.minOrNull()?.let { times.remove(it) }
        }
        handler.post { feed() }
    }

    private fun withFixedSps(frame: MediaFrame.Video): MediaFrame.Video {
        if (AnnexB.split(frame.data).none { it.type == AnnexB.NAL_SPS }) return frame
        return runCatching {
            MediaFrame.Video(frame.key, frame.seq, frame.captureNs, frame.encodedNs, frame.sendNs, SpsFixup.fixCsd(frame.data))
        }.getOrDefault(frame)
    }

    fun close() {
        closed = true
        handler.post {
            releaseCodec()
            thread.quitSafely()
        }
    }

    private fun recreate() {
        releaseCodec()
        val (c, w, h) = synchronized(lock) { Triple(csd, width, height) }
        if (c == null || closed) return
        for (attempt in 0..2) {
            val decoder = try {
                pickDecoder()?.let { MediaCodec.createByCodecName(it) } ?: MediaCodec.createDecoderByType(MIME)
            } catch (e: Exception) {
                Log.w(TAG, "no decoder", e)
                return
            }
            try {
                decoder.setCallback(callback, handler)
                decoder.configure(format(c, w, h, attempt), surface, null, 0)
                decoder.setOnFrameRenderedListener({ _, ptsUs, renderedNs -> onRendered(ptsUs, renderedNs) }, handler)
                decoder.start()
                synchronized(lock) {
                    codec = decoder
                    freeInputs.clear()
                }
                decoderName = decoder.name
                Log.i(TAG, "decoder ${decoder.name} ${w}x$h attempt $attempt")
                onNeedKeyframe()
                return
            } catch (e: Exception) {
                Log.w(TAG, "decoder ${decoder.name} refused attempt $attempt", e)
                runCatching { decoder.release() }
            }
        }
    }

    private fun releaseCodec() {
        val c = synchronized(lock) {
            freeInputs.clear()
            codec.also { codec = null }
        } ?: return
        runCatching { c.stop() }
        runCatching { c.release() }
    }

    private fun feed() {
        while (true) {
            val (c, index, frame) = synchronized(lock) {
                val c = codec ?: return
                if (freeInputs.isEmpty() || pending.isEmpty()) return
                Triple(c, freeInputs.pollFirst(), pending.pollFirst())
            }
            try {
                val buffer = c.getInputBuffer(index) ?: continue
                buffer.clear()
                if (frame.data.size > buffer.capacity()) {
                    c.queueInputBuffer(index, 0, 0, frame.seq.toLong(), 0)
                    onNeedKeyframe()
                    continue
                }
                buffer.put(frame.data)
                c.queueInputBuffer(index, 0, frame.data.size, frame.seq.toLong(), 0)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "queueInputBuffer failed", e)
                return
            }
        }
    }

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            synchronized(lock) {
                if (codec !== this@VideoDecoder.codec) return
                freeInputs.addLast(index)
            }
            feed()
        }

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            if (codec !== synchronized(lock) { this@VideoDecoder.codec }) return
            val now = System.nanoTime()
            try {
 // An explicit render time of "now" (Moonlight's low-latency path): the default (true) asks for the
 // frame's pts, which is our sequence number, and that made the present metric meaningless.
                codec.releaseOutputBuffer(index, now)
            } catch (e: IllegalStateException) {
                return
            }
            val t = synchronized(lock) { times.remove(info.presentationTimeUs) } ?: return
            released[info.presentationTimeUs] = now
            if (released.size > MAX_TIMES) released.keys.minOrNull()?.let { released.remove(it) }
            recordOutputStats(stats, clock, t.captureNs, t.sendNs, t.receivedNs, now)
            onVideoReady()
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            Log.w(TAG, "decoder error", e)
            synchronized(lock) { waitingForKey = true }
            handler.post { recreate() }
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            Log.i(TAG, "decoder output format $format")
        }
    }

    private fun onRendered(ptsUs: Long, renderedNs: Long) {
        val releasedNs = released.remove(ptsUs) ?: return
        stats.add(LatencyStats.Metric.PRESENT, renderedNs - releasedNs)
    }

    private fun format(csd: ByteArray, w: Int, h: Int, attempt: Int): MediaFormat =
        MediaFormat.createVideoFormat(MIME, w, h).apply {
            val nals = AnnexB.split(csd)
            val sps = nals.firstOrNull { it.type == AnnexB.NAL_SPS }
            val pps = nals.firstOrNull { it.type == AnnexB.NAL_PPS }
            if (sps != null && pps != null) {
                setByteBuffer("csd-0", ByteBuffer.wrap(AnnexB.join(listOf(csd.copyOfRange(sps.start, sps.end)))))
                setByteBuffer("csd-1", ByteBuffer.wrap(AnnexB.join(listOf(csd.copyOfRange(pps.start, pps.end)))))
            } else {
                setByteBuffer("csd-0", ByteBuffer.wrap(csd))
            }
            if (attempt <= 1) {
                if (Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                setInteger(MediaFormat.KEY_PRIORITY, 0)
            }
 // No KEY_OPERATING_RATE / KEY_FRAME_RATE: on the MT6835 they made decoding slower .
            if (attempt == 0) setInteger(KEY_VDEC_LOW_LATENCY, 1)
        }

 /** A hardware AVC decoder when there is one (API 29 tells us), else null for the default. */
    private fun pickDecoder(): String? {
        StreamDebug.prop("decoder")?.let { return it }
        if (Build.VERSION.SDK_INT < 29) return null
        return MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { !it.isEncoder && it.supportedTypes.any { t -> t.equals(MIME, ignoreCase = true) } }
            .sortedByDescending { it.isHardwareAccelerated }
            .firstOrNull()
            ?.name
    }

    companion object {
        const val MIME: String = MediaFormat.MIMETYPE_VIDEO_AVC
 /** MediaTek's ACodec extension key (moonlight-android MediaCodecHelper). */
        const val KEY_VDEC_LOW_LATENCY: String = "vdec-lowlatency"
        private const val TAG = "GameBEXStream"
        private const val MAX_PENDING = 8
        private const val MAX_TIMES = 120

        internal fun recordOutputStats(stats: LatencyStats, clock: ClockSync, captureNs: Long, sendNs: Long, receivedNs: Long, releasedNs: Long) {
            stats.frameRendered(releasedNs)
            stats.add(LatencyStats.Metric.DECODE, releasedNs - receivedNs)
            if (clock.ready) {
                if (captureNs != 0L) stats.add(LatencyStats.Metric.VIDEO, releasedNs - clock.hostToGuest(captureNs))
                stats.add(LatencyStats.Metric.NETWORK, receivedNs - clock.hostToGuest(sendNs))
            }
        }

 /** True when the phone has any H.264 decoder. */
        fun available(): Boolean = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .any { !it.isEncoder && it.supportedTypes.any { t -> t.equals(MIME, ignoreCase = true) } }
    }
}
