package com.gamebex.client.stream.shell

import com.gamebex.client.stream.proto.InputWire
import com.gamebex.client.stream.proto.PadState
import java.net.SocketAddress

class InputAuthority(
    private val write: (PadState) -> Boolean,
    private val clock: () -> Long = System::nanoTime,
    private val timeoutNs: Long = 250_000_000L,
) {
    data class Applied(val packetSeq: Int, val sampleNs: Long, val appliedNs: Long, val wrote: Boolean)
    data class Route(val address: SocketAddress, val receivedNs: Long)

    private var attachId = 0L
    private var ready = false
    private var needsNeutral = false
    private var haveSeq = false
    private var lastSeq = 0
    private var lastSuccessNs = clock()
    private var lastWriteNs = 0L
    private var route: Route? = null
    @Volatile var state: PadState = PadState.NEUTRAL
        private set

    @Synchronized fun attach(id: Long): Boolean {
        require(id != 0L)
        if (attachId == id) return ready
        attachId = id
        ready = false
        haveSeq = false
        route = null
        needsNeutral = true
        release()
        return ready
    }

    @Synchronized fun detach(id: Long) {
        if (id != attachId || id == 0L) return
        attachId = 0L
        ready = false
        haveSeq = false
        route = null
        needsNeutral = true
        release()
    }

    @Synchronized fun isAttached(id: Long): Boolean = id != 0L && attachId == id

    @Synchronized fun isStale(seq: Int): Boolean = haveSeq && !InputWire.isNewer(seq, lastSeq)

    @Synchronized fun apply(id: Long, seq: Int, sampleNs: Long, desired: PadState, from: SocketAddress? = null): Applied? {
        if (!isAttached(id) || isStale(seq)) return null
        if (from != null) route = Route(from, clock())
        if (!ready) return null
        val next = desired.sanitized()
        val changed = next != state
        if (changed) {
            if (!write(next)) return null
            state = next
            lastWriteNs = clock()
        }
        haveSeq = true
        lastSeq = seq
        lastSuccessNs = clock()
        return Applied(seq, sampleNs, lastWriteNs, changed)
    }

    @Synchronized fun audioRoute(maxAgeNs: Long): SocketAddress? {
        val current = route ?: return null
        return current.address.takeIf { attachId != 0L && clock() - current.receivedNs <= maxAgeNs }
    }

    @Synchronized fun tick() {
        if (needsNeutral || (!state.isNeutral && clock() - lastSuccessNs > timeoutNs)) {
            ready = false
            needsNeutral = true
            release()
        }
    }

    @Synchronized fun close() {
        attachId = 0L
        ready = false
        haveSeq = false
        route = null
        if (!state.isNeutral || needsNeutral) {
            needsNeutral = true
            release()
        }
    }

    private fun release() {
        if (!write(PadState.NEUTRAL)) return
        state = PadState.NEUTRAL
        lastWriteNs = clock()
        lastSuccessNs = lastWriteNs
        needsNeutral = false
        ready = attachId != 0L
    }
}
