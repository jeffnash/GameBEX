package com.gamebex.client.stream.proto

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import java.net.DatagramSocket
import java.net.Socket

/**
 * Socket tuning shared by both ends. Wi-Fi (WMM) queues packets by their DSCP mark, so video rides the
 * video access category and player 2's input the voice one, ahead of bulk traffic on the same air.
 * Every call is best effort: a refusal leaves the socket as it was.
 */
object StreamSockets {
    /** Measured net p95 29 ms with marking on, 32 ms off, so it stays on. */
    @Volatile var enabled: Boolean = true

    /** DSCP AF41 (interactive video) in the IPv4 TOS byte. */
    const val TOS_VIDEO: Int = 0x88

    const val TOS_INPUT: Int = 0xB8
    const val TOS_CS6: Int = 0xC0

    /** Linux TCP_NOTSENT_LOWAT: how much unsent data the kernel may hold before writes block. */
    private const val TCP_NOTSENT_LOWAT = 25

    /** ~5 frames at 4 Mb/s: more than that waiting in the kernel is latency the send queue can't see or drop. */
    const val NOT_SENT_LOW_WATER: Int = 24 * 1024

    fun markVideo(s: Socket) {
        if (!enabled) return
        runCatching { s.trafficClass = TOS_VIDEO }
    }

    fun inputTos(cs6: Boolean): Int = if (cs6) TOS_CS6 else TOS_INPUT

    fun markInput(s: DatagramSocket, cs6: Boolean = false): Int? {
        if (!enabled) return null
        return runCatching {
            s.trafficClass = inputTos(cs6)
            s.trafficClass
        }.getOrNull()
    }

    /** Host media socket: video mark, and a small unsent-bytes cap so congestion shows up in our queue. */
    fun tuneMedia(s: Socket) {
        if (!enabled) return
        markVideo(s)
        runCatching {
            ParcelFileDescriptor.fromSocket(s).use { pfd ->
                Os.setsockoptInt(pfd.fileDescriptor, OsConstants.IPPROTO_TCP, TCP_NOTSENT_LOWAT, NOT_SENT_LOW_WATER)
            }
        }
    }
}
