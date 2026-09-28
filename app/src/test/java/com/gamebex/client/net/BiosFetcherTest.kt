package com.gamebex.client.net

import java.io.File
import java.net.ServerSocket
import java.security.MessageDigest
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** BIOS fetch: placement, MD5-gated install, skip-when-present. */
class BiosFetcherTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun md5(bytes: ByteArray): String {
        val d = MessageDigest.getInstance("MD5")
        return d.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    @Test fun targetDirBranches() {
        val root = File("/sdcard")
        assertEquals(
            File("/sdcard/RetroArch/system/dc"),
            BiosFetcher.targetDir(root, "dreamcast", "RetroArch/system/dc", null),
        )
        assertEquals(
            File("/sdcard/RetroArch/system"),
            BiosFetcher.targetDir(root, "psx", "RetroArch/system", null),
        )
        val roms = File("/sdcard/ROMs/neogeo")
        assertEquals(
            roms,
            BiosFetcher.targetDir(root, "neogeo", "the neogeo ROM folder (not system/)", roms),
        )
        assertEquals(
            null,
            BiosFetcher.targetDir(root, "neogeo", "the neogeo ROM folder (not system/)", null),
        )
        assertEquals(
            File("/sdcard/GameBEX/bios/ps2"),
            BiosFetcher.targetDir(root, "ps2", "NetherSX2 bios folder (pick in the app)", null),
        )
    }

    /** neogeo.zip is unpinned, so the fixture index can pin the served bytes' real md5. */
    @Test fun installsVerifiedBytesToRomFolder() {
        val payload = "neogeo-bios-bytes".toByteArray()
        val digest = md5(payload)
        val sha1 = "a".repeat(40)
        val index = testIndex("neogeo.zip", digest, sha1, payload.size.toLong())
        val romDir = tmp.newFolder("roms")
        val server = serve(mapOf("/bios/Arcade/neogeo.zip" to payload))
        try {
            val report = BiosFetcher.fetchWithIndex(
                index, "neogeo", romDir, emptyList(), tmp.root,
                baseUrl = "http://localhost:${server.port}",
            )
            assertEquals(1, report.installed.size)
            assertEquals("neogeo.zip", report.installed.single().name)
            assertTrue(report.failed.isEmpty())
            assertEquals(payload.toList(), File(romDir, "neogeo.zip").readBytes().toList())
        } finally {
            server.close()
        }
    }

    @Test fun md5MismatchLeavesNoFile() {
        val payload = "corrupt".toByteArray()
        // Index expects a different dump than the server sends.
        val index = testIndex("neogeo.zip", "0".repeat(32), "b".repeat(40), payload.size.toLong())
        val romDir = tmp.newFolder("roms")
        val server = serve(mapOf("/bios/Arcade/neogeo.zip" to payload))
        try {
            val report = BiosFetcher.fetchWithIndex(
                index, "neogeo", romDir, emptyList(), tmp.root,
                baseUrl = "http://localhost:${server.port}",
            )
            assertEquals(1, report.failed.size)
            assertTrue(report.failed.single().reason.contains("md5"))
            assertTrue(!File(romDir, "neogeo.zip").exists())
            assertTrue(!File(romDir, "neogeo.zip.part").exists())
        } finally {
            server.close()
        }
    }

    @Test fun summaryCarriesStagingPointer() {
        val r = BiosFetcher.FetchReport(
            outcomes = listOf(BiosFetcher.Outcome.Installed("a.bin", 4)),
            stagedNote = "Point the emulator's BIOS folder at /sdcard/GameBEX/bios/ps2 (one-time setup)",
        )
        assertTrue(r.summary().contains("1 BIOS installed: a.bin"))
        assertTrue(r.summary().contains("/sdcard/GameBEX/bios/ps2"))
        val plain = BiosFetcher.FetchReport(
            outcomes = listOf(BiosFetcher.Outcome.Installed("a.bin", 4)),
        )
        assertEquals("1 BIOS installed: a.bin", plain.summary())
    }

    @Test fun skipsFileAlreadyAtTarget() {
        val romDir = tmp.newFolder("roms")
        File(romDir, "neogeo.zip").writeText("already here")
        val index = testIndex("neogeo.zip", "0".repeat(32), "c".repeat(40), 12)
        val report = BiosFetcher.fetchWithIndex(index, "neogeo", romDir, emptyList(), tmp.root)
        assertTrue(report.installed.isEmpty())
        assertTrue(report.failed.isEmpty())
        assertEquals("already here", File(romDir, "neogeo.zip").readText())
    }

    private fun testIndex(name: String, digest: String, sha1: String, size: Long) =
        com.gamebex.client.data.RetroBios.Index(
            declarations = mapOf(
                "arcade" to listOf(
                    com.gamebex.client.data.RetroBios.RbFile(
                        name = name, destination = name, md5 = digest, sha1 = sha1, size = size,
                    ),
                ),
            ),
            bySha1 = mapOf(
                sha1 to com.gamebex.client.data.RetroBios.DbEntry(
                    sha1, "bios/Arcade/neogeo.zip", name, size, digest,
                ),
            ),
            byName = emptyMap(),
            byMd5 = emptyMap(),
        )

    /** Minimal one-thread HTTP stub: GET path -> bytes or 404. */
    private class StubHttp(val routes: Map<String, ByteArray>) : AutoCloseable {
        val socket = ServerSocket(0)
        val port: Int get() = socket.localPort

        init {
            thread(isDaemon = true, name = "stub-http") {
                while (!socket.isClosed) {
                    val conn = runCatching { socket.accept() }.getOrNull() ?: break
                    thread(isDaemon = true) {
                        conn.use { c ->
                            val reader = c.getInputStream().bufferedReader()
                            val line = runCatching { reader.readLine() }.getOrNull() ?: return@thread
                            val path = line.split(" ").getOrNull(1)?.substringBefore("?") ?: ""
                            var h: String?
                            do { h = runCatching { reader.readLine() }.getOrNull() } while (!h.isNullOrEmpty())
                            val body = routes[path]
                            val out = c.getOutputStream()
                            if (body == null) {
                                out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                            } else {
                                out.write("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                                out.write(body)
                            }
                            out.flush()
                        }
                    }
                }
            }
        }

        override fun close() = runCatching { socket.close() }.let { }
    }

    private fun serve(routes: Map<String, ByteArray>): StubHttp = StubHttp(routes)
}
