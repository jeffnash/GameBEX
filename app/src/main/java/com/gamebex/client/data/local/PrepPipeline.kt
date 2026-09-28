package com.gamebex.client.data.local

import android.content.Context
import android.os.Environment
import android.util.Log
import com.gamebex.client.data.BiosCheck
import com.gamebex.client.data.BiosTable
import com.gamebex.client.data.Emu
import com.gamebex.client.data.PlatformRegistry
import com.gamebex.client.data.Route
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Preparation pipeline: turns a scanned ROM into a bootable launch
 * target. Disc zips extract next to the ROM (user game folders: into the app-owned
 * prep root, never the user tree), cue sheets validate, multi-
 * disc sets get an .m3u, arcade zips keep exact shortnames, and the BIOS
 * table says what's missing and where it goes.
 *
 * Result persists on the row (prepState/launchRelPath/prepNote) for the
 * orchestrator; EmuRouter boots launchRelPath when set. Blocking; call off
 * the main thread.
 */
object PrepPipeline {
    private const val TAG = "GameBEXPrep"

    const val PREP_NONE = "NONE"
    const val PREP_READY = "READY"
    const val PREP_NEEDS_BIOS = "NEEDS_BIOS"
    const val PREP_NEEDS_MANUAL = "NEEDS_MANUAL"
    const val PREP_FAILED = "FAILED"

    /** Extraction sanity caps (zip-bomb guard). */
    private const val MAX_ENTRIES = 10_000
    private const val MAX_TOTAL_BYTES = 20L * 1024 * 1024 * 1024
    /**
     * Marker associating a published dir with its prep run: line 1 is the
     * source fingerprint, the rest is the manifest of prep-owned relative
     * paths. Rebuild deletes manifest entries only — the dir may also hold
     * foreign files, which must survive. A fingerprint-only marker carries
     * no manifest and therefore never deletes (merge-only, safe direction).
     */
    const val STAGE_MARKER = ".gamebex_prep"

    data class PrepReport(
        val gameId: Long,
        val state: String,
        val launchRelPath: String?,
        val note: String,
        val bios: BiosCheck.FolderReport?,
    )

    /**
     * Candidate RetroArch system dirs for the CONFIGURED runtime package.
     * The per-app path is unreadable on modern Android (scoped storage)
     * but harmless to probe. (Legacy 32-bit com.retroarch is deliberately
     * not probed: our runtime can't read its system dir either.)
     */
    fun defaultSystemDirs(ctx: Context): List<File> {
        @Suppress("DEPRECATION")
        val sharedRoot = Environment.getExternalStorageDirectory()
        return emuSystemDirs(true, null, "", sharedRoot)
    }

    /** GameBEX-private BIOS dir: noted, but never satisfies readiness (emulators can't read it). */
    fun biosPrivateDirs(ctx: Context): List<File> = listOf(File(ctx.filesDir, "bios"))

    /**
     * Emulator-readable system dirs for the game's effective route:
     * RetroArch's dirs for core routes (+ system/dc for the flycast core),
     * shared staging for standalone routes with BIOS needs (their
     * configured folders aren't visible, but staging is — the note tells
     * the user where to point the app), nothing for the rest.
     * A per-game emulator override reroutes this exactly like a launch.
     */
    fun biosEmuDirs(ctx: Context, folder: String, emuOverride: String?): List<File> {
        val overrideEmu = emuOverride?.let { pkg -> Emu.entries.firstOrNull { it.pkg == pkg } }
        val readsRaSystem = if (overrideEmu != null) {
            overrideEmu == Emu.RETROARCH
        } else {
            PlatformRegistry.byFolder[folder]?.route is Route.RetroCore
        }
        val core = (PlatformRegistry.byFolder[folder]?.route as? Route.RetroCore)?.core
        @Suppress("DEPRECATION")
        val sharedRoot = Environment.getExternalStorageDirectory()
        return emuSystemDirs(readsRaSystem, core, folder, sharedRoot)
    }

    /**
     * Pure routing behind [biosEmuDirs] (unit-testable): RA system dirs
     * for core routes, shared staging for standalone routes with needs.
     */
    fun emuSystemDirs(
        readsRaSystem: Boolean,
        core: String?,
        folder: String,
        sharedRoot: File,
    ): List<File> {
        if (!readsRaSystem) {
            if (BiosTable.TABLE[folder].isNullOrEmpty()) return emptyList()
            return listOf(File(sharedRoot, BiosTable.stagingRelPath(folder)))
        }
        val sys = listOf(
            File(sharedRoot, "RetroArch/system"),
            File(sharedRoot, "Android/data/${Emu.RETROARCH.pkg}/files/system"),
        )
        return if (core == "flycast") sys + sys.map { File(it, "dc") } else sys
    }

