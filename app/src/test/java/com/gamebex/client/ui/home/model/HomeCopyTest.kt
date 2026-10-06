package com.gamebex.client.ui.home.model

import com.gamebex.client.ui.console.ConsoleDestination
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Every HomeCopy formatter plus one assertion per card row. */
class HomeCopyTest {

    private val gb = HomeCopy.GB
    private val twoGib = 2L * gb // 64% of 3.1 GB
    private val totalGib = 3_355_443_200L

    private val gc = SystemTag("gc", "GC", "GameCube", "RetroArch · Dolphin")
    private val ps2 = SystemTag("ps2", "PS2", "PlayStation 2", "NetherSX2")
    private val saturn = SystemTag("saturn", "SAT", "Saturn", "RetroArch · Beetle Saturn")
    private val free = (28.4 * HomeCopy.GB).toLong()

    private fun sizeOf(bytes: Long) = SizeInfo(bytes, HomeCopy.size(bytes))

    @Test
    fun relativeCoversEveryBranch() {
        fun ago(ms: Long) = HomeCopy.relative(NOW - ms, NOW, ZONE)
        assertEquals("just now", ago(30_000))
        assertEquals("12 min ago", ago(12 * MIN))
        assertEquals("today", ago(3 * HOUR))
        assertEquals("yesterday", ago(26 * HOUR))
        assertEquals("Monday", ago(3 * DAY + HOUR))
        assertEquals("last week", ago(8 * DAY))
        assertEquals("3 weeks ago", ago(25 * DAY))
        assertEquals("Mar 3", HomeCopy.relative(Instant.parse("2025-03-03T09:00:00Z").toEpochMilli(), NOW, ZONE))
        // Clock skew never reads as the future.
        assertEquals("just now", HomeCopy.relative(NOW + HOUR, NOW, ZONE))
    }

    @Test
    fun sizeAndParseSizeRoundTrip() {
        assertEquals("480 MB", HomeCopy.size(480L * HomeCopy.MB))
        assertEquals("1.4 GB", HomeCopy.size(1_503_238_553L))
        assertEquals("812 KB", HomeCopy.size(812L * HomeCopy.KB))
        assertEquals(1_503_238_553L, HomeCopy.parseSize("1.4 GB")!!)
        assertEquals(503_316_480L, HomeCopy.parseSize("480MB")!!)
        assertEquals(1_288_490_188L, HomeCopy.parseSize("1,2 Go")!!)
        assertEquals(768_606_208L, HomeCopy.parseSize("733 MB")!!)
        assertEquals(2_147_483_648L, HomeCopy.parseSize("2 GiB")!!)
        assertEquals(831_488L, HomeCopy.parseSize("812 KB")!!)
        assertNull(HomeCopy.parseSize("no size"))
        assertNull(HomeCopy.parseSize(""))
        assertNull(HomeCopy.parseSize(null))
        assertNull(HomeCopy.parseSize("1.4 parsecs"))
    }

    @Test
    fun parseSizeRequiresUnitsExceptForWholeByteCounts() {
        assertEquals(1224L, HomeCopy.parseSize("1224")!!)
        assertEquals(3L, HomeCopy.parseSize("3")!!)
        assertEquals(0L, HomeCopy.parseSize("0")!!)
        assertNull(HomeCopy.parseSize("1224", allowBareBytes = false))
        assertNull(HomeCopy.parseSize("4", allowBareBytes = false))
        assertEquals(3L, HomeCopy.parseSize("3 B", allowBareBytes = false)!!)
        assertEquals(4L, HomeCopy.parseSize("4B", allowBareBytes = false)!!)
        assertEquals(1224L, HomeCopy.parseSize(" 1224 ")!!)
        assertEquals(1024L, HomeCopy.parseSize("1 KB", allowBareBytes = false)!!)
        assertEquals((5.09 * HomeCopy.MB).toLong(), HomeCopy.parseSize("5.09MiB")!!)
        assertEquals((1.2 * HomeCopy.GB).toLong(), HomeCopy.parseSize("1.2 GB compressed")!!)
    }

    @Test
    fun parseSizeRejectsRatingsCountsRangesAndVersions() {
        listOf(
            "3.8", "4.6", "1,2", "1.0.2", "v1.0.2",
            "1-4 players", "3 players", "4 files", "2 downloads",
            "3 (players)", "4 / 5", "3-4", "3Bottles", "1GB2",
        ).forEach { text -> assertNull(text, HomeCopy.parseSize(text)) }
    }

