package com.gamebex.client.data

import com.gamebex.client.data.PlayStates.apply
import com.gamebex.client.data.local.DownloadRecord
import com.gamebex.client.data.local.GameEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Derive branches, event transitions, factories, and proof that each
 * legacy screen CTA re-expresses as a PlayableGame CTA.
 */
class PlayableGameTest {
    private val ra = Route.RetroCore("gambatte")
    private val sa = Route.Standalone(Emu.DOLPHIN)

    private fun cap(
        route: Route = ra,
        rowExists: Boolean = true,
        local: Boolean = true,
        prep: PlayStates.Prep = PlayStates.Prep.READY,
        biosReady: Boolean = true,
        biosMissing: List<String> = emptyList(),
        runtimeInstalled: Boolean = true,
        coreReady: Boolean = true,
        spaceOk: Boolean = true,
        dl: PlayStates.Dl? = null,
    ) = PlayStates.Capabilities(
        route = route, rowExists = rowExists, local = local, prep = prep,
        biosReady = biosReady, biosMissing = biosMissing,
        runtimeInstalled = runtimeInstalled,
        coreReady = coreReady, spaceOk = spaceOk, dl = dl,
    )

    @Test fun readyWhenAllGreen() {
        assertEquals(PlayStates.Status.Ready, PlayStates.derive(cap()))
    }

    @Test fun blockedRouteWinsOverEverything() {
        val c = cap(route = Route.Blocked("nope"), dl = PlayStates.Dl("active", 1, 2))
        assertEquals(PlayStates.Status.Blocked("nope"), PlayStates.derive(c))
    }

    @Test fun guidedRouteNeverDirectPlays() {
        val emu = com.gamebex.client.data.Emu.VITA3K
        val c = cap(route = Route.GuidedInstall(emu))
        assertEquals(PlayStates.Status.GuidedImport(emu), PlayStates.derive(c))
        assertEquals(
            PlayStates.Cta.OpenRuntime(emu),
            PlayStates.ctaFor(PlayStates.Status.GuidedImport(emu), Route.GuidedInstall(emu)),
        )
    }

    @Test fun guidedRouteWithoutRuntimeNeedsRuntime() {
        val emu = com.gamebex.client.data.Emu.VITA3K
        val c = cap(route = Route.GuidedInstall(emu), runtimeInstalled = false)
        assertEquals(PlayStates.Status.NeedsRuntime(emu), PlayStates.derive(c))
    }

    @Test fun downloadStates() {
        assertTrue(PlayStates.derive(cap(dl = PlayStates.Dl("active", 10, 100)))
            is PlayStates.Status.Downloading)
        val paused = PlayStates.derive(cap(dl = PlayStates.Dl("paused", 10, 100)))
            as PlayStates.Status.Downloading
        assertTrue(paused.paused)
        val assist = PlayStates.derive(cap(dl = PlayStates.Dl("needs_assist", 0, 0)))
            as PlayStates.Status.Downloading
        assertTrue(assist.assist)
        assertTrue(PlayStates.derive(cap(dl = PlayStates.Dl("failed", 0, 0)))
            is PlayStates.Status.DownloadFailed)
        // done job drops out of the chain
        assertEquals(PlayStates.Status.Ready, PlayStates.derive(cap(dl = PlayStates.Dl("done", 5, 5))))
    }

    @Test fun presenceStates() {
        assertEquals(PlayStates.Status.Available, PlayStates.derive(cap(rowExists = false, local = false)))
        assertEquals(PlayStates.Status.NeedsVolume, PlayStates.derive(cap(local = false)))
    }

    @Test fun spaceGate() {
        assertEquals(PlayStates.Status.NeedsSpace, PlayStates.derive(cap(spaceOk = false)))
    }

