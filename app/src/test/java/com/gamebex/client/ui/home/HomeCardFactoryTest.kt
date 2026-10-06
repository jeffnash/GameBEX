package com.gamebex.client.ui.home

import com.gamebex.client.data.Emu
import com.gamebex.client.data.PlayStates
import com.gamebex.client.data.local.GameEntity
import com.gamebex.client.data.local.PrepPhase
import com.gamebex.client.data.local.PrepProgress
import com.gamebex.client.net.DownloadEngine
import com.gamebex.client.ui.LibSort
import com.gamebex.client.ui.home.model.ArtKind
import com.gamebex.client.ui.home.model.ButtonTone
import com.gamebex.client.ui.home.model.CardKey
import com.gamebex.client.ui.home.model.CardKind
import com.gamebex.client.ui.home.model.CardState
import com.gamebex.client.ui.home.model.HomeAction
import com.gamebex.client.ui.home.model.HomeCopy
import com.gamebex.client.ui.home.model.HomeDestination
import com.gamebex.client.ui.home.model.HomeGroup
import com.gamebex.client.ui.home.model.InviteKind
import com.gamebex.client.ui.home.model.LinkKind
import com.gamebex.client.ui.home.model.SaveInfo
import com.gamebex.client.ui.home.model.SizeInfo
import com.gamebex.client.ui.home.model.SystemTag
import com.gamebex.client.ui.home.model.TvContent
import com.gamebex.client.ui.home.model.TvGlyph
import com.gamebex.client.ui.home.model.TvTone
import com.gamebex.source.RomEntry
import com.gamebex.source.SourceRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every card row: state -> actions, labels, tones and TV lines. */
class HomeCardFactoryTest {

    private val now = 1_700_000_000_000L
    private val min = 60_000L
    private val day = 24 * 60 * min

    private val free28 = 30_494_258_216L // 28.4 GB
    private val site = "ROMS-I-Own.com"

    private val gc = SystemTag("gc", "GC", "GameCube", "RetroArch · Dolphin")
    private val saturn = SystemTag("saturn", "SAT", "Saturn", "RetroArch · Beetle Saturn")

    private var readyStatus: PlayStates.Status = PlayStates.Status.Ready
    private var readinessCalls = 0
    private val factory = HomeCardFactory { readinessCalls++; readyStatus }

    private fun game(
        id: Long = 1,
        title: String = "Crazy Taxi",
        playCount: Int = 0,
        deletedAtMs: Long = 0,
        coverPath: String? = null,
        coverUrl: String? = null,
        addedAtMs: Long = now - day,
        available: Boolean = true,
    ) = GameEntity(
            id = id, title = title, folder = "gc", volumeId = "vol",
            relPath = "gc/$id.iso", fileName = "$id.iso",
            sizeBytes = 1_288_490_189L, mtimeMs = 0, ext = "iso",
            playCount = playCount, lastPlayedMs = now - day,
            addedAtMs = addedAtMs, deletedAtMs = deletedAtMs,
            available = available,
            sourceRefJson = """{"sourceId":"acme","itemKey":"gc/$id","pageUrl":"https://acme/$id"}""",
            coverPath = coverPath, coverUrl = coverUrl,
        )

    private fun localCard(
        state: CardState? = null,
        group: HomeGroup = HomeGroup.RESUME,
        g: GameEntity = game(),
        save: SaveInfo? = null,
        online: Boolean = true,
    ) = state?.let {
        factory.gameCard(
            key = CardKey.local(g.id), title = g.title, system = gc, state = it,
            nowMs = now, freeBytes = free28, siteName = site,
            sizeBytes = g.sizeBytes, gameId = g.id,
        )
    } ?: factory.localCard(group, g, save, online, free28, site, now)

    private val gb = HomeCopy.GB

    @Test fun savedRow() {
        val card = localCard(CardState.Saved(now - 12 * min, count = 3))
        assertEquals(HomeAction.Play(1), card.primary)
        assertEquals("Resume", card.primaryLabel)
        assertEquals(ButtonTone.PLAY, card.primaryTone)
        assertEquals(HomeAction.Go(HomeDestination.Game(1)), card.secondary)
        assertEquals("Details", card.secondaryLabel)
        assertEquals("RESUME · SAVED 12 MIN AGO", card.tv.stateLine)
        assertEquals("GameCube · RetroArch · Dolphin · 3 saves", card.tv.factsLine)
        assertEquals(TvTone.NORMAL, card.tv.tone)
    }