    @Test
    fun etaHasThreeShapes() {
        assertEquals("under a minute", HomeCopy.eta(30))
        assertEquals("3 min", HomeCopy.eta(180))
        assertEquals("1 h 5 min", HomeCopy.eta(3900))
        assertEquals("2 h", HomeCopy.eta(7200))
        assertEquals("", HomeCopy.eta(null))
    }

    @Test
    fun systemShortUsesTheFixedMapThenFallsBack() {
        assertEquals("GC", HomeCopy.systemShort("gc"))
        assertEquals("PS1", HomeCopy.systemShort("psx"))
        assertEquals("MD", HomeCopy.systemShort("genesis"))
        assertEquals("PCECD", HomeCopy.systemShort("tg-cd"))
        assertEquals("AMIG", HomeCopy.systemShort("amiga"))
        assertEquals("VERY", HomeCopy.systemShort("verylongfolder"))
    }

    private fun assertRow(
        state: CardState,
        system: SystemTag?,
        cardLine: String,
        tvState: String,
        tvFacts: String?,
        primary: String,
        tone: ButtonTone,
        tvTone: TvTone,
        sizeBytes: Long? = null,
    ) {
        assertEquals("card line", cardLine, HomeCopy.cardLine(state, system, NOW))
        assertEquals("tv state", tvState, HomeCopy.tvState(state, SITE, NOW, system))
        assertEquals("tv facts", tvFacts, HomeCopy.tvFacts(state, system, free, sizeBytes))
        assertEquals("primary", primary, HomeCopy.primaryLabel(state))
        assertEquals("button tone", tone, HomeCopy.buttonTone(state))
        assertEquals("tv tone", tvTone, HomeCopy.tvTone(state))
    }

    @Test
    fun savedRowIsResume() = assertRow(
        CardState.Saved(NOW - 12 * MIN, 3), gc,
        "GC · saved 12 min ago", "RESUME · SAVED 12 MIN AGO",
        "GameCube · RetroArch · Dolphin · 3 saves",
        "Resume", ButtonTone.PLAY, TvTone.NORMAL,
    )

    @Test
    fun playedRowIsResume() = assertRow(
        CardState.Played(NOW - 26 * HOUR), ps2,
        "PS2 · played yesterday", "PLAYED YESTERDAY",
        "PlayStation 2 · NetherSX2",
        "Resume", ButtonTone.PLAY, TvTone.NORMAL,
    )

    @Test
    fun addedRowIsPlay() = assertRow(
        CardState.Added(NOW - 3 * HOUR), gc,
        "GC · added today", "NEW · ADDED TODAY",
        "GameCube · RetroArch · Dolphin · 1.2 GB",
        "Play", ButtonTone.PLAY, TvTone.GOOD,
        sizeBytes = 1_288_490_188L,
    )

    @Test
    fun needsBiosRowNamesTheSystem() = assertRow(
        CardState.NeedsBios(listOf("sega_101.bin")), saturn,
        "⚠ Needs BIOS", "NEEDS THE SATURN BIOS",
        "Add sega_101.bin once and every Saturn game starts.",
        "Add BIOS", ButtonTone.FIX, TvTone.WARN,
    )

    @Test
    fun needsEmulatorRowIsFix() = assertRow(
        CardState.NeedsEmulator("the RetroArch mGBA core"), gc,
        "⚠ Needs emulator", "NEEDS AN EMULATOR",
        "Install the RetroArch mGBA core, then it starts.",
        "Install emulator", ButtonTone.FIX, TvTone.WARN,
    )

    @Test
    fun needsFixRowShowsTheNote() = assertRow(
        CardState.NeedsFix("Put the disc image back."), gc,
        "⚠ Needs setup", "CAN'T START YET",
        "Put the disc image back.",
        "Fix setup", ButtonTone.FIX, TvTone.WARN,
    )

    @Test
    fun preparingRowIsDisabled() = assertRow(
        CardState.Preparing(), gc,
        "GC · preparing…", "PREPARING",
        "Unpacking and checking the files.",
        "Preparing…", ButtonTone.NEUTRAL, TvTone.NORMAL,
    )

