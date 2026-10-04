package com.gamebex.client.stream.guest

class PlaybackPosition {
    private var last: Long? = null
    private var total = 0L

    fun reset() { last = null; total = 0L }

    fun update(raw: Int): Long? {
        val value = raw.toLong() and 0xffff_ffffL
        val previous = last
        if (previous == null) { last = value; total = value; return total }
        val delta = (value - previous) and 0xffff_ffffL
        if (delta > 0x7fff_ffffL) return null
        last = value
        total += delta
        return total
    }
}