    @Test fun playedRow() {
        val card = localCard(CardState.Played(now - day))
        assertEquals(HomeAction.Play(1), card.primary)
        assertEquals("Resume", card.primaryLabel)
        assertEquals("PLAYED YESTERDAY", card.tv.stateLine)
        assertEquals("GameCube · RetroArch · Dolphin", card.tv.factsLine)
    }

    @Test fun addedRow() {
        val card = localCard(CardState.Added(now))
        assertEquals(HomeAction.Play(1), card.primary)
        assertEquals("Play", card.primaryLabel)
        assertEquals("NEW · ADDED JUST NOW", card.tv.stateLine)
        assertEquals("GameCube · RetroArch · Dolphin · 1.2 GB", card.tv.factsLine)
    }

    @Test fun needsBiosRow() {
        val card = factory.gameCard(
            key = CardKey.local(1), title = "Nights", system = saturn,
            state = CardState.NeedsBios(listOf("sega_101.bin")),
            nowMs = now, freeBytes = free28, siteName = site, gameId = 1,
        )
        assertEquals(HomeAction.Go(HomeDestination.Game(1)), card.primary)
        assertEquals("Add BIOS", card.primaryLabel)
        assertEquals(ButtonTone.FIX, card.primaryTone)
        assertEquals("NEEDS THE SATURN BIOS", card.tv.stateLine)
        assertEquals("Add sega_101.bin once and every Saturn game starts.", card.tv.factsLine)
        assertEquals(TvTone.WARN, card.tv.tone)
    }

    @Test fun needsEmulatorRow() {
        val card = localCard(CardState.NeedsEmulator("Dolphin"))
        assertEquals("Install emulator", card.primaryLabel)
        assertEquals(ButtonTone.FIX, card.primaryTone)
        assertEquals("NEEDS AN EMULATOR", card.tv.stateLine)
        assertEquals("Install Dolphin, then it starts.", card.tv.factsLine)
    }

    @Test fun needsFixRow() {
        val card = localCard(CardState.NeedsFix("Move the file out of Downloads."))
        assertEquals("Fix setup", card.primaryLabel)
        assertEquals(ButtonTone.FIX, card.primaryTone)
        assertEquals("CAN'T START YET", card.tv.stateLine)
        assertEquals("Move the file out of Downloads.", card.tv.factsLine)
    }

    @Test fun preparingRow() {
        val card = localCard(CardState.Preparing())
        assertEquals(HomeAction.None, card.primary)
        assertEquals("Preparing…", card.primaryLabel)
        assertEquals(ButtonTone.NEUTRAL, card.primaryTone)
        assertEquals(HomeAction.Go(HomeDestination.Game(1)), card.secondary)
        assertEquals("PREPARING", card.tv.stateLine)
    }

    @Test fun prepPatchOverlaysLiveBytes() {
        val mb = 1024L * 1024
        val card = localCard(CardState.Preparing())
        val snap = PrepProgress(1L, PrepPhase.EXTRACTING, 312 * mb, 996 * mb, now, now)
        val patched = factory.prepPatch(card, snap, now + 1_000)
        assertEquals("GC · Extracting archive… 312 of 996 MB · 31%", patched.cardLine)
        assertEquals("PREPARING", patched.tv.stateKey)
        assertEquals("Extracting archive", patched.tv.factsLine)
    }

    @Test fun prepPatchMarksStall() {
        val mb = 1024L * 1024
        val card = localCard(CardState.Preparing())
        val snap = PrepProgress(1L, PrepPhase.EXTRACTING, 312 * mb, 996 * mb, now - 60_000, now - 60_000)
        val patched = factory.prepPatch(card, snap, now)
        assertEquals("GC · Still working… 312 of 996 MB · 31%", patched.cardLine)
        assertEquals("STILL WORKING", patched.tv.stateKey)
        assertEquals("Still working…", patched.primaryLabel)
    }

