package com.gamebex.client.data

import kotlinx.serialization.Serializable
import java.net.URLEncoder

/**
 * Dynamic RetroBIOS resolver. No BIOS URL is baked into the app: at
 * fetch time we pull the repo's own indexes (platforms.json declares
 * which file each RetroArch system wants; database.json maps every
 * content hash to its repo path) and download raw file bytes from the
 * `Abdess/retrobios` tree. Pure JVM + kotlinx.serialization, so the
 * whole resolve chain is unit-testable.
 *
 * Resolution for one wanted file, in order:
 *  1. platform declaration (exact name in this folder's RA system) —
 *     authoritative md5/sha1, bytes located via database.json;
 *  2. md5 match anywhere in the tree (repo filenames often differ:
 *     GBA_bios.rom vs gba_bios.bin, same dump);
 *  3. case-insensitive name match (SCPH-39001.bin vs scph39001.bin).
 * A pinned BiosTable md5 that matches nothing is a loud Conflict —
 * never a silent wrong dump.
 */
object RetroBios {
    const val PLATFORMS_URL = "https://abdess.github.io/retrobios/api/v1/platforms.json"
    const val DATABASE_URL = "https://abdess.github.io/retrobios/api/v1/database.json"
    const val RAW_BASE = "https://raw.githubusercontent.com/Abdess/retrobios/main/"

    /** GameBEX folder -> RetroArch system id in platforms.json. Folders
     * absent here (switch, wiiu, gc) resolve by md5/name from the tree. */
    val SYSTEMS = mapOf(
        "dreamcast" to "sega-dreamcast",
        "psx" to "sony-playstation",
        "ps2" to "sony-playstation-2",
        "segacd" to "sega-mega-cd",
        "saturn" to "sega-saturn",
        "neogeo" to "arcade",
        "neogeocd" to "snk-neogeo-cd",
        "tg-cd" to "nec-pc-engine",
        "nds" to "nintendo-ds",
        "gba" to "nintendo-gba",
        "gb" to "nintendo-gb",
        "gbc" to "nintendo-gbc",
    )

    // Wire models (slim; unknown keys ignored).

    @Serializable
    private data class PlatformsDoc(val items: List<PlatformItem> = emptyList())

    @Serializable
    private data class PlatformItem(val id: String = "", val contract: Contract = Contract())

    @Serializable
    private data class Contract(val systems: Map<String, RbSystem> = emptyMap())

    @Serializable
    data class RbSystem(val files: List<RbFile> = emptyList())

    @Serializable
    data class RbFile(
        val name: String = "",
        val destination: String? = null,
        val md5: String? = null,
        val sha1: String? = null,
        val size: Long? = null,
        val required: Boolean? = null,
    )

    @Serializable
    private data class DatabaseDoc(
        val schema_version: Int = 0,
        val files: Map<String, DbFile> = emptyMap(),
    )

    @Serializable
    private data class DbFile(
        val path: String = "",
        val name: String = "",
        val size: Long = -1,
        val md5: String = "",
    )

    data class DbEntry(
        val sha1: String,
        val path: String,
        val name: String,
        val size: Long,
        val md5: String,
    )

    data class Index(
        /** RA system id -> declared files. */
        val declarations: Map<String, List<RbFile>>,
        val bySha1: Map<String, DbEntry>,
        /** Lowercased filename -> tree entries. */
        val byName: Map<String, List<DbEntry>>,
        /** Lowercased md5 -> tree entries. */
        val byMd5: Map<String, List<DbEntry>>,
        /** Normalized filename ([BiosCheck.normName]) -> tree entries. */
        val byNormName: Map<String, List<DbEntry>> = emptyMap(),
    )

    fun parsePlatforms(json: String, platformId: String = "retroarch"): Map<String, List<RbFile>> {
        val doc = AppJson.decodeFromString<PlatformsDoc>(json)
        val item = doc.items.firstOrNull { it.id == platformId } ?: return emptyMap()
        return item.contract.systems.mapValues { (_, s) -> s.files }
    }

    fun parseDatabase(json: String): Index {
        val doc = AppJson.decodeFromString<DatabaseDoc>(json)
        val bySha1 = mutableMapOf<String, DbEntry>()
        val byName = mutableMapOf<String, MutableList<DbEntry>>()
        val byMd5 = mutableMapOf<String, MutableList<DbEntry>>()
        val byNormName = mutableMapOf<String, MutableList<DbEntry>>()
        for ((sha1, f) in doc.files) {
            if (f.path.isBlank() || f.md5.isBlank()) continue
            val e = DbEntry(sha1, f.path, f.name, f.size, f.md5.lowercase())
            bySha1[sha1.lowercase()] = e
            byName.getOrPut(f.name.lowercase()) { mutableListOf() }.add(e)
            byMd5.getOrPut(e.md5) { mutableListOf() }.add(e)
            byNormName.getOrPut(BiosCheck.normName(f.name)) { mutableListOf() }.add(e)
        }
        return Index(emptyMap(), bySha1, byName, byMd5, byNormName)
    }

