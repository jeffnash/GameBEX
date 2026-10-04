package com.gamebex.client.stream.guest

import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sqrt

class AudioPlc(private val enabled: Boolean = false) {
    private val history = ShortArray(4_096 * 2)
    private var position = 0L
    private var age = 0
    private var period = 0
    private var gapPosition = 0L
    private var heldL = 0
    private var heldR = 0
    private var recovery = 0
    private var recoveryL = 0
    private var recoveryR = 0
    var gapStarted = false
        private set

    fun reset() {
        history.fill(0); position = 0; age = 0; period = 0; recovery = 0
        recoveryL = 0; recoveryR = 0; gapStarted = false
        gapPosition = 0L; heldL = 0; heldR = 0
    }

    private fun value(at: Long, channel: Int): Int =
        if (at < 0 || position - at > 4_096) 0 else history[(at % 4_096).toInt() * 2 + channel].toInt()

    private fun choosePeriod(): Int {
        if (position < 608) return 0
        var best = .65
        var result = 0
        for (lag in 48..480) {
            var xx = 0.0; var yy = 0.0; var xy = 0.0
            for (i in 1..128) {
                val x = (value(position - i, 0) + value(position - i, 1)).toDouble()
                val y = (value(position - i - lag, 0) + value(position - i - lag, 1)).toDouble()
                xx += x * x; yy += y * y; xy += x * y
            }
            val score = if (xx > 1 && yy > 1) xy / sqrt(xx * yy) else 0.0
            if (score > best) { best = score; result = lag }
        }
        return result
    }

    fun sample(present: Boolean, left: Int, right: Int, out: ShortArray, offset: Int = 0) {
        gapStarted = !present && age == 0
        var l = left; var r = right
        if (!present) {
            if (age == 0) {
                period = if (enabled) choosePeriod() else 0
                gapPosition = position
                heldL = value(position - 1, 0); heldR = value(position - 1, 1)
            }
            age++
            val gain = exp(-age / 960.0)
            val predicted = if (period > 0) gapPosition - period + (age - 1) % period else 0L
            l = if (!enabled) 0 else ((if (period > 0) value(predicted, 0) else heldL) * gain).roundToInt()
            r = if (!enabled) 0 else ((if (period > 0) value(predicted, 1) else heldR) * gain).roundToInt()
            recoveryL = l; recoveryR = r; recovery = 96
        } else {
            if (enabled && recovery > 0) {
                val realWeight = (97 - recovery) / 96.0
                l = (recoveryL * (1 - realWeight) + left * realWeight).roundToInt()
                r = (recoveryR * (1 - realWeight) + right * realWeight).roundToInt()
                recovery--
            }
            age = 0
        }
        l = l.coerceIn(-32768, 32767); r = r.coerceIn(-32768, 32767)
        val slot = (position % 4_096).toInt() * 2
        history[slot] = l.toShort(); history[slot + 1] = r.toShort(); position++
        out[offset] = l.toShort(); out[offset + 1] = r.toShort()
    }

    val missingAge: Int get() = age
}
