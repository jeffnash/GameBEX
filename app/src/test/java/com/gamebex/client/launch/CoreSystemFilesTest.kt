package com.gamebex.client.launch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CoreSystemFilesTest {
    @get:Rule val tmp = TemporaryFolder()

    private val pack = CoreSystemFiles.Pack("Test.zip", setOf("dolphin-emu"), "dolphin-emu/Sys/marker.bin", 1)

    private fun zipOf(vararg entries: Pair<String, String>): File {
        val f = tmp.newFile("pack${System.nanoTime()}.zip")
        ZipOutputStream(f.outputStream()).use { z ->
            for ((name, body) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(body.toByteArray())
                z.closeEntry()
            }
        }
        return f
    }

    @Test fun safeEntryPathRejectsTraversalAndForeignRoots() {
        val roots = setOf("dolphin-emu")
        assertEquals("dolphin-emu/Sys/a.bin", CoreSystemFiles.safeEntryPath("dolphin-emu/Sys/a.bin", roots))
        assertNull(CoreSystemFiles.safeEntryPath("dolphin-emu/../x", roots))
        assertNull(CoreSystemFiles.safeEntryPath("/dolphin-emu/a", roots))
        assertNull(CoreSystemFiles.safeEntryPath("dolphin-emu\\a", roots))
        assertNull(CoreSystemFiles.safeEntryPath("other/a", roots))
        assertNull(CoreSystemFiles.safeEntryPath("dolphin-emu", roots))
        assertNull(CoreSystemFiles.safeEntryPath("dolphin-emu/dir/", roots))
        assertNull(CoreSystemFiles.safeEntryPath("dolphin-emu//a", roots))
    }

    @Test fun unpackInstallsFilesAndMarker() {
        val sys = tmp.newFolder("system")
        val z = zipOf("dolphin-emu/Sys/marker.bin" to "m", "dolphin-emu/Sys/GC/font.bin" to "f")
        val r = CoreSystemFiles.unpack(z, pack, sys)
        assertEquals(CoreSystemFiles.Result.Installed(2), r)
        assertEquals("f", File(sys, "dolphin-emu/Sys/GC/font.bin").readText())
        assertTrue(File(sys, pack.marker).isFile)
        assertFalse(sys.walk().any { it.name.endsWith(".part") })
    }

    @Test fun unpackNeverOverwritesExistingFiles() {
        val sys = tmp.newFolder("system")
        File(sys, "dolphin-emu/Sys").mkdirs()
        File(sys, "dolphin-emu/Sys/user.ini").writeText("mine")
        val z = zipOf("dolphin-emu/Sys/user.ini" to "theirs", "dolphin-emu/Sys/marker.bin" to "m")
        assertEquals(CoreSystemFiles.Result.Installed(1), CoreSystemFiles.unpack(z, pack, sys))
        assertEquals("mine", File(sys, "dolphin-emu/Sys/user.ini").readText())
    }

    @Test fun unsafeEntryRejectsWholePackBeforeWriting() {
        val sys = tmp.newFolder("system")
        val z = zipOf("dolphin-emu/Sys/ok.bin" to "ok", "dolphin-emu/../../evil" to "x", "dolphin-emu/Sys/marker.bin" to "m")
        assertTrue(CoreSystemFiles.unpack(z, pack, sys) is CoreSystemFiles.Result.Failed)
        assertEquals(0, sys.walk().count { it.isFile })
    }

    @Test fun packWithoutMarkerIsRejected() {
        val sys = tmp.newFolder("system")
        val z = zipOf("dolphin-emu/Sys/ok.bin" to "ok")
        assertTrue(CoreSystemFiles.unpack(z, pack, sys) is CoreSystemFiles.Result.Failed)
        assertFalse(CoreSystemFiles.isInstalled("dolphin", sys))
    }

    @Test fun realPacksUseTheirOwnRootsForMarkers() {
        for ((core, p) in CoreSystemFiles.PACKS) {
            assertEquals(core, p.marker, CoreSystemFiles.safeEntryPath(p.marker, p.roots))
        }
        assertTrue(CoreSystemFiles.isInstalled("snes9x", tmp.newFolder("empty")))
    }
}
