package com.gamebex.client.debug

import com.gamebex.client.data.PlayStates
import com.gamebex.client.data.local.DownloadRecord
import com.gamebex.client.data.local.GameEntity
import com.gamebex.client.net.DownloadEngine
import com.gamebex.client.ui.home.HomeCardFactory
import com.gamebex.client.ui.home.HomeGrouping
import com.gamebex.client.ui.home.SiteHit
import com.gamebex.client.ui.home.SiteQuery
import com.gamebex.client.ui.home.model.HeaderModel
import com.gamebex.client.ui.home.model.HomeCopy
import com.gamebex.client.ui.home.model.HomeScreenModel
import com.gamebex.client.ui.home.model.SaveInfo
import com.gamebex.client.ui.home.model.SizeInfo
import com.gamebex.source.RomEntry
import com.gamebex.source.SourceRef
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Debug-only Home sample models. Every model is built through the REAL
 * pipeline — [HomeGrouping.build] over fake in-memory rows (GameEntity,
 * SaveInfo, DownloadEngine.DlSnapshot, DownloadRecord, SiteQuery and a
 * free-bytes lambda) with a real [HomeCardFactory] — so the fixtures render
 * exactly what the app renders for the same data and can never drift from
 * it. Only the header (assembled outside the grouping in the real store
 * too) is built here.
 *
 * Art is whatever the factory resolves for rows without art files: a NONE
 * [com.gamebex.client.ui.home.model.ArtRef] carrying the game's folder +
 * title, so the generated-case bed shows. Every string goes through
 * [HomeCopy].
 */
object HomeFixtures {

    /** ROMS-I-Own.com, the mockup's site. */
    const val SITE = "ROMS-I-Own.com"

    const val FREE_BYTES = 30_494_258_216L // 28.4 GB

    private const val MIN = 60_000L
    private const val HOUR_MS = 60 * MIN
    private const val DAY_MS = 24 * HOUR_MS

    /** The query both search fixtures carry (androidTests depend on it). */
    private const val QUERY = "sonic"

    val states = listOf(
        "normal", "noplugin", "offline", "firstrun", "few",
        "search", "searchnoplugin", "surprise", "downloading",
    )

    /** Fixture for [state]; unknown names fall back to `normal`. */
    fun model(state: String, now: Long = System.currentTimeMillis()): HomeScreenModel =
        when (state) {
            "noplugin" -> noplugin(now)
            "offline" -> offline(now)
            "firstrun" -> firstrun(now)
            "few" -> few(now)
            "search" -> search(now)
            "searchnoplugin" -> searchnoplugin(now)
            "surprise" -> surprise(now)
            "downloading" -> downloading(now)
            else -> normal(now)
        }

    private fun normal(now: Long) = assemble(
        now = now,
        games = homeGames(now),
        saves = homeSaves(now),
        jobs = mapOf(DL_KH to kingdomHearts()),
        records = listOf(kingdomHeartsRecord()),
        etaSec = { s -> if (s.id == DL_KH) ETA_KH else null },
    )

    private fun noplugin(now: Long) = assemble(
        now = now,
        games = nopluginGames(now),
        saves = homeSaves(now) + mapOf(
            MARIO64 to SaveInfo(now - 2 * DAY_MS - 3 * HOUR_MS, 2, null),
        ),
        siteName = null,
    )

    private fun offline(now: Long) = assemble(
        now = now,
        games = homeGames(now),
        saves = homeSaves(now),
        jobs = mapOf(DL_KH to kingdomHearts().copy(status = DownloadEngine.ST_PAUSED)),
        records = listOf(kingdomHeartsRecord()),
        online = false,
    )

    private fun firstrun(now: Long) = assemble(
        now = now,
        games = emptyList(),
        siteName = null,
        warnings = 0,
        systemWarnings = 0,
    )

    /**
     * A phone with few games and almost no free space: Metal Gear Solid's
     * file is gone (a tombstone) and can't fit back on — the NoRoom card.
     */
    private fun few(now: Long) = assemble(
        now = now,
        games = listOf(metalGear(now), metroid(now), chronoTrigger(now), galaga(now)),
        saves = homeSaves(now),
        free = (0.6 * HomeCopy.GB).toLong(),
    )