    @Test fun downloadingRow() {
        val card = localCard(CardState.Downloading(2 * gb, TOTAL, etaSec = 180, paused = false, armed = false))
        assertEquals(HomeAction.TogglePlayWhenReady(0), card.primary)
        assertEquals("Play when ready", card.primaryLabel)
        assertEquals(ButtonTone.NEUTRAL, card.primaryTone)
        assertEquals(HomeAction.PauseDownload(0), card.secondary)
        assertEquals("Pause", card.secondaryLabel)
        assertEquals("DOWNLOADING 64%", card.tv.stateLine)
        assertEquals("2.0 of 3.1 GB · 3 min left", card.tv.factsLine)
        assertEquals(TvTone.GOOD, card.tv.tone)
    }

    @Test fun downloadingArmedRow() {
        val card = localCard(CardState.Downloading(2 * gb, TOTAL, etaSec = 180, paused = false, armed = true))
        assertEquals("Plays when ready ✓", card.primaryLabel)
        assertEquals(ButtonTone.ARMED, card.primaryTone)
        assertEquals("DOWNLOADING 64% · PLAYS WHEN READY", card.tv.stateLine)
        assertEquals(TvTone.GOOD, card.tv.tone)
    }

    @Test fun downloadingPausedRow() {
        val card = localCard(CardState.Downloading(2 * gb, TOTAL, etaSec = null, paused = true, armed = false))
        assertEquals(HomeAction.ResumeDownload(0), card.primary)
        assertEquals("Resume download", card.primaryLabel)
        assertEquals(HomeAction.Go(HomeDestination.Downloads), card.secondary)
        assertEquals("Details", card.secondaryLabel)
        assertEquals("PAUSED 64%", card.tv.stateLine)
        assertEquals("2.0 of 3.1 GB", card.tv.factsLine)
        assertEquals(TvTone.MUTED, card.tv.tone)
    }

    @Test fun queuedRow() {
        val card = localCard(CardState.Queued(armed = false))
        assertEquals(HomeAction.TogglePlayWhenReady(0), card.primary)
        assertEquals("Play when ready", card.primaryLabel)
        assertEquals(HomeAction.PauseDownload(0), card.secondary)
        assertEquals("Pause", card.secondaryLabel)
        assertEquals("QUEUED", card.tv.stateLine)
        assertEquals("Starts after the current download.", card.tv.factsLine)
    }

    @Test fun downloadProblemRow() {
        val card = localCard(CardState.DownloadProblem("Connection lost.", verify = false))
        assertEquals(HomeAction.ResumeDownload(0), card.primary)
        assertEquals("Retry", card.primaryLabel)
        assertEquals(ButtonTone.FIX, card.primaryTone)
        assertEquals(HomeAction.Go(HomeDestination.Downloads), card.secondary)
        assertEquals("DOWNLOAD STOPPED", card.tv.stateLine)
        assertEquals("Connection lost.", card.tv.factsLine)
        assertEquals(TvTone.WARN, card.tv.tone)
    }

    @Test fun downloadProblemVerifyRow() {
        val card = localCard(CardState.DownloadProblem("Check the site.", verify = true))
        assertEquals(HomeAction.Go(HomeDestination.Downloads), card.primary)
        assertEquals("Verify", card.primaryLabel)
    }

    @Test fun offPhoneRow() {
        val card = localCard(CardState.OffPhone(SizeInfo(1_288_490_189L, "1.2 GB"), saves = 2))
        assertEquals(HomeAction.DownloadAgain(1), card.primary)
        assertEquals("Download again · 1.2 GB", card.primaryLabel)
        assertEquals(ButtonTone.DOWNLOAD, card.primaryTone)
        assertEquals(HomeAction.Go(HomeDestination.Game(1)), card.secondary)
        assertEquals("NOT ON YOUR PHONE · 2 SAVES KEPT", card.tv.stateLine)
        assertEquals("1.2 GB · 28.4 GB free", card.tv.factsLine)
        val pic = card.tv.content as TvContent.Picture
        assertTrue(pic.dim)
    }

    @Test fun onSiteRow() {
        val entry = entry()
        val card = factory.siteCard(entry, SizeInfo(1_503_238_553L, "1.4 GB"), true, free28, site, now)
        assertEquals(HomeAction.DownloadFromSite(entry), card.primary)
        assertEquals("Download · 1.4 GB", card.primaryLabel)
        assertEquals(ButtonTone.DOWNLOAD, card.primaryTone)
        assertEquals(HomeAction.Go(HomeDestination.SiteEntry(entry)), card.secondary)
        assertEquals("ON ROMS-I-OWN.COM · NOT ON YOUR PHONE", card.tv.stateLine)
        assertEquals("GameCube · 1.4 GB · 28.4 GB free", card.tv.factsLine)
        assertEquals(CardKey.site("acme", "gc/sonic"), card.key)
    }

