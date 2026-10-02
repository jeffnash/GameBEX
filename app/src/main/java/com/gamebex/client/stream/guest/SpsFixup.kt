package com.gamebex.client.stream.guest

import com.gamebex.client.stream.proto.AnnexB
import java.io.ByteArrayOutputStream

/**
 * Rewrites an H.264 SPS so decoders output each frame as soon as it is decoded:
 * VUI bitstream_restriction with max_num_reorder_frames = 0 and
 * max_dec_frame_buffering = max_num_ref_frames. Without it a decoder may hold
 * frames to fill its DPB. Moonlight applies the same fields on Android 8+
 * (moonlight-android MediaCodecDecoderRenderer, "Adding bitstream
 * restrictions"); this is an independent implementation of ITU-T H.264
 * 7.3.2.1.1 and E.1.1.
 *
 * Everything before bitstream_restriction_flag is copied bit for bit; an SPS
 * without VUI gets a minimal VUI carrying only the restriction.
 */
object SpsFixup {
    /** A parsed view of the fields the rewrite needs. */
    class Parsed(val profileIdc: Int, val maxNumRefFrames: Int, val widthMbs: Int, val heightMapUnits: Int, val frameMbsOnly: Boolean, val hasVui: Boolean, val hasRestriction: Boolean, val maxNumReorderFrames: Int?, val maxDecFrameBuffering: Int?)