    private fun search(now: Long) = assemble(
        now = now,
        games = homeGames(now),
        saves = homeSaves(now),
        jobs = mapOf(DL_KH to kingdomHearts()),
        records = listOf(kingdomHeartsRecord()),
        query = QUERY,
        site = SiteQuery.Found(
            listOf(
                SiteHit(
                    siteEntry("gc/sonic-adventure-2", "Sonic Adventure 2", "gc"),
                    sizeOf((1.4 * HomeCopy.GB).toLong()),
                ),
                SiteHit(
                    siteEntry("md/sonic-the-hedgehog-2", "Sonic the Hedgehog 2", "genesis"),
                    sizeOf(1L * HomeCopy.MB),
                ),
            ),
        ),
    )

    private fun searchnoplugin(now: Long) = assemble(
        now = now,
        games = listOf(sonic2(now)),
        saves = mapOf(SONIC2 to SaveInfo(now - 2 * DAY_MS - 3 * HOUR_MS, 1, null)),
        query = QUERY,
        site = SiteQuery.NoPlugin,
        siteName = null,
    )

    /**
     * Surprise: Galaga is the pick (the SURPRISE row leads and owns the
     * default), and the normal HOME sections render below it.
     */
    private fun surprise(now: Long): HomeScreenModel {
        val pick = galaga(now)
        return assemble(
            now = now,
            games = homeGames(now) + pick,
            saves = homeSaves(now),
            jobs = mapOf(DL_KH to kingdomHearts()),
            records = listOf(kingdomHeartsRecord()),
            surprise = pick,
            etaSec = { s -> if (s.id == DL_KH) ETA_KH else null },
        )
    }

    /** Four jobs in the queue (badge "4"): Kingdom Hearts II leads, armed. */
    private fun downloading(now: Long) = assemble(
        now = now,
        games = homeGames(now),
        saves = homeSaves(now),
        jobs = mapOf(
            DL_KH to kingdomHearts(),
            DL_DQ8 to queuedJob(DL_DQ8, "Dragon Quest VIII", "ps2", "dragon-quest-viii.iso", 4L * HomeCopy.GB),
            DL_BURNOUT to queuedJob(
                DL_BURNOUT, "Burnout 3: Takedown", "ps2", "burnout-3-takedown.iso", 2L * HomeCopy.GB,
            ),
            DL_IKARUGA to pausedJob(
                DL_IKARUGA, "Ikaruga", "dreamcast", "ikaruga.iso",
                (0.7 * HomeCopy.GB).toLong(), (1.3 * HomeCopy.GB).toLong(),
            ),
        ),
        records = listOf(
            kingdomHeartsRecord(),
            queuedRecord(DL_DQ8, "Dragon Quest VIII", "ps2", "dragon-quest-viii.iso", 4L * HomeCopy.GB),
            queuedRecord(DL_BURNOUT, "Burnout 3: Takedown", "ps2", "burnout-3-takedown.iso", 2L * HomeCopy.GB),
            pausedRecord(
                DL_IKARUGA, "Ikaruga", "dreamcast", "ikaruga.iso",
                (0.7 * HomeCopy.GB).toLong(), (1.3 * HomeCopy.GB).toLong(),
            ),
        ),
        armed = setOf(DL_KH),
        etaSec = { s -> if (s.id == DL_KH) ETA_KH else null },
    )

    /**
     * The one assembler: [HomeGrouping.build] over the fake rows plus the
     * header the store assembles around it. [free] is the free-bytes answer
     * per folder (the `freeBytes` input).
     */
    private fun assemble(
        now: Long,
        games: List<GameEntity>,
        saves: Map<Long, SaveInfo> = emptyMap(),
        jobs: Map<Long, DownloadEngine.DlSnapshot> = emptyMap(),
        records: List<DownloadRecord> = emptyList(),
        armed: Set<Long> = emptySet(),
        online: Boolean = true,
        free: Long = FREE_BYTES,
        siteName: String? = SITE,
        query: String = "",
        site: SiteQuery = SiteQuery.None,
        surprise: GameEntity? = null,
        etaSec: (DownloadEngine.DlSnapshot) -> Long? = { null },
        warnings: Int = 2,
        systemWarnings: Int = 0,
    ): HomeScreenModel {
        val result = HomeGrouping.build(
            games = games, saves = saves, jobs = jobs, records = records, armed = armed,
            online = online, freeBytes = { free }, siteName = siteName, query = query,
            site = site, surprise = surprise, now = now,
            cards = HomeCardFactory(::readiness), etaSec = etaSec,
            systemWarnings = systemWarnings,
        )
        return HomeScreenModel(
            mode = result.mode,
            header = HeaderModel(
                searchPlaceholder = HomeCopy.headerSearchPlaceholder(siteName),
                browseLabel = HomeCopy.headerBrowseLabel(siteName),
                downloadsBadge = HomeCopy.headerDownloadsBadge(HomeGrouping.downloadQueue(jobs).size, online),
                systemWarnings = warnings,
            ),
            sections = result.sections,
            query = query,
            defaultKey = result.defaultKey,
        )
    }