    @Test
    fun preparingRowShowsLiveBytes() = assertRow(
        CardState.Preparing(
            read = 312L * HomeCopy.MB, total = 996L * HomeCopy.MB,
            phase = "Extracting archive",
        ), gc,
        "GC · Extracting archive… 312 of 996 MB · 31%", "PREPARING",
        "Extracting archive",
        "Preparing…", ButtonTone.NEUTRAL, TvTone.NORMAL,
    )

    @Test
    fun stalledPreparingRowSaysStillWorking() = assertRow(
        CardState.Preparing(
            read = 312L * HomeCopy.MB, total = 996L * HomeCopy.MB,
            phase = "Extracting archive", stalled = true,
        ), gc,
        "GC · Still working… 312 of 996 MB · 31%", "STILL WORKING",
        "Extracting archive",
        "Still working…", ButtonTone.NEUTRAL, TvTone.NORMAL,
    )

    @Test
    fun downloadingRowShowsProgressAndEta() = assertRow(
        CardState.Downloading(twoGib, totalGib, etaSec = 180, paused = false, armed = false), gc,
        "64% · 3 min left", "DOWNLOADING 64%",
        "2.0 of 3.1 GB · 3 min left",
        "Play when ready", ButtonTone.NEUTRAL, TvTone.GOOD,
    )

    @Test
    fun armedDownloadingRowIsArmed() = assertRow(
        CardState.Downloading(twoGib, totalGib, etaSec = 180, paused = false, armed = true), gc,
        "64% · plays when ready", "DOWNLOADING 64% · PLAYS WHEN READY",
        "2.0 of 3.1 GB · 3 min left",
        "Plays when ready ✓", ButtonTone.ARMED, TvTone.GOOD,
    )

    @Test
    fun pausedDownloadingRowIsMuted() = assertRow(
        CardState.Downloading(twoGib, totalGib, etaSec = null, paused = true, armed = false), gc,
        "Paused · 64%", "PAUSED 64%",
        "2.0 of 3.1 GB",
        "Resume download", ButtonTone.NEUTRAL, TvTone.MUTED,
    )

    @Test
    fun queuedRowWaitsForTheCurrentDownload() = assertRow(
        CardState.Queued(armed = false), gc,
        "Queued", "QUEUED",
        "Starts after the current download.",
        "Play when ready", ButtonTone.NEUTRAL, TvTone.GOOD,
    )

    @Test
    fun armedQueuedRowShowsArmedFeedbackLikeDownloading() = assertRow(
        CardState.Queued(armed = true), gc,
        "Queued · plays when ready", "QUEUED · PLAYS WHEN READY",
        "Starts after the current download.",
        "Plays when ready ✓", ButtonTone.ARMED, TvTone.GOOD,
    )

    @Test
    fun downloadProblemRowOffersRetryOrVerify() {
        assertRow(
            CardState.DownloadProblem("The site stopped the download.", verify = false), gc,
            "⚠ The site stopped the download.", "DOWNLOAD STOPPED",
            "The site stopped the download.",
            "Retry", ButtonTone.FIX, TvTone.WARN,
        )
        assertRow(
            CardState.DownloadProblem("The file looks wrong.", verify = true), gc,
            "⚠ The file looks wrong.", "DOWNLOAD STOPPED",
            "The file looks wrong.",
            "Verify", ButtonTone.FIX, TvTone.WARN,
        )
    }

    @Test
    fun offPhoneRowKeepsTheSaves() = assertRow(
        CardState.OffPhone(sizeOf(1_288_490_188L), saves = 2), gc,
        "☁ Download again · 1.2 GB", "NOT ON YOUR PHONE · 2 SAVES KEPT",
        "1.2 GB · 28.4 GB free",
        "Download again · 1.2 GB", ButtonTone.DOWNLOAD, TvTone.NORMAL,
    )

    @Test
    fun onSiteRowDownloads() = assertRow(
        CardState.OnSite(sizeOf(1_503_238_553L)), gc,
        "☁ Download · 1.4 GB", "ON ROMS-I-OWN.COM · NOT ON YOUR PHONE",
        "GameCube · 1.4 GB · 28.4 GB free",
        "Download · 1.4 GB", ButtonTone.DOWNLOAD, TvTone.NORMAL,
    )

