package com.gamebex.client.data.local

/**
 * Prep logic: pure string/list decisions (cue parsing, M3U generation,
 * arcade shortnames, disc-entry classification). JVM-unit-testable; the
 * file IO lives in PrepPipeline.
 */
object PrepLogic {
    data class CueTrack(val file: String, val type: String)

    /** Track files referenced by a .cue sheet, in order. Lenient parser. */
    fun parseCue(text: String): List<CueTrack> {
        val out = mutableListOf<CueTrack>()
        for (raw in text.lines()) {
            val line = raw.trim()
            if (!line.startsWith("FILE", ignoreCase = true)) continue
            // FILE "name.bin" BINARY  |  FILE name.bin BINARY
            val rest = line.substring(4).trim()
            val name: String
            val type: String
            if (rest.startsWith("\"")) {
                val end = rest.indexOf('"', 1)
                if (end < 0) continue
                name = rest.substring(1, end)
                type = rest.substring(end + 1).trim().substringBefore(' ').uppercase()
            } else {
                val parts = rest.split(Regex("\\s+"))
                if (parts.size < 2) continue
                name = parts[0]
                type = parts[1].uppercase()
            }
            if (name.isNotBlank()) out.add(CueTrack(name, type.ifBlank { "BINARY" }))
        }
        return out
    }

    /** M3U body for a multi-disc set (cue/gdi paths, one per line). */
    fun genM3u(entries: List<String>): String = entries.joinToString("\n", postfix = "\n")

    /** A .cue FILE paired with its first TRACK mode (MODE1/2352, AUDIO...). Lenient parser. */
    data class CueDataTrack(val file: String, val mode: String)

