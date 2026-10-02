package com.gamebex.client.stream.proto

/**
 * H.264 Annex-B byte streams (ITU-T H.264 Annex B), as MediaCodec encoders
 * emit them and decoders take them: NAL units behind 00 00 01 or 00 00 00 01
 * start codes. Shared by the host encoder and the guest decoder.
 */
object AnnexB {
    const val NAL_SLICE: Int = 1
    const val NAL_IDR: Int = 5
    const val NAL_SEI: Int = 6
    const val NAL_SPS: Int = 7
    const val NAL_PPS: Int = 8
    const val NAL_AUD: Int = 9

    val START_CODE: ByteArray = byteArrayOf(0, 0, 0, 1)

    /** One NAL unit of [type]: bytes [start] (the NAL header) up to [end], exclusive, with no start code. */
    data class Nal(val type: Int, val start: Int, val end: Int) {
        val size: Int get() = end - start
    }

    /** The NAL units in the first [length] bytes of [data], in order. Bytes before the first start code are ignored. */
    fun split(data: ByteArray, length: Int = data.size): List<Nal> {
        val out = ArrayList<Nal>()
        var nalStart = -1
        var i = 0
        while (i + 2 < length) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1) {
                if (nalStart >= 0) add(out, data, nalStart, i)
                nalStart = i + 3
                i += 3
            } else {
                i += 1
            }
        }
        if (nalStart >= 0) add(out, data, nalStart, length)
        return out
    }

    /** SPS and PPS of [data] as their own Annex-B stream (csd), or null when either is missing. */
    fun parameterSets(data: ByteArray, length: Int = data.size): ByteArray? {
        val nals = split(data, length)
        val sps = nals.firstOrNull { it.type == NAL_SPS } ?: return null
        val pps = nals.firstOrNull { it.type == NAL_PPS } ?: return null
        return join(listOf(data.copyOfRange(sps.start, sps.end), data.copyOfRange(pps.start, pps.end)))
    }

    fun hasIdr(data: ByteArray, length: Int = data.size): Boolean = split(data, length).any { it.type == NAL_IDR }

    /** NAL payloads (header included) joined behind 4-byte start codes. */
    fun join(nals: List<ByteArray>): ByteArray {
        val out = ByteArray(nals.sumOf { it.size + START_CODE.size })
        var p = 0
        for (n in nals) {
            START_CODE.copyInto(out, p)
            p += START_CODE.size
            n.copyInto(out, p)
            p += n.size
        }
        return out
    }

    /** Adds the NAL at [start] ending before [limit], without the zero bytes that belong to the next start code. */
    private fun add(out: MutableList<Nal>, data: ByteArray, start: Int, limit: Int) {
        var end = limit
        while (end > start && data[end - 1].toInt() == 0) end -= 1
        if (end > start) out += Nal(data[start].toInt() and 0x1F, start, end)
    }
}
