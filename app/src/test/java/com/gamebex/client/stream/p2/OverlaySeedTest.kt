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

    @Test fun assetForCoreNullOnlyWhenBlank() {
        assertNull(OverlaySeed.assetForCore(null))
        assertNull(OverlaySeed.assetForCore(""))
        assertNull(OverlaySeed.assetForCore("  "))
    }

    @Test fun assetForCoreCoversNewSystems() {
        assertEquals("atari2600.cfg", OverlaySeed.assetForCore("stella")!!.cfg)
        assertEquals("atari7800.cfg", OverlaySeed.assetForCore("prosystem")!!.cfg)
        assertEquals("turbografx-16.cfg", OverlaySeed.assetForCore("mednafen_pce")!!.cfg)
        assertEquals("pc-fx.cfg", OverlaySeed.assetForCore("mednafen_pcfx")!!.cfg)
        assertEquals("virtualboy.cfg", OverlaySeed.assetForCore("mednafen_vb")!!.cfg)
        assertEquals("wonderswan.cfg", OverlaySeed.assetForCore("mednafen_wswan")!!.cfg)
        assertEquals("neogeo_pocket.cfg", OverlaySeed.assetForCore("mednafen_ngp")!!.cfg)
        assertEquals("NDS.cfg", OverlaySeed.assetForCore("melonds")!!.cfg)
        assertEquals("neogeo.cfg", OverlaySeed.assetForCore("neocd")!!.cfg)
        assertEquals("pokemini.cfg", OverlaySeed.assetForCore("pokemini")!!.cfg)
        assertEquals("psp.cfg", OverlaySeed.assetForCore("ppsspp")!!.cfg)
        assertEquals("DOS.cfg", OverlaySeed.assetForCore("dosbox_pure")!!.cfg)
        assertEquals("genesis.cfg", OverlaySeed.assetForCore("picodrive")!!.cfg)
    }

    @Test fun assetForCoreFallsBackToRetropad() {
        // What RetroArch itself shows for cores with no system overlay.
        assertEquals("retropad.cfg", OverlaySeed.assetForCore("citra")!!.cfg)
        assertEquals("retropad.cfg", OverlaySeed.assetForCore("same_cdi")!!.cfg)
        assertEquals("retropad.cfg", OverlaySeed.assetForCore("applewin")!!.cfg)
        assertEquals("retropad.cfg", OverlaySeed.assetForCore("bogus_core")!!.cfg)
    }

    @Test fun assetForCoreCoversDolphinAndOpera() {
        // gc/wii and 3do fall back to RA stock too.
        assertEquals("gamecube.cfg", OverlaySeed.assetForCore("dolphin")!!.cfg)
        assertEquals("The 3DO Company - 3DO.cfg", OverlaySeed.assetForCore("opera")!!.cfg)
    }

    @Test fun normalizeZipPathContainsDotDot() {
        assertEquals(
            "assets/overlays/gamepads/flat/img/A.png",
            OverlaySeed.normalizeZipPath("assets/overlays/gamepads/Named_Overlays/../flat/img/A.png"),
        )
        assertEquals("assets/overlays/x.png", OverlaySeed.normalizeZipPath("assets/overlays/./x.png"))
        assertNull(OverlaySeed.normalizeZipPath("assets/overlays/../../evil.png"))
        assertNull(OverlaySeed.normalizeZipPath("/abs/path.png"))
    }

    @Test fun readLoadsCfgAndImagesToMemory() {
        val asset = OverlaySeed.Asset("assets/overlays/gamepads/n64", "n64.cfg")
        val apk = fakeApk(
            "assets/overlays/gamepads/n64/n64.cfg" to CFG,
            "assets/overlays/gamepads/n64/img/a.png" to "PNG-A",
        )
        val (cfgText, images) = OverlaySeed.read(apk, asset)!!
        assertTrue(cfgText.contains("overlay0_desc0"))
        assertEquals("PNG-A", images.getValue("img/a.png").decodeToString())
        assertNull(OverlaySeed.read(File(tmp, "nope.apk"), asset))
        assertNull(OverlaySeed.read(fakeApk(), asset))
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

    @Test fun seedFlattensContainedDotDotRefs() {
        // Named overlays share ../flat/img art: contained `..` flattens into
        // the dest dir and the seeded cfg is rewritten to match.
        val named = CFG + "overlay0_desc1_overlay = ../shared/b.png\n"
        val asset = OverlaySeed.Asset("assets/overlays/gamepads/Named", "named.cfg")
        val apk = fakeApk(
            "assets/overlays/gamepads/Named/named.cfg" to named,
            "assets/overlays/gamepads/Named/img/a.png" to "PNG-A",
            "assets/overlays/gamepads/shared/b.png" to "PNG-B",
        )
        val cfg = OverlaySeed.seed(apk, asset, tmp)!!
        assertTrue(File(tmp, "overlays/gamebex/named/shared_b.png").isFile)
        assertTrue(cfg.readText().contains("shared_b.png"))
        assertTrue(!cfg.readText().contains(".."))
    }

    @Test fun seedRefusesEscapingRefs() {
        val evil = CFG + "overlay0_desc1_overlay = ../../../../evil.png\n"
        val asset = OverlaySeed.Asset("assets/overlays/gamepads/n64", "n64.cfg")
        val apk = fakeApk(
            "assets/overlays/gamepads/n64/n64.cfg" to evil,
            "evil.png" to "EVIL",
        )
        val cfg = OverlaySeed.seed(apk, asset, tmp)!!
        assertTrue(cfg.isFile)
        assertTrue(!File(tmp, "evil.png").exists())
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
