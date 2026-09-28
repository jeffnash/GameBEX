package com.gamebex.client.together.proto

import com.gamebex.client.together.CoreBuild
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlobSinkTest {
    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test fun writesChunksAndRenamesAfterChecks() {
        val dir = Files.createTempDirectory("blob-sink").toFile()
        val bytes = ByteArray(700 * 1024) { (it * 31).toByte() }
        val dest = File(dir, "core.so")
        val sink = BlobSink(dest, CoreBuild("snes9x", digest(bytes), bytes.size.toLong()))
        bytes.asList().chunked(256 * 1024).forEach { sink.write(it.toByteArray()) }
        assertTrue(sink.finish())
        assertTrue(dest.exists())
        assertFalse(File(dest.path + ".part").exists())
    }

    @Test fun wrongHashDeletesFiles() {
        val dir = Files.createTempDirectory("blob-sink").toFile()
        val bytes = byteArrayOf(1, 2, 3)
        val dest = File(dir, "core.so")
        val sink = BlobSink(dest, CoreBuild("snes9x", "0".repeat(64), bytes.size.toLong()))
        sink.write(bytes)
        assertFalse(sink.finish())
        assertFalse(dest.exists())
        assertFalse(File(dest.path + ".part").exists())
    }

    @Test(expected = java.io.IOException::class) fun overSizeThrows() {
        val dir = Files.createTempDirectory("blob-sink").toFile()
        val sink = BlobSink(File(dir, "core.so"), CoreBuild("snes9x", "0".repeat(64), 2))
        sink.write(byteArrayOf(1, 2, 3))
    }

    @Test fun abortDeletesPart() {
        val dir = Files.createTempDirectory("blob-sink").toFile()
        val dest = File(dir, "core.so")
        val sink = BlobSink(dest, CoreBuild("snes9x", "0".repeat(64), 3))
        sink.write(byteArrayOf(1))
        sink.abort()
        assertFalse(File(dest.path + ".part").exists())
    }

    @Test fun finishingTwiceReturnsFalse() {
        val dir = Files.createTempDirectory("blob-sink").toFile()
        val bytes = byteArrayOf(1, 2, 3)
        val dest = File(dir, "core.so")
        val sink = BlobSink(dest, CoreBuild("snes9x", digest(bytes), bytes.size.toLong()))
        sink.write(bytes)
        assertTrue(sink.finish())
        assertFalse(sink.finish())
    }
}
