package com.gamebex.client.together.net

import com.gamebex.client.together.RaRoom
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** RetroArch's private UDP RANQ/RANS LAN room probe. */
object RaLanProbe {
    val QUERY: ByteArray = byteArrayOf(0x52, 0x41, 0x4E, 0x51)

    private const val MIN_PACKET_LENGTH = 688

    fun parse(bytes: ByteArray, length: Int): RaRoom? {
        if (length < MIN_PACKET_LENGTH || length > bytes.size) return null
        if (bytes[0] != 'R'.code.toByte() || bytes[1] != 'A'.code.toByte() ||
            bytes[2] != 'N'.code.toByte() || bytes[3] != 'S'.code.toByte()
        ) return null
        return RaRoom(
            nick = text(bytes, 16, 32),
            core = text(bytes, 80, 32),
            coreVersion = text(bytes, 112, 32),
            content = text(bytes, 176, 256),
            contentCrc = readInt(bytes, 4).toLong() and 0xffff_ffffL,
            port = readInt(bytes, 8),
            raVersion = text(bytes, 144, 32),
        )
    }

    suspend fun room(address: String, port: Int, timeoutMs: Long = 800L): RaRoom? =
        withContext(Dispatchers.IO) {
            val timeout = timeoutMs.coerceAtLeast(1L).coerceAtMost(Int.MAX_VALUE.toLong())
            try {
                val expected = InetAddress.getByName(address)
                DatagramSocket().use { socket ->
                    socket.soTimeout = timeout.toInt()
                    val target = InetSocketAddress(expected, port)
                    socket.send(DatagramPacket(QUERY, QUERY.size, target))
                    val buffer = ByteArray(1024)
                    val packet = DatagramPacket(buffer, buffer.size)
                    val deadline = System.nanoTime() + timeout * 1_000_000L
                    var result: RaRoom? = null
                    while (result == null) {
                        val remainingMs = (deadline - System.nanoTime()) / 1_000_000L
                        if (remainingMs <= 0) break
                        socket.soTimeout = remainingMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(1)
                        packet.length = buffer.size
                        try {
                            socket.receive(packet)
                        } catch (_: SocketTimeoutException) {
                            break
                        }
                        if (packet.address == expected) {
                            result = parse(buffer, packet.length)
                        }
                    }
                    result
                }
            } catch (_: IOException) {
                null
            }
        }

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 24) or
            ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or
            (bytes[offset + 3].toInt() and 0xff)

    private fun text(bytes: ByteArray, offset: Int, width: Int): String {
        var end = offset
        val limit = offset + width
        while (end < limit && bytes[end].toInt() != 0) end++
        return String(bytes, offset, end - offset, StandardCharsets.UTF_8)
    }
}
