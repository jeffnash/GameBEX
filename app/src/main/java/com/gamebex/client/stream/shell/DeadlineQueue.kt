package com.gamebex.client.stream.shell

import com.gamebex.client.stream.StreamCaps
import java.util.ArrayDeque

class DeadlineQueue(deadlineMs: Int = 0) {
    enum class Reason { NONE, COUNT, AGE, COUNT_AND_AGE, EPOCH, AWAIT_KEY, STALE_POLLED }
    data class Result(
        val dropped: Int, val requestKeyframe: Boolean, val pressure: Boolean,
        val reason: Reason = Reason.NONE, val ageNs: Long = 0L, val depth: Int = 0,
        val incomingReason: Reason = Reason.NONE,
    )
    data class Entry(val frame: EncodedFrame, val queuedNs: Long, val revision: Long)

    private val deadline = deadlineMs.coerceIn(0, 40) * 1_000_000L
    private val limit = if (deadline != 0L) 2 else StreamCaps.MAX_QUEUED_FRAMES
    private val frames = ArrayDeque<Entry>()
    var epoch: Long = 0L
        private set
    var revision: Long = 0L
        private set
    var waitingForKey: Boolean = true
        private set
    var ageFallbacks: Long = 0L
        private set
    val size: Int get() = frames.size
    val isEmpty: Boolean get() = frames.isEmpty()

    fun clear(): Int {
        val count = size
        frames.clear()
        revision++
        waitingForKey = true
        return count
    }

    fun reset(codecEpoch: Long): Int {
        epoch = codecEpoch
        return clear()
    }

    fun ageNs(now: Long): Long = frames.peekFirst()?.let { ageNs(it, now) } ?: 0L

    fun offer(frame: EncodedFrame, now: Long): Result {
        if (frame.codecEpoch != epoch) return Result(1, false, false, Reason.EPOCH, depth = size,
            incomingReason = Reason.EPOCH)
        var dropped = 0
        val depth = size
        val oldestAgeNs = ageNs(now)
        val oldestExpired = frames.peekFirst()?.let { expired(it, now) } == true
        val countPressure = size >= limit
        val pressure = countPressure || oldestExpired
        val reason = when {
            countPressure && oldestExpired -> Reason.COUNT_AND_AGE
            countPressure -> Reason.COUNT
            oldestExpired -> Reason.AGE
            else -> Reason.NONE
        }
        if (pressure) dropped += clear()
        val entry = Entry(frame, now, revision)
        if (deadline != 0L && frame.encodedNs <= 0L) ageFallbacks++
        val incomingExpired = deadline != 0L && expired(entry, now)
        if (incomingExpired || (waitingForKey && !frame.key)) {
            if (incomingExpired && !waitingForKey) dropped += clear()
            val incomingReason = if (incomingExpired) Reason.AGE else Reason.AWAIT_KEY
            return Result(dropped + 1, true, pressure || incomingExpired,
                if (pressure) reason else incomingReason,
                if (pressure) oldestAgeNs else ageNs(entry, now), depth, incomingReason)
        }
        frames.addLast(entry)
        waitingForKey = false
        return Result(dropped, false, pressure, reason, oldestAgeNs, depth)
    }

    fun poll(now: Long): Pair<Entry?, Result> {
        if (deadline != 0L && frames.peekFirst()?.let { expired(it, now) } == true) {
            val ageNs = ageNs(now)
            val depth = size
            val count = clear()
            return null to Result(count, true, true, Reason.AGE, ageNs, depth)
        }
        return frames.pollFirst() to Result(0, false, false)
    }

    fun valid(entry: Entry, now: Long): Boolean = entry.frame.codecEpoch == epoch &&
        entry.revision == revision && (deadline == 0L || !expired(entry, now))

    internal fun ageNs(entry: Entry, now: Long): Long {
        val origin = if (deadline != 0L && entry.frame.encodedNs > 0L) entry.frame.encodedNs else entry.queuedNs
        return (now - origin).coerceAtLeast(0L)
    }

    private fun expired(entry: Entry, now: Long): Boolean = ageNs(entry, now) >
        if (deadline != 0L) deadline else StreamCaps.MAX_QUEUE_AGE_MS * 1_000_000L
}