    /**
     * Per-game serialization: Detail's Finish Setup, the launch-boundary
     * BIOS retry, background auto-prep after a download, and Delete all
     * touch the same stage/output dirs — one at a time per game. Not
     * reentrant: never call [prepare] from inside [withGameLock].
     */
    private val gameLocks = ConcurrentHashMap<Long, Mutex>()

    suspend fun <T> withGameLock(gameId: Long, block: suspend () -> T): T =
        gameLocks.getOrPut(gameId) { Mutex() }.withLock { block() }

    suspend fun prepare(ctx: Context, db: LibraryDb, gameId: Long): PrepReport =
        withGameLock(gameId) { prepareLocked(ctx, db, gameId) }

    private suspend fun prepareLocked(ctx: Context, db: LibraryDb, gameId: Long): PrepReport {
        val game = db.games().findById(gameId)
            ?: return PrepReport(gameId, PREP_FAILED, null, "Game not in library.", null)
        val rom = RomStore.resolveFile(ctx, game.volumeId, game.relPath)
        if (rom == null || !rom.isFile) {
            return persist(db, game, PrepReport(gameId, PREP_FAILED, null, "ROM file missing.", null))
        }
        val report = when {
            game.folder in BiosTable.ARCADE_FOLDERS -> prepareArcade(ctx, game, rom)
            game.folder in BiosTable.DISC_FOLDERS -> prepareDisc(ctx, game, rom)
            else -> prepareCart(ctx, gameId, game, rom)
        }
        val emuDirs = biosEmuDirs(ctx, game.folder, game.emulatorOverride) + listOf(rom.parentFile ?: rom)
        val bios = BiosCheck.checkFolder(game.folder, emuDirs, biosPrivateDirs(ctx))
        val final = if (!bios.ready && report.state == PREP_READY) {
            val names = bios.missingRequired.joinToString(", ") { it.name }
            val missingNames = bios.missingRequired.map { it.name }.toSet()
            val hint = bios.needs.firstOrNull { nr ->
                nr.files.any { it.want.name in missingNames }
            }?.need?.dirHint
            var note = "${report.note} Missing BIOS: $names — see placement below."
            if (bios.privateOnly.isNotEmpty()) {
                val priv = bios.privateOnly.joinToString(", ") { it.name }
                note += " Found in GameBEX storage (emulators can't read it): $priv — move to ${hint ?: "the emulator's system folder"}."
            }
            report.copy(
                state = PREP_NEEDS_BIOS,
                note = note,
                bios = bios,
            )
        } else report.copy(bios = bios)
        return persist(db, game, final)
    }

    private suspend fun persist(db: LibraryDb, game: GameEntity, report: PrepReport): PrepReport {
        // Never report success the DB doesn't hold: a failed write becomes
        // FAILED so the UI retries instead of trusting a stale row.
        val write = runCatching {
            db.games().setPrep(game.id, report.state, report.launchRelPath, report.note)
        }
        if (write.isFailure) {
            Log.w(TAG, "game ${game.id}: setPrep failed", write.exceptionOrNull())
            return report.copy(
                state = PREP_FAILED,
                launchRelPath = null,
                note = "Couldn't save prep state — tap to retry.",
            )
        }
        Log.d(TAG, "game ${game.id}: ${report.state} ${report.launchRelPath ?: ""} — ${report.note}")
        return report
    }

    /** Cartridge ROMs boot as-is — except known-unbootable pairs and per-file firmware gates. */
    private fun prepareCart(ctx: Context, gameId: Long, game: GameEntity, rom: File): PrepReport {
        val ext = rom.extension.lowercase()
        PrepLogic.unbootableNoteFor(game.folder, ext)?.let {
            return PrepReport(gameId, PREP_NEEDS_MANUAL, null, it, null)
        }
        missingFirmwareNote(ctx, game.folder, ext)?.let {
            return PrepReport(gameId, PREP_NEEDS_BIOS, null, it, null)
        }
        return PrepReport(gameId, PREP_READY, null, "Cartridge ROM — nothing to prepare.", null)
    }

    /**
     * Per-file firmware the folder-level BIOS table can't express (only
     * FDS/64DD files need it, not every NES/N64 ROM). Fail-open when the
     * system dirs are unreadable.
     */
    private fun missingFirmwareNote(ctx: Context, folder: String, ext: String): String? {
        val wants: List<String> = when {
            folder == "nes" && ext == "fds" -> listOf("disksys.rom")
            folder == "n64" && ext == "ndd" -> listOf(
                "Mupen64plus/IPL.n64", "Mupen64plus/64DD_IPL.bin",
                "IPL.n64", "64DD_IPL.bin",
            )
            else -> return null
        }
        val dirs = defaultSystemDirs(ctx).filter { it.isDirectory }
        if (dirs.isEmpty()) return null
        val found = dirs.any { d -> wants.any { File(d, it).isFile } }
        if (found) return null
        return if (folder == "nes") {
            "FDS games need disksys.rom in RetroArch/system — see placement below."
        } else {
            "64DD needs IPL.n64 (or 64DD_IPL.bin) in RetroArch/system/Mupen64plus."
        }
    }

