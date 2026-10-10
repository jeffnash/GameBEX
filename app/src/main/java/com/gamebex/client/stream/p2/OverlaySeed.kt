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

    /**
     * Core id (graphics suffixes stripped) -> the overlay RA ships for
     * it. Verified landscape + normalized in the RA v3 APK.
     */
    val CORE_ASSETS: Map<String, Asset> = mapOf(
        "mupen64plus_next" to Asset("assets/overlays/gamepads/n64", "n64.cfg"),
        "snes9x" to Asset("assets/overlays/gamepads/snes", "snes.cfg"),
        "gambatte" to Asset("assets/overlays/gamepads/gameboy", "gameboy.cfg"),
        "mgba" to Asset("assets/overlays/gamepads/gba", "gba.cfg"),
        "stella" to Asset("assets/overlays/gamepads/flat", "atari2600.cfg"),
        "prosystem" to Asset("assets/overlays/gamepads/flat", "atari7800.cfg"),
        "mednafen_ngp" to Asset("assets/overlays/gamepads/flat", "neogeo_pocket.cfg"),
        "mednafen_pce" to Asset("assets/overlays/gamepads/flat", "turbografx-16.cfg"),
        "mednafen_pcfx" to Asset("assets/overlays/gamepads/flat", "pc-fx.cfg"),
        "mednafen_vb" to Asset("assets/overlays/gamepads/flat", "virtualboy.cfg"),
        "mednafen_wswan" to Asset("assets/overlays/gamepads/flat", "wonderswan.cfg"),
        "melonds" to Asset("assets/overlays/gamepads/lite", "NDS.cfg"),
        "neocd" to Asset("assets/overlays/gamepads/flat", "neogeo.cfg"),
        "pokemini" to Asset("assets/overlays/gamepads/flat", "pokemini.cfg"),
        "ppsspp" to Asset("assets/overlays/gamepads/flat", "psp.cfg"),
        "dosbox_pure" to Asset("assets/overlays/gamepads/lite", "DOS.cfg"),
        "picodrive" to Asset("assets/overlays/gamepads/genesis", "genesis.cfg"),
        "mesen" to Asset("assets/overlays/gamepads/nes", "nes.cfg"),
        "genesis_plus_gx" to Asset("assets/overlays/gamepads/genesis", "genesis.cfg"),
        "mednafen_psx" to Asset("assets/overlays/gamepads/psx", "psx.cfg"),
        "fbneo" to Asset("assets/overlays/gamepads/arcade", "arcade.cfg"),
        "mame" to Asset("assets/overlays/gamepads/arcade", "arcade.cfg"),
        "flycast" to Asset("assets/overlays/gamepads/flat", "dreamcast.cfg"),
        "mednafen_saturn" to Asset("assets/overlays/gamepads/flat", "saturn.cfg"),
        "dolphin" to Asset("assets/overlays/gamepads/flat", "gamecube.cfg"),
        "opera" to Asset("assets/overlays/gamepads/Named_Overlays", "The 3DO Company - 3DO.cfg"),
    )

    private val VARIANT_SUFFIX = Regex("_(gles3|gles|vulkan|gl)$")

    /** RetroArch's own default overlay, for cores that ship no system one. */
    val RETROPAD_FALLBACK: Asset = Asset("assets/overlays/gamepads/retropad", "retropad.cfg")

    /**
     * The overlay asset for [core] (`mupen64plus_next_gles3` and
     * `mame2003_plus` included). Cores RA ships no system overlay for
     * (3DS, CD-i, computers) get RA's own default retropad overlay —
     * what RetroArch itself shows there. Null only for blank input.
     */
    fun assetForCore(core: String?): Asset? {
        if (core.isNullOrBlank()) return null
        val id = VARIANT_SUFFIX.replace(core.lowercase(), "")
        CORE_ASSETS[id]?.let { return it }
        CORE_ASSETS.entries.firstOrNull { (k, _) -> id.startsWith(k) }?.value?.let { return it }
        return RETROPAD_FALLBACK
    }

    /**
     * Reads [asset] out of the RetroArch APK [apk] into memory: the cfg text
     * plus every image any page names (ref → bytes, keyed by the ref exactly
     * as written). Null when the cfg is missing or blank. Never throws.
     * `..` refs (e.g. Named overlays sharing `../flat/img/`) resolve
     * lexically and stay inside `assets/overlays/`.
     */
    fun read(apk: File, asset: Asset): Pair<String, Map<String, ByteArray>>? = runCatching {
        if (!apk.isFile) return null
        ZipFile(apk).use { zip ->
            val cfgEntry = zip.getEntry("${asset.dir}/${asset.cfg}") ?: return null
            val cfgText = zip.getInputStream(cfgEntry).bufferedReader().readText()
            if (cfgText.isBlank()) return null
            val refs = linkedSetOf<String>()
            OverlayCfg.parse(cfgText).pages.forEach { page ->
                page.image?.let(refs::add)
                page.descs.mapNotNullTo(refs) { it.image }
            }
            val images = LinkedHashMap<String, ByteArray>()
            for (ref in refs) {
                val entry = zipEntryForRef(zip, asset.dir, ref) ?: continue
                images[ref] = zip.getInputStream(entry).readBytes()
            }
            cfgText to images
        }
    }.getOrNull()

    /**
     * The zip entry for an image [ref] written in a cfg under [cfgDir]:
     * beside the cfg first (lexically normalizing `..`, contained in
     * `assets/overlays/`), then at the APK root. Null when neither exists.
     */
    internal fun zipEntryForRef(zip: ZipFile, cfgDir: String, ref: String): java.util.zip.ZipEntry? {
        if (ref.isEmpty() || ref.startsWith("/") || ref.startsWith(":")) return null
        normalizeZipPath("$cfgDir/$ref")?.let { zip.getEntry(it) }?.let { return it }
        return normalizeZipPath(ref)?.let { zip.getEntry(it) }
    }

    /**
     * Lexically normalizes a zip path, resolving `.` and `..`; null when it
     * escapes `assets/overlays/` (or is absolute).
     */
    internal fun normalizeZipPath(path: String): String? {
        if (path.startsWith("/")) return null
        val out = ArrayDeque<String>()
        path.split('/').forEach { part ->
            when {
                part.isEmpty() || part == "." -> Unit
                part == ".." -> if (out.isEmpty()) return null else out.removeLast()
                else -> out.add(part)
            }
        }
        val clean = out.joinToString("/")
        if (!clean.startsWith("assets/overlays/")) return null
        return clean
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
            var cfgText = zip.getInputStream(cfgEntry).bufferedReader().readText()
            if (cfgText.isBlank()) return null
            val refs = linkedSetOf<String>()
            OverlayCfg.parse(cfgText).pages.forEach { page ->
                page.image?.let(refs::add)
                page.descs.mapNotNullTo(refs) { it.image }
            }
            for (ref in refs) {
                val entry = zipEntryForRef(zip, asset.dir, ref) ?: continue
                // `..` refs flatten into the dest dir (the cfg text is
                // rewritten to match): the seeded tree stays self-contained
                // and the host packer never follows `..` on disk.
                val flat = if (ref.contains("..")) {
                    normalizeZipPath("${asset.dir}/$ref")
                        ?.removePrefix("assets/overlays/gamepads/")?.replace('/', '_')
                        ?: continue
                } else ref
                val out = File(destDir, flat)
                if (!out.canonicalPath.startsWith(base)) continue
                // Whole-token only (a ref is a prefix of nothing else).
                if (flat != ref) cfgText = Regex(Regex.escape(ref) + "(?=[\"\\s]|$)").replace(cfgText, flat)
                out.parentFile?.mkdirs()
                zip.getInputStream(entry).use { ins ->
                    out.outputStream().use { outs -> ins.copyTo(outs) }
                }
            }
            destCfg.writeText(cfgText)
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
