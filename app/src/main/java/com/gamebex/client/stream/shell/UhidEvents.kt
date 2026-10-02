package com.gamebex.client.stream.shell

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * struct uhid_event, as Linux include/uapi/linux/uhid.h lays it out (packed,
 * native byte order). Pure, so the byte layout is JVM-tested; UhidPad writes
 * these buffers to /dev/uhid. The same layout scrcpy's UhidManager writes
 * (scrcpy v4.1, server/src/main/java/com/genymobile/scrcpy/control/UhidManager.java).
 */
object UhidEvents {
    const val UHID_DESTROY: Int = 1
    const val UHID_START: Int = 2
    const val UHID_STOP: Int = 3
    const val UHID_OPEN: Int = 4
    const val UHID_CLOSE: Int = 5
    const val UHID_OUTPUT: Int = 6
    const val UHID_GET_REPORT: Int = 9
    const val UHID_GET_REPORT_REPLY: Int = 10
    const val UHID_CREATE2: Int = 11
    const val UHID_INPUT2: Int = 12
    const val UHID_SET_REPORT: Int = 13
    const val UHID_SET_REPORT_REPLY: Int = 14

    /** sizeof(struct uhid_event): the read buffer size. */
    const val EVENT_SIZE: Int = 4380
    const val NAME_BYTES: Int = 128
    const val PHYS_BYTES: Int = 64
    const val UNIQ_BYTES: Int = 64
    const val MAX_DESCRIPTOR: Int = 4096
    const val DATA_MAX: Int = 4096

    /** EIO, the error GameBEX answers feature-report requests with (the pad has none). */
    const val EIO: Int = 5

    /** type(4) + name(128) + phys(64) + uniq(64) + rd_size(2) + bus(2) + vendor/product/version/country(16). */
    const val CREATE2_HEADER: Int = 280

    fun create2(
        name: String,
        phys: String,
        uniq: String,
        bus: Int,
        vendor: Int,
        product: Int,
        version: Int,
        descriptor: ByteArray,
        order: ByteOrder = ByteOrder.nativeOrder(),
    ): ByteArray {
        require(descriptor.size in 1..MAX_DESCRIPTOR) { "descriptor size ${descriptor.size}" }
        val b = ByteBuffer.allocate(CREATE2_HEADER + descriptor.size).order(order)
        b.putInt(UHID_CREATE2)
        putString(b, name, NAME_BYTES)
        putString(b, phys, PHYS_BYTES)
        putString(b, uniq, UNIQ_BYTES)
        b.putShort(descriptor.size.toShort())
        b.putShort(bus.toShort())
        b.putInt(vendor)
        b.putInt(product)
        b.putInt(version)
        b.putInt(0)
        b.put(descriptor)
        return b.array()
    }

    fun input2(report: ByteArray, order: ByteOrder = ByteOrder.nativeOrder()): ByteArray {
        require(report.size in 1..DATA_MAX) { "report size ${report.size}" }
        val b = ByteBuffer.allocate(6 + report.size).order(order)
        b.putInt(UHID_INPUT2)
        b.putShort(report.size.toShort())
        b.put(report)
        return b.array()
    }

    fun destroy(order: ByteOrder = ByteOrder.nativeOrder()): ByteArray =
        ByteBuffer.allocate(4).order(order).putInt(UHID_DESTROY).array()

    /** Answer to UHID_GET_REPORT [id]: error [err], no data. */
    fun getReportReply(id: Int, err: Int, order: ByteOrder = ByteOrder.nativeOrder()): ByteArray {
        val b = ByteBuffer.allocate(12).order(order)
        b.putInt(UHID_GET_REPORT_REPLY)
        b.putInt(id)
        b.putShort(err.toShort())
        b.putShort(0)
        return b.array()
    }

    /** Answer to UHID_SET_REPORT [id] with error [err]. */
    fun setReportReply(id: Int, err: Int, order: ByteOrder = ByteOrder.nativeOrder()): ByteArray {
        val b = ByteBuffer.allocate(10).order(order)
        b.putInt(UHID_SET_REPORT_REPLY)
        b.putInt(id)
        b.putShort(err.toShort())
        return b.array()
    }

    /** The event type of a buffer read from /dev/uhid, or -1 when it is too short. */
    fun typeOf(buffer: ByteArray, length: Int, order: ByteOrder = ByteOrder.nativeOrder()): Int =
        if (length < 4) -1 else ByteBuffer.wrap(buffer, 0, 4).order(order).int

    /** The request id of a UHID_GET_REPORT or UHID_SET_REPORT event (the u32 after the type). */
    fun requestId(buffer: ByteArray, length: Int, order: ByteOrder = ByteOrder.nativeOrder()): Int =
        if (length < 8) -1 else ByteBuffer.wrap(buffer, 4, 4).order(order).int

    /** Writes [value] as UTF-8, cut to [width]-1 bytes at a character boundary, zero-padded to [width]. */
    private fun putString(b: ByteBuffer, value: String, width: Int) {
        val start = b.position()
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        var n = minOf(bytes.size, width - 1)
        while (n > 0 && n < bytes.size && (bytes[n].toInt() and 0xC0) == 0x80) n--
        b.put(bytes, 0, n)
        b.position(start + width)
    }
}