    private fun prepareArcade(ctx: Context, game: GameEntity, rom: File): PrepReport {
        if (rom.extension.lowercase() != "zip" || !rom.isFile) {
            return PrepReport(
                game.id, PREP_NEEDS_MANUAL, null,
                "Arcade sets must be .zip (${rom.name}); this core can't use ${rom.extension.uppercase()}.",
                null,
            )
        }
        if (!PrepLogic.isArcadeShortname(rom.name)) {
            return PrepReport(
                game.id, PREP_NEEDS_MANUAL, null,
                "Arcade zips need their exact shortname (e.g. galaga.zip); “${rom.name}” won't match.",
                null,
            )
        }
        if (ArchiveHub.kindFor(rom) != ArchiveHub.Kind.ZIP) {
            return PrepReport(
                game.id, PREP_NEEDS_MANUAL, null,
                "“${rom.name}” isn't actually a zip — cores need a real shortname set; re-download it.",
                null,
            )
        }
        val bios = BiosCheck.checkFolder(game.folder, listOf(rom.parentFile ?: rom))
        return if (!bios.ready) {
            val names = bios.missingRequired.joinToString(", ") { it.name }
            PrepReport(game.id, PREP_NEEDS_BIOS, null, "Shortname OK. Missing BIOS in this folder: $names.", bios)
        } else {
            PrepReport(game.id, PREP_READY, null, "Shortname OK, stays zipped.", bios)
        }
    }

    private fun prepareDisc(ctx: Context, game: GameEntity, rom: File): PrepReport {
        // Format comes from magic bytes, not the extension: sites ship 7z
        // (incl. torrent7z) misnamed .zip, and vice versa is harmless too.
        // RAR routes here as well: detected, honestly unsupported.
        if (rom.extension.lowercase() in ArchiveHub.ROUTE_EXTS) return prepareDiscArchive(ctx, game, rom)
        val ext = rom.extension.lowercase()
        PrepLogic.unbootableNoteFor(game.folder, ext)?.let {
            return PrepReport(game.id, PREP_NEEDS_MANUAL, null, it, null)
        }
        if ((game.folder == "gc" || game.folder == "wii") && PrepLogic.isNkitName(rom.name)) {
            return PrepReport(
                game.id, PREP_NEEDS_MANUAL, null,
                "NKit images don't boot in Dolphin — restore to ISO on a PC (NKit), then re-scan.",
                null,
            )
        }
        if (ext == "cue" || ext == "gdi" || ext == "m3u" || ext == "ccd") {
            if (ext == "ccd" && game.folder !in PrepLogic.CCD_BOOT_FOLDERS) {
                return PrepReport(
                    game.id, PREP_NEEDS_MANUAL, null,
                    "This route can't boot CCD — convert to CUE/CHD.",
                    null,
                )
            }
            val refs = parseSheetRefs(rom)
            if (refs == null || refs.isEmpty()) {
                return PrepReport(
                    game.id, PREP_NEEDS_MANUAL, null,
                    "${rom.name} has no readable track/file references — needs a valid sheet.",
                    null,
                )
            }
            val missing = missingDeepRefs(rom)
            if (missing.isEmpty() && ext == "cue" && game.folder == "dreamcast" &&
                dreamcastCueNeedsGdi(rom)
            ) {
                // Same CD-R-numbering hang as the zip flow, but a loose
                // tree is the user's files — prep won't write a sibling
                // sheet there (it would scan back in as a duplicate row).
                return PrepReport(
                    game.id, PREP_NEEDS_MANUAL, null,
                    "This cue uses CD-R sector numbering and boots black in flycast — " +
                        "zip the set and Finish Setup so prep can generate a bootable .gdi.",
                    null,
                )
            }
            if (missing.isEmpty() && (ext == "cue" || ext == "m3u")) {
                val badAudio = cueAudioProblems(rom)
                if (badAudio.isNotEmpty()) {
                    return PrepReport(
                        game.id, PREP_NEEDS_MANUAL, null,
                        "Cue audio isn't raw PCM (${badAudio.size} track(s)) — " +
                            "convert tracks to WAV/BIN on a PC.",
                        null,
                    )
                }
            }
            return if (missing.isEmpty()) {
                PrepReport(game.id, PREP_READY, game.relPath, "${ext.uppercase()} sheet valid — boots as-is.", null)
            } else {
                PrepReport(
                    game.id, PREP_NEEDS_MANUAL, null,
                    "${rom.name} references missing files: ${missing.joinToString(", ")}.",
                    null,
                )
            }
        }
        if (ext in PrepLogic.directBootFor(game.folder)) {
            if (ext == "chd") {
                val v = chdVersionAt(rom)
                if (v != null && v < 5) {
                    return PrepReport(
                        game.id, PREP_NEEDS_MANUAL, null,
                        "CHD v$v too old — upgrade to v5+ with chdman.",
                        null,
                    )
                }
            }
            return PrepReport(game.id, PREP_READY, game.relPath, "${ext.uppercase()} boots as-is.", null)
        }
        if (ext in PrepLogic.cartReadyFor(game.folder)) {
            return PrepReport(game.id, PREP_READY, game.relPath, "${ext.uppercase()} boots as-is on this route.", null)
        }
        return PrepReport(
            game.id, PREP_NEEDS_MANUAL, null,
            PrepLogic.manualNoteFor(ext),
            null,
        )
    }

