package com.gamebex.client.stream.p2

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** OverlaySeed: core mapping plus APK extraction into shared storage. */
class OverlaySeedTest {
    private val tmp: File = Files.createTempDirectory("gbx-seed").toFile()

    @After
    fun tearDown() {
        tmp.deleteRecursively()
    }

    @Test fun assetForCoreStripsGraphicsSuffixes() {
        assertEquals("n64.cfg", OverlaySeed.assetForCore("mupen64plus_next_gles3")!!.cfg)
        assertEquals("n64.cfg", OverlaySeed.assetForCore("mupen64plus_next")!!.cfg)
        assertEquals("psx.cfg", OverlaySeed.assetForCore("mednafen_psx")!!.cfg)
        assertEquals("arcade.cfg", OverlaySeed.assetForCore("mame2003_plus")!!.cfg)
        assertEquals("arcade.cfg", OverlaySeed.assetForCore("fbneo")!!.cfg)
    }

    @Test fun assetForCoreNullWhenUnmapped() {
        assertNull(OverlaySeed.assetForCore(null))
        assertNull(OverlaySeed.assetForCore(""))
        assertNull(OverlaySeed.assetForCore("dolphin"))
        assertNull(OverlaySeed.assetForCore("ppsspp"))
    }

    @Test fun seedCopiesCfgAndImages() {
        val asset = OverlaySeed.Asset("assets/overlays/gamepads/n64", "n64.cfg")
        val apk = fakeApk(
            "assets/overlays/gamepads/n64/n64.cfg" to CFG,
            "assets/overlays/gamepads/n64/img/a.png" to "PNG-A",
        )
        val cfg = OverlaySeed.seed(apk, asset, tmp)!!
        assertEquals(File(tmp, "overlays/gamebex/n64/n64.cfg"), cfg)
        assertTrue(File(tmp, "overlays/gamebex/n64/img/a.png").isFile)
        // Idempotent: a second call reuses the files without rewriting.
        val stamp = cfg.lastModified()
        assertEquals(cfg, OverlaySeed.seed(File(tmp, "missing.apk"), asset, tmp))
        assertEquals(stamp, cfg.lastModified())
    }

    @Test fun seedNullWhenCfgMissing() {
        val asset = OverlaySeed.Asset("assets/overlays/gamepads/n64", "n64.cfg")
        assertNull(OverlaySeed.seed(fakeApk(), asset, tmp))
        assertNull(OverlaySeed.seed(File(tmp, "nope.apk"), asset, tmp))
    }

    @Test fun seedRefusesDotDotRefs() {
        val evil = CFG + "overlay0_desc1_overlay = ../evil.png\n"
        val asset = OverlaySeed.Asset("assets/overlays/gamepads/n64", "n64.cfg")
        val apk = fakeApk(
            "assets/overlays/gamepads/n64/n64.cfg" to evil,
            "assets/overlays/gamepads/evil.png" to "EVIL",
        )
        val cfg = OverlaySeed.seed(apk, asset, tmp)!!
        assertTrue(cfg.isFile)
        assertTrue(!File(tmp, "overlays/gamebex/evil.png").exists())
        assertTrue(!File(tmp, "overlays/evil.png").exists())
    }

    private fun fakeApk(vararg entries: Pair<String, String>): File {
        val apk = File(tmp, "fake-${System.nanoTime()}.apk")
        ZipOutputStream(apk.outputStream()).use { zos ->
            for ((name, body) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(body.toByteArray())
                zos.closeEntry()
            }
        }
        return apk
    }

    private companion object {
        const val CFG: String = """overlays = 1
overlay0_name = "landscape"
overlay0_full_screen = true
overlay0_normalized = true
overlay0_descs = 2
overlay0_desc0 = "a,0.9,0.8,radial,0.05,0.07"
overlay0_desc0_overlay = img/a.png
overlay0_desc1 = "start,0.5,0.9,rect,0.04,0.05"
"""
    }
}