    @Test
    fun noRoomRowPointsAtTheLibrary() = assertRow(
        CardState.NoRoom((1.4 * gb).toLong(), (0.6 * gb).toLong()), gc,
        "Needs 1.4 GB · 614 MB free", "NOT ENOUGH SPACE",
        "Needs 1.4 GB, 614 MB free. Delete a game to make room.",
        "Make room", ButtonTone.FIX, TvTone.WARN,
    )

    @Test
    fun waitingForConnectionRowIsDisabled() = assertRow(
        CardState.WaitingForConnection, gc,
        "Download when online", "WAITING FOR A CONNECTION",
        "It downloads when you're back online.",
        "Download when online", ButtonTone.NEUTRAL, TvTone.MUTED,
    )

    @Test
    fun unsupportedRowExplains() = assertRow(
        CardState.Unsupported("It's a MAME software-list game."), gc,
        "Can't play on this phone yet", "CAN'T PLAY THIS YET",
        "It's a MAME software-list game.",
        "Details", ButtonTone.NEUTRAL, TvTone.MUTED,
    )

    @Test
    fun unknownSizeDropsTheSizeFromTheLabels() {
        assertEquals("☁ Download", HomeCopy.cardLine(CardState.OnSite(null), gc, NOW))
        assertEquals("Download", HomeCopy.primaryLabel(CardState.OnSite(null)))
        assertEquals("☁ Download again", HomeCopy.cardLine(CardState.OffPhone(null, 1), gc, NOW))
        assertEquals("Download again", HomeCopy.primaryLabel(CardState.OffPhone(null, 1)))
    }

    @Test
    fun primaryShortDropsTheSize() {
        assertEquals("Download again", HomeCopy.primaryShort(CardState.OffPhone(sizeOf(1_288_490_188L), 1)))
        assertEquals("Download", HomeCopy.primaryShort(CardState.OnSite(sizeOf(1_503_238_553L))))
        assertEquals("Resume", HomeCopy.primaryShort(CardState.Saved(NOW, 1)))
        assertEquals("Fix setup", HomeCopy.primaryShort(CardState.NeedsFix("x")))
    }

    @Test
    fun groupLabelsAndDetails() {
        assertEquals("RESUME", HomeCopy.groupLabel(HomeGroup.RESUME, SITE))
        assertEquals("7 played", HomeCopy.groupDetail(HomeGroup.RESUME, 7, SITE))
        assertEquals("DOWNLOADING", HomeCopy.groupLabel(HomeGroup.DOWNLOADING, SITE))
        assertEquals("1 · from ROMS-I-Own.com", HomeCopy.groupDetail(HomeGroup.DOWNLOADING, 1, SITE))
        assertEquals("NEW ON YOUR PHONE", HomeCopy.groupLabel(HomeGroup.NEW, SITE))
        assertEquals("9 never played", HomeCopy.groupDetail(HomeGroup.NEW, 9, SITE))
        assertEquals("YOUR GAMES", HomeCopy.groupLabel(HomeGroup.SEARCH_YOURS, SITE))
        assertEquals("4", HomeCopy.groupDetail(HomeGroup.SEARCH_YOURS, 4, SITE))
        assertEquals("ROMS-I-OWN.COM", HomeCopy.groupLabel(HomeGroup.SEARCH_SITE, SITE))
        assertEquals("first page · not on your phone", HomeCopy.groupDetail(HomeGroup.SEARCH_SITE, 2, SITE))
        assertEquals("SURPRISE", HomeCopy.groupLabel(HomeGroup.SURPRISE, SITE))
        assertEquals("Y rolls again", HomeCopy.groupDetail(HomeGroup.SURPRISE, 2, SITE))
        assertEquals("START HERE", HomeCopy.groupLabel(HomeGroup.SETUP, null))
        assertEquals("3 steps", HomeCopy.groupDetail(HomeGroup.SETUP, 3, null))
    }

    @Test
    fun allTileStrings() {
        assertEquals("All 7", HomeCopy.allTitle(7))
        assertEquals("4 more ▸", HomeCopy.allBody(HomeGroup.RESUME, 4))
        assertEquals("6 more never played ▸", HomeCopy.allBody(HomeGroup.NEW, 6))
        assertEquals("Queue, speed, history ▸", HomeCopy.allBody(HomeGroup.DOWNLOADING, 3))
        assertEquals("Games with saves, newest first.", HomeCopy.allFacts(HomeGroup.RESUME))
        assertEquals("Games you haven't started, newest first.", HomeCopy.allFacts(HomeGroup.NEW))
        assertEquals("The queue, speeds and finished downloads.", HomeCopy.allFacts(HomeGroup.DOWNLOADING))
        assertNull(HomeCopy.allFacts(HomeGroup.SURPRISE))
    }