    private fun prepareDiscArchive(ctx: Context, game: GameEntity, rom: File): PrepReport {
        if (ArchiveHub.isSplitVolume(rom.name)) {
            return PrepReport(
                game.id, PREP_NEEDS_MANUAL, null,
                "Multi-volume archive (.001/.z01/.r00) — join it on a PC, then re-scan.",
                null,
            )
        }
        val kind = ArchiveHub.kindFor(rom)
        if (kind == ArchiveHub.Kind.UNKNOWN) {
            return PrepReport(
                game.id, PREP_FAILED, null,
                "Not a supported archive (zip/7z/rar/tar/gz/bz2/xz) — the file may be corrupt; try Replace.",
                null,
            )
        }
        val rawEntries: List<String> = try {
            ArchiveHub.entries(rom, kind)
        } catch (e: CancellationException) {
            throw e
        } catch (e: UnsupportedArchiveException) {
            return PrepReport(game.id, PREP_NEEDS_MANUAL, null, e.message ?: ArchiveHub.RAR_MESSAGE, null)
        } catch (e: Exception) {
            return PrepReport(game.id, PREP_FAILED, null, "Unreadable archive: ${e.message}", null)
        }
        // Same normalization the extractor uses, so the plan names the
        // files that actually land on disk (subdirs preserved).
        val entries = rawEntries.mapNotNull { ArchiveHub.normalizeEntry(it) }
        val plan = PrepLogic.planZip(entries, PrepLogic.directBootFor(game.folder))
        // Same-disc duplicates (cue+ccd, cue+gdi) collapse to one boot
        // sheet; single-ROM archives (Jaguar carts, PSX homebrew) boot
        // the lone cart file.
        val sheets = PrepLogic.preferSheets(plan.sheets, game.folder)
        val carts = PrepLogic.zipCartCandidates(entries, game.folder)
        if (!plan.isDiscSet && carts.isEmpty()) {
            return PrepReport(
                game.id, PREP_NEEDS_MANUAL, null,
                "Archive doesn't look like a disc set (no cue/gdi/m3u/ccd or bootable image inside).",
                null,
            )
        }
        // User game folders are read-only: their outputs go to the
        // app-owned prep root instead of next to the ROM.
        val base = RomStore.prepBaseDir(ctx, game, rom)
            ?: return PrepReport(game.id, PREP_FAILED, null, "ROM folder unknown.", null)
        if (RomStore.isUserVolume(game.volumeId) && !base.isDirectory && !base.mkdirs()) {
            return PrepReport(game.id, PREP_FAILED, null, "Can't create GameBEX prep folder.", null)
        }
        val outDir = File(base, rom.nameWithoutExtension)
        val stage = File(base, rom.nameWithoutExtension + ".staging")
        // Idempotent Run: a matching marker proves this exact archive
        // already prepped — validate the output, skip the re-extract.
        val finger = PrepLogic.prepFinger(rom.length(), rom.lastModified())
        val marker = runCatching { File(outDir, STAGE_MARKER).readText() }.getOrNull()
        val fresh = PrepLogic.prepMarkerFresh(marker, finger) && outDir.isDirectory
        val workDir: File
        if (fresh) {
            workDir = outDir
        } else {
            runCatching { stage.deleteRecursively() }
            val extracted = try {
                ArchiveHub.extract(rom, stage, kind, MAX_ENTRIES, MAX_TOTAL_BYTES)
            } catch (e: CancellationException) {
                runCatching { stage.deleteRecursively() }
                throw e
            } catch (e: UnsupportedArchiveException) {
                runCatching { stage.deleteRecursively() }
                return PrepReport(game.id, PREP_NEEDS_MANUAL, null, e.message ?: ArchiveHub.RAR_MESSAGE, null)
            } catch (e: Exception) {
                runCatching { stage.deleteRecursively() }
                return PrepReport(game.id, PREP_FAILED, null, "Extract failed: ${e.message}", null)
            }
            if (!extracted) {
                runCatching { stage.deleteRecursively() }
                return PrepReport(
                    game.id, PREP_NEEDS_MANUAL, null,
                    "Archive hit the extraction safety caps — extract on a PC, then re-scan.",
                    null,
                )
            }
            workDir = stage
        }
        // Boot target: existing sheet wins; else a lone loose image boots
        // directly; else a lone cart file (single-ROM archive); else
        // generate an .m3u over sheets + loose images.
        // Names are stage-relative paths (subdirs preserved).
        var bootRel: String = PrepLogic.pickBootTarget(sheets, plan.loose, carts)
            ?: run {
                if (sheets.isEmpty() && plan.loose.isEmpty() && carts.size > 1) {
                    if (!fresh) runCatching { stage.deleteRecursively() }
                    return PrepReport(
                        game.id, PREP_NEEDS_MANUAL, null,
                        "Archive holds ${carts.size} ROM files — keep one game per archive.",
                        null,
                    )
                }
                // Skip loose images a sheet already references (no doubles).
                val referenced = sheets.flatMap { sheetRel ->
                    parseSheetRefs(File(workDir, sheetRel)).orEmpty()
                }.map { it.substringAfterLast('/').substringAfterLast('\\').lowercase() }.toSet()
                val ordered = (sheets + plan.loose.filter {
                    it.substringAfterLast('/').substringAfterLast('\\').lowercase() !in referenced
                }).sortedBy { PrepLogic.discIndex(it.substringAfterLast('/')) }
                val m3u = File(workDir, "${rom.nameWithoutExtension}.m3u")
                runCatching { m3u.writeText(PrepLogic.genM3u(ordered)) }.onFailure {
                    if (!fresh) runCatching { stage.deleteRecursively() }
                    return PrepReport(game.id, PREP_FAILED, null, "Can't write .m3u: ${it.message}", null)
                }
                m3u.name
            }
        // Dreamcast CD-R-numbered cue: flycast maps cue HD data at the
        // 45150 GD-ROM base, so a 45000-based rip reads 150 sectors early
        // and hangs black after the logo. Boot a generated .gdi declaring
        // the last track at 45000 instead; a failed generation keeps the
        // cue (fail-open).
        var generatedGdi: String? = null
        if (game.folder == "dreamcast" && bootRel.lowercase().endsWith(".cue")) {
            val cueFile = File(workDir, bootRel)
            if (dreamcastCueNeedsGdi(cueFile)) {
                val gdi = write45000Gdi(cueFile)
                val gdiRel = gdi?.let {
                    runCatching { it.relativeTo(workDir).path.replace('\\', '/') }.getOrNull()
                }
                if (gdiRel != null) {
                    generatedGdi = gdiRel
                    bootRel = gdiRel
                }
            }
        }
        // Boot-target gates: known-unbootable formats and NKit images
        // fail honestly here instead of black-screening the core.
        val bootExt = bootRel.substringAfterLast('.', "").lowercase()
        PrepLogic.unbootableNoteFor(game.folder, bootExt)?.let {
            if (!fresh) runCatching { stage.deleteRecursively() }
            return PrepReport(game.id, PREP_NEEDS_MANUAL, null, it, null)
        }
        if ((game.folder == "gc" || game.folder == "wii") &&
            PrepLogic.isNkitName(bootRel.substringAfterLast('/'))
        ) {
            if (!fresh) runCatching { stage.deleteRecursively() }
            return PrepReport(
                game.id, PREP_NEEDS_MANUAL, null,
                "NKit images don't boot in Dolphin — restore to ISO on a PC (NKit), then re-scan.",
                null,
            )
        }
        // Sheet boots validate (structure + references, recursively);
        // loose-image boots need no validation.
        val stageBoot = File(workDir, bootRel)
        if (fresh && !stageBoot.isFile) {
            // Marker says fresh but the boot file is gone (output edited
            // by hand): drop the marker and prep from scratch, once.
            runCatching { File(outDir, STAGE_MARKER).delete() }
            return prepareDiscArchive(ctx, game, rom)
        }
        if (stageBoot.extension.lowercase() in setOf("cue", "gdi", "m3u", "ccd")) {
            val refs = parseSheetRefs(stageBoot)
            if (refs.isNullOrEmpty()) {
                if (!fresh) runCatching { stage.deleteRecursively() }
                return PrepReport(
                    game.id, PREP_NEEDS_MANUAL, null,
                    "Extracted, but $bootRel has no readable track/file references.",
                    null,
                )
            }
            val missing = missingDeepRefs(stageBoot)
            if (missing.isNotEmpty()) {
                if (!fresh) runCatching { stage.deleteRecursively() }
                return PrepReport(
                    game.id, PREP_NEEDS_MANUAL, null,
                    "Extracted, but $bootRel references missing files: ${missing.joinToString(", ")}.",
                    null,
                )
            }
            val sheetExt = stageBoot.extension.lowercase()
            if (missing.isEmpty() && (sheetExt == "cue" || sheetExt == "m3u")) {
                val badAudio = cueAudioProblems(stageBoot)
                if (badAudio.isNotEmpty()) {
                    if (!fresh) runCatching { stage.deleteRecursively() }
                    return PrepReport(
                        game.id, PREP_NEEDS_MANUAL, null,
                        "Cue audio isn't raw PCM (${badAudio.size} track(s)) — " +
                            "convert tracks to WAV/BIN on a PC.",
                        null,
                    )
                }
            }
        }
        if (bootRel.lowercase().endsWith(".chd")) {
            val v = chdVersionAt(stageBoot)
            if (v != null && v < 5) {
                if (!fresh) runCatching { stage.deleteRecursively() }
                return PrepReport(
                    game.id, PREP_NEEDS_MANUAL, null,
                    "CHD v$v too old — upgrade to v5+ with chdman.",
                    null,
                )
            }
        }
        // Publish: rebuild deletes only files the previous prep owned
        // (its manifest) — the dir may also hold foreign files, which must
        // survive. Fingerprint-only markers merge without deletion.
        // Staging never survives a failure. Fresh outputs skip publish
        // entirely (validated above, already live).
        if (!fresh) {
            val owned = parseMarkerManifest(marker)
            if (owned.isNotEmpty()) clearOwned(outDir, owned)
            val manifest = collectManifest(stage)
            val published = if (!outDir.exists()) {
                stage.renameTo(outDir)
            } else {
                var ok = true
                stage.listFiles()?.forEach { child ->
                    val dest = File(outDir, child.name)
                    ok = if (child.isDirectory) {
                        runCatching { child.copyRecursively(dest, overwrite = true) }.isSuccess && ok
                    } else {
                        runCatching { child.copyTo(dest, overwrite = true) }.isSuccess && ok
                    }
                } ?: run { ok = false }
                if (ok) runCatching { stage.deleteRecursively() }
                ok
            }
            if (!published || !outDir.isDirectory) {
                runCatching { stage.deleteRecursively() }
                return PrepReport(game.id, PREP_FAILED, null, "Couldn't publish extracted files.", null)
            }
            // Marker binds this output to the source fingerprint + manifest.
            writeMarker(outDir, finger, manifest)
        }
        val generated = generatedGdi
        if (fresh && generated != null) {
            // Fresh outputs skip publish: fold the generated sheet into
            // the live marker so rebuilds own (and clean) it.
            writeMarker(outDir, finger, (parseMarkerManifest(marker) + generated).distinct())
        }
        val bootFile = File(outDir, bootRel)
        val rel = RomStore.launchRelFor(ctx, game.volumeId, bootFile)
            ?: return PrepReport(game.id, PREP_FAILED, null, "Extracted outside the library volume.", null)
        var note = if (bootRel.lowercase().endsWith(".m3u")) {
            "Extracted ${sheets.size + plan.loose.size} disc(s); generated $bootRel."
        } else if (bootRel in carts) {
            "Extracted; boots $bootRel (single-ROM archive)."
        } else {
            "Extracted; boots $bootRel. (${sheets.size} sheet(s), ${plan.loose.size} loose)"
        }
        if (generated != null) note += " Cue uses CD-R sector numbering; boots generated GDI."
        return PrepReport(game.id, PREP_READY, rel, note, null)
    }

