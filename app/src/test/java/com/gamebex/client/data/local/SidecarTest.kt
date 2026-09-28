package com.gamebex.client.data.local

import com.gamebex.source.DownloadFile
import com.gamebex.source.SourceRef
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SidecarTest {
    private fun scratch(): File = Files.createTempDirectory("sidecar").toFile()

    private fun ref() = SourceRef("acme", "gba/emerald", "https://catalog.example.com/roms/gba/emerald.html")

    @Test fun writeThenReadRoundTrip() {
        val dir = scratch()
        val rom = File(dir, "emerald.zip").apply { writeBytes(ByteArray(4)) }
        val variant = DownloadFile(ref(), "https://catalog.example.com/download/x/1", "emerald.zip", pageUrl = "https://catalog.example.com/download/x/1")
        Sidecars.write(rom, Sidecar(title = "Emerald", source = "SOURCE", sourceId = "acme", sourceRef = ref(), sourceVariant = variant))
        val read = Sidecars.read(rom)!!
        assertEquals("Emerald", read.title)
        assertEquals("SOURCE", read.source)
        assertEquals("acme", read.sourceId)
        assertEquals(ref(), read.sourceRef)
        assertEquals(variant, read.sourceVariant)
        assertTrue(File(dir, "emerald.zip.gamebex.json").isFile)
        assertTrue(dir.listFiles { f -> f.name.endsWith(".tmp") }!!.isEmpty())
    }

    @Test fun corruptReadsNull() {
        val dir = scratch()
        val rom = File(dir, "d.zip").apply { writeBytes(ByteArray(4)) }
        File(dir, "d.zip.gamebex.json").writeText("{bad")
        assertNull(Sidecars.read(rom))
    }

    @Test fun sidecarNameIsNeverAGame() {
        assertTrue(Sidecars.isSidecarName("x.zip.gamebex.json"))
        assertTrue(!Sidecars.isSidecarName("mod.json"))
    }

    @Test fun missingReadsNull() {
        val dir = scratch()
        val rom = File(dir, "n.zip").apply { writeBytes(ByteArray(4)) }
        assertNull(Sidecars.read(rom))
    }

    @Test fun forwardedFieldsSurviveUnknownKeys() {
        val dir = scratch()
        val rom = File(dir, "f.zip").apply { writeBytes(ByteArray(4)) }
        File(dir, "f.zip.gamebex.json").writeText("""{"title":"F","futureField":{"a":[1,2]}}""")
        assertEquals("F", Sidecars.read(rom)!!.title)
    }
}