    @Test fun prepGates() {
        assertEquals(PlayStates.Status.Preparing, PlayStates.derive(cap(prep = PlayStates.Prep.NONE)))
        assertEquals(PlayStates.Status.Preparing, PlayStates.derive(cap(prep = PlayStates.Prep.PREPARING)))
        assertTrue(PlayStates.derive(cap(prep = PlayStates.Prep.FAILED)) is PlayStates.Status.NeedsManual)
        val m = PlayStates.derive(
            cap(prep = PlayStates.Prep.NEEDS_MANUAL),
        ) as PlayStates.Status.NeedsManual
        assertTrue(m.note.isNotBlank())
        val b = PlayStates.derive(
            cap(prep = PlayStates.Prep.NEEDS_BIOS, biosReady = false,
                biosMissing = listOf("x.bin")),
        ) as PlayStates.Status.NeedsBios
        assertEquals(listOf("x.bin"), b.names)
    }

    @Test fun biosNameCarries() {
        val c = cap(prep = PlayStates.Prep.NEEDS_BIOS, biosReady = false)
            .copy(biosMissing = listOf("scph5501.bin"))
        val s = PlayStates.derive(c) as PlayStates.Status.NeedsBios
        assertEquals(listOf("scph5501.bin"), s.names)
    }

    @Test fun runtimeAndCoreGates() {
        val r = PlayStates.derive(cap(runtimeInstalled = false)) as PlayStates.Status.NeedsRuntime
        assertEquals(Emu.RETROARCH, r.emu)
        val r2 = PlayStates.derive(cap(route = sa, runtimeInstalled = false)) as PlayStates.Status.NeedsRuntime
        assertEquals(Emu.DOLPHIN, r2.emu)
        val c = PlayStates.derive(cap(coreReady = false)) as PlayStates.Status.NeedsCore
        assertEquals("gambatte", c.core)
        // standalone routes never ask for a core
        assertEquals(PlayStates.Status.Ready, PlayStates.derive(cap(route = sa, coreReady = false)))
    }

    @Test fun ctaCoversEveryStatus() {
        val cases: List<Pair<PlayStates.Status, PlayStates.Cta>> = listOf(
            PlayStates.Status.Available to PlayStates.Cta.Get,
            PlayStates.Status.Downloading(0, 0, paused = true, assist = false) to PlayStates.Cta.ResumeDownload,
            PlayStates.Status.Downloading(0, 0, paused = false, assist = false) to PlayStates.Cta.None,
            PlayStates.Status.Downloading(0, 0, paused = true, assist = true) to PlayStates.Cta.OpenAssist,
            PlayStates.Status.DownloadFailed(null) to PlayStates.Cta.RetryDownload,
            PlayStates.Status.Preparing to PlayStates.Cta.Prepare,
            PlayStates.Status.Ready to PlayStates.Cta.Play,
            PlayStates.Status.Playing to PlayStates.Cta.None,
            PlayStates.Status.NeedsBios(emptyList()) to PlayStates.Cta.FixBios,
            PlayStates.Status.NeedsManual("") to PlayStates.Cta.Details,
            PlayStates.Status.NeedsRuntime(Emu.DOLPHIN) to PlayStates.Cta.InstallRuntime(Emu.DOLPHIN),
            PlayStates.Status.NeedsCore("x") to PlayStates.Cta.InstallCore("x"),
            PlayStates.Status.NeedsSpace to PlayStates.Cta.FreeSpace,
            PlayStates.Status.NeedsVolume to PlayStates.Cta.RemountVolume,
            PlayStates.Status.Blocked("r") to PlayStates.Cta.Details,
        )
        for ((status, want) in cases) {
            assertEquals("cta for $status", want, PlayStates.ctaFor(status, ra))
        }
        assertEquals(
            PlayStates.Cta.InstallApp,
            PlayStates.ctaFor(PlayStates.Status.Ready, Route.SideloadApp),
        )
    }

