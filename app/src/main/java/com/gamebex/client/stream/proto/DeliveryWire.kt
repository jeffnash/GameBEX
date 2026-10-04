package com.gamebex.client.stream.proto

import java.nio.ByteBuffer

object DeliveryWire {
    const val SIZE: Int = 56

    data class Report(
        val codecEpoch: Long,
        val seq: Int,
        val endNs: Long,
        val spanNs: Long,
        val bytes: Long,
        val frames: Int,
        val relativeP95Ns: Long,
        val pending: Int,
        val lastFrameSeq: Int,
    )

    fun encode(report: Report): ByteArray {
        require(valid(report))
        return ByteBuffer.allocate(SIZE).apply {
            putLong(report.codecEpoch)
            putInt(report.seq)
            putLong(report.endNs)
            putLong(report.spanNs)
            putLong(report.bytes)
            putInt(report.frames)
            putLong(report.relativeP95Ns)
            putShort(report.pending.toShort())
            putShort(0)
            putInt(report.lastFrameSeq)
        }.array()
    }

    fun decode(bytes: ByteArray): Report? {
        if (bytes.size != SIZE) return null
        val b = ByteBuffer.wrap(bytes)
        val epoch = b.long
        val seq = b.int
        val end = b.long
        val span = b.long
        val count = b.long
        val frames = b.int
        val delay = b.long
        val pending = b.short.toInt() and 0xffff
        if (b.short.toInt() != 0) return null
        val report = Report(epoch, seq, end, span, count, frames, delay, pending, b.int)
        return report.takeIf(::valid)
    }

    private fun valid(report: Report): Boolean =
        report.codecEpoch > 0L && report.spanNs in 200_000_000L..2_000_000_000L &&
            report.bytes in 0L..64_000_000L && report.frames in 0..500 &&
            report.relativeP95Ns in -1L..2_000_000_000L && report.pending in 0..32
}
