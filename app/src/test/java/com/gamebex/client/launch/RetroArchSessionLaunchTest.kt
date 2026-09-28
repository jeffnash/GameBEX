package com.gamebex.client.launch

import com.gamebex.client.together.TogetherCopy
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A RetroArch session launch fires only after RetroArch was really force-stopped. */
class RetroArchSessionLaunchTest {
    @Test fun aFailedForceStopNeverFiresRetroArch() = runBlocking {
        var wrote = 0
        var fired = 0
        val outcome = EmuRouter.launchRetroArchSession(
            stop = { false },
            writeConfig = { wrote++; File("cfg") },
            fire = { fired++; EmuRouter.Outcome.Launched("RetroArch", "com.retroarch.aarch64") },
        )
        assertEquals(EmuRouter.Outcome.NeedsSetup(TogetherCopy.shizukuRequiredRa), outcome)
        assertEquals(0, fired)
        assertEquals(0, wrote)
    }

    @Test fun aSuccessfulForceStopWritesTheConfigThenFires() = runBlocking {
        val calls = mutableListOf<String>()
        val outcome = EmuRouter.launchRetroArchSession(
            stop = { calls += "stop"; true },
            writeConfig = { calls += "config"; File("cfg") },
            fire = { calls += "fire:${it.name}"; EmuRouter.Outcome.Launched("RetroArch", "com.retroarch.aarch64") },
        )
        assertTrue(outcome is EmuRouter.Outcome.Launched)
        assertEquals(listOf("stop", "config", "fire:cfg"), calls)
    }

    @Test fun anUnwritableConfigFailsWithoutFiring() = runBlocking {
        var fired = 0
        val outcome = EmuRouter.launchRetroArchSession(stop = { true }, writeConfig = { null }, fire = { fired++; EmuRouter.Outcome.Launched("x", "y") })
        assertEquals(EmuRouter.Outcome.Failed(TogetherCopy.configUnwritable), outcome)
        assertEquals(0, fired)
    }
}
