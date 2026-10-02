package com.gamebex.client.stream.guest

import java.util.ArrayDeque

/**
 * Host–guest clock offset from ping/pong, NTP-style: for guest send t0,
 * host receive t1, host send t2 and guest receive t3, offset = ((t1-t0)+(t2-t3))/2
 * and round trip = (t3-t0)-(t2-t1). The sample with the smallest round trip
 * among the last [window] wins, because queueing only ever adds delay.
 * host time = guest time + [offsetNs]. Thread-safe; pure.
 */
class ClockSync(private val window: Int = 16) {
    private class Sample(val offsetNs: Long, val rttNs: Long)

    private val samples = ArrayDeque<Sample>()

    @Synchronized
    fun add(t0: Long, t1: Long, t2: Long, t3: Long) {
        val rtt = (t3 - t0) - (t2 - t1)
        if (rtt < 0 || t2 < t1) return
        samples.addLast(Sample(((t1 - t0) + (t2 - t3)) / 2, rtt))
        while (samples.size > window) samples.pollFirst()
    }

    val ready: Boolean
        @Synchronized get() = samples.isNotEmpty()

    val offsetNs: Long
        @Synchronized get() = best()?.offsetNs ?: 0L

 /** Smallest recent round trip, or -1 before the first pong. */
    val rttNs: Long
        @Synchronized get() = best()?.rttNs ?: -1L

    fun hostToGuest(hostNs: Long): Long = hostNs - offsetNs

    fun guestToHost(guestNs: Long): Long = guestNs + offsetNs

    @Synchronized
    fun reset() = samples.clear()

    private fun best(): Sample? = samples.minByOrNull { it.rttNs }
}