    @Test
    fun inviteStrings() {
        assertEquals("Add a ROM site plugin", HomeCopy.inviteTitle(InviteKind.ADD_PLUGIN))
        assertEquals(
            "In System → Game sources. Then search the site from here.",
            HomeCopy.inviteBody(InviteKind.ADD_PLUGIN),
        )
        assertEquals("Open Game sources", HomeCopy.inviteLabel(InviteKind.ADD_PLUGIN))
        assertEquals("Find games on this phone", HomeCopy.inviteTitle(InviteKind.SCAN_PHONE))
        assertEquals("Scans Downloads, your ROM folders and the SD card.", HomeCopy.inviteBody(InviteKind.SCAN_PHONE))
        assertEquals("Scan phone", HomeCopy.inviteLabel(InviteKind.SCAN_PHONE))
        assertEquals("Check systems", HomeCopy.inviteTitle(InviteKind.CHECK_SYSTEMS))
        assertEquals("See which systems need a BIOS or an emulator.", HomeCopy.inviteBody(InviteKind.CHECK_SYSTEMS))
        assertEquals("Open System", HomeCopy.inviteLabel(InviteKind.CHECK_SYSTEMS))
        assertEquals("Roll again", HomeCopy.inviteTitle(InviteKind.ROLL_AGAIN))
        assertEquals("Picks another random game.", HomeCopy.inviteBody(InviteKind.ROLL_AGAIN))
        assertEquals("Roll again", HomeCopy.inviteLabel(InviteKind.ROLL_AGAIN))
    }

    @Test
    fun linkStrings() {
        assertEquals(
            "No ROM site plugin",
            HomeCopy.linkTitle(LinkKind.NO_PLUGIN_HINT, "sonic", SITE),
        )
        assertEquals(
            "Add one in System → Game sources to search a site from here.",
            HomeCopy.linkBody(LinkKind.NO_PLUGIN_HINT, SITE),
        )
        assertEquals("Open Game sources", HomeCopy.linkLabel(LinkKind.NO_PLUGIN_HINT))
        assertEquals(
            "See every result for “sonic” on ROMS-I-Own.com",
            HomeCopy.linkTitle(LinkKind.SEE_ALL_ON_SITE, "sonic", SITE),
        )
        assertNull(HomeCopy.linkBody(LinkKind.SEE_ALL_ON_SITE, SITE))
        assertEquals("Open in Discover", HomeCopy.linkLabel(LinkKind.SEE_ALL_ON_SITE))
        assertEquals("You're offline", HomeCopy.linkTitle(LinkKind.OFFLINE_NOTE, "sonic", SITE))
        assertEquals(
            "Your games still work. Searching ROMS-I-Own.com needs a connection.",
            HomeCopy.linkBody(LinkKind.OFFLINE_NOTE, SITE),
        )
        assertNull(HomeCopy.linkLabel(LinkKind.OFFLINE_NOTE))
        assertEquals(
            "Nothing on ROMS-I-Own.com for “sonic”",
            HomeCopy.linkTitle(LinkKind.NOTHING_FOUND, "sonic", SITE),
        )
        assertNull(HomeCopy.linkLabel(LinkKind.NOTHING_FOUND))
        assertEquals(
            "Couldn't search ROMS-I-Own.com",
            HomeCopy.linkTitle(LinkKind.SEARCH_FAILED, "sonic", SITE),
        )
        assertEquals(
            "The site answered with an error.",
            HomeCopy.linkBody(LinkKind.SEARCH_FAILED, SITE, "The site answered with an error."),
        )
        assertNull(HomeCopy.linkLabel(LinkKind.SEARCH_FAILED))
        assertEquals("Searching ROMS-I-Own.com…", HomeCopy.linkTitle(LinkKind.SEARCHING, "sonic", SITE))
        assertNull(HomeCopy.linkLabel(LinkKind.SEARCHING))
    }