    fun buildIndex(platformsJson: String, databaseJson: String): Index {
        return parseDatabase(databaseJson).copy(declarations = parsePlatforms(platformsJson))
    }

    /** A downloadable dump: repo path + expected bytes + save name. */
    data class Resolved(
        val repoPath: String,
        val md5: String,
        val size: Long,
        /** Emulator-expected filename (declaration name wins). */
        val saveName: String,
    ) {
        val url: String get() = rawUrl(repoPath)
    }

    sealed interface ResolveResult {
        data class Ok(val resolved: Resolved) : ResolveResult
        /** No candidate at all. */
        data class Missing(val reason: String) : ResolveResult
        /** Pinned md5 matches nothing: data conflict, never download. */
        data class Conflict(val reason: String) : ResolveResult
    }

    fun Index.resolve(folder: String, want: BiosTable.BiosFile): ResolveResult {
        val systemId = SYSTEMS[folder]
        // 1. platform declaration by exact name.
        val decl = systemId?.let { id ->
            declarations[id].orEmpty().firstOrNull { it.name == want.name }
        }
        if (decl != null && decl.md5 != null && decl.sha1 != null) {
            val entry = bySha1[decl.sha1.lowercase()]
            if (entry != null && entry.md5.equals(decl.md5, ignoreCase = true)) {
                return checkPin(want, entry, decl.name)
            }
            // Declared dump not sourced in the tree (upstream gap, e.g.
            // neocdz): fall through to md5/name search by decl hash.
            byMd5[decl.md5.lowercase()]?.firstOrNull()?.let {
                return checkPin(want, it, decl.name)
            }
        }
        // 2. pinned md5 anywhere in the tree.
        if (want.md5 != null) {
            val hit = byMd5[want.md5.lowercase()]?.let { pick(it, systemId) }
            if (hit != null) return ResolveResult.Ok(toResolved(hit, want.name))
            return ResolveResult.Conflict(
                "pinned md5 ${want.md5} for ${want.name} matches no RetroBIOS dump",
            )
        }
        // 3. case-insensitive name match, then normalized
        // (punctuation-insensitive: scph39001 <-> SCPH-39001).
        val cands = (byName[want.name.lowercase()].orEmpty())
            .filter { want.sizeBytes == null || it.size == want.sizeBytes }
        pick(cands, systemId)?.let { return ResolveResult.Ok(toResolved(it, want.name)) }
        val norm = (byNormName[BiosCheck.normName(want.name)].orEmpty())
            .filter { want.sizeBytes == null || it.size == want.sizeBytes }
        pick(norm, systemId)?.let { return ResolveResult.Ok(toResolved(it, want.name)) }
        return ResolveResult.Missing("${want.name}: no dump in the RetroBIOS tree")
    }

    private fun checkPin(want: BiosTable.BiosFile, entry: DbEntry, saveName: String): ResolveResult {
        if (want.md5 != null && !entry.md5.equals(want.md5, ignoreCase = true)) {
            return ResolveResult.Conflict(
                "${want.name}: table pins ${want.md5} but RetroArch wants ${entry.md5}",
            )
        }
        if (want.sizeBytes != null && entry.size != want.sizeBytes) {
            return ResolveResult.Conflict(
                "${want.name}: table pins size ${want.sizeBytes} but dump is ${entry.size}",
            )
        }
        return ResolveResult.Ok(toResolved(entry, saveName))
    }

    private fun toResolved(e: DbEntry, saveName: String) =
        Resolved(e.path, e.md5, e.size, saveName.ifBlank { e.name })

    /**
     * Deterministic pick among same-content candidates: system-native
     * tree over Arcade romset members (arcade folders prefer Arcade),
     * canonical over .variants, USA over EUR over JAP, then shortest
     * path, then lexical.
     */
    private fun pick(cands: List<DbEntry>, systemId: String?): DbEntry? {
        if (cands.isEmpty()) return null
        val wantArcade = systemId == "arcade"
        return cands.sortedWith(
            compareBy<DbEntry> { ("/Arcade/" in it.path) != wantArcade }
                .thenBy { "/.variants/" in it.path }
                .thenBy { regionRank(it.path) }
                .thenBy { it.path.length }
                .thenBy { it.path },
        ).first()
    }

    private fun regionRank(path: String): Int = when {
        "/USA/" in path -> 0
        "/EUR/" in path -> 1
        "/JAP/" in path -> 2
        else -> 3
    }

    /** Raw file URL for a repo-relative path (segments encoded). */
    fun rawUrl(repoPath: String, base: String = RAW_BASE): String =
        base.trimEnd('/') + "/" + repoPath.split("/").joinToString("/") { seg ->
            URLEncoder.encode(seg, "UTF-8").replace("+", "%20")
        }
}
