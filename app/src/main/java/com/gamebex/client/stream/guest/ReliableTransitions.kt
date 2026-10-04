package com.gamebex.client.stream.guest

import com.gamebex.client.stream.proto.PadState
import java.util.ArrayDeque

class ReliableTransitions(private val orderedEdges: Boolean = true, initialGeneration: Int = 0) {
    data class Send(val generation: Int?, val state: PadState, val originNs: Long, val cancel: Boolean)
    private data class Pending(val generation: Int, val buttons: Int, val hat: Int, val originNs: Long, val queuedNs: Long, val cancel: Boolean)

    private val edges = ArrayDeque<Pending>()
    private var wanted = PadState.NEUTRAL
    private var sampleNs = 0L
    private var generation = initialGeneration
    private var lastSendNs: Long? = null
    var cancellations = 0L
        private set

    fun update(state: PadState, atNs: Long, nowNs: Long = atNs) {
        val next = state.sanitized()
        if (next == wanted) return
        val digitalChanged = next.buttons != wanted.buttons || next.hat != wanted.hat
        if (orderedEdges && (digitalChanged || next.isNeutral)) {
            if (edges.size >= MAX_EDGES) {
                cancel(nowNs)
                return
            }
            edges.addLast(Pending(++generation, next.buttons, next.hat, atNs, nowNs, false))
        } else if (!orderedEdges) {
            if (edges.peekFirst()?.cancel != true) {
                edges.clear()
                if (next.isNeutral) edges.addLast(Pending(++generation, next.buttons, next.hat, atNs, nowNs, false))
            }
        }
        wanted = next
        sampleNs = atNs
        if (edges.size <= 1) lastSendNs = null
    }

    fun cancel(nowNs: Long) {
        cancellations++
        edges.clear()
        wanted = PadState.NEUTRAL
        sampleNs = nowNs
        edges.addLast(Pending(++generation, 0, PadState.NEUTRAL.hat, nowNs, nowNs, true))
        lastSendNs = null
    }

    fun next(nowNs: Long): Send? {
        expire(nowNs)
        if (delayNs(nowNs) > 0L) return null
        lastSendNs = nowNs
        val head = edges.peekFirst()
        return if (head == null) Send(null, wanted, sampleNs, false)
        else Send(head.generation, if (head.cancel) PadState.NEUTRAL else wanted.copy(buttons = head.buttons, hat = head.hat), head.originNs, head.cancel)
    }

    fun delayNs(nowNs: Long): Long {
        val last = lastSendNs ?: return 0L
        val interval = if (edges.isNotEmpty() || !wanted.isNeutral) ACTIVE_NS else IDLE_NS
        var delay = (interval - (nowNs - last)).coerceAtLeast(0L)
        val head = edges.peekFirst()
        if (head != null && !head.cancel) delay = minOf(delay, (MAX_AGE_NS + 1L - (nowNs - head.queuedNs)).coerceAtLeast(0L))
        return delay
    }

    fun ack(generation: Int): Boolean {
        val head = edges.peekFirst() ?: return false
        if (head.generation != generation) return false
        edges.removeFirst()
        lastSendNs = null
        return true
    }

    fun queued(): Int = edges.size

    private fun expire(nowNs: Long) {
        val head = edges.peekFirst() ?: return
        if (!head.cancel && nowNs - head.queuedNs > MAX_AGE_NS) cancel(nowNs)
    }

    companion object {
        const val MAX_EDGES = 64
        const val MAX_AGE_NS = 250_000_000L
        const val ACTIVE_NS = 8_000_000L
        const val IDLE_NS = 50_000_000L
    }
}