    @Test
    fun oneSpellingAndDisplayTitles() {
        assertEquals("GAMEBEX", HomeCopy.gameBex)
        assertEquals("The Legend of Zelda", HomeCopy.displayTitle("Legend of Zelda, The"))
        assertEquals("An American Tail", HomeCopy.displayTitle("American Tail, An"))
        assertEquals("A Bug's Life", HomeCopy.displayTitle("Bug's Life, A"))
        assertEquals("Chrono Trigger", HomeCopy.displayTitle("Chrono Trigger"))
        assertEquals("The Simpsons: Hit & Run", HomeCopy.displayTitle("The Simpsons: Hit & Run"))
    }

    @Test
    fun initialsSkipLeadingArticles() {
        assertEquals("CT", HomeCopy.initials("Chrono Trigger"))
        assertEquals("LO", HomeCopy.initials("The Legend of Zelda"))
        assertEquals("AT", HomeCopy.initials("An American Tail"))
        assertEquals("T", HomeCopy.initials("Tetris"))
        assertEquals("?", HomeCopy.initials("  ---  "))
    }

    @Test
    fun parseSizeHandlesNoBreakSpaceTrailingTextAndThousands() {
        // No-break space (U+00A0) and narrow no-break space (U+202F).
        assertEquals(1_503_238_553L, HomeCopy.parseSize("1.4\u00A0GB")!!)
        assertEquals(1_503_238_553L, HomeCopy.parseSize("1.4\u202FGB")!!)
        // Trailing text after the size.
        assertEquals(1_503_238_553L, HomeCopy.parseSize("1.4 GB (zip)")!!)
        // "1,234 MB" is thousands; "1,2 Go" stays a decimal.
        assertEquals(1_234L * HomeCopy.MB, HomeCopy.parseSize("1,234 MB")!!)
        assertEquals(1_288_490_188L, HomeCopy.parseSize("1,2 Go")!!)
        assertEquals((12.34 * HomeCopy.GB).toLong(), HomeCopy.parseSize("12,34 Go")!!)
        assertEquals((1234.5 * HomeCopy.MB).toLong(), HomeCopy.parseSize("1,234.5 MB")!!)
    }

    @Test
    fun headerStrings() {
        assertEquals("Search your games and ROMS-I-Own.com", HomeCopy.headerSearchPlaceholder(SITE))
        assertEquals("Search your games", HomeCopy.headerSearchPlaceholder(null))
        // The top bar reads exactly like the rail.
        assertEquals(ConsoleDestination.Discover.label, HomeCopy.headerBrowseLabel(SITE))
        assertEquals(ConsoleDestination.Discover.label, HomeCopy.headerBrowseLabel(null))
        assertEquals(ConsoleDestination.Library.label, HomeCopy.navLibrary)
        assertEquals(ConsoleDestination.Downloads.label, HomeCopy.navDownloads)
        assertEquals(ConsoleDestination.System.label, HomeCopy.navSystem)
        assertEquals("2", HomeCopy.headerDownloadsBadge(2, online = true))
        assertNull(HomeCopy.headerDownloadsBadge(0, online = true))
        assertEquals("offline", HomeCopy.headerDownloadsBadge(2, online = false))
    }

    @Test
    fun toastsAndNotes() {
        assertEquals("Downloading Crazy Taxi · 1.2 GB", HomeCopy.downloadingToast("Crazy Taxi", sizeOf(1_288_490_188L)))
        assertEquals("Downloading Crazy Taxi", HomeCopy.downloadingToast("Crazy Taxi", null as SizeInfo?))
        assertEquals("Already downloading Crazy Taxi.", HomeCopy.alreadyDownloading("Crazy Taxi"))
        assertEquals("Add a game first, then Y picks one for you.", HomeCopy.surpriseNothing)
        assertEquals("There isn't enough free space.", HomeCopy.needsSpace)
        assertEquals("The SD card with this game isn't inserted.", HomeCopy.needsVolume)
        assertEquals("Its ROM site plugin was removed.", HomeCopy.sourceGone)
        assertEquals(
            "Delete Crazy Taxi from this phone? Your saves stay, and you can download it again from Home.",
            HomeCopy.deleteSourceGameBody("Crazy Taxi"),
        )
        assertEquals("Delete Crazy Taxi?", HomeCopy.deleteSourceGameTitle("Crazy Taxi"))
    }

