package com.gamebex.client.stream.shell

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import java.net.Socket

fun interface RSoundQuickAckFactory {
    fun create(socket: Socket): RSoundQuickAck?
}

interface RSoundQuickAck : AutoCloseable {
    fun rearm(): Boolean
    override fun close()
}

object AndroidRSoundQuickAckFactory : RSoundQuickAckFactory {
    override fun create(socket: Socket): RSoundQuickAck? {
        if (!isAndroidRuntime()) return null
        val descriptor = ParcelFileDescriptor.fromSocket(socket)
        return object : RSoundQuickAck {
            override fun rearm(): Boolean = try {
                Os.setsockoptInt(descriptor.fileDescriptor, OsConstants.IPPROTO_TCP, TCP_QUICKACK, 1)
                true
            } catch (_: Exception) {
                false
            }

            override fun close() { descriptor.close() }
        }
    }

    internal fun isAndroidRuntime(): Boolean =
        System.getProperty("java.vm.name") == "Dalvik" ||
            System.getProperty("java.runtime.name") == "Android Runtime"

    private const val TCP_QUICKACK = 12
}