    @Test fun onSiteUnknownSizeRow() {
        val entry = entry()
        val card = factory.siteCard(entry, null, true, free28, site, now)
        assertEquals("Download", card.primaryLabel)
        assertEquals(HomeAction.DownloadFromSite(entry), card.primary)
    }

    @Test fun noRoomRow() {
        val card = localCard(CardState.NoRoom(1_503_238_553L, 644_245_094L))
        assertEquals(HomeAction.Go(HomeDestination.Library(LibSort.Size)), card.primary)
        assertEquals("Make room", card.primaryLabel)
        assertEquals(ButtonTone.FIX, card.primaryTone)
        assertEquals("NOT ENOUGH SPACE", card.tv.stateLine)
        assertTrue(card.tv.factsLine!!.startsWith("Needs 1.4 GB, 614 MB free."))
        assertEquals(TvTone.WARN, card.tv.tone)
    }

    @Test fun waitingForConnectionRow() {
        val card = localCard(CardState.WaitingForConnection)
        assertEquals(HomeAction.None, card.primary)
        assertEquals("Download when online", card.primaryLabel)
        assertEquals(ButtonTone.NEUTRAL, card.primaryTone)
        assertEquals("WAITING FOR A CONNECTION", card.tv.stateLine)
        assertEquals("It downloads when you're back online.", card.tv.factsLine)
        assertEquals(TvTone.MUTED, card.tv.tone)
    }

    @Test fun unsupportedRow() {
        val card = localCard(CardState.Unsupported("No Android route (PC-based arcade)."))
        assertEquals(HomeAction.Go(HomeDestination.Game(1)), card.primary)
        assertEquals("Details", card.primaryLabel)
        assertEquals(ButtonTone.NEUTRAL, card.primaryTone)
        assertNull(card.secondary)
        assertNull(card.secondaryLabel)
        assertEquals("CAN'T PLAY THIS YET", card.tv.stateLine)
        assertEquals(TvTone.MUTED, card.tv.tone)
    }

    @Test fun onSiteOverridesToNoRoomWhenTooBig() {
        val entry = entry()
        val card = factory.siteCard(entry, SizeInfo(2 * gb, "2.0 GB"), true, 1 * gb, site, now)
        assertEquals(CardState.NoRoom(2 * gb, 1 * gb), card.state)
    }

    @Test fun onSiteOverridesToWaitingWhileOffline() {
        val entry = entry()
        val card = factory.siteCard(entry, SizeInfo(2 * gb, "2.0 GB"), online = false, freeBytes = 1 * gb, siteName = site, nowMs = now)
        assertEquals(CardState.WaitingForConnection, card.state)
    }

    @Test fun offPhoneOverridesToNoRoomWhenTooBig() {
        // Overrides live on the localCard path (gameCard wires one row as given).
        val card = factory.localCard(
            HomeGroup.RESUME, game(deletedAtMs = now), save = null,
            online = true, freeBytes = 1 * gb, siteName = site, nowMs = now,
        )
        assertEquals(CardState.NoRoom(1_288_490_189L, 1 * gb), card.state)
    }

    @Test fun readinessMapsToStates() {
        readyStatus = PlayStates.Status.NeedsBios(listOf("sega_101.bin"))
        assertEquals(CardState.NeedsBios(listOf("sega_101.bin")), localCard().state)

        readyStatus = PlayStates.Status.NeedsRuntime(Emu.DOLPHIN)
        assertEquals(CardState.NeedsEmulator("Dolphin"), localCard().state)

        readyStatus = PlayStates.Status.NeedsCore("dolphin")
        assertEquals(CardState.NeedsEmulator("the RetroArch Dolphin core"), localCard().state)

        readyStatus = PlayStates.Status.NeedsManual("Point the emulator at the folder.")
        assertEquals(CardState.NeedsFix("Point the emulator at the folder."), localCard().state)

        readyStatus = PlayStates.Status.Preparing
        assertEquals(CardState.Preparing(), localCard().state)

        readyStatus = PlayStates.Status.NeedsSpace
        assertEquals(CardState.NeedsFix(HomeCopy.needsSpace), localCard().state)

        readyStatus = PlayStates.Status.NeedsVolume
        assertEquals(CardState.Unavailable(HomeCopy.needsVolume), localCard().state)

        readyStatus = PlayStates.Status.Blocked("No Android route.")
        assertEquals(CardState.Unsupported("No Android route."), localCard().state)

        readyStatus = PlayStates.Status.GuidedImport(Emu.VITA3K)
        assertEquals(CardState.Unsupported("Import in Vita3K"), localCard().state)

        readyStatus = PlayStates.Status.SourceUnavailable("acme")
        assertEquals(CardState.NeedsFix(HomeCopy.sourceGone), localCard().state)
    }

