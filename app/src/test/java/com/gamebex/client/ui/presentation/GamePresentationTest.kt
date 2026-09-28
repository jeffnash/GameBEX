package com.gamebex.client.ui.presentation

import com.gamebex.client.data.CatalogFixtures
import com.gamebex.client.data.PlayStates
import com.gamebex.client.data.PlayableGame
import com.gamebex.client.data.local.GameEntity
import com.gamebex.client.net.DownloadEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unified detail CTA mapping: Download is the one step before Play. */
class GamePresentationTest {
    private fun entry() = CatalogFixtures.entry(
        itemKey = "n64/super-mario-64", title = "Super Mario 64",
        platformId = "n64", consoleName = "Nintendo 64",
        coverUrl = "https://cover/m64.png",
    )

    @Test fun catalogAvailableMapsDownload() {
        val lp = LocalGamePresenter.presentCatalog(entry(), "n64", null)
        assertTrue(lp.playable.status is PlayStates.Status.Available)
        assertEquals(GamePrimaryAction.Download, lp.presentation.primaryAction)
        assertEquals("Download", lp.presentation.primaryAction.label())
        assertEquals(GameAvailability.Available, lp.presentation.availability)
    }

    @Test fun catalogCarriesEntryArt() {
        val lp = LocalGamePresenter.presentCatalog(entry(), "n64", null)
        assertEquals("https://cover/m64.png", lp.presentation.cover)
        assertEquals("Super Mario 64", lp.presentation.title)
    }

    @Test fun liveDownloadMapsProgress() {
        val lp = LocalGamePresenter.presentCatalog(
            entry(), "n64", PlayStates.Dl(DownloadEngine.ST_ACTIVE, 42, 100),
        )
        assertEquals(GamePrimaryAction.Downloading(42), lp.presentation.primaryAction)
        assertEquals("Downloading… 42%", lp.presentation.primaryAction.label())
        assertEquals(GameAvailability.Downloading, lp.presentation.availability)
    }

    @Test fun sourceUnavailableMapsManageSources() {
        val lp = LocalGamePresenter.presentCatalog(
            entry(), "n64",
            PlayStates.Dl(DownloadEngine.ST_SOURCE_UNAVAILABLE, 10, 100, sourceId = "s1"),
        )
        assertEquals(GamePrimaryAction.ManageSources, lp.presentation.primaryAction)
        assertEquals("Manage sources", lp.presentation.primaryAction.label())
        assertEquals(GameAvailability.NeedsSetup, lp.presentation.availability)
    }

    @Test fun unknownTotalOmitsPct() {
        val lp = LocalGamePresenter.presentCatalog(
            entry(), "n64", PlayStates.Dl(DownloadEngine.ST_ACTIVE, 0, 0),
        )
        assertEquals(GamePrimaryAction.Downloading(null), lp.presentation.primaryAction)
        assertEquals("Downloading…", lp.presentation.primaryAction.label())
    }

    @Test fun pausedMapsResumeFailedMapsRetryAssistMapsVerify() {
        val paused = LocalGamePresenter.presentCatalog(
            entry(), "n64", PlayStates.Dl(DownloadEngine.ST_PAUSED, 10, 100),
        )
        assertEquals(GamePrimaryAction.Resume, paused.presentation.primaryAction)
        val failed = LocalGamePresenter.presentCatalog(
            entry(), "n64", PlayStates.Dl(DownloadEngine.ST_FAILED, 10, 100),
        )
        assertEquals(GamePrimaryAction.Retry, failed.presentation.primaryAction)
        assertEquals("Retry", failed.presentation.primaryAction.label())
        val assist = LocalGamePresenter.presentCatalog(
            entry(), "n64", PlayStates.Dl(DownloadEngine.ST_NEEDS_ASSIST, 0, 0),
        )
        assertEquals(GamePrimaryAction.Verify, assist.presentation.primaryAction)
        assertEquals("Verify & continue", assist.presentation.primaryAction.label())
    }

    @Test fun unmappedFolderIsUnsupportedNotMissing() {
        val lp = LocalGamePresenter.presentCatalog(entry(), null, null)
        assertTrue(lp.playable.status is PlayStates.Status.Blocked)
        assertEquals(GameAvailability.Unsupported, lp.presentation.availability)
        assertEquals(GamePrimaryAction.FinishSetup, lp.presentation.primaryAction)
        // Disabled "Unsupported", never "Locate file".
        assertEquals("Unsupported", lp.presentation.ctaLabel())
        assertEquals(false, lp.presentation.ctaEnabled)
    }

    private fun pres(a: GameAvailability, action: GamePrimaryAction = GamePrimaryAction.Play) =
        GamePresentation("local:1", "T", "GB", null, null, a, action)

    @Test fun ctaLabelByAvailability() {
        assertEquals("Locate file", pres(GameAvailability.Missing, GamePrimaryAction.FinishSetup).ctaLabel())
        assertEquals(true, pres(GameAvailability.Missing).ctaEnabled)
        assertEquals("Continue", pres(GameAvailability.Local, GamePrimaryAction.Continue).ctaLabel())
        assertEquals("Finish Setup", pres(GameAvailability.NeedsSetup, GamePrimaryAction.FinishSetup).ctaLabel())
    }

    private fun disc(preparing: Boolean) = PlayableGame.fromLocal(
        GameEntity(
            id = 7, title = "Crash", folder = "psx", volumeId = "internal",
            relPath = "psx/crash.zip", fileName = "crash.zip", sizeBytes = 1,
            mtimeMs = 0, ext = "zip", prepState = "NONE", available = true,
        ),
        dl = null, biosReady = true, runtimeInstalled = true, coreReady = true, spaceOk = true,
        preparing = preparing,
    ).toPresentation()

    @Test fun runningPrepParksTheCtaInsteadOfOfferingFinishSetup() {
        val busy = disc(preparing = true)
        assertEquals(GamePrimaryAction.Preparing, busy.primaryAction)
        assertEquals("Preparing…", busy.ctaLabel())
        assertEquals(GamePrimaryAction.FinishSetup, disc(preparing = false).primaryAction)
    }
}