    fun parseCueTracks(text: String): List<CueDataTrack> {
        val out = mutableListOf<CueDataTrack>()
        var pending: String? = null
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.startsWith("FILE", ignoreCase = true)) {
                pending = cueFileName(line)
            } else if (pending != null && line.startsWith("TRACK", ignoreCase = true)) {
                val parts = line.split(Regex("\\s+"))
                if (parts.size >= 3 && parts[2].isNotBlank()) {
                    out.add(CueDataTrack(pending, parts[2].uppercase()))
                    pending = null
                }
            }
        }
        return out
    }

    /** FILE "name.bin" BINARY | FILE name.bin BINARY → name, or null. Shared by both cue parsers. */
    private fun cueFileName(line: String): String? {
        val rest = line.substring(4).trim()
        if (rest.startsWith("\"")) {
            val end = rest.indexOf('"', 1)
            if (end < 0) return null
            return rest.substring(1, end).ifBlank { null }
        }
        val parts = rest.split(Regex("\\s+"))
        if (parts.size < 2) return null
        return parts[0].ifBlank { null }
    }

    /** GD-ROM high-density base (real discs) vs CD-R-layout base (converted rips). */
    const val GD_HD_LBA = 45150
    const val CDR_HD_LBA = 45000

    /** Sector size for a cue TRACK mode; unknown modes assume raw 2352. */
    fun sectorSizeForMode(mode: String): Int {
        val m = mode.uppercase()
        if (m == "AUDIO") return 2352
        return when (m.substringAfter('/', "").toIntOrNull()) {
            2048, 2336, 2352, 2448 -> m.substringAfter('/').toInt()
            else -> 2352
        }
    }

    /** GDI track type: CDDA is 0, data follows the Redump 2352 convention (4). */
    fun gdiTypeForMode(mode: String): Int = if (mode.uppercase() == "AUDIO") 0 else 4

    data class GdiTrack(val file: String, val lba: Long, val type: Int, val sector: Int)

    /** GDI sheet body; spaced names quote Redump-style. */
    fun genGdi(tracks: List<GdiTrack>): String = buildString {
        appendLine(tracks.size)
        tracks.forEachIndexed { i, t ->
            val name = if (t.file.any { it.isWhitespace() }) "\"${t.file}\"" else t.file
            appendLine("${i + 1} ${t.lba} ${t.type} ${t.sector} $name 0")
        }
    }

    /** ISO9660 primary-volume root extent, or null when [pvd] isn't a PVD. Expects 2048 user bytes. */
    fun rootExtentFromPvd(pvd: ByteArray): Int? {
        if (pvd.size < 2048) return null
        if (pvd[0] != 1.toByte() || pvd[6] != 1.toByte()) return null
        if (pvd[1] != 'C'.code.toByte() || pvd[2] != 'D'.code.toByte() ||
            pvd[3] != '0'.code.toByte() || pvd[4] != '0'.code.toByte() ||
            pvd[5] != '1'.code.toByte()
        ) return null
        val b = pvd.sliceArray(158 until 162)
        return (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8) or
            ((b[2].toInt() and 0xFF) shl 16) or ((b[3].toInt() and 0xFF) shl 24)
    }

    /** True when a track-3 root extent uses CD-R numbering (reads land 150 sectors early at the GD base). */
    fun needs45000Gdi(rootExtent: Int): Boolean = rootExtent in CDR_HD_LBA until GD_HD_LBA

    /**
     * MAME/NeoGeo shortname rule: base (no ext) is lowercase 8.3-ish
     * token — letters, digits, underscore, dash, dot; no spaces/tags.
     */
    fun isArcadeShortname(fileName: String): Boolean {
        val base = fileName.substringBeforeLast('.')
        if (base.isEmpty() || base.length > 32) return false
        return base.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' } &&
            base == base.lowercase() && !base.contains(' ')
    }

    /** Disc-image extensions the pipeline knows how to boot or extract. */
    val BOOTABLE_DISC = setOf("cue", "gdi", "m3u", "iso", "chd", "ccd", "mds")
    val ARCHIVE = setOf("zip", "7z", "rar")
    val RAW_TRACK = setOf("bin", "img", "iso", "chd", "wav")

    /** Loose images that boot with no sheet on every route (universal). */
    val DIRECT_BOOT_BASE = setOf("iso", "chd")

    /**
     * Route-aware extras: formats the folder's emulators boot directly
     * (core and standalone alike), so no cue/sheet is demanded.
     * PPSSPP: cso/pbp/elf. Dolphin: rvz/gcm/gcz/wbfs/wia/ciso + dol/elf
     * homebrew. Flycast (core + standalone): cdi. PSX: pbp/exe/toc.
     * Saturn and TG-CD: toc.
     */
    val DIRECT_BOOT_EXTRA: Map<String, Set<String>> = mapOf(
        "psp" to setOf("cso", "pbp", "elf"),
        "gc" to setOf("rvz", "gcm", "gcz", "wbfs", "wia", "ciso", "dol", "elf"),
        "wii" to setOf("rvz", "gcm", "gcz", "wbfs", "wia", "ciso", "dol", "elf"),
        "dreamcast" to setOf("cdi"),
        "psx" to setOf("pbp", "exe", "psexe", "toc"),
        "saturn" to setOf("toc"),
        "tg-cd" to setOf("toc"),
    )

    fun directBootFor(folder: String): Set<String> =
        DIRECT_BOOT_BASE + DIRECT_BOOT_EXTRA[folder].orEmpty()

    /** Exts that are never plain cartridge boots (descriptors, tracks, archives, route-scoped CDI). */
    val NON_CART = setOf(
        "cue", "gdi", "m3u", "ccd", "mds", "iso", "chd", "cdi",
        "zip", "7z", "rar", "bin", "img", "wav",
    )

    /**
     * Registry exts that boot as plain files on this folder's route
     * (e.g. Jaguar .j64/.jag, PSX .exe homebrew) — decided from content
     * format, not folder membership alone.
     */
    fun cartReadyFor(folder: String): Set<String> {
        val reg = com.gamebex.client.data.PlatformRegistry.byFolder[folder]?.extensions.orEmpty()
        return reg - NON_CART - directBootFor(folder)
    }

    /** Accurate per-format explanation for unbootable loose files. */
    fun manualNoteFor(ext: String): String = when (ext) {
        "bin", "img", "wav" -> "Loose .$ext with no cue sheet — cores need the .cue (or .m3u/.gdi)."
        "ccd", "mds" -> "Loose .$ext descriptor without its image — needs the full set."
        "cdi" -> "Loose .cdi isn't bootable on this route's emulator."
        else -> "Loose .$ext isn't bootable on this route — needs a cue/m3u or a supported image."
    }

    data class ZipPlan(
        /** Bootable sheets found (cue/gdi/m3u/ccd), sorted. */
        val sheets: List<String>,
        /** Loose bootable images without a sheet (iso/chd + route extras). */
        val loose: List<String>,
        /** True when the archive looks like a disc set at all. */
        val isDiscSet: Boolean,
    )

    /**
     * Classify zip entry names (case-insensitive) for the pipeline.
     * [directBoot] is the folder's bootable-without-sheet set.
     */
    fun planZip(entries: List<String>, directBoot: Set<String> = DIRECT_BOOT_BASE): ZipPlan {
        val lower = entries.associateBy { it.lowercase() }
        val sheets = lower.keys
            .filter {
                it.endsWith(".cue") || it.endsWith(".gdi") ||
                    it.endsWith(".m3u") || it.endsWith(".ccd")
            }
            .sorted()
            .map { lower.getValue(it) }
        val loose = lower.keys
            .filter { n -> directBoot.any { n.endsWith(".$it") } }
            .sorted()
            .map { lower.getValue(it) }
        // Any loose entry is inherently bootable (it matched directBoot),
        // so single- AND multi-image sets without sheets still count.
        return ZipPlan(sheets, loose, sheets.isNotEmpty() || loose.isNotEmpty())
    }

    /**
     * Which sheet to boot: existing .m3u wins, else the single sheet
     * (callers dedupe same-disc pairs via [preferSheets] first).
     * Returns null when the plan needs an M3U generated (multi-sheet).
     */
    fun pickBootSheet(sheets: List<String>): String? {
        if (sheets.isEmpty()) return null
        sheets.firstOrNull { it.lowercase().endsWith(".m3u") }?.let { return it }
        if (sheets.size == 1) return sheets[0]
        return null // multi-disc: pipeline generates the .m3u
    }

    /** Source fingerprint stored in the prep marker (length + mtime). */
    fun prepFinger(length: Long, mtime: Long): String = "$length:$mtime"

    /** True when the marker proves this exact archive already prepped. Pure. */
    fun prepMarkerFresh(marker: String?, finger: String): Boolean =
        marker != null && marker.lineSequence().firstOrNull()?.trim() == finger

    /** Disc number for M3U ordering ("disc 2", "(Disc 3)", "diskb"...). */
    fun discIndex(name: String): Int {
        // Explicit disc/disk markers beat a bare "cdN": "Amiga CD32 (Disc 1)"
        // is disc 1, not 32.
        val m = DISC_MARKER.find(name) ?: CD_MARKER.find(name)
        val num = m?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }?.toIntOrNull()
        return num ?: 99
    }

    private val DISC_MARKER = Regex("(?i)disc[^0-9a-z]*([0-9]+)|disk[^0-9a-z]*([0-9]+)|\\(d([0-9]+)\\)")
    private val CD_MARKER = Regex("(?i)(?<![a-z0-9])cd([0-9]+)")

    /**
     * Direct boot target without generating an .m3u: an existing sheet
     * wins; a lone loose image boots only when there are no sheets (a
     * stray image must not hide a multi-disc cue set); a lone cart boots
     * only when nothing disc-like exists. Null means generate an .m3u.
     */
    fun pickBootTarget(sheets: List<String>, loose: List<String>, carts: List<String>): String? =
        pickBootSheet(sheets)
            ?: (if (sheets.isEmpty()) loose.singleOrNull() else null)
            ?: (if (sheets.isEmpty() && loose.isEmpty()) carts.singleOrNull() else null)

    /**
     * Known-unbootable (folder, extension) pairs: the registry accepts the
     * file (it belongs to the platform) but the route's emulator cannot
     * boot it — prep must say NEEDS_MANUAL, never READY. Absence from this
     * map keeps the previous behavior (boot as-is / generic note).
     */
    private val UNBOOTABLE: Map<Pair<String, String>, String> = mapOf(
        ("psx" to "ecm") to "ECM-compressed — decode to BIN/CUE on a PC (unecm), then re-scan.",
        ("psx" to "mdf") to "MDF/MDS isn't supported — convert to BIN/CUE on a PC.",
        ("psx" to "mds") to "MDS/MDF isn't supported — convert to BIN/CUE on a PC.",
        ("psx" to "psf") to "PSF is ripped game music, not a bootable game.",
        ("psx" to "minipsf") to "PSF is ripped game music, not a bootable game.",
        ("psx" to "z") to "Compressed/obscure PSX format — convert to BIN/CUE or CHD.",
        ("psx" to "znx") to "Compressed/obscure PSX format — convert to BIN/CUE or CHD.",
        ("psx" to "cbn") to "Compressed/obscure PSX format — convert to BIN/CUE or CHD.",
        ("saturn" to "mds") to "MDS/MDF isn't supported — convert to BIN/CUE on a PC.",
        ("atarijaguar" to "cue") to "Jaguar CD isn't emulated — this core plays cartridge ROMs (.j64/.jag) only.",
        ("atarijaguar" to "cdi") to "Jaguar CD isn't emulated — this core plays cartridge ROMs (.j64/.jag) only.",
        ("atarijaguar" to "bin") to "Ambiguous .bin — rename cartridge ROMs to .j64.",
        ("dreamcast" to "dat") to "Not a bootable image (stray metadata?) — needs the cue/gdi set.",
        ("psp" to "prx") to "PRX is a PSP plugin, not a game.",
        ("gc" to "json") to "JSON isn't a bootable disc image.",
        ("gc" to "dff") to "DFF isn't a bootable disc image.",
        ("wii" to "json") to "JSON isn't a bootable disc image.",
        ("wii" to "dff") to "DFF isn't a bootable disc image.",
        ("amiga" to "rp9") to "RP9 packages aren't supported — use ADF/IPF/HDF or LHA.",
        ("amiga" to "uae") to "A UAE config isn't a game — needs the disk image.",
        ("amiga" to "nrg") to "NRG isn't supported — convert to ISO/CUE.",
        ("amiga" to "mds") to "MDS/MDF isn't supported — convert to ISO/CUE.",
        ("amiga" to "ccd") to "CCD isn't supported here — use CUE/CHD.",
        ("msx" to "di1") to "Not supported by blueMSX — use ROM/DSK/CAS.",
        ("msx" to "di2") to "Not supported by blueMSX — use ROM/DSK/CAS.",
        ("msx" to "dmk") to "Not supported by blueMSX — use ROM/DSK/CAS.",
        ("msx" to "fd1") to "Not supported by blueMSX — use ROM/DSK/CAS.",
        ("msx" to "fd2") to "Not supported by blueMSX — use ROM/DSK/CAS.",
        ("msx" to "ogv") to "Not supported by blueMSX — use ROM/DSK/CAS.",
        ("msx" to "wav") to "Not supported by blueMSX — use ROM/DSK/CAS.",
        ("msx" to "xsa") to "Not supported by blueMSX — use ROM/DSK/CAS.",
    )

    /** NEEDS_MANUAL note for known-unbootable pairs, or null when bootable-or-unknown. */
    fun unbootableNoteFor(folder: String, ext: String): String? =
        UNBOOTABLE[folder to ext.lowercase()]

    /** NKit images (Dolphin cannot boot them) match ".nkit." anywhere in the name. */
    fun isNkitName(name: String): Boolean = name.lowercase().contains(".nkit.")

    /** Folders whose emulator boots CloneCD sheets (mednafen family). */
    val CCD_BOOT_FOLDERS = setOf("psx", "saturn", "tg-cd")

    /** Cue FILE types the disc cores read raw; anything else (MP3/OGG/FLAC...) they cannot decode. */
    private val CUE_RAW_TYPES = setOf("BINARY", "WAVE")

    /** Track files in a .cue sheet using an undecodable audio type. Empty means all raw. */
    fun unsupportedCueAudio(text: String): List<String> =
        parseCue(text).filter { it.type.uppercase() !in CUE_RAW_TYPES }.map { it.file }

    /**
     * Same-disc sheet duplicates: a set often ships cue+ccd (or cue+gdi)
     * for one disc — booting both via m3u is wrong. Keeps one sheet per
     * directory+basename (Dreamcast prefers .gdi, elsewhere .cue, then
     * .ccd); .m3u entries pass through untouched.
     */
    fun preferSheets(sheets: List<String>, folder: String): List<String> {
        val order = if (folder == "dreamcast") listOf("gdi", "cue", "ccd") else listOf("cue", "gdi", "ccd")
        val rank = order.withIndex().associate { it.value to it.index }
        val (playlists, discs) = sheets.partition { it.lowercase().endsWith(".m3u") }
        val winners = discs.groupBy {
            it.substringBeforeLast('/', "").lowercase() + "\u0000" +
                it.substringAfterLast('/').substringBeforeLast('.').lowercase()
        }.values.map { group ->
            group.minByOrNull { rank[it.substringAfterLast('.').lowercase()] ?: 99 }!!
        }.sorted()
        return (playlists.sorted() + winners)
    }

    /**
     * Zip entries that boot as plain files on this folder's route (Jaguar
     * carts in zips, PSX homebrew, Dreamcast ELF...): registry-bootable,
     * not a sheet/image, and not a known-unbootable pair.
     */
    fun zipCartCandidates(entries: List<String>, folder: String): List<String> {
        val cart = cartReadyFor(folder)
        return entries.filter { e ->
            val ext = e.substringAfterLast('.', "").lowercase()
            ext.isNotEmpty() && ext in cart && unbootableNoteFor(folder, ext) == null
        }.sorted()
    }

    /**
     * True when [folder]'s games go through a real preparation step (disc
     * sheets/archives, arcade shortname sets, or required BIOS). Plain
     * cartridge systems boot as-is, so the UI hides their prep row. Pure.
     */
    fun hasPrepStep(folder: String): Boolean =
        folder in com.gamebex.client.data.BiosTable.DISC_FOLDERS ||
            folder in com.gamebex.client.data.BiosTable.ARCADE_FOLDERS ||
            com.gamebex.client.data.BiosTable.TABLE[folder].orEmpty().any { it.required }

    /** Sheet extensions whose references the scanner folds into one row. */
    val SCAN_SHEETS = setOf("cue", "gdi", "ccd", "m3u")

    private val DISC_TAG = Regex("(?i)\\s*[(\\[](?:disc|disk|cd)\\s*[0-9]+(?:\\s*of\\s*[0-9]+)?[)\\]]")

    /** Title key with the "(Disc N)" / "(Disk 1 of 2)" tag removed, or null when untagged. */
    fun discGroupKey(name: String): String? {
        val base = name.substringBeforeLast('.')
        if (!DISC_TAG.containsMatchIn(base)) return null
        return DISC_TAG.replace(base, "").replace(Regex("\\s+"), " ").trim().lowercase()
    }

    /**
     * Files in ONE directory listing that must not become their own
     * library rows (pure; case-insensitive):
     *  - tracks/images a sibling .cue/.gdi/.ccd references (.bin/.img/.sub/.wav...);
     *  - sheets/images a sibling .m3u lists;
     *  - the losing sheet of a same-basename pair ([preferSheets]);
     *  - "(Disc N)" siblings of one title: an .m3u of that title covers all
     *    of them, else only the lowest disc stays listed.
     * [refs] maps a sheet name to its parsed references (null = unreadable).
     * Never writes: the user's folder is left exactly as-is.
     */
    fun scanSkips(folder: String, names: List<String>, refs: Map<String, List<String>?>): Set<String> {
        val byLower = names.associateBy { it.lowercase() }
        val skip = mutableSetOf<String>()
        fun refName(ref: String): String? {
            val r = ref.trim().removePrefix("./").removePrefix(".\\")
            // Only direct siblings: nested refs point outside this listing.
            if (r.isEmpty() || r.contains('/') || r.contains('\\')) return null
            return byLower[r.lowercase()]
        }
        for ((sheet, list) in refs) {
            if (sheet.substringAfterLast('.').lowercase() !in SCAN_SHEETS) continue
            list.orEmpty().mapNotNull(::refName).filter { it != sheet }.forEach { skip += it }
        }
        val sheets = names.filter { it.substringAfterLast('.').lowercase() in SCAN_SHEETS }
        val kept = preferSheets(sheets.filter { it !in skip }, folder).toSet()
        sheets.filter { it !in skip && it !in kept }.forEach { skip += it }
        // Multi-disc sets without a covering playlist: one row per title.
        val playlistKeys = names.filter { it.lowercase().endsWith(".m3u") && it !in skip }
            .map { (discGroupKey(it) ?: it.substringBeforeLast('.').replace(Regex("\\s+"), " ").trim().lowercase()) }
            .toSet()
        names.filter { it !in skip }
            .mapNotNull { n -> discGroupKey(n)?.let { it to n } }
            .groupBy({ it.first }, { it.second })
            .forEach { (key, discs) ->
                if (key in playlistKeys) {
                    skip += discs.filterNot { it.lowercase().endsWith(".m3u") }
                } else if (discs.size > 1) {
                    val first = discs.minWith(compareBy<String>({ discIndex(it) }, { it.lowercase() }))
                    skip += discs.filter { it != first }
                }
            }
        return skip
    }

    /** CHD container version from a 16-byte header, or null when not a CHD. Big-endian u32 at 12. */
    fun chdVersion(header: ByteArray): Int? {
        if (header.size < 16) return null
        if (!header.sliceArray(0 until 8).contentEquals("MComprHD".toByteArray())) return null
        return ((header[12].toInt() and 0xFF) shl 24) or ((header[13].toInt() and 0xFF) shl 16) or
            ((header[14].toInt() and 0xFF) shl 8) or (header[15].toInt() and 0xFF)
    }
}