    @Test fun tombstonesSkipReadiness() {
        val before = readinessCalls
        val card = localCard(group = HomeGroup.RESUME, g = game(deletedAtMs = now))
        assertEquals(CardState.OffPhone(SizeInfo(1_288_490_189L, "1.2 GB"), 0), card.state)
        assertEquals(before, readinessCalls)
    }

    @Test fun groupBaseStates() {
        val played = game(playCount = 2)
        assertEquals(CardState.Played(now - day), localCard(group = HomeGroup.RESUME, g = played).state)
        assertEquals(
            CardState.Saved(now - 5 * min, 2),
            localCard(group = HomeGroup.RESUME, g = played, save = SaveInfo(now - 5 * min, 2, null)).state,
        )
        // NEW ignores saves: the state is Added or the readiness problem.
        val fresh = game()
        assertEquals(
            CardState.Added(now - day),
            localCard(group = HomeGroup.NEW, g = fresh, save = SaveInfo(now - 5 * min, 2, null)).state,
        )
    }

    @Test fun allTiles() {
        val resume = factory.allTile(HomeGroup.RESUME, total = 7, more = 4, stack = emptyList())
        assertEquals("All 7", resume.title)
        assertEquals("4 more ▸", resume.body)
        assertEquals(HomeAction.Go(HomeDestination.Library(LibSort.Played)), resume.primary)
        assertEquals("Open Library", resume.primaryLabel)
        assertEquals("ALL 7 · 4 MORE", resume.tv.stateLine)
        assertEquals("Games with saves, newest first.", resume.tv.factsLine)
        assertTrue(resume.tv.content is TvContent.Mosaic)

        val downloads = factory.allTile(HomeGroup.DOWNLOADING, total = 4, more = 3, stack = emptyList())
        assertEquals(HomeAction.Go(HomeDestination.Downloads), downloads.primary)
        assertEquals("Open Downloads", downloads.primaryLabel)

        val news = factory.allTile(HomeGroup.NEW, total = 5, more = 2, stack = emptyList())
        assertEquals(HomeAction.Go(HomeDestination.Library(LibSort.Recent)), news.primary)
    }

    @Test fun invites() {
        val add = factory.invite(InviteKind.ADD_PLUGIN)
        assertEquals(CardKind.INVITE, add.kind)
        assertEquals(2, add.span)
        assertEquals(HomeAction.Go(HomeDestination.Sources), add.primary)
        assertEquals("Open Game sources", add.primaryLabel)
        assertEquals(HomeAction.RescanPhone, factory.invite(InviteKind.SCAN_PHONE).primary)
        assertEquals(HomeAction.Go(HomeDestination.System), factory.invite(InviteKind.CHECK_SYSTEMS).primary)
        assertEquals(HomeAction.RollAgain, factory.invite(InviteKind.ROLL_AGAIN).primary)
    }

    @Test fun inviteTvNoticesCarryTheFinal05Labels() {
        val plug = factory.invite(InviteKind.ADD_PLUGIN)
        assertEquals(TvContent.Notice(TvGlyph.PLUG, "NO ROM SITE PLUGIN", TvTone.NORMAL), plug.tv.content)
        assertEquals("NO ROM SITE PLUGIN", plug.tv.stateKey)
        assertEquals(TvGlyph.PLUG, plug.tv.glyph)
        val scan = factory.invite(InviteKind.SCAN_PHONE)
        assertEquals(TvContent.Notice(TvGlyph.FOLDER, "FIND YOUR GAMES", TvTone.NORMAL), scan.tv.content)
        val roll = factory.invite(InviteKind.ROLL_AGAIN)
        assertEquals(TvContent.Notice(TvGlyph.DICE, "ROLL AGAIN", TvTone.NORMAL), roll.tv.content)
    }

