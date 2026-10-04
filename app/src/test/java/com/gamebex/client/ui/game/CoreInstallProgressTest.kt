package com.gamebex.client.ui.game

import com.gamebex.client.launch.CoreInstallProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CoreInstallProgressTest {
    private val core = "mupen64plus_next_gles3"

    @Test fun immediateProgressOutranksLaunchingAndUsesAHumanCoreName() {
        val progress = CoreInstallProgress(core)
        assertEquals("Installing the N64 core…", progress.text)
        assertNull(progress.fraction)
        assertEquals("Installing core…", coreInstallActionLabel(progress, true, false, false, "Play"))
    }

    @Test fun byteProgressIsReadableAndClampsTheFraction() {
        val progress = CoreInstallProgress(core, read = 3_355_443, total = 6_501_171)
        assertEquals("Downloading 3.2 of 6.2 MB", progress.text)
        assertEquals(3_355_443f / 6_501_171f, progress.fraction!!, 0.0001f)
        assertEquals(1f, progress.copy(read = 9_000_000).fraction!!, 0f)
    }

    @Test fun installationAndUnknownLengthNeverShowAFakePercentage() {
        val progress = CoreInstallProgress(core, read = 3_355_443, total = 6_501_171, installing = true)
        assertEquals("Installing in RetroArch…", progress.text)
        assertNull(progress.fraction)
        assertNull(progress.copy(installing = false, total = 0).fraction)
    }

    @Test fun stalledOrCancelledProgressOffersRetryRatherThanLaunching() {
        val progress = CoreInstallProgress(core, stalled = true)
        assertEquals("Still working…", progress.text)
        assertEquals("Retry", coreInstallActionLabel(progress, true, false, false, "Play"))
        assertEquals("Retry", coreInstallActionLabel(null, false, false, true, "Play"))
    }

    @Test fun confirmedSetupFailureKeepsRetryAvailableAlongsideRuntimeGuidance() {
        assertEquals("Retry", coreInstallActionLabel(null, false, true, false, "Play"))
        assertEquals("Launching…", coreInstallActionLabel(null, true, false, false, "Play"))
        assertEquals("Continue", coreInstallActionLabel(null, false, false, false, "Continue"))
    }
}
