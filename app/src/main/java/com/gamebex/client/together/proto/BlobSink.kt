package com.gamebex.client.together.proto

import com.gamebex.client.together.CoreBuild
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/**
 * Receives one blob into [dest], checking size and SHA-256. Writes go to `<dest>.part`;
 * [finish] renames only after both checks pass. Blocking: call from Dispatchers.IO, from one coroutine.
 */
class BlobSink(private val dest: File, private val expected: CoreBuild) {
    private val part = File(dest.path + ".part")
    private val digest = MessageDigest.getInstance("SHA-256")
    private var out: FileOutputStream? = null
    private var closed = false

    var written: Long = 0L
        private set

    fun write(bytes: ByteArray) {
        if (closed) throw IOException("sink closed")
        if (written + bytes.size > expected.sizeBytes) throw IOException("blob larger than expected")
        val o = out ?: run {
            dest.parentFile?.mkdirs()
            FileOutputStream(part).also { out = it }
        }
        o.write(bytes)
        digest.update(bytes)
        written += bytes.size
    }

    fun finish(): Boolean {
        if (closed) return false
        closed = true
        return try {
            out?.close()
            val hex = digest.digest().joinToString("") { "%02x".format(it) }
            if (out == null || written != expected.sizeBytes || hex != expected.sha256) {
                part.delete(); dest.delete(); false
            } else {
                if (dest.exists()) dest.delete()
                if (!part.renameTo(dest)) {
                    part.copyTo(dest, overwrite = true)
                    part.delete()
                }
                true
            }
        } catch (e: Exception) {
            part.delete(); dest.delete(); false
        }
    }

    fun abort() {
        if (!closed) {
            closed = true
            runCatching { out?.close() }
        }
        part.delete()
    }
}