    @Test fun checkSystemsInviteNoticeFollowsTheWarningCount() {
        val fix = factory.invite(InviteKind.CHECK_SYSTEMS, systemWarnings = 2)
        assertEquals(TvContent.Notice(TvGlyph.WARN, "2 SYSTEMS NEED SETUP", TvTone.WARN), fix.tv.content)
        val ready = factory.invite(InviteKind.CHECK_SYSTEMS, systemWarnings = 0)
        assertEquals(TvContent.Notice(TvGlyph.WARN, "ALL SYSTEMS READY", TvTone.WARN), ready.tv.content)
    }

    @Test fun noGameTvShowsTheShortPlateAndTheLongBanner() {
        val tv = factory.noGameTv()
        assertEquals(TvContent.TestCard("NO GAME"), tv.content)
        assertEquals("NO GAME INSERTED", tv.stateKey)
    }

    @Test fun unavailablePlayedGameNeedsNoReadinessProbe() {
        // Ejected SD card — the state comes from the row alone.
        val before = readinessCalls
        val card = localCard(group = HomeGroup.RESUME, g = game(playCount = 2, available = false))
        assertEquals(CardState.Unavailable(HomeCopy.needsVolume), card.state)
        assertEquals(HomeAction.Go(HomeDestination.Game(1)), card.primary)
        assertEquals("Details", card.primaryLabel)
        assertNull(card.secondary)
        assertEquals(before, readinessCalls)
    }

    @Test fun pausedDownloadLineSaysOfflineWhenOffline() {
        val job = DownloadEngine.DlSnapshot(
            id = 9, title = "Job 9", fileName = "j9.zip",
            status = DownloadEngine.ST_PAUSED, read = 2 * gb, total = TOTAL,
            error = null, pageUrl = "https://acme/9", transferKind = "ROM",
        )
        val offline = factory.jobCard(
            snapshot = job, record = null, gameId = null, armed = false,
            etaSec = null, siteName = site, nowMs = now, online = false,
        )
        assertEquals("Paused · offline · 64%", offline.cardLine)
        val online = factory.jobCard(
            snapshot = job, record = null, gameId = null, armed = false,
            etaSec = null, siteName = site, nowMs = now, online = true,
        )
        assertEquals("Paused · 64%", online.cardLine)
    }

    @Test fun links() {
        val noPlugin = factory.link(LinkKind.NO_PLUGIN_HINT, "sonic", null)
        assertEquals(2, noPlugin.span)
        assertEquals(HomeAction.Go(HomeDestination.Sources), noPlugin.primary)
        assertEquals("Open Game sources", noPlugin.primaryLabel)
        assertTrue(noPlugin.tv.content is TvContent.PlugCard)

        val seeAll = factory.link(LinkKind.SEE_ALL_ON_SITE, "sonic", site)
        assertEquals(HomeAction.Go(HomeDestination.SiteSearch("sonic")), seeAll.primary)
        assertEquals("Open in Discover", seeAll.primaryLabel)

        for (kind in listOf(LinkKind.OFFLINE_NOTE, LinkKind.NOTHING_FOUND, LinkKind.SEARCHING)) {
            val link = factory.link(kind, "sonic", site)
            assertEquals(HomeAction.None, link.primary)
            assertEquals("", link.primaryLabel)
        }
    }

    @Test fun thumbPrefersSaveShot() {
        val saveFile = java.io.File.createTempFile("shot", ".png").apply { deleteOnExit() }
        val withSave = factory.thumbArt(game(), SaveInfo(now, 1, saveFile))
        assertEquals(ArtKind.SAVE_THUMB, withSave.kind)
        assertEquals(saveFile, withSave.model)
        assertEquals(saveFile.lastModified(), withSave.revision)
        assertEquals(ArtKind.NONE, factory.thumbArt(game(), null).kind)
    }

    @Test fun queuedArmedRowShowsArmedFeedback() {
        val card = localCard(CardState.Queued(armed = true))
        assertEquals("Plays when ready ✓", card.primaryLabel)
        assertEquals(ButtonTone.ARMED, card.primaryTone)
        assertEquals("QUEUED · PLAYS WHEN READY", card.tv.stateLine)
        assertEquals(TvTone.GOOD, card.tv.tone)
    }