    /**
     * Fake readiness for the sample rows (production probes the shown cards):
     * Nights into Dreams needs the Saturn BIOS; the rest are Ready.
     */
    private fun readiness(game: GameEntity): PlayStates.Status = when (game.id) {
        NIGHTS -> PlayStates.Status.NeedsBios(listOf("sega_101.bin"))
        else -> PlayStates.Status.Ready
    }

    /** The rows shared by the HOME-ish fixtures: 7 played, 9 never. */
    private fun homeGames(now: Long): List<GameEntity> = listOf(
        crazyTaxi(now), simpsons(now), metalGear(now),
        fzero(now), viewtiful(now), granTurismo4(now), tonyHawk3(now),
        metroid(now), nights(now), pikmin(now),
        marioSunshine(now), ratchet(now), melee(now), ffx(now), soulCalibur(now), waveRace(now),
    )

    /** `noplugin` keeps Mario 64 and no source rows: 6 played, 9 never. */
    private fun nopluginGames(now: Long): List<GameEntity> = listOf(
        crazyTaxi(now), simpsons(now), mario64(now),
        fzero(now), viewtiful(now), granTurismo4(now),
        metroid(now), nights(now), pikmin(now),
        marioSunshine(now), ratchet(now), melee(now), ffx(now), soulCalibur(now), waveRace(now),
    )

    /** Crazy Taxi's 3 saves and Metal Gear Solid's 2. */
    private fun homeSaves(now: Long): Map<Long, SaveInfo> = mapOf(
        CT to SaveInfo(now - 12 * MIN, 3, null),
        MGS to SaveInfo(now - 4 * DAY_MS, 2, null),
    )

    /** Crazy Taxi: GC, saved 12 min ago, 3 saves. */
    private fun crazyTaxi(now: Long) = game(
        CT, "Crazy Taxi", "gc", (1.2 * HomeCopy.GB).toLong(),
        playCount = 3, lastPlayedMs = now - 12 * MIN,
    )

    /** The Simpsons: Hit & Run: PS2, played yesterday. */
    private fun simpsons(now: Long) = game(
        SIMPSONS, "The Simpsons: Hit & Run", "ps2", (2.4 * HomeCopy.GB).toLong(),
        playCount = 1, lastPlayedMs = yesterdayAt(now),
    )

    /** Metal Gear Solid: off phone, 1.2 GB, 2 saves — a tombstone. */
    private fun metalGear(now: Long) = game(
        MGS, "Metal Gear Solid", "psx", (1.2 * HomeCopy.GB).toLong(),
        playCount = 2, lastPlayedMs = now - 3 * DAY_MS,
        deletedAtMs = now - DAY_MS,
        sourceRefJson = """{"sourceId":"roms-i-own","itemKey":"psx/metal-gear-solid"}""",
        sourceId = "roms-i-own", sourceItemKey = "psx/metal-gear-solid",
    )

    /** Metroid Prime: added today. */
    private fun metroid(now: Long) = game(
        METROID, "Metroid Prime", "gc", (1.2 * HomeCopy.GB).toLong(),
        addedAtMs = todayAt(now),
    )

    /**
     * Nights into Dreams: needs BIOS sega_101.bin. Added between
     * Metroid and Pikmin so the pipeline's NEW sort keeps the sample order.
     */
    private fun nights(now: Long) = game(
        NIGHTS, "Nights into Dreams", "saturn", 90L * HomeCopy.MB,
        addedAtMs = between(todayAt(now), tuesdayAt(now)),
    )

    /** Pikmin: added Tuesday. */
    private fun pikmin(now: Long) = game(
        PIKMIN, "Pikmin", "gc", 400L * HomeCopy.MB,
        addedAtMs = tuesdayAt(now),
    )

    /** Super Mario 64: saved 2 days ago, 2 saves (`noplugin`'s extra row). */
    private fun mario64(now: Long) = game(
        MARIO64, "Super Mario 64", "n64", 8L * HomeCopy.MB,
        playCount = 2, lastPlayedMs = now - 2 * DAY_MS - 3 * HOUR_MS,
    )

    private fun chronoTrigger(now: Long) = game(
        CHRONO, "Chrono Trigger", "snes", 4L * HomeCopy.MB,
        addedAtMs = todayAt(now),
    )

    private fun galaga(now: Long) = game(
        GALAGA, "Galaga", "mame", 40L * HomeCopy.KB,
        addedAtMs = tuesdayAt(now),
    )

