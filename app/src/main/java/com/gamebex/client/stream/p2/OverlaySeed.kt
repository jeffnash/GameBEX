package com.gamebex.client.stream.p2

import android.content.pm.PackageManager
import com.gamebex.client.stream.guest.OverlayCfg
import java.io.File
import java.util.zip.ZipFile

/**
 * Stock RetroArch configures no per-core overlay, which leaves the host
 * nothing to share with the guest. This copies the core's own overlay
 * (cfg + images) out of the RetroArch APK into shared storage, where
 * both RetroArch (`input_overlay` in the stream session config) and
 * the host's overlay reader can use it.
 *
 * The APK's `base.apk` is world-readable, so this needs no root and no
 * RetroArch-private paths. A seeded overlay is reused until it is deleted.
 */
object OverlaySeed {
    /** One overlay inside the APK: [dir]/[cfg] plus its image refs. */
    data class Asset(val dir: String, val cfg: String)

    /** Core id (graphics suffixes stripped) -> the overlay RA ships for it. */
    val CORE_ASSETS: Map<String, Asset> = mapOf(
        "mupen64plus_next" to Asset("assets/overlays/gamepads/n64", "n64.cfg"),
        "snes9x" to Asset("assets/overlays/gamepads/snes", "snes.cfg"),
        "gambatte" to Asset("assets/overlays/gamepads/gameboy", "gameboy.cfg"),
        "mgba" to Asset("assets/overlays/gamepads/gba", "gba.cfg"),
        "mesen" to Asset("assets/overlays/gamepads/nes", "nes.cfg"),
        "genesis_plus_gx" to Asset("assets/overlays/gamepads/genesis", "genesis.cfg"),
        "mednafen_psx" to Asset("assets/overlays/gamepads/psx", "psx.cfg"),
        "fbneo" to Asset("assets/overlays/gamepads/arcade", "arcade.cfg"),
        "mame" to Asset("assets/overlays/gamepads/arcade", "arcade.cfg"),
        "flycast" to Asset("assets/overlays/gamepads/flat", "dreamcast.cfg"),
        "mednafen_saturn" to Asset("assets/overlays/gamepads/flat", "saturn.cfg"),
    )

    private val VARIANT_SUFFIX = Regex("_(gles3|gles|vulkan|gl)$")

    /**
     * The overlay asset for [core] (`mupen64plus_next_gles3` and
     * `mame2003_plus` included), or null when no overlay is mapped —
     * then the guest keeps the 26a fallback.
     */
    fun assetForCore(core: String?): Asset? {
        if (core.isNullOrBlank()) return null
        val id = VARIANT_SUFFIX.replace(core.lowercase(), "")
        CORE_ASSETS[id]?.let { return it }
        return CORE_ASSETS.entries.firstOrNull { (k, _) -> id.startsWith(k) }?.value
    }

    /**
     * Copies [asset] out of the RetroArch APK [apk] into
     * `[destRoot]/overlays/gamebex/<name>/` (cfg + every image any page
     * names). Returns the seeded cfg, or null when anything is missing.
     * Never throws; never follows `..` out of the destination.
     */
    fun seed(apk: File, asset: Asset, destRoot: File): File? = runCatching {
        val destDir = File(destRoot, "overlays/gamebex/" + asset.cfg.removeSuffix(".cfg"))
        val destCfg = File(destDir, asset.cfg)
        if (destCfg.isFile && destCfg.length() > 0) return destCfg
        if (!apk.isFile) return null
        destDir.mkdirs()
        val base = destDir.canonicalPath + File.separator
        ZipFile(apk).use { zip ->
            val cfgEntry = zip.getEntry("${asset.dir}/${asset.cfg}") ?: return null
            val cfgText = zip.getInputStream(cfgEntry).bufferedReader().readText()
            if (cfgText.isBlank()) return null
            val refs = linkedSetOf<String>()
            OverlayCfg.parse(cfgText).pages.forEach { page ->
                page.image?.let(refs::add)
                page.descs.mapNotNullTo(refs) { it.image }
            }
            destCfg.writeText(cfgText)
            for (ref in refs) {
                if (ref.contains("..")) continue
                val entry = zip.getEntry("${asset.dir}/$ref")
                    ?: zip.getEntry(ref)
                    ?: continue
                val out = File(destDir, ref)
                if (!out.canonicalPath.startsWith(base)) continue
                out.parentFile?.mkdirs()
                zip.getInputStream(entry).use { ins ->
                    out.outputStream().use { outs -> ins.copyTo(outs) }
                }
            }
        }
        destCfg.takeIf { it.isFile && it.length() > 0 }
    }.getOrNull()

    /** RetroArch's APK on this phone, or null when it isn't installed. */
    fun findRetroArchApk(pm: PackageManager): File? {
        for (pkg in listOf("com.retroarch.aarch64", "com.retroarch")) {
            val src = runCatching { pm.getApplicationInfo(pkg, 0).sourceDir }.getOrNull()
            if (src != null) {
                val f = File(src)
                if (f.isFile) return f
            }
        }
        return null
    }
}