    /** Relative `/`-separated paths of every file under [dir] (pure file walk). */
    fun collectManifest(dir: File): List<String> = dir.walkTopDown()
        .filter { it.isFile }
        .map { it.relativeTo(dir).path.replace('\\', '/') }
        .toList().sorted()

    /** Marker lines after the fingerprint, sanitized (hand-edited markers can't escape). */
    fun parseMarkerManifest(marker: String?): List<String> {
        if (marker == null) return emptyList()
        return marker.lineSequence().drop(1)
            .map { it.trim() }
            .filter { it.isNotEmpty() && isSafeRef(it) }
            .toList()
    }

    /** Writes the fingerprint + manifest marker; best-effort. */
    fun writeMarker(outDir: File, finger: String, manifest: List<String>) {
        runCatching {
            File(outDir, STAGE_MARKER).writeText((listOf(finger) + manifest).joinToString("\n"))
        }
    }

    /**
     * Deletes exactly the manifest-owned files, then prunes parent dirs left
     * empty (never [outDir] itself). Foreign files and non-empty dirs always
     * survive — this is the only deletion a rebuild may perform.
     */
    fun clearOwned(outDir: File, manifest: List<String>) {
        manifest.forEach { rel ->
            if (!isSafeRef(rel)) return@forEach
            val f = File(outDir, rel)
            if (f.isFile) runCatching { f.delete() }
        }
        val parents = manifest.mapNotNull { File(outDir, it).parentFile }.toSet()
        parents.sortedByDescending { it.absolutePath.length }.forEach { start ->
            var d: File? = start
            while (d != null && d != outDir && d.isDirectory && d.list()?.isEmpty() == true) {
                runCatching { d.delete() }
                d = d.parentFile
            }
        }
    }