    /** Sonic the Hedgehog 2 on the phone (`searchnoplugin`'s local hit). */
    private fun sonic2(now: Long) = game(
        SONIC2, "Sonic the Hedgehog 2", "genesis", 1L * HomeCopy.MB,
        playCount = 1, lastPlayedMs = now - 2 * DAY_MS - 3 * HOUR_MS,
    )

    // RESUME fillers: played before the sample rows (7 played in total).

    private fun fzero(now: Long) = game(
        FZERO, "F-Zero GX", "gc", (1.4 * HomeCopy.GB).toLong(),
        playCount = 5, lastPlayedMs = now - 5 * DAY_MS,
    )

    private fun viewtiful(now: Long) = game(
        VIEWTIFUL, "Viewtiful Joe", "gc", (1.1 * HomeCopy.GB).toLong(),
        playCount = 2, lastPlayedMs = now - 6 * DAY_MS,
    )

    private fun granTurismo4(now: Long) = game(
        GT4, "Gran Turismo 4", "ps2", (4.2 * HomeCopy.GB).toLong(),
        playCount = 8, lastPlayedMs = now - 7 * DAY_MS,
    )

    private fun tonyHawk3(now: Long) = game(
        THPS3, "Tony Hawk's Pro Skater 3", "ps2", (1.8 * HomeCopy.GB).toLong(),
        playCount = 1, lastPlayedMs = now - 8 * DAY_MS,
    )

    // NEW fillers: added before Tuesday (9 never played in total).

    private fun marioSunshine(now: Long) = game(
        SUNSHINE, "Super Mario Sunshine", "gc", (1.2 * HomeCopy.GB).toLong(),
        addedAtMs = tuesdayAt(now) - DAY_MS,
    )

    private fun ratchet(now: Long) = game(
        RATCHET, "Ratchet & Clank", "ps2", (3.0 * HomeCopy.GB).toLong(),
        addedAtMs = tuesdayAt(now) - 2 * DAY_MS,
    )

    private fun melee(now: Long) = game(
        MELEE, "Super Smash Bros. Melee", "gc", (1.1 * HomeCopy.GB).toLong(),
        addedAtMs = tuesdayAt(now) - 3 * DAY_MS,
    )

    private fun ffx(now: Long) = game(
        FFX, "Final Fantasy X", "ps2", (3.8 * HomeCopy.GB).toLong(),
        addedAtMs = tuesdayAt(now) - 4 * DAY_MS,
    )

    private fun soulCalibur(now: Long) = game(
        SOULCALIBUR, "SoulCalibur", "dreamcast", (0.9 * HomeCopy.GB).toLong(),
        addedAtMs = tuesdayAt(now) - 5 * DAY_MS,
    )

    private fun waveRace(now: Long) = game(
        WAVERACE, "Wave Race: Blue Storm", "gc", (0.6 * HomeCopy.GB).toLong(),
        addedAtMs = tuesdayAt(now) - 6 * DAY_MS,
    )

    /** Kingdom Hearts II: downloading 64%, 3 min left. */
    private fun kingdomHearts() = DownloadEngine.DlSnapshot(
        id = DL_KH, title = "Kingdom Hearts II", fileName = "kingdom-hearts-ii.iso",
        status = DownloadEngine.ST_ACTIVE, read = TWO_GIB, total = TOTAL_GIB,
        error = null, pageUrl = "https://roms-i-own.com/ps2/kingdom-hearts-ii",
        transferKind = "ROM",
    )

    private fun kingdomHeartsRecord() = DownloadRecord(
        id = DL_KH, pageUrl = "https://roms-i-own.com/ps2/kingdom-hearts-ii",
        title = "Kingdom Hearts II", destRelPath = "ps2/kingdom-hearts-ii.iso",
        volumeId = VOLUME, bytes = TWO_GIB, status = DownloadEngine.ST_ACTIVE,
        totalBytes = TOTAL_GIB, fileName = "kingdom-hearts-ii.iso",
        platformFolder = "ps2", sourceId = "roms-i-own", sourceItemKey = "ps2/kingdom-hearts-ii",
        transferKind = "ROM",
    )

    private fun queuedJob(id: Long, title: String, folder: String, file: String, total: Long) =
        DownloadEngine.DlSnapshot(
            id = id, title = title, fileName = file,
            status = DownloadEngine.ST_QUEUED, read = 0, total = total,
            error = null, pageUrl = "https://roms-i-own.com/$folder/$file",
            transferKind = "ROM",
        )

