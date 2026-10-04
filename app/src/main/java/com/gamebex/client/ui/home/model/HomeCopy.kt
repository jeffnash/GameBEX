package com.gamebex.client.ui.home.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Every user-visible Home string and every formatter.
 * Pure Kotlin: no Android, no Compose, no `now` hidden inside. Callers pass
 * the clock and (for [relative]) the zone, so tests are deterministic.
 */
object HomeCopy {

    const val KB = 1024L
    const val MB = 1024L * KB
    const val GB = 1024L * MB

    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR

    private val MONTH_DAY: DateTimeFormatter =
        DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH)

    private val SIZE_RE = Regex("""^([0-9][0-9.,]*)\s*([a-zA-Z]+)(?=$|[^a-zA-Z0-9])""")
    private val BYTE_COUNT_RE = Regex("""^[0-9]+$""")
    private val THOUSANDS = Regex("""^[0-9]{1,3}(?:,[0-9]{3})+(?:\.[0-9]+)?$""")

 /**
 * "just now", "12 min ago", "today", "yesterday", "Monday",
 * "last week", "3 weeks ago", "Mar 3".
 */
    fun relative(atMs: Long, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val delta = (nowMs - atMs).coerceAtLeast(0)
        if (delta < MINUTE) return "just now"
        if (delta < HOUR) return "${delta / MINUTE} min ago"
        val then = localDate(atMs, zone)
        val today = localDate(nowMs, zone)
        if (then == today) return "today"
        if (then == today.minusDays(1)) return "yesterday"
        if (delta < 7 * DAY) return then.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
        if (delta < 14 * DAY) return "last week"
        if (delta < 60 * DAY) return "${delta / (7 * DAY)} weeks ago"
        return MONTH_DAY.format(then)
    }

 /** Midnight-free local date; `LocalDate.ofInstant` needs API 34. */
    private fun localDate(ms: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

 /** "480 MB" under 1 GB (no decimals), else "1.4 GB" (one decimal). */
    fun size(bytes: Long): String {
        val b = bytes.coerceAtLeast(0)
        return when {
            b < KB -> "$b B"
            b < MB -> "${b / KB} KB"
            b < GB -> "${b / MB} MB"
            else -> String.format(Locale.ENGLISH, "%.1f GB", b.toDouble() / GB)
        }
    }

 /**
 * "1.4 GB", "480MB", "1,2 Go", "733 MB", "2 GiB", "812 KB", a no-break
 * space between value and unit, and trailing text ("1.4 GB (zip)") →
 * bytes, or null when [text] doesn't start with a size. `1,234 MB` is a
 * thousands separator (1234 MB) but `1,2 Go` is a decimal (1.2 Go).
 * Binary units.
 */
    fun parseSize(text: String?, allowBareBytes: Boolean = true): Long? {
        val t = text
            ?.replace('\u00A0', ' ')
            ?.replace('\u202F', ' ')
            ?.trim()
            .orEmpty()
        if (t.isEmpty()) return null
        if (BYTE_COUNT_RE.matches(t)) return if (allowBareBytes) t.toLongOrNull() else null
        val m = SIZE_RE.find(t) ?: return null
        val value = parseDecimal(m.groupValues[1]) ?: return null
        val mult = when (m.groupValues[2].uppercase(Locale.ENGLISH)) {
            "B" -> 1L
            "K", "KB", "KO", "KIB" -> KB
            "M", "MB", "MO", "MIB" -> MB
            "G", "GB", "GO", "GIB" -> GB
            "T", "TB", "TO", "TIB" -> 1024L * GB
            else -> return null
        }
        return (value * mult).toLong()
    }

 /** `1,234[.5]` → 1234.5 (thousands), `1,2` → 1.2 (decimal). */
    private fun parseDecimal(raw: String): Double? {
        val cleaned = if (THOUSANDS.matches(raw)) raw.replace(",", "") else raw.replace(',', '.')
        return cleaned.toDoubleOrNull()
    }

 /** "under a minute", "3 min", "1 h 5 min"; empty when [sec] is unknown. */
    fun eta(sec: Long?): String {
        val s = (sec ?: return "").coerceAtLeast(0)
        if (s < 60) return "under a minute"
        if (s < 3600) return "${s / 60} min"
        val h = s / 3600
        val m = (s % 3600) / 60
        return if (m == 0L) "$h h" else "$h h $m min"
    }

 /** Fixed map; unknown folders are uppercased and cut to 4. */
    private val SYSTEM_SHORT = mapOf(
        "gc" to "GC", "wii" to "Wii", "ps2" to "PS2", "psx" to "PS1", "psp" to "PSP",
        "psvita" to "Vita", "n64" to "N64", "nds" to "DS", "n3ds" to "3DS", "gba" to "GBA",
        "gbc" to "GBC", "gb" to "GB", "snes" to "SNES", "nes" to "NES", "genesis" to "MD",
        "mastersystem" to "SMS", "gamegear" to "GG", "saturn" to "SAT", "dreamcast" to "DC",
        "segacd" to "MCD", "sega32x" to "32X", "neogeo" to "NEO", "neogeocd" to "NGCD",
        "mame" to "MAME", "fbneo" to "ARC", "atari2600" to "2600", "atari7800" to "7800",
        "pcengine" to "PCE", "tg-cd" to "PCECD", "3do" to "3DO", "msx" to "MSX", "dos" to "DOS",
        "j2me" to "J2ME",
    )

    fun systemShort(folder: String): String =
        SYSTEM_SHORT[folder.lowercase(Locale.ENGLISH)]
            ?: folder.uppercase(Locale.ENGLISH).take(4)

    private val TRAILING_ARTICLE = Regex("""^(.*?),\s*(The|An|A)(\s*[-:].*)?$""", RegexOption.IGNORE_CASE)

 /**
 * "Legend of Zelda, The" → "The Legend of Zelda" (also "A" and "An",
 * keeping any "- Game Subtitle" suffix: "Legend of Zelda, The - A Link
 * to the Past" → "The Legend of Zelda - A Link to the Past"; No-Intro
 * form). Used for card titles, the TV case art and search.
 */
    fun displayTitle(title: String): String {
        val t = title.trim()
        val m = TRAILING_ARTICLE.matchEntire(t) ?: return t
        return "${m.groupValues[2]} ${m.groupValues[1]}${m.groupValues[3]}".trim()
    }

    private val LEADING_ARTICLES = setOf("the", "a", "an")

 /**
 * Up to two initials from a title, skipping leading articles ("The Legend
 * of Zelda" → "LO"). Code points, not chars, so surrogate pairs (𝐀, emoji
 * scripts) survive intact. A title with no usable initials (blank,
 * whitespace-only, punctuation) falls back to [fallback] — the game's
 * system tag — else "?".
 */
    fun initials(title: String, fallback: String? = null): String {
        val words = title.split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.isNotEmpty() }
            .dropWhile { it.lowercase(Locale.ENGLISH) in LEADING_ARTICLES }
        val marks = words.take(2).map { w ->
            String(Character.toChars(w.codePointAt(0))).uppercase(Locale.ENGLISH)
        }
        return if (marks.isEmpty()) fallback ?: "?" else marks.joinToString("")
    }

 /** Card line. [online] false tags download lines with "offline". */
    fun cardLine(state: CardState, system: SystemTag?, nowMs: Long, online: Boolean = true): String = when (state) {
        is CardState.Saved -> parts(system?.short, "saved ${relative(state.atMs, nowMs)}")
        is CardState.Played -> parts(system?.short, "played ${relative(state.atMs, nowMs)}")
        is CardState.Added -> parts(system?.short, "added ${relative(state.atMs, nowMs)}")
        is CardState.NeedsBios -> "⚠ Needs BIOS"
        is CardState.NeedsEmulator -> "⚠ Needs emulator"
        is CardState.NeedsFix -> "⚠ Needs setup"
        CardState.Preparing -> parts(system?.short, "preparing…")
        is CardState.Downloading -> downloadingLine(state, online)
        is CardState.Queued ->
            if (state.armed) "Queued · plays when ready" else "Queued"
        is CardState.DownloadProblem -> "⚠ ${state.text}"
        is CardState.OffPhone -> "☁ " + downloadedAgain(state.size, again = true)
        is CardState.OnSite -> "☁ " + downloadedAgain(state.size, again = false)
        is CardState.NoRoom -> "Needs ${size(state.neededBytes)} · ${size(state.freeBytes)} free"
        CardState.WaitingForConnection -> "Download when online"
        is CardState.Unsupported -> "Can't play on this phone yet"
        is CardState.Unavailable -> state.reason
        CardState.None -> ""
    }

 /** "Download again · 1.2 GB" / "Download · 1.4 GB", size dropped when unknown. */
    private fun downloadedAgain(size: SizeInfo?, again: Boolean): String {
        val verb = if (again) "Download again" else "Download"
        return if (size == null) verb else "$verb · ${size.label}"
    }

    private fun downloadingLine(s: CardState.Downloading, online: Boolean = true): String {
        val offline = if (online) "" else " · offline"
        return when {
            s.paused -> "Paused$offline · ${percent(s.read, s.total)}%"
            s.armed -> "${percent(s.read, s.total)}% · plays when ready"
            s.etaSec != null -> "${percent(s.read, s.total)}% · ${eta(s.etaSec)} left"
            else -> "${percent(s.read, s.total)}%"
        }
    }

    private fun percent(read: Long, total: Long): Int =
        if (total > 0) ((read.coerceIn(0, total) * 100) / total).toInt() else 0

 /** "2.0 of 3.1 GB" — the unit is written once, after the total. */
    private fun progressPair(read: Long, total: Long): String {
        if (total <= 0) return size(read)
        val r = read.coerceIn(0, total)
        return when {
            total >= GB -> "${oneDecimal(r)} of ${oneDecimal(total)} GB"
            total >= MB -> "${r / MB} of ${total / MB} MB"
            total >= KB -> "${r / KB} of ${total / KB} KB"
            else -> "$r of $total B"
        }
    }

    private fun oneDecimal(bytes: Long): String =
        String.format(Locale.ENGLISH, "%.1f", bytes.toDouble() / GB)

 /**
 * TV state line, already uppercase. [system] names the BIOS in the
 * NeedsBios line ("NEEDS THE SATURN BIOS"). [tvStateKey] is the
 * tone-coloured headline and [tvStateDetail] the light-gray continuation;
 * this is the two joined with " · ".
 */
    fun tvState(
        state: CardState,
        siteName: String?,
        nowMs: Long,
        system: SystemTag? = null,
    ): String = listOfNotNull(
        tvStateKey(state, siteName, nowMs, system),
        tvStateDetail(state, siteName, nowMs),
    ).joinToString(" · ")

 /** Tone-coloured uppercase headline of the TV state line. */
    fun tvStateKey(
        state: CardState,
        siteName: String?,
        nowMs: Long,
        system: SystemTag? = null,
    ): String = when (state) {
        is CardState.Saved -> "RESUME"
        is CardState.Played -> "PLAYED ${relative(state.atMs, nowMs).uppercase(Locale.ENGLISH)}"
        is CardState.Added -> "NEW"
        is CardState.NeedsBios ->
            system?.let { "NEEDS THE ${it.name.uppercase(Locale.ENGLISH)} BIOS" } ?: "NEEDS A BIOS"
        is CardState.NeedsEmulator -> "NEEDS AN EMULATOR"
        is CardState.NeedsFix -> "CAN'T START YET"
        CardState.Preparing -> "PREPARING"
        is CardState.Downloading ->
            if (state.paused) "PAUSED ${percent(state.read, state.total)}%"
            else "DOWNLOADING ${percent(state.read, state.total)}%"
        is CardState.Queued -> "QUEUED"
        is CardState.DownloadProblem -> "DOWNLOAD STOPPED"
        is CardState.OffPhone -> "NOT ON YOUR PHONE"
        is CardState.OnSite ->
            siteName?.let { "ON ${it.uppercase(Locale.ENGLISH)}" } ?: "NOT ON YOUR PHONE"
        is CardState.NoRoom -> "NOT ENOUGH SPACE"
        CardState.WaitingForConnection -> "WAITING FOR A CONNECTION"
        is CardState.Unsupported -> "CAN'T PLAY THIS YET"
        is CardState.Unavailable -> "NOT AVAILABLE"
        CardState.None -> ""
    }

 /** Light-gray uppercase continuation of the TV state line. */
    fun tvStateDetail(state: CardState, siteName: String?, nowMs: Long): String? = when (state) {
        is CardState.Saved -> "SAVED ${relative(state.atMs, nowMs).uppercase(Locale.ENGLISH)}"
        is CardState.Added -> "ADDED ${relative(state.atMs, nowMs).uppercase(Locale.ENGLISH)}"
        is CardState.Downloading ->
            if (!state.paused && state.armed) "PLAYS WHEN READY" else null
        is CardState.Queued -> if (state.armed) "PLAYS WHEN READY" else null
        is CardState.OffPhone ->
            when {
                state.saves <= 0 -> null
                state.saves == 1 -> "1 SAVE KEPT"
                else -> "${state.saves} SAVES KEPT"
            }
        is CardState.OnSite -> if (siteName != null) "NOT ON YOUR PHONE" else null
        else -> null
    }

 /** Leading glyph for [tvStateKey]. */
    fun tvGlyph(state: CardState): TvGlyph? = when (state) {
        is CardState.Saved, is CardState.Played -> TvGlyph.RESUME
        is CardState.Added -> TvGlyph.NEW
        is CardState.Downloading, is CardState.Queued, CardState.Preparing -> TvGlyph.DOWNLOAD
        is CardState.OffPhone, is CardState.OnSite -> TvGlyph.CLOUD
        is CardState.NeedsBios, is CardState.NeedsEmulator, is CardState.NeedsFix,
        is CardState.DownloadProblem, is CardState.NoRoom, is CardState.Unsupported,
        is CardState.Unavailable,
        -> TvGlyph.WARN
        CardState.WaitingForConnection -> TvGlyph.OFFLINE
        CardState.None -> null
    }

 /**
 * TV facts line; null when there is nothing to say. [sizeBytes] is
 * the game's own size (Added, OnSite) when the caller knows it.
 */
    fun tvFacts(
        state: CardState,
        system: SystemTag?,
        freeBytes: Long?,
        sizeBytes: Long? = null,
    ): String? {
        val free = freeBytes?.let { "${size(it)} free" }
        return when (state) {
            is CardState.Saved -> parts(
                system?.name, emulatorOf(system),
                if (state.count == 1) "1 save" else "${state.count} saves",
            )
            is CardState.Played -> parts(system?.name, emulatorOf(system))
            is CardState.Added -> parts(system?.name, emulatorOf(system), sizeBytes?.let { size(it) })
            is CardState.NeedsBios -> {
                val target = system?.name?.let { "every $it game" } ?: "every game"
                if (state.files.isEmpty()) "Add the missing BIOS file once and it starts."
                else "Add ${state.files.joinToString(" and ")} once and $target starts."
            }
            is CardState.NeedsEmulator -> "Install ${state.what}, then it starts."
            is CardState.NeedsFix -> state.note.takeIf { it.isNotBlank() }
            CardState.Preparing -> "Unpacking and checking the files."
            is CardState.Downloading -> {
                val done = progressPair(state.read, state.total)
                if (!state.paused && state.etaSec != null) {
                    "$done · ${eta(state.etaSec)} left"
                } else {
                    done
                }
            }
            is CardState.Queued -> "Starts after the current download."
            is CardState.DownloadProblem -> state.text.takeIf { it.isNotBlank() }
            is CardState.OffPhone ->
                parts(state.size?.label, free).takeIf { it.isNotEmpty() }
            is CardState.OnSite ->
                parts(system?.name, state.size?.label, free).takeIf { it.isNotEmpty() }
            is CardState.NoRoom ->
                "Needs ${size(state.neededBytes)}, ${size(state.freeBytes)} free. " +
                    "Delete a game to make room."
            CardState.WaitingForConnection -> "It downloads when you're back online."
            is CardState.Unsupported -> state.reason.takeIf { it.isNotBlank() }
            is CardState.Unavailable -> null
            CardState.None -> null
        }
    }

 /** Primary button; the size is part of the label. */
    fun primaryLabel(state: CardState): String = when (state) {
        is CardState.Saved, is CardState.Played -> "Resume"
        is CardState.Added -> "Play"
        is CardState.NeedsBios -> "Add BIOS"
        is CardState.NeedsEmulator -> "Install emulator"
        is CardState.NeedsFix -> "Fix setup"
        CardState.Preparing -> "Preparing…"
        is CardState.Downloading -> when {
            state.paused -> "Resume download"
            state.armed -> "Plays when ready ✓"
            else -> "Play when ready"
        }
        is CardState.Queued ->
            if (state.armed) "Plays when ready ✓" else "Play when ready"
        is CardState.DownloadProblem -> if (state.verify) "Verify" else "Retry"
        is CardState.OffPhone -> downloadedAgain(state.size, again = true)
        is CardState.OnSite -> downloadedAgain(state.size, again = false)
        is CardState.NoRoom -> "Make room"
        CardState.WaitingForConnection -> "Download when online"
        is CardState.Unsupported, is CardState.Unavailable -> "Details"
        CardState.None -> ""
    }

 /** "Primary short": the primary label without the size. */
    fun primaryShort(state: CardState): String = when (state) {
        is CardState.OffPhone -> "Download again"
        is CardState.OnSite -> "Download"
        else -> primaryLabel(state)
    }

 /** Button tone. */
    fun buttonTone(state: CardState): ButtonTone = when (state) {
        is CardState.Saved, is CardState.Played, is CardState.Added -> ButtonTone.PLAY
        is CardState.NeedsBios, is CardState.NeedsEmulator, is CardState.NeedsFix,
        is CardState.NoRoom, is CardState.DownloadProblem,
        -> ButtonTone.FIX
        is CardState.Downloading -> if (state.armed) ButtonTone.ARMED else ButtonTone.NEUTRAL
        is CardState.Queued -> if (state.armed) ButtonTone.ARMED else ButtonTone.NEUTRAL
        is CardState.OffPhone, is CardState.OnSite -> ButtonTone.DOWNLOAD
        CardState.Preparing, CardState.WaitingForConnection,
        is CardState.Unsupported, is CardState.Unavailable, CardState.None,
        -> ButtonTone.NEUTRAL
    }

 /** TV state line tone: NEW/DOWNLOADING mint, RESUME pink, fixes amber, offline gray. */
    fun tvTone(state: CardState): TvTone = when (state) {
        is CardState.NeedsBios, is CardState.NeedsEmulator, is CardState.NeedsFix,
        is CardState.DownloadProblem, is CardState.NoRoom,
        -> TvTone.WARN
        is CardState.Added, is CardState.Queued -> TvTone.GOOD
        is CardState.Downloading -> if (state.paused) TvTone.MUTED else TvTone.GOOD
        CardState.WaitingForConnection, is CardState.Unsupported,
        is CardState.Unavailable,
        -> TvTone.MUTED
        else -> TvTone.NORMAL
    }

    fun groupLabel(group: HomeGroup, siteName: String?): String = when (group) {
        HomeGroup.RESUME -> "RESUME"
        HomeGroup.DOWNLOADING -> "DOWNLOADING"
        HomeGroup.NEW -> "NEW ON YOUR PHONE"
        HomeGroup.SEARCH_YOURS -> "YOUR GAMES"
        HomeGroup.SEARCH_SITE -> siteName?.uppercase(Locale.ENGLISH) ?: "ROM SITE"
        HomeGroup.SURPRISE -> "SURPRISE"
        HomeGroup.SETUP -> "START HERE"
    }

    fun groupDetail(group: HomeGroup, count: Int, siteName: String?): String? = when (group) {
        HomeGroup.RESUME -> "$count played"
        HomeGroup.DOWNLOADING -> if (siteName != null) "$count · from $siteName" else "$count"
        HomeGroup.NEW -> "$count never played"
        HomeGroup.SEARCH_YOURS -> "$count"
        HomeGroup.SEARCH_SITE -> "first page · not on your phone"
        HomeGroup.SURPRISE -> "Y rolls again"
        HomeGroup.SETUP -> "3 steps"
    }

    fun allTitle(n: Int): String = "All $n"

    fun allBody(group: HomeGroup, more: Int): String = when (group) {
        HomeGroup.RESUME -> "$more more ▸"
        HomeGroup.NEW -> "$more more never played ▸"
        HomeGroup.DOWNLOADING -> "Queue, speed, history ▸"
        else -> "$more more ▸"
    }

    fun allFacts(group: HomeGroup): String? = when (group) {
        HomeGroup.RESUME -> "Games with saves, newest first."
        HomeGroup.NEW -> "Games you haven't started, newest first."
        HomeGroup.DOWNLOADING -> "The queue, speeds and finished downloads."
        else -> null
    }

    fun inviteTitle(kind: InviteKind): String = when (kind) {
        InviteKind.ADD_PLUGIN -> "Add a ROM site plugin"
        InviteKind.SCAN_PHONE -> "Find games on this phone"
        InviteKind.CHECK_SYSTEMS -> "Check systems"
        InviteKind.ROLL_AGAIN -> "Roll again"
    }

    fun inviteBody(kind: InviteKind): String = when (kind) {
        InviteKind.ADD_PLUGIN -> "In System → Game sources. Then search the site from here."
        InviteKind.SCAN_PHONE -> "Scans Downloads, your ROM folders and the SD card."
        InviteKind.CHECK_SYSTEMS -> "See which systems need a BIOS or an emulator."
        InviteKind.ROLL_AGAIN -> "Picks another random game."
    }

    fun inviteLabel(kind: InviteKind): String = when (kind) {
        InviteKind.ADD_PLUGIN -> "Open Game sources"
        InviteKind.SCAN_PHONE -> "Scan phone"
        InviteKind.CHECK_SYSTEMS -> "Open System"
        InviteKind.ROLL_AGAIN -> "Roll again"
    }

    fun linkTitle(kind: LinkKind, query: String, siteName: String?): String = when (kind) {
        LinkKind.NO_PLUGIN_HINT -> "No ROM site plugin"
        LinkKind.SEE_ALL_ON_SITE -> "See every result for “$query” on ${siteName ?: "the site"}"
        LinkKind.OFFLINE_NOTE -> "You're offline"
        LinkKind.NOTHING_FOUND -> "Nothing on ${siteName ?: "the site"} for “$query”"
        LinkKind.SEARCH_FAILED -> "Couldn't search ${siteName ?: "the site"}"
        LinkKind.SEARCHING -> "Searching ${siteName ?: "the site"}…"
    }

 /** Second line of a quiet link; null when the row is title-only. */
    fun linkBody(kind: LinkKind, siteName: String?, message: String? = null): String? = when (kind) {
        LinkKind.NO_PLUGIN_HINT -> "Add one in System → Game sources to search a site from here."
        LinkKind.OFFLINE_NOTE ->
            "Your games still work. Searching ${siteName ?: "the site"} needs a connection."
        LinkKind.SEARCH_FAILED -> message?.takeIf { it.isNotBlank() }
        else -> null
    }

 /** Trailing action label; null = no action (the row isn't tappable). */
    fun linkLabel(kind: LinkKind): String? = when (kind) {
        LinkKind.NO_PLUGIN_HINT -> "Open Game sources"
        LinkKind.SEE_ALL_ON_SITE -> "Open in Discover"
        else -> null
    }

    fun headerSearchPlaceholder(siteName: String?): String =
        if (siteName != null) "Search your games and $siteName" else "Search your games"

 /** The top bar reads exactly like the rail ("Discover"), whatever site is installed. */
    @Suppress("UNUSED_PARAMETER")
    fun headerBrowseLabel(siteName: String?): String = navDiscover

 /** "2", "offline", or null. */
    fun headerDownloadsBadge(active: Int, online: Boolean): String? = when {
        !online -> "offline"
        active > 0 -> active.toString()
        else -> null
    }

 /** Delete confirm, source-downloaded games (the body text). */
    fun deleteSourceGameBody(title: String): String =
        "Delete $title from this phone? Your saves stay, and you can download it again from Home."

    fun deleteSourceGameTitle(title: String): String = "Delete $title?"

 /** "Downloading {title} · {size}", or "Downloading {title}" when unknown. */
    fun downloadingToast(title: String, size: SizeInfo?): String =
        downloadingToast(title, size?.label)

    fun downloadingToast(title: String, sizeLabel: String?): String =
        if (sizeLabel.isNullOrBlank()) "Downloading $title" else "Downloading $title · $sizeLabel"

    fun alreadyDownloading(title: String): String = "Already downloading $title."

    const val surpriseNothing = "Add a game first, then Y picks one for you."

 // ---------------------------------------------------- readiness notes

    const val needsSpace = "There isn't enough free space."
    const val needsVolume = "The SD card with this game isn't inserted."
    const val sourceGone = "Its ROM site plugin was removed."

 // -------------------------------- package constants

 // From HomeCardFactory.
    const val details = "Details"
    const val pause = "Pause"
    const val openLibrary = "Open Library"
    const val openDownloads = "Open Downloads"
    const val gameBex = "GAMEBEX"
    const val allDownloads = "All downloads"
    const val noGameInserted = "NO GAME INSERTED"
 /** Test-card plate label (the banner above it reads [noGameInserted]). */
    const val noGameLabel = "NO GAME"
    const val noPluginLabel = "NO ROM SITE PLUGIN"
 /** TvContent.Notice labels for the SETUP invites and Roll again. */
    const val findYourGames = "FIND YOUR GAMES"
    const val rollAgainTv = "ROLL AGAIN"
    const val allSystemsReady = "ALL SYSTEMS READY"

 /** "1 SYSTEM NEEDS SETUP" / "N SYSTEMS NEED SETUP" (CHECK_SYSTEMS invite). */
    fun systemsNeedSetup(n: Int): String =
        if (n == 1) "1 SYSTEM NEEDS SETUP" else "$n SYSTEMS NEED SETUP"
    const val moreOn = "MORE ON"
    const val offlineLabel = "OFFLINE"
    const val notFoundLabel = "NOTHING FOUND"
    const val searchFailedLabel = "SEARCH FAILED"
    const val searchingLabel = "SEARCHING"
    const val downloadStopped = "Download stopped"
    const val verifyDownload = "Waiting for you to verify the download."
    const val importIn = "Import in "

    const val gameGone = "This game is no longer in your library."

    const val tvBadge = "GAMEBEX"
    const val tvChannelPrefix = "CH "

    const val hintOpen = "Open"
    const val hintBack = "Back"
    const val hintSearch = "Search"
    const val hintSurprise = "Surprise me"
    const val hintClearSearch = "Clear search"
    const val hintPutBack = "Put it back"
    const val hintDismiss = "Dismiss"

    const val navLibrary = "Library"
    const val navDiscover = "Discover"
    const val navDownloads = "Downloads"
    const val navSystem = "System"
    const val glyphCloudPrefix = "\u2601"
    const val glyphCloud = "\u2601\uFE0E"
    const val glyphWarn = "\u26A0\uFE0E"

 /**
 * The emulator part of a facts line, or null when the label says nothing
 * ("None" is `emulatorLabel`'s unknown-system output and must never show).
 */
    private fun emulatorOf(system: SystemTag?): String? =
        system?.emulator?.takeIf { it.isNotBlank() && it != "None" }

 /** Joins the non-blank parts of a line with " · ". */
    private fun parts(vararg bits: String?): String =
        bits.mapNotNull { it?.takeIf { s -> s.isNotBlank() } }.joinToString(" · ")
}
