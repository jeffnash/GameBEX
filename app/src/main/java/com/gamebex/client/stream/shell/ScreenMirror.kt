package com.gamebex.client.stream.shell

object ScreenMirror {
    fun fit(srcW: Int, srcH: Int, dstW: Int, dstH: Int): IntArray {
        if (srcW <= 0 || srcH <= 0) return intArrayOf(0, 0, dstW, dstH)
        val byWidth = dstW.toLong() * srcH <= dstH.toLong() * srcW
        val w = if (byWidth) dstW else ((dstH.toLong() * srcW) / srcH).toInt()
        val h = if (byWidth) ((dstW.toLong() * srcH) / srcW).toInt() else dstH
        val left = (dstW - w) / 2
        val top = (dstH - h) / 2
        return intArrayOf(left, top, left + w, top + h)
    }
}