    private val HIGH_PROFILES = setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)

    /** [csd]: an Annex-B stream holding the SPS (and usually the PPS). Returns it with the SPS rewritten, or unchanged if it can't be parsed. */
    fun fixCsd(csd: ByteArray): ByteArray {
        val nals = AnnexB.split(csd)
        if (nals.none { it.type == AnnexB.NAL_SPS }) return csd
        val out = nals.map { n ->
            val nal = csd.copyOfRange(n.start, n.end)
            if (n.type == AnnexB.NAL_SPS) fixSps(nal) ?: nal else nal
        }
        return AnnexB.join(out)
    }

    /** [nal]: one SPS NAL unit, header byte included, no start code. Null when it can't be parsed. */
    fun fixSps(nal: ByteArray): ByteArray? = try {
        val rbsp = unescape(nal, 1)
        val r = BitReader(rbsp)
        val info = parse(r)
        val w = BitWriter()
        val copyBits = if (info.hasVui) r.restrictionFlagPos else r.vuiFlagPos
        w.copyBits(rbsp, copyBits)
        if (!info.hasVui) {
            w.bit(1) // vui_parameters_present_flag
            repeat(8) { w.bit(0) } // aspect, overscan, video_signal, chroma_loc, timing, nal_hrd, vcl_hrd, pic_struct (no low_delay without HRD)
        }
        w.bit(1) // bitstream_restriction_flag
        w.bit(1) // motion_vectors_over_pic_boundaries_flag
        w.ue(2) // max_bytes_per_pic_denom
        w.ue(1) // max_bits_per_mb_denom
        w.ue(16) // log2_max_mv_length_horizontal
        w.ue(16) // log2_max_mv_length_vertical
        w.ue(0) // max_num_reorder_frames
        w.ue(maxOf(1, info.maxNumRefFrames)) // max_dec_frame_buffering
        w.bit(1) // rbsp_stop_one_bit
        w.alignZero()
        byteArrayOf(nal[0]) + escape(w.toByteArray())
    } catch (_: IndexOutOfBoundsException) {
        null
    } catch (_: IllegalStateException) {
        null
    }

    /** Parses [nal] (an SPS, header included) for tests and logs. */
    fun inspect(nal: ByteArray): Parsed = parse(BitReader(unescape(nal, 1)))

    private fun parse(r: BitReader): Parsed {
        val profile = r.bits(8)
        r.bits(8) // constraint_set flags + reserved_zero_2bits
        r.bits(8) // level_idc
        r.ue() // seq_parameter_set_id
        if (profile in HIGH_PROFILES) {
            val chroma = r.ue()
            if (chroma == 3) r.bits(1)
            r.ue()
            r.ue()
            r.bits(1)
            if (r.bits(1) == 1) {
                val lists = if (chroma != 3) 8 else 12
                for (i in 0 until lists) if (r.bits(1) == 1) skipScalingList(r, if (i < 6) 16 else 64)
            }
        }
        r.ue() // log2_max_frame_num_minus4
        when (r.ue()) { // pic_order_cnt_type
            0 -> r.ue()
            1 -> {
                r.bits(1)
                r.se()
                r.se()
                val n = r.ue()
                repeat(n) { r.se() }
            }
            else -> Unit
        }
        val refFrames = r.ue()
        r.bits(1) // gaps_in_frame_num_value_allowed_flag
        val widthMbs = r.ue() + 1
        val heightUnits = r.ue() + 1
        val frameMbsOnly = r.bits(1) == 1
        if (!frameMbsOnly) r.bits(1)
        r.bits(1) // direct_8x8_inference_flag
        if (r.bits(1) == 1) repeat(4) { r.ue() }
        r.vuiFlagPos = r.pos
        val hasVui = r.bits(1) == 1
        var hasRestriction = false
        var reorder: Int? = null
        var dpb: Int? = null
        if (hasVui) {
            if (r.bits(1) == 1 && r.bits(8) == 255) r.bits(32) // aspect_ratio_idc, Extended_SAR
            if (r.bits(1) == 1) r.bits(1) // overscan
            if (r.bits(1) == 1) { // video_signal_type
                r.bits(4)
                if (r.bits(1) == 1) r.bits(24)
            }
            if (r.bits(1) == 1) { // chroma_loc
                r.ue()
                r.ue()
            }
            if (r.bits(1) == 1) { // timing_info
                r.bits(32)
                r.bits(32)
                r.bits(1)
            }
            val nalHrd = r.bits(1) == 1
            if (nalHrd) skipHrd(r)
            val vclHrd = r.bits(1) == 1
            if (vclHrd) skipHrd(r)
            if (nalHrd || vclHrd) r.bits(1) // low_delay_hrd_flag
            r.bits(1) // pic_struct_present_flag
            r.restrictionFlagPos = r.pos
            hasRestriction = r.bits(1) == 1
            if (hasRestriction) {
                r.bits(1)
                r.ue()
                r.ue()
                r.ue()
                r.ue()
                reorder = r.ue()
                dpb = r.ue()
            }
        }
        return Parsed(profile, refFrames, widthMbs, heightUnits, frameMbsOnly, hasVui, hasRestriction, reorder, dpb)
    }

    private fun skipScalingList(r: BitReader, size: Int) {
        var last = 8
        var next = 8
        for (j in 0 until size) {
            if (next != 0) next = (last + r.se() + 256) % 256
            last = if (next == 0) last else next
        }
    }

    private fun skipHrd(r: BitReader) {
        val cpbCnt = r.ue() + 1
        r.bits(4)
        r.bits(4)
        repeat(cpbCnt) {
            r.ue()
            r.ue()
            r.bits(1)
        }
        r.bits(20)
    }

    /** Removes emulation-prevention bytes (00 00 03 → 00 00) from [nal] starting at [from]. */
    fun unescape(nal: ByteArray, from: Int): ByteArray {
        val out = ByteArrayOutputStream(nal.size)
        var zeros = 0
        for (i in from until nal.size) {
            val b = nal[i].toInt() and 0xFF
            if (zeros >= 2 && b == 3) {
                zeros = 0
                continue
            }
            out.write(b)
            zeros = if (b == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    /** Adds emulation-prevention bytes so no 00 00 0x (x ≤ 3) appears in [rbsp]. */
    fun escape(rbsp: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(rbsp.size + 8)
        var zeros = 0
        for (v in rbsp) {
            val b = v.toInt() and 0xFF
            if (zeros >= 2 && b <= 3) {
                out.write(3)
                zeros = 0
            }
            out.write(b)
            zeros = if (b == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    class BitReader(private val data: ByteArray) {
        var pos: Int = 0
            private set
        var vuiFlagPos: Int = -1
        var restrictionFlagPos: Int = -1

        fun bits(n: Int): Int {
            var v = 0L
            repeat(n) {
                val byte = data[pos ushr 3].toInt() and 0xFF
                v = (v shl 1) or ((byte ushr (7 - (pos and 7))) and 1).toLong()
                pos += 1
            }
            return v.toInt()
        }

        fun ue(): Int {
            var zeros = 0
            while (bits(1) == 0) {
                zeros += 1
                check(zeros <= 31) { "bad exp-golomb code" }
            }
            return if (zeros == 0) 0 else ((1L shl zeros) - 1 + bits(zeros).toLong()).toInt()
        }

        fun se(): Int {
            val k = ue()
            return if (k and 1 == 1) (k + 1) / 2 else -(k / 2)
        }
    }

    class BitWriter {
        private val out = ByteArrayOutputStream()
        private var current = 0
        private var count = 0

        fun bit(b: Int) {
            current = (current shl 1) or (b and 1)
            count += 1
            if (count == 8) {
                out.write(current)
                current = 0
                count = 0
            }
        }

        fun bits(value: Long, n: Int) {
            for (i in n - 1 downTo 0) bit(((value ushr i) and 1L).toInt())
        }

        fun ue(value: Int) {
            val v = value.toLong() + 1
            val len = 64 - java.lang.Long.numberOfLeadingZeros(v)
            repeat(len - 1) { bit(0) }
            bits(v, len)
        }

        fun se(value: Int) = ue(if (value > 0) 2 * value - 1 else -2 * value)

        fun copyBits(src: ByteArray, n: Int) {
            for (i in 0 until n) bit((src[i ushr 3].toInt() ushr (7 - (i and 7))) and 1)
        }

        fun alignZero() {
            while (count != 0) bit(0)
        }

        fun toByteArray(): ByteArray = out.toByteArray()
    }
}