    @Test fun tvModelFillsKeyDetailAndGlyphForEveryState() {
        val states = listOf(
            CardState.Saved(now, 1), CardState.Played(now), CardState.Added(now),
            CardState.NeedsBios(listOf("bios.bin")), CardState.NeedsEmulator("Dolphin"),
            CardState.NeedsFix("note"), CardState.Preparing(),
            CardState.Downloading(1, 2, null, paused = false, armed = false),
            CardState.Queued(false), CardState.DownloadProblem("x", false),
            CardState.OffPhone(null, 1), CardState.OnSite(null), CardState.NoRoom(2, 1),
            CardState.WaitingForConnection, CardState.Unsupported("r"),
        )
        for (state in states) {
            val card = localCard(state)
            assertTrue("$state has a state key", card.tv.stateKey.isNotBlank())
            assertTrue("$state has a glyph", card.tv.glyph != null)
        }
        // Two-tone banner split: key is the tone-coloured headline.
        val saved = localCard(CardState.Saved(now - 12 * min, 1))
        assertEquals("RESUME", saved.tv.stateKey)
        assertEquals("SAVED 12 MIN AGO", saved.tv.stateDetail)
        assertEquals(TvGlyph.RESUME, saved.tv.glyph)
    }

    @Test fun downloadingPictureCarriesTheProgressRing() {
        val card = localCard(CardState.Downloading(2 * gb, TOTAL, etaSec = 180, paused = false, armed = false))
        val pic = card.tv.content as TvContent.Picture
        assertEquals(0.64f, pic.progress!!, 0.005f)
        assertNull((localCard(CardState.Saved(now, 1)).tv.content as TvContent.Picture).progress)
    }

    @Test fun cardLineIsPrecomputedAndTitlesUseTheDisplayForm() {
        val card = localCard(
            group = HomeGroup.NEW,
            g = game(title = "Legend of Zelda, The", addedAtMs = now - 5 * min),
        )
        assertEquals("The Legend of Zelda", card.title)
        assertEquals("GC · added 5 min ago", card.cardLine)
    }

    @Test fun linksUseThePlayToneAndFailuresCarryTheMessage() {
        assertEquals(ButtonTone.PLAY, factory.link(LinkKind.NO_PLUGIN_HINT, "sonic", site).primaryTone)

        val failed = factory.link(LinkKind.SEARCH_FAILED, "sonic", site, message = "The site timed out.")
        assertEquals("Couldn't search ROMS-I-Own.com", failed.title)
        assertEquals("The site timed out.", failed.body)
        assertEquals(ButtonTone.PLAY, failed.primaryTone)
    }

    @Test fun coverArtFallsBackFromLocalFileToUrlToGeneratedCase() {
        val onDisk = java.io.File.createTempFile("cover", ".png").apply { deleteOnExit() }
        val gone = java.io.File.createTempFile("gone", ".png").apply { delete(); deleteOnExit() }

        val local = factory.coverArt(game(coverPath = onDisk.path))
        assertEquals(ArtKind.COVER, local.kind)
        assertEquals(onDisk, local.model)
        assertEquals("gc", local.systemFolder)
        assertEquals("Crazy Taxi", local.title)

        val remote = factory.coverArt(game(coverPath = gone.path, coverUrl = "https://acme/c.png"))
        assertEquals(ArtKind.COVER, remote.kind)
        assertEquals("https://acme/c.png", remote.model)

        val none = factory.coverArt(game(coverPath = gone.path))
        assertEquals(ArtKind.NONE, none.kind)
        assertNull(none.model)
        assertEquals("gc", none.systemFolder)
        assertEquals("Crazy Taxi", none.title)
    }

    @Test fun thumbArtSkipsAMissingSaveShot() {
        val gone = java.io.File.createTempFile("shot", ".png").apply { delete(); deleteOnExit() }
        val art = factory.thumbArt(game(coverUrl = "https://acme/c.png"), SaveInfo(now, 1, gone))
        assertEquals(ArtKind.COVER, art.kind)
        assertEquals("https://acme/c.png", art.model)
    }

    private fun entry() = RomEntry(
        ref = SourceRef("acme", "gc/sonic", "https://acme/gc/sonic"),
        title = "Sonic Adventure 2",
        platformId = "gc",
        coverUrl = "https://acme/cover.png",
    )

    private companion object {
        const val TOTAL = 3_355_443_200L // 2.0 of 3.1 GB = 64%
    }
}
