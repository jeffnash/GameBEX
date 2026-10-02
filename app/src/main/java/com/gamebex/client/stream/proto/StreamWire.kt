package com.gamebex.client.stream.proto

import com.gamebex.client.stream.StreamCaps
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Stream wire format v1. Every integer is big-endian. The host shell process
 * and the guest share this one codec.
 *
 * Media (TCP, guest connects to the host's media port):
 *   guest → host  hello   24 bytes: "GBXS", version u8, 0 u8, 0 u16, token 16 bytes
 *   host → guest  welcome 28 bytes: "GBXS", version u8, status u8, 0 u16, width u16,
 *                                   height u16, audioRate u32, audioChannels u8,
 *                                   0 u8 ×3, hostNowNs u64
 *   then frames both ways: length u32 (type byte + payload), type u8, payload.
 *
 * Input (UDP, guest → host input port): 38-byte [InputPacket]; the host answers
 * each accepted packet with a 28-byte [InputAck].
 */
object StreamWire {
    const val VERSION: Int = 1

    val MEDIA_MAGIC: ByteArray = byteArrayOf(0x47, 0x42, 0x58, 0x53)
    val INPUT_MAGIC: ByteArray = byteArrayOf(0x47, 0x42, 0x58, 0x49)
    val ACK_MAGIC: ByteArray = byteArrayOf(0x47, 0x42, 0x58, 0x41)
    /** "GBXP": host -> guest PCM on the input socket pair. */
    val AUDIO_UDP_MAGIC: ByteArray = byteArrayOf(0x47, 0x42, 0x58, 0x50)

    const val TOKEN_BYTES: Int = 16
    const val HELLO_SIZE: Int = 24
    const val WELCOME_SIZE: Int = 28
    const val INPUT_SIZE: Int = 38
    const val ACK_SIZE: Int = 28
    /** Header of an audio datagram; the PCM follows. */
    const val AUDIO_UDP_HEADER: Int = 32

    /** Largest frame body (type byte + payload) either side accepts. */
    const val MAX_FRAME: Int = 2 * 1024 * 1024

    const val T_VIDEO_CONFIG: Int = 0x01
    const val T_VIDEO_FRAME: Int = 0x02
    const val T_AUDIO: Int = 0x03
    const val T_PING: Int = 0x10
    const val T_PONG: Int = 0x11
    const val T_KEYFRAME_REQUEST: Int = 0x12
    const val T_REPORT: Int = 0x13
    const val T_INFO: Int = 0x20
    const val T_BYE: Int = 0x21

    const val STATUS_OK: Int = 0
    const val STATUS_BAD_TOKEN: Int = 1
    const val STATUS_BUSY: Int = 2
    const val STATUS_BAD_VERSION: Int = 3

    const val VIDEO_FLAG_KEY: Int = 1

    /** Input packet flag: release everything (the guest is leaving). */
    const val INPUT_FLAG_NEUTRAL: Int = 1

    const val BYE_LEAVING: Int = 0
    const val BYE_REPLACED: Int = 1
    const val BYE_STOPPING: Int = 2
}

/** Guest's first bytes on the media connection. */
class Hello(val version: Int, val token: ByteArray)

/** Host's answer to [Hello]. [audioRate] 0 means the stream has no sound. */
data class Welcome(
    val version: Int,
    val status: Int,
    val width: Int,
    val height: Int,
    val audioRate: Int,
    val audioChannels: Int,
    val hostNowNs: Long,
)

sealed interface MediaFrame {
    /** Annex-B SPS and PPS, sent before the first frame and after each encoder restart. */
    class VideoConfig(val csd: ByteArray) : MediaFrame

    /** One H.264 access unit (Annex-B). Times are the host's System.nanoTime(). */
    class Video(
        val key: Boolean,
        val seq: Int,
        val captureNs: Long,
        val encodedNs: Long,
        val sendNs: Long,
        val data: ByteArray,
    ) : MediaFrame

    /** [frames] interleaved 16-bit little-endian PCM frames captured at [captureNs]. */
    class Audio(val seq: Int, val captureNs: Long, val frames: Int, val pcm: ByteArray) : MediaFrame

    /** Guest clock probe; [t0] is the guest's System.nanoTime() at send. */
    data class Ping(val id: Int, val t0: Long) : MediaFrame

    /** [t1] host time at receipt of the ping, [t2] host time at send. */
    data class Pong(val id: Int, val t0: Long, val t1: Long, val t2: Long) : MediaFrame

    /** Guest asks for an IDR frame (decoder error, reconnect). */
    data object KeyframeRequest : MediaFrame

    /** Guest health, once a second; the host shows it in its status line. */
    data class Report(
        val received: Int,
        val decoded: Int,
        val rendered: Int,
        val videoP95Us: Int,
        val audioUnderruns: Int,
    ) : MediaFrame

    /** Host changed quality level or bitrate. */
    data class Info(val level: Int, val width: Int, val height: Int, val bitrate: Int) : MediaFrame

    data class Bye(val reason: Int) : MediaFrame

    /** A type this version does not know; skipped, never link-fatal. */
    data class Unknown(val type: Int) : MediaFrame
}

/** One input packet. [key] is the first 8 bytes of the session token. */
data class InputPacket(
    val flags: Int,
    val key: Long,
    val seq: Int,
    val sampleNs: Long,
    val state: PadState,
)

/** Host's receipt for an input packet: guest [sampleNs] echoed, host [appliedNs]. */
data class InputAck(val seq: Int, val sampleNs: Long, val appliedNs: Long)

object StreamHandshake {
    fun encodeHello(token: ByteArray): ByteArray {
        require(token.size == StreamWire.TOKEN_BYTES) { "token must be ${StreamWire.TOKEN_BYTES} bytes" }
        val b = ByteBuffer.allocate(StreamWire.HELLO_SIZE)
        b.put(StreamWire.MEDIA_MAGIC)
        b.put(StreamWire.VERSION.toByte())
        b.put(0)
        b.putShort(0)
        b.put(token)
        return b.array()
    }

    /** Null when [bytes] is not a hello (wrong size or magic). */
    fun decodeHello(bytes: ByteArray): Hello? {
        if (bytes.size != StreamWire.HELLO_SIZE) return null
        if (!magic(bytes, StreamWire.MEDIA_MAGIC)) return null
        return Hello(bytes[4].toInt() and 0xFF, bytes.copyOfRange(8, 8 + StreamWire.TOKEN_BYTES))
    }

    fun encodeWelcome(w: Welcome): ByteArray {
        val b = ByteBuffer.allocate(StreamWire.WELCOME_SIZE)
        b.put(StreamWire.MEDIA_MAGIC)
        b.put(w.version.toByte())
        b.put(w.status.toByte())
        b.putShort(0)
        b.putShort(w.width.toShort())
        b.putShort(w.height.toShort())
        b.putInt(w.audioRate)
        b.put(w.audioChannels.toByte())
        b.put(0)
        b.put(0)
        b.put(0)
        b.putLong(w.hostNowNs)
        return b.array()
    }

    fun decodeWelcome(bytes: ByteArray): Welcome? {
        if (bytes.size != StreamWire.WELCOME_SIZE) return null
        if (!magic(bytes, StreamWire.MEDIA_MAGIC)) return null
        val b = ByteBuffer.wrap(bytes)
        b.position(4)
        val version = b.get().toInt() and 0xFF
        val status = b.get().toInt() and 0xFF
        b.short
        val width = b.short.toInt() and 0xFFFF
        val height = b.short.toInt() and 0xFFFF
        val rate = b.int
        val channels = b.get().toInt() and 0xFF
        b.get()
        b.get()
        b.get()
        val now = b.long
        return Welcome(version, status, width, height, rate, channels, now)
    }

    /** Reads exactly [size] bytes; throws EOFException when the peer closes first. */
    fun readExactly(input: DataInputStream, size: Int): ByteArray {
        val out = ByteArray(size)
        input.readFully(out)
        return out
    }

    internal fun magic(bytes: ByteArray, magic: ByteArray): Boolean =
        bytes.size >= magic.size && magic.indices.all { bytes[it] == magic[it] }
}

object MediaFrames {
    private const val VIDEO_HEADER = 1 + 4 + 8 + 8 + 8
    private const val AUDIO_HEADER = 4 + 8 + 2

    /** The complete frame bytes, length prefix included. */
    fun encode(frame: MediaFrame): ByteArray = when (frame) {
        is MediaFrame.VideoConfig -> body(StreamWire.T_VIDEO_CONFIG, frame.csd.size) { it.put(frame.csd) }
        is MediaFrame.Video -> body(StreamWire.T_VIDEO_FRAME, VIDEO_HEADER + frame.data.size) {
            it.put((if (frame.key) StreamWire.VIDEO_FLAG_KEY else 0).toByte())
            it.putInt(frame.seq)
            it.putLong(frame.captureNs)
            it.putLong(frame.encodedNs)
            it.putLong(frame.sendNs)
            it.put(frame.data)
        }
        is MediaFrame.Audio -> body(StreamWire.T_AUDIO, AUDIO_HEADER + frame.pcm.size) {
            it.putInt(frame.seq)
            it.putLong(frame.captureNs)
            it.putShort(frame.frames.toShort())
            it.put(frame.pcm)
        }
        is MediaFrame.Ping -> body(StreamWire.T_PING, 12) {
            it.putInt(frame.id)
            it.putLong(frame.t0)
        }
        is MediaFrame.Pong -> body(StreamWire.T_PONG, 28) {
            it.putInt(frame.id)
            it.putLong(frame.t0)
            it.putLong(frame.t1)
            it.putLong(frame.t2)
        }
        MediaFrame.KeyframeRequest -> body(StreamWire.T_KEYFRAME_REQUEST, 0) {}
        is MediaFrame.Report -> body(StreamWire.T_REPORT, 20) {
            it.putInt(frame.received)
            it.putInt(frame.decoded)
            it.putInt(frame.rendered)
            it.putInt(frame.videoP95Us)
            it.putInt(frame.audioUnderruns)
        }
        is MediaFrame.Info -> body(StreamWire.T_INFO, 9) {
            it.put(frame.level.toByte())
            it.putShort(frame.width.toShort())
            it.putShort(frame.height.toShort())
            it.putInt(frame.bitrate)
        }
        is MediaFrame.Bye -> body(StreamWire.T_BYE, 1) { it.put(frame.reason.toByte()) }
        is MediaFrame.Unknown -> throw IllegalArgumentException("cannot encode unknown frame ${frame.type}")
    }

    /**
     * Reads one frame. Null only when the stream ends cleanly before a frame starts.
     * Throws IOException for a malformed or oversized frame.
     */
    fun read(input: DataInputStream): MediaFrame? {
        val length = try {
            input.readInt()
        } catch (_: EOFException) {
            return null
        }
        if (length < 1 || length > StreamWire.MAX_FRAME) throw IOException("bad stream frame length $length")
        val body = ByteArray(length)
        input.readFully(body)
        return decodeBody(body)
    }

    /** Decodes a frame body (type byte + payload), as [read] does after the length. */
    fun decodeBody(body: ByteArray): MediaFrame {
        val b = ByteBuffer.wrap(body)
        val type = b.get().toInt() and 0xFF
        fun need(n: Int) {
            if (b.remaining() < n) throw IOException("short stream frame type $type")
        }
        return when (type) {
            StreamWire.T_VIDEO_CONFIG -> MediaFrame.VideoConfig(rest(b))
            StreamWire.T_VIDEO_FRAME -> {
                need(VIDEO_HEADER)
                val flags = b.get().toInt() and 0xFF
                val seq = b.int
                val capture = b.long
                val encoded = b.long
                val sent = b.long
                MediaFrame.Video(flags and StreamWire.VIDEO_FLAG_KEY != 0, seq, capture, encoded, sent, rest(b))
            }
            StreamWire.T_AUDIO -> {
                need(AUDIO_HEADER)
                val seq = b.int
                val capture = b.long
                val frames = b.short.toInt() and 0xFFFF
                MediaFrame.Audio(seq, capture, frames, rest(b))
            }
            StreamWire.T_PING -> {
                need(12)
                MediaFrame.Ping(b.int, b.long)
            }
            StreamWire.T_PONG -> {
                need(28)
                MediaFrame.Pong(b.int, b.long, b.long, b.long)
            }
            StreamWire.T_KEYFRAME_REQUEST -> MediaFrame.KeyframeRequest
            StreamWire.T_REPORT -> {
                need(20)
                MediaFrame.Report(b.int, b.int, b.int, b.int, b.int)
            }
            StreamWire.T_INFO -> {
                need(9)
                val level = b.get().toInt() and 0xFF
                val width = b.short.toInt() and 0xFFFF
                val height = b.short.toInt() and 0xFFFF
                MediaFrame.Info(level, width, height, b.int)
            }
            StreamWire.T_BYE -> {
                need(1)
                MediaFrame.Bye(b.get().toInt() and 0xFF)
            }
            else -> MediaFrame.Unknown(type)
        }
    }

    private fun rest(b: ByteBuffer): ByteArray {
        val out = ByteArray(b.remaining())
        b.get(out)
        return out
    }

    private inline fun body(type: Int, payload: Int, fill: (ByteBuffer) -> Unit): ByteArray {
        val length = 1 + payload
        if (length > StreamWire.MAX_FRAME) throw IOException("stream frame too large: $length")
        val b = ByteBuffer.allocate(4 + length)
        b.putInt(length)
        b.put(type.toByte())
        fill(b)
        check(b.remaining() == 0) { "frame type $type wrote ${b.position()} of ${b.capacity()} bytes" }
        return b.array()
    }
}

object InputWire {
    fun encode(p: InputPacket): ByteArray {
        val s = p.state.sanitized()
        val b = ByteBuffer.allocate(StreamWire.INPUT_SIZE)
        b.put(StreamWire.INPUT_MAGIC)
        b.put(StreamWire.VERSION.toByte())
        b.put(p.flags.toByte())
        b.putShort(0)
        b.putLong(p.key)
        b.putInt(p.seq)
        b.putLong(p.sampleNs)
        b.putShort(s.buttons.toShort())
        b.put(s.lx.toByte())
        b.put(s.ly.toByte())
        b.put(s.rx.toByte())
        b.put(s.ry.toByte())
        b.put(s.lt.toByte())
        b.put(s.rt.toByte())
        b.put(s.hat.toByte())
        b.put(0)
        return b.array()
    }

    /** Null when the datagram is not a v1 input packet. */
    fun decode(bytes: ByteArray, length: Int = bytes.size): InputPacket? {
        if (length != StreamWire.INPUT_SIZE || bytes.size < length) return null
        if (!StreamHandshake.magic(bytes, StreamWire.INPUT_MAGIC)) return null
        val b = ByteBuffer.wrap(bytes, 0, length)
        b.position(4)
        if ((b.get().toInt() and 0xFF) != StreamWire.VERSION) return null
        val flags = b.get().toInt() and 0xFF
        b.short
        val key = b.long
        val seq = b.int
        val sample = b.long
        val buttons = b.short.toInt() and 0xFFFF
        val lx = b.get().toInt()
        val ly = b.get().toInt()
        val rx = b.get().toInt()
        val ry = b.get().toInt()
        val lt = b.get().toInt() and 0xFF
        val rt = b.get().toInt() and 0xFF
        val hat = b.get().toInt() and 0xFF
        val state = PadState(buttons, lx, ly, rx, ry, lt, rt, hat).sanitized()
        return InputPacket(flags, key, seq, sample, state)
    }

    fun encodeAck(a: InputAck): ByteArray {
        val b = ByteBuffer.allocate(StreamWire.ACK_SIZE)
        b.put(StreamWire.ACK_MAGIC)
        b.put(StreamWire.VERSION.toByte())
        b.put(0)
        b.putShort(0)
        b.putInt(a.seq)
        b.putLong(a.sampleNs)
        b.putLong(a.appliedNs)
        return b.array()
    }

    fun decodeAck(bytes: ByteArray, length: Int = bytes.size): InputAck? {
        if (length != StreamWire.ACK_SIZE || bytes.size < length) return null
        if (!StreamHandshake.magic(bytes, StreamWire.ACK_MAGIC)) return null
        val b = ByteBuffer.wrap(bytes, 0, length)
        b.position(8)
        return InputAck(b.int, b.long, b.long)
    }

    /**
     * Audio datagram (host -> guest, sent to the address player 2's input comes from):
     * magic "GBXP", version, 3 reserved, key (the token's first 8 bytes), seq, captureNs (host clock),
     * frames, 2 reserved, then [frames] interleaved 16-bit little-endian stereo PCM frames.
     */
    fun encodeAudio(key: Long, seq: Int, captureNs: Long, frames: Int, pcm: ByteArray): ByteArray {
        val b = ByteBuffer.allocate(StreamWire.AUDIO_UDP_HEADER + pcm.size)
        b.put(StreamWire.AUDIO_UDP_MAGIC)
        b.put(StreamWire.VERSION.toByte())
        b.put(0)
        b.putShort(0)
        b.putLong(key)
        b.putInt(seq)
        b.putLong(captureNs)
        b.putShort(frames.toShort())
        b.putShort(0)
        b.put(pcm)
        return b.array()
    }

    class AudioDatagram(val key: Long, val seq: Int, val captureNs: Long, val frames: Int, val pcm: ByteArray)

    /** Null when the datagram is not a v1 audio datagram. */
    fun decodeAudio(bytes: ByteArray, length: Int = bytes.size): AudioDatagram? {
        if (length < StreamWire.AUDIO_UDP_HEADER || bytes.size < length) return null
        if (!StreamHandshake.magic(bytes, StreamWire.AUDIO_UDP_MAGIC)) return null
        val b = ByteBuffer.wrap(bytes, 0, length)
        b.position(4)
        if ((b.get().toInt() and 0xFF) != StreamWire.VERSION) return null
        b.position(8)
        val key = b.long
        val seq = b.int
        val capture = b.long
        val frames = b.short.toInt() and 0xFFFF
        b.short
        val size = length - StreamWire.AUDIO_UDP_HEADER
        if (size != frames * StreamCaps.AUDIO_CHANNELS * 2) return null
        val pcm = ByteArray(size)
        b.get(pcm)
        return AudioDatagram(key, seq, capture, frames, pcm)
    }

    /** True when [seq] is newer than [last] under 32-bit wraparound. */
    fun isNewer(seq: Int, last: Int): Boolean = seq - last > 0
}