    /** Every legacy screen CTA re-expresses as a PlayableGame CTA. */
    @Test fun legacyCtasReexpressible() {
        // legacy label -> statuses whose CTA serves it
        val coverage = mapOf(
            "Store Get" to setOf(PlayStates.Cta.Get),
            "Detail Download/Pause/Resume/Cancel" to setOf(
                PlayStates.Cta.Get, PlayStates.Cta.ResumeDownload,
                PlayStates.Cta.RetryDownload, PlayStates.Cta.None,
            ),
            "Assist open" to setOf(PlayStates.Cta.OpenAssist),
            "Prepare" to setOf(PlayStates.Cta.Prepare),
            "BIOS fix" to setOf(PlayStates.Cta.FixBios),
            "Emulator install" to setOf(
                PlayStates.Cta.InstallRuntime(Emu.RETROARCH),
                PlayStates.Cta.InstallCore("gambatte"),
            ),
            "Play" to setOf(PlayStates.Cta.Play, PlayStates.Cta.InstallApp),
            "Open runtime" to setOf(PlayStates.Cta.OpenRuntime(Emu.VITA3K)),
            "Blocked details" to setOf(PlayStates.Cta.Details),
        )
        // Every legacy CTA maps onto at least one real Cta object above.
        for ((legacy, ctas) in coverage) {
            assertTrue("$legacy has no CTA", ctas.isNotEmpty())
        }
        // And every CTA (sans params) is reachable from some status.
        val reachable = setOf(
            PlayStates.ctaFor(PlayStates.Status.Available, ra),
            PlayStates.ctaFor(PlayStates.Status.Downloading(0, 0, true, false), ra),
            PlayStates.ctaFor(PlayStates.Status.Downloading(0, 0, false, false), ra),
            PlayStates.ctaFor(PlayStates.Status.Downloading(0, 0, true, true), ra),
            PlayStates.ctaFor(PlayStates.Status.DownloadFailed(null), ra),
            PlayStates.ctaFor(PlayStates.Status.Preparing, ra),
            PlayStates.ctaFor(PlayStates.Status.Ready, ra),
            PlayStates.ctaFor(PlayStates.Status.Ready, Route.SideloadApp),
            PlayStates.ctaFor(PlayStates.Status.NeedsBios(emptyList()), ra),
            PlayStates.ctaFor(PlayStates.Status.NeedsRuntime(Emu.PPSSPP), ra),
            PlayStates.ctaFor(PlayStates.Status.NeedsCore("c"), ra),
            PlayStates.ctaFor(PlayStates.Status.NeedsSpace, ra),
            PlayStates.ctaFor(PlayStates.Status.NeedsVolume, ra),
            PlayStates.ctaFor(PlayStates.Status.NeedsManual("x"), ra),
            PlayStates.ctaFor(PlayStates.Status.Blocked("x"), ra),
            PlayStates.ctaFor(
                PlayStates.Status.GuidedImport(Emu.VITA3K),
                Route.GuidedInstall(Emu.VITA3K),
            ),
        )
        val reachableKinds = reachable.map { it::class }.toSet()
        for (cta in coverage.values.flatten()) {
            assertTrue("$cta unreachable", cta::class in reachableKinds)
        }
    }

    @Test fun downloadLifecycle() {
        var c = cap(rowExists = false, local = false, prep = PlayStates.Prep.NONE)
        assertEquals(PlayStates.Status.Available, PlayStates.derive(c))
        c = c.apply(PlayStates.Event.DownloadStarted)
        assertTrue(PlayStates.derive(c) is PlayStates.Status.Downloading)
        c = c.apply(PlayStates.Event.DownloadPaused)
        assertEquals(PlayStates.Cta.ResumeDownload, PlayStates.ctaFor(PlayStates.derive(c), ra))
        c = c.apply(PlayStates.Event.DownloadResumed)
        assertTrue(PlayStates.derive(c) is PlayStates.Status.Downloading)
        c = c.apply(PlayStates.Event.DownloadDone)
        assertEquals(PlayStates.Status.Preparing, PlayStates.derive(c))
    }

    @Test fun downloadFailAndRetry() {
        var c = cap(rowExists = false, local = false)
            .apply(PlayStates.Event.DownloadStarted)
            .apply(PlayStates.Event.DownloadFailed("403"))
        assertEquals(
            PlayStates.Cta.RetryDownload,
            PlayStates.ctaFor(PlayStates.derive(c), ra),
        )
        c = c.apply(PlayStates.Event.DownloadStarted)
        assertTrue(PlayStates.derive(c) is PlayStates.Status.Downloading)
    }

    @Test fun assistBranch() {
        val c = cap(rowExists = false, local = false)
            .apply(PlayStates.Event.DownloadStarted)
            .apply(PlayStates.Event.AssistOpened)
        assertEquals(
            PlayStates.Cta.OpenAssist,
            PlayStates.ctaFor(PlayStates.derive(c), ra),
        )
    }