    /** Parsed references of a sheet, or null when unreadable/unsupported. */
    fun parseSheetRefs(sheet: File): List<String>? {
        val ext = sheet.extension.lowercase()
        return try {
            when (ext) {
                "cue" -> PrepLogic.parseCue(sheet.readText()).map { it.file }
                "gdi" -> parseGdi(sheet.readText())
                "ccd" -> ccdRefs(sheet)
                "m3u" -> sheet.readLines().map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * CloneCD image files: same-basename .img (or .bin) plus the optional
     * .sub. Names match case-insensitively but resolve exactly, so odd
     * cases still validate on case-sensitive filesystems.
     */
    fun ccdRefs(sheet: File): List<String>? {
        val dir = sheet.parentFile ?: return null
        val names = try {
            dir.list()?.toList().orEmpty()
        } catch (_: Exception) {
            return null
        }
        val base = sheet.nameWithoutExtension
        val img = names.firstOrNull { it.equals("$base.img", ignoreCase = true) }
            ?: names.firstOrNull { it.equals("$base.bin", ignoreCase = true) }
            ?: return listOf("$base.img")
        val out = mutableListOf(img)
        names.firstOrNull { it.equals("$base.sub", ignoreCase = true) }?.let { out += it }
        return out
    }

    /**
     * Cue tracks using an undecodable audio type (MP3/OGG/FLAC...), with
     * the same cycle-safe M3U recursion as [missingDeepRefs]. Fail-open:
     * unreadable sheets and over-deep chains report nothing.
     */
    fun cueAudioProblems(
        sheet: File,
        depth: Int = 0,
        seen: MutableSet<String> = mutableSetOf(),
    ): List<String> {
        if (depth > 3) return emptyList()
        val key = runCatching { sheet.canonicalPath }.getOrNull() ?: sheet.absolutePath
        if (!seen.add(key)) return emptyList()
        return try {
            when (sheet.extension.lowercase()) {
                "cue" -> runCatching {
                    PrepLogic.unsupportedCueAudio(sheet.readText())
                }.getOrDefault(emptyList())
                "m3u" -> parseSheetRefs(sheet).orEmpty().flatMap { ref ->
                    if (!isSafeRef(ref)) return@flatMap emptyList()
                    val child = File(sheet.parentFile, ref)
                    if (child.isFile && child.extension.lowercase() in setOf("cue", "m3u")) {
                        cueAudioProblems(child, depth + 1, seen)
                    } else emptyList()
                }.distinct()
                else -> emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** CHD container version, or null when unreadable/not a CHD. Fail-open. */
    fun chdVersionAt(image: File): Int? {
        return try {
            if (!image.isFile || image.length() < 16) return null
            java.io.RandomAccessFile(image, "r").use { raf ->
                val h = ByteArray(16)
                raf.readFully(h)
                PrepLogic.chdVersion(h)
            }
        } catch (_: Exception) {
            null
        }
    }

    /** A ref is safe when it stays inside the sheet's directory. */
    fun isSafeRef(ref: String): Boolean {
        if (ref.isBlank()) return false
        val parts = ref.replace('\\', '/').split('/')
        return parts.none { it.isEmpty() || it == "." || it == ".." } &&
            !ref.startsWith("/") && !ref.contains(":")
    }

    /** Files a cue/gdi/m3u sheet points at that don't exist next to it. */
    fun missingRefs(sheet: File): List<String> {
        val names = parseSheetRefs(sheet) ?: return listOf(sheet.name)
        return names.filter { !isSafeRef(it) || !File(sheet.parentFile, it).isFile }
    }

    /**
     * missingRefs plus recursive validation of sheets an M3U names
     * (cycle-safe, depth-capped): an M3U over a broken cue must fail.
     */
    fun missingDeepRefs(sheet: File, depth: Int = 0, seen: MutableSet<String> = mutableSetOf()): List<String> {
        // Never fail open: a chain past the cap is unverifiable, not valid.
        if (depth > 3) return listOf("${sheet.name}: nested playlists too deep to validate")
        val key = runCatching { sheet.canonicalPath }.getOrNull() ?: sheet.absolutePath
        if (!seen.add(key)) return emptyList()
        val missing = missingRefs(sheet).toMutableList()
        if (sheet.extension.lowercase() == "m3u") {
            parseSheetRefs(sheet).orEmpty().forEach { ref ->
                if (!isSafeRef(ref)) return@forEach
                val child = File(sheet.parentFile, ref)
                if (child.isFile && child.extension.lowercase() in setOf("cue", "gdi", "m3u")) {
                    missing += missingDeepRefs(child, depth + 1, seen)
                }
            }
        }
        return missing.distinct()
    }

    /**
     * True when [cueFile] is a CD-R-numbered Dreamcast rip: its last
     * (high-density) track's ISO root extent sits below the 45150 GD-ROM
     * base, so flycast reads every file 150 sectors early and the boot
     * hangs black after the logo. Bounded reads only; any doubt or IO
     * failure returns false (fail-open: boot the cue).
     */
    fun dreamcastCueNeedsGdi(cueFile: File): Boolean {
        try {
            val tracks = PrepLogic.parseCueTracks(cueFile.readText())
            if (tracks.size < 2) return false
            val last = tracks.last()
            if (!last.mode.startsWith("MODE1/")) return false
            if (!isSafeRef(last.file)) return false
            val sector = PrepLogic.sectorSizeForMode(last.mode)
            if (sector != 2048 && sector != 2352) return false
            val bin = File(cueFile.parentFile, last.file)
            if (!bin.isFile) return false
            // PVD lives in track sector 16; raw sectors prefix 16 header bytes.
            val off = 16L * sector + if (sector == 2352) 16 else 0
            if (bin.length() < off + 2048) return false
            java.io.RandomAccessFile(bin, "r").use { raf ->
                raf.seek(off)
                val pvd = ByteArray(2048)
                raf.readFully(pvd)
                val extent = PrepLogic.rootExtentFromPvd(pvd) ?: return false
                return PrepLogic.needs45000Gdi(extent)
            }
        } catch (_: Exception) {
            return false
        }
    }

    /**
     * Writes a sibling .gdi declaring the last track at the 45000 CD-R
     * base so ISO extents resolve (proven on-device: Sonic Adventure
     * USA boots past the logo via the generated sheet). Returns the
     * file, or null when the layout isn't rewriteable (fail-open: the
     * caller keeps booting the cue). Idempotent: same cue, same bytes.
     */
    fun write45000Gdi(cueFile: File): File? {
        try {
            val tracks = PrepLogic.parseCueTracks(cueFile.readText())
            if (tracks.size < 2 || tracks.size > 99) return null
            if (!tracks.last().mode.startsWith("MODE1/")) return null
            val gdi = mutableListOf<PrepLogic.GdiTrack>()
            var lba = 0L
            var ldSectors = 0L
            tracks.forEachIndexed { i, t ->
                if (!isSafeRef(t.file)) return null
                val f = File(cueFile.parentFile, t.file)
                if (!f.isFile) return null
                val sector = PrepLogic.sectorSizeForMode(t.mode)
                if (f.length() % sector != 0L) return null
                val sectors = f.length() / sector
                val start = if (i == tracks.lastIndex) PrepLogic.CDR_HD_LBA.toLong() else lba
                gdi += PrepLogic.GdiTrack(t.file, start, PrepLogic.gdiTypeForMode(t.mode), sector)
                if (i != tracks.lastIndex) ldSectors += sectors
                lba += sectors
            }
            // The forced 45000 base must sit past the low-density area;
            // otherwise this isn't the CD-R layout we know how to fix.
            if (ldSectors >= PrepLogic.CDR_HD_LBA) return null
            val out = File(cueFile.parentFile, cueFile.nameWithoutExtension + ".gdi")
            out.writeText(PrepLogic.genGdi(gdi))
            return out.takeIf { it.isFile }
        } catch (_: Exception) {
            return null
        }
    }

    private val GDI_LINE = Regex("""^\s*\d+\s+\d+\s+\d+\s+\d+\s+(?:"([^"]+)"|(\S+))""")

    /** GDI track lines; quoted filenames with spaces parse whole. */
    private fun parseGdi(text: String): List<String> = text.lines().mapNotNull { raw ->
        val m = GDI_LINE.find(raw) ?: return@mapNotNull null
        m.groupValues[1].ifEmpty { m.groupValues[2] }.takeIf { it.isNotBlank() }
    }
}
