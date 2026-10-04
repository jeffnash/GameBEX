package com.gamebex.client.stream.guest

class PendingPcm(val bytes: ByteArray, val info: AudioRenderInfo, val outputFirstByte: Long) {
    var offset = 0
        private set
    val complete: Boolean get() = offset == bytes.size
    val outputFirstFrame: Long get() = outputFirstByte / 4
    val remaining: Int get() = bytes.size - offset

    init { require(bytes.size == info.outputFrames * 4 && outputFirstByte >= 0) }

    fun wrote(bytes: Int) {
        require(bytes in 0..remaining)
        offset += bytes
    }
}