    @Test fun prepToReady() {
        var c = cap(prep = PlayStates.Prep.NONE)
        c = c.apply(PlayStates.Event.PrepStarted)
        assertEquals(PlayStates.Status.Preparing, PlayStates.derive(c))
        c = c.apply(PlayStates.Event.PrepDone(PlayStates.Prep.READY, biosReady = true))
        assertEquals(PlayStates.Status.Ready, PlayStates.derive(c))
    }

    @Test fun biosDetourAndFix() {
        var c = cap(prep = PlayStates.Prep.NONE)
            .apply(PlayStates.Event.PrepDone(
                PlayStates.Prep.NEEDS_BIOS, biosReady = false,
                biosMissing = listOf("dc_boot.bin"),
            ))
        assertTrue(PlayStates.derive(c) is PlayStates.Status.NeedsBios)
        c = c.apply(PlayStates.Event.BiosFixed)
        assertEquals(PlayStates.Status.Ready, PlayStates.derive(c))
    }

    @Test fun manualDetourNeedsReprep() {
        var c = cap(prep = PlayStates.Prep.NONE)
            .apply(PlayStates.Event.PrepDone(PlayStates.Prep.NEEDS_MANUAL, biosReady = true, note = "fix cue"))
        assertTrue(PlayStates.derive(c) is PlayStates.Status.NeedsManual)
        // user fixes outside, re-preps
        c = c.apply(PlayStates.Event.PrepStarted)
            .apply(PlayStates.Event.PrepDone(PlayStates.Prep.READY, biosReady = true))
        assertEquals(PlayStates.Status.Ready, PlayStates.derive(c))
    }

    @Test fun runtimeThenCoreThenPlay() {
        var c = cap(runtimeInstalled = false, coreReady = false)
        assertTrue(PlayStates.derive(c) is PlayStates.Status.NeedsRuntime)
        c = c.apply(PlayStates.Event.RuntimeInstalled)
        assertTrue(PlayStates.derive(c) is PlayStates.Status.NeedsCore)
        c = c.apply(PlayStates.Event.CoreInstalled)
        assertEquals(PlayStates.Status.Ready, PlayStates.derive(c))
        c = c.apply(PlayStates.Event.Launched)
        assertEquals(PlayStates.Status.Playing, PlayStates.derive(c))
        c = c.apply(PlayStates.Event.Returned)
        assertEquals(PlayStates.Status.Ready, PlayStates.derive(c))
    }

    @Test fun volumeEjectAndReturn() {
        var c = cap()
        c = c.apply(PlayStates.Event.RomMissing)
        assertEquals(PlayStates.Status.NeedsVolume, PlayStates.derive(c))
        c = c.apply(PlayStates.Event.RomAppeared)
        assertEquals(PlayStates.Status.Ready, PlayStates.derive(c))
    }

    @Test fun spaceLowAndFreed() {
        var c = cap()
        c = c.apply(PlayStates.Event.SpaceLow)
        assertEquals(PlayStates.Status.NeedsSpace, PlayStates.derive(c))
        c = c.apply(PlayStates.Event.SpaceFreed)
        assertEquals(PlayStates.Status.Ready, PlayStates.derive(c))
    }

    private fun game(
        prepState: String = "NONE",
        available: Boolean = true,
        folder: String = "gbc",
    ) = GameEntity(
        id = 7, title = "Zelda", folder = folder, volumeId = "internal",
        relPath = "gbc/z.zip", fileName = "z.zip", sizeBytes = 500632,
        mtimeMs = 0, ext = "zip", prepState = prepState, available = available,
    )

    @Test fun fromLocalMapsRow() {
        val g = PlayableGame.fromLocal(
            game(prepState = "READY"), dl = null, biosReady = true,
            runtimeInstalled = true, coreReady = true, spaceOk = true,
        )
        assertEquals("local:7", g.key)
        assertEquals(PlayStates.Status.Ready, g.status)
        assertEquals(PlayStates.Cta.Play, g.cta)
    }