    private fun pausedJob(id: Long, title: String, folder: String, file: String, read: Long, total: Long) =
        DownloadEngine.DlSnapshot(
            id = id, title = title, fileName = file,
            status = DownloadEngine.ST_PAUSED, read = read, total = total,
            error = null, pageUrl = "https://roms-i-own.com/$folder/$file",
            transferKind = "ROM",
        )

    private fun queuedRecord(id: Long, title: String, folder: String, file: String, total: Long) =
        DownloadRecord(
            id = id, pageUrl = "https://roms-i-own.com/$folder/$file", title = title,
            destRelPath = "$folder/$file", volumeId = VOLUME, bytes = 0,
            status = DownloadEngine.ST_QUEUED, totalBytes = total, fileName = file,
            platformFolder = folder, transferKind = "ROM",
        )

    private fun pausedRecord(id: Long, title: String, folder: String, file: String, read: Long, total: Long) =
        DownloadRecord(
            id = id, pageUrl = "https://roms-i-own.com/$folder/$file", title = title,
            destRelPath = "$folder/$file", volumeId = VOLUME, bytes = read,
            status = DownloadEngine.ST_PAUSED, totalBytes = total, fileName = file,
            platformFolder = folder, transferKind = "ROM",
        )

    private fun game(
        id: Long,
        title: String,
        folder: String,
        sizeBytes: Long,
        playCount: Int = 0,
        lastPlayedMs: Long = 0,
        addedAtMs: Long = 0,
        deletedAtMs: Long = 0,
        sourceRefJson: String? = null,
        sourceId: String? = null,
        sourceItemKey: String? = null,
    ) = GameEntity(
        id = id, title = title, folder = folder, volumeId = VOLUME,
        relPath = "$folder/$id.iso", fileName = "$id.iso",
        sizeBytes = sizeBytes, mtimeMs = 0, ext = "iso",
        playCount = playCount, lastPlayedMs = lastPlayedMs, addedAtMs = addedAtMs,
        deletedAtMs = deletedAtMs, sourceRefJson = sourceRefJson,
        sourceId = sourceId, sourceItemKey = sourceItemKey,
    )

    private fun siteEntry(itemKey: String, title: String, platformId: String?): RomEntry = RomEntry(
        ref = SourceRef("roms-i-own", itemKey, "https://roms-i-own.com/$itemKey"),
        title = title,
        platformId = platformId,
    )

    private fun sizeOf(bytes: Long) = SizeInfo(bytes, HomeCopy.size(bytes))

    private fun localDate(ms: Long): LocalDate =
        Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()

    private fun startOfDay(now: Long): Long =
        localDate(now).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    /** Same local day, old enough to read "today" (never in the future). */
    private fun todayAt(now: Long): Long =
        minOf(startOfDay(now) + 5 * MIN, now - MIN)

    /** The previous local day, late enough to never read "N min ago". */
    private fun yesterdayAt(now: Long): Long = startOfDay(now) - HOUR_MS

    /** The most recent Tuesday at least two days back (reads "Tuesday"). */
    private fun tuesdayAt(now: Long): Long {
        val dow = localDate(now).dayOfWeek.value
        var days = (dow - DayOfWeek.TUESDAY.value + 7) % 7
        if (days == 0) days = 7
        return now - (days * DAY_MS - HOUR_MS)
    }

    /** Strictly between [newer] and [older] (NEW sorts newest first). */
    private fun between(newer: Long, older: Long): Long = (newer + older) / 2

    private const val CT = 1L
    private const val SIMPSONS = 2L
    private const val MGS = 3L
    private const val KH = 4L
    private const val METROID = 5L
    private const val NIGHTS = 6L
    private const val PIKMIN = 7L
    private const val SONIC2 = 8L
    private const val MARIO64 = 9L
    private const val CHRONO = 10L
    private const val GALAGA = 11L
    private const val FZERO = 20L
    private const val VIEWTIFUL = 21L
    private const val GT4 = 22L
    private const val THPS3 = 23L
    private const val SUNSHINE = 24L
    private const val RATCHET = 25L
    private const val MELEE = 26L
    private const val FFX = 27L
    private const val SOULCALIBUR = 28L
    private const val WAVERACE = 29L

    private const val DL_KH = 101L
    private const val DL_DQ8 = 102L
    private const val DL_BURNOUT = 103L
    private const val DL_IKARUGA = 104L

    /** "downloading 64%, 3 min left". */
    private const val ETA_KH = 180L

    private const val VOLUME = "vol"

    private val TWO_GIB = 2L * HomeCopy.GB // 2.0 GB of 3.1 GB = 64%
    private val TOTAL_GIB = 3_355_443_200L
}