    @Test
    fun unavailableRowIsDetailsWithNoFacts() {
        // A played game on an ejected SD card.
        val state = CardState.Unavailable(HomeCopy.needsVolume)
        assertEquals(HomeCopy.needsVolume, HomeCopy.cardLine(state, gc, NOW))
        assertEquals("NOT AVAILABLE", HomeCopy.tvState(state, SITE, NOW))
        assertNull(HomeCopy.tvFacts(state, gc, free))
        assertEquals("Details", HomeCopy.primaryLabel(state))
        assertEquals(ButtonTone.NEUTRAL, HomeCopy.buttonTone(state))
        assertEquals(TvTone.MUTED, HomeCopy.tvTone(state))
    }

    @Test
    fun offPhoneWithNoSavesKeepsNothing() {
        // "0 saves kept" is never shown.
        assertEquals("NOT ON YOUR PHONE", HomeCopy.tvState(CardState.OffPhone(null, 0), SITE, NOW))
        assertEquals("NOT ON YOUR PHONE · 1 SAVE KEPT", HomeCopy.tvState(CardState.OffPhone(null, 1), SITE, NOW))
    }

    @Test
    fun pausedLineIncludesOfflineWhenOffline() {
        val paused = CardState.Downloading(twoGib, totalGib, etaSec = null, paused = true, armed = false)
        assertEquals("Paused · offline · 64%", HomeCopy.cardLine(paused, gc, NOW, online = false))
        assertEquals("Paused · 64%", HomeCopy.cardLine(paused, gc, NOW, online = true))
    }

    @Test
    fun factsLineNeverShowsTheNoneEmulatorLabel() {
        // The "None" emulator label (unknown system) is dropped.
        val unknown = SystemTag("zzunknown", "ZZUN", "zzunknown", "None")
        assertEquals("zzunknown · 4 KB", HomeCopy.tvFacts(CardState.Added(NOW - HOUR), unknown, free, sizeBytes = 4096L))
        assertEquals("zzunknown", HomeCopy.tvFacts(CardState.Played(NOW - HOUR), unknown, free))
        // A real emulator label still shows.
        assertEquals("GameCube · RetroArch · Dolphin", HomeCopy.tvFacts(CardState.Played(NOW - HOUR), gc, free))
    }

    @Test
    fun displayTitleMovesNoIntroArticlesKeepingTheSubtitle() {
        // Five real No-Intro titles.
        assertEquals(
            "The Legend of Zelda - A Link to the Past",
            HomeCopy.displayTitle("Legend of Zelda, The - A Link to the Past"),
        )
        assertEquals(
            "The Addams Family - Pugsley's Scavenger Hunt",
            HomeCopy.displayTitle("Addams Family, The - Pugsley's Scavenger Hunt"),
        )
        assertEquals("The Amazing Spider-Man", HomeCopy.displayTitle("Amazing Spider-Man, The"))
        assertEquals(
            "The Simpsons - Bart vs. the Space Mutants",
            HomeCopy.displayTitle("Simpsons, The - Bart vs. the Space Mutants"),
        )
        assertEquals("The Immortal", HomeCopy.displayTitle("Immortal, The"))
        // An article mid-title never moves.
        assertEquals("The Simpsons: Hit & Run", HomeCopy.displayTitle("The Simpsons: Hit & Run"))
    }

    @Test
    fun initialsUseCodePointsAndFallBackToTheSystemTag() {
        // Surrogate pairs stay whole.
        val boldA = "\uD835\uDC00" // 𝐀
        val boldB = "\uD835\uDC01" // 𝐁
        assertEquals(boldA + boldB, HomeCopy.initials("$boldA $boldB Quest"))
        assertEquals(boldA + "M", HomeCopy.initials("$boldA$boldB Math Letters"))
        // Whitespace-only and empty titles fall back to the system tag.
        assertEquals("SNES", HomeCopy.initials("   ", "SNES"))
        assertEquals("SNES", HomeCopy.initials("", "SNES"))
        // Without a fallback the "?" shows; plain titles are unchanged.
        assertEquals("?", HomeCopy.initials("\t \n"))
        assertEquals("ML", HomeCopy.initials(" Math Letters"))
    }

    private companion object {
        const val SITE = "ROMS-I-Own.com"
        val ZONE: ZoneId = ZoneId.of("UTC")
        val NOW: Long = Instant.parse("2026-03-05T15:00:00Z").toEpochMilli()
        const val MIN = 60_000L
        const val HOUR = 60 * MIN
        const val DAY = 24 * HOUR
    }
}