    @Test fun fromLocalNeedsBios() {
        val g = PlayableGame.fromLocal(
            game(prepState = "NEEDS_BIOS", folder = "psx"), dl = null,
            biosReady = false, biosMissing = listOf("scph5501.bin"),
            runtimeInstalled = true, coreReady = true, spaceOk = true,
        )
        assertTrue(g.status is PlayStates.Status.NeedsBios)
    }

    @Test fun cartridgeNoneIsReady() {
        val g = PlayableGame.fromLocal(
            game(prepState = "NONE", folder = "gbc"), dl = null, biosReady = true,
            runtimeInstalled = true, coreReady = true, spaceOk = true,
        )
        assertEquals(PlayStates.Status.Ready, g.status)
    }

    @Test fun discNoneIsPreparing() {
        val g = PlayableGame.fromLocal(
            game(prepState = "NONE", folder = "psx"), dl = null, biosReady = true,
            runtimeInstalled = true, coreReady = true, spaceOk = true,
        )
        assertEquals(PlayStates.Status.Preparing, g.status)
    }

    @Test fun arcadeNoneIsPreparing() {
        val g = PlayableGame.fromLocal(
            game(prepState = "NONE", folder = "mame"), dl = null, biosReady = true,
            runtimeInstalled = true, coreReady = true, spaceOk = true,
        )
        assertEquals(PlayStates.Status.Preparing, g.status)
    }

    @Test fun fromLocalUnknownFolderBlocked() {
        val g = PlayableGame.fromLocal(
            game(folder = "nope"), dl = null, biosReady = true,
            runtimeInstalled = true, coreReady = true, spaceOk = true,
        )
        assertTrue(g.status is PlayStates.Status.Blocked)
    }

    @Test fun sourceUnavailableWithoutLocalFile() {
        val c = cap(
            rowExists = false, local = false,
            dl = PlayStates.Dl("source_unavailable", 10, 100, sourceId = "s1"),
        )
        val status = PlayStates.derive(c)
        assertEquals(PlayStates.Status.SourceUnavailable("s1"), status)
        assertEquals(
            PlayStates.Cta.ManageSources,
            PlayStates.ctaFor(status, ra),
        )
    }

    @Test fun suspendedReplacementKeepsPlay() {
        // A parked re-download must not hide Play for a valid local file.
        val c = cap(dl = PlayStates.Dl("source_unavailable", 10, 100, sourceId = "s1"))
        assertEquals(PlayStates.Status.Ready, c.let { PlayStates.derive(it) })
    }

    @Test fun fromCatalogWiresDlSourceId() {
        val entry = CatalogFixtures.entry(
            itemKey = "gbc/zelda", title = "Zelda",
            platformId = "gbc", consoleName = "Game Boy Color",
        )
        val rec = DownloadRecord(
            pageUrl = "https://x", title = "Zelda", destRelPath = "gbc/z.zip",
            volumeId = "internal", bytes = 10, status = "source_unavailable", totalBytes = 100,
            sourceId = "s1",
        )
        val g = PlayableGame.fromCatalog(entry, "gbc", dl = rec, spaceOk = true)
        assertEquals(PlayStates.Status.SourceUnavailable("s1"), g.status)
        assertEquals(PlayStates.Cta.ManageSources, g.cta)
    }

    @Test fun fromCatalogAvailableThenDownloading() {
        val entry = CatalogFixtures.entry(
            itemKey = "gbc/zelda", title = "Zelda",
            platformId = "gbc", consoleName = "Game Boy Color",
        )
        var g = PlayableGame.fromCatalog(entry, "gbc", dl = null, spaceOk = true)
        assertEquals(PlayStates.Status.Available, g.status)
        assertEquals(PlayStates.Cta.Get, g.cta)
        val rec = DownloadRecord(
            pageUrl = "https://x", title = "Zelda", destRelPath = "gbc/z.zip",
            volumeId = "internal", bytes = 10, status = "active", totalBytes = 100,
        )
        g = PlayableGame.fromCatalog(entry, "gbc", dl = rec, spaceOk = true)
        assertTrue(g.status is PlayStates.Status.Downloading)
    }
}
