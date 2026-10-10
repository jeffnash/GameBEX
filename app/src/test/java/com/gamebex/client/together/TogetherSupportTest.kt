package com.gamebex.client.together

import com.gamebex.client.data.PlatformRegistry
import com.gamebex.client.data.Route
import com.gamebex.client.data.local.GameEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import com.gamebex.client.data.Emu
import org.junit.Test

class TogetherSupportTest {
    private fun game(folder: String, coreOverride: String? = null, available: Boolean = true, deletedAtMs: Long = 0, emulatorOverride: String? = null) =
        GameEntity(title = "Test", folder = folder, volumeId = "v", relPath = "r", fileName = "x", sizeBytes = 1, mtimeMs = 1, ext = "bin", coreOverride = coreOverride, available = available, deletedAtMs = deletedAtMs, emulatorOverride = emulatorOverride)

    @Test fun supportedModes() {
        assertEquals(TogetherMode.RA_NETPLAY, TogetherSupport.modeFor(game("snes")))
        assertEquals(TogetherMode.STREAM, TogetherSupport.modeFor(game("snes", coreOverride = "bsnes")))
        assertEquals(TogetherMode.PSP_ADHOC, TogetherSupport.modeFor(game("psp")))
        assertEquals(TogetherMode.GB_LINK, TogetherSupport.modeFor(game("gb", coreOverride = "gambatte")))
        assertEquals(TogetherMode.GB_LINK, TogetherSupport.modeFor(game("gbc", coreOverride = "gambatte")))
        assertEquals(TogetherMode.STREAM, TogetherSupport.modeFor(game("gba")))
        assertEquals(TogetherMode.STREAM, TogetherSupport.modeFor(game("ps2")))
        assertNull(TogetherSupport.modeFor(game("snes", available = false)))
        assertNull(TogetherSupport.modeFor(game("snes", deletedAtMs = 1)))
    }

    // Every system whose host emulator Stream can run offers
    // Stream; native modes stay first (default), Stream is the alternate.
    @Test fun streamOfferedForEveryPlayableFolder() {
        for (platform in PlatformRegistry.all) {
            val modes = TogetherSupport.modesFor(game(platform.folder))
            val playable = platform.route is Route.RetroCore ||
                platform.route is Route.Standalone ||
                platform.route is Route.GuidedInstall
            if (playable) {
                assertTrue(platform.folder, TogetherMode.STREAM in modes)
                val native = TogetherSupport.nativeModeFor(game(platform.folder))
                if (native == null) assertEquals(platform.folder, listOf(TogetherMode.STREAM), modes)
                else assertEquals(platform.folder, native, modes.first())
            } else {
                assertFalse(platform.folder, TogetherMode.STREAM in modes)
            }
        }
        // Native systems keep the default with Stream as the switch target.
        assertEquals(
            listOf(TogetherMode.PSP_ADHOC, TogetherMode.STREAM),
            TogetherSupport.modesFor(game("psp")),
        )
        assertEquals(
            listOf(TogetherMode.GB_LINK, TogetherMode.STREAM),
            TogetherSupport.modesFor(game("gb", coreOverride = "gambatte")),
        )
        assertEquals(
            listOf(TogetherMode.RA_NETPLAY, TogetherMode.STREAM),
            TogetherSupport.modesFor(game("snes")),
        )
        assertEquals(emptyList<TogetherMode>(), TogetherSupport.modesFor(game("n64", available = false)))
        assertEquals(emptyList<TogetherMode>(), TogetherSupport.modesFor(game("no-such-system")))
    }

    // Nothing is verified yet; a verified native mode would come first.
    @Test fun verifiedNativeModesAreEmptyUntilTheOwnerApproves() {
        assertTrue(TogetherCaps.VERIFIED_NATIVE.isEmpty())
        assertNull(TogetherSupport.nativeModeFor(game("psx")))
    }

    @Test fun matchFoldersByCore() {
        assertEquals(setOf("segacd", "gamegear", "genesis", "mastersystem", "sg-1000"), TogetherSupport.matchFolders(TogetherMode.RA_NETPLAY, "genesis"))
        assertEquals(setOf("mame", "neogeo"), TogetherSupport.matchFolders(TogetherMode.RA_NETPLAY, "mame"))
    }

    @Test fun coreForHonorsRetroArchAlternate() {
        assertEquals("snes9x", TogetherSupport.coreFor(game("snes"), TogetherMode.RA_NETPLAY))
        // GC defaults to the Dolphin app: no RA core without the override.
        assertNull(TogetherSupport.coreFor(game("gc"), TogetherMode.RA_NETPLAY))
        assertEquals("dolphin", TogetherSupport.coreFor(game("gc", emulatorOverride = Emu.RETROARCH.pkg), TogetherMode.RA_NETPLAY))
        assertEquals("dolphin", TogetherSupport.coreFor(game("wii", emulatorOverride = Emu.RETROARCH.pkg), TogetherMode.RA_NETPLAY))
        assertNull(TogetherSupport.coreFor(game("psp"), TogetherMode.RA_NETPLAY))
        assertEquals("ppsspp", TogetherSupport.coreFor(game("psp", emulatorOverride = Emu.RETROARCH.pkg), TogetherMode.RA_NETPLAY))
        // A Standalone platform without an RA alternate never resolves one.
        assertNull(TogetherSupport.coreFor(game("ps2", emulatorOverride = Emu.RETROARCH.pkg), TogetherMode.RA_NETPLAY))
    }

    @Test fun safeCoreBuildRejectsUnsafeValues() {
        val hash = "a".repeat(64)
        val valid = CoreBuild("snes9x", hash, 1)
        assertTrue(TogetherSupport.isSafeCoreBuild(valid, "snes9x"))
        assertFalse(TogetherSupport.isSafeCoreBuild(valid.copy(core = "../x"), "snes9x"))
        assertFalse(TogetherSupport.isSafeCoreBuild(valid.copy(sha256 = "A".repeat(64)), "snes9x"))
        assertFalse(TogetherSupport.isSafeCoreBuild(valid.copy(sha256 = "a".repeat(63)), "snes9x"))
        assertFalse(TogetherSupport.isSafeCoreBuild(valid.copy(sizeBytes = 0), "snes9x"))
        assertFalse(TogetherSupport.isSafeCoreBuild(valid.copy(sizeBytes = com.gamebex.client.launch.RetroCoreFetcher.MAX_CORE_ZIP_BYTES + 1), "snes9x"))
        assertFalse(TogetherSupport.isSafeCoreBuild(valid.copy(core = "gambatte"), "snes9x"))
    }

    @Test fun nullProbeWithoutALedgerEntryNeedsTheCore() {
        assertEquals(Readiness.NeedsCore("gambatte"), raReadiness(probed = null, ledgerVerified = false))
    }

    @Test fun nullProbeWithAVerifiedLedgerEntryIsReady() {
        assertEquals(Readiness.Ready, raReadiness(probed = null, ledgerVerified = true))
    }

    @Test fun probeWithoutTheCoreNeedsItEvenWhenTheLedgerHasIt() {
        assertEquals(Readiness.NeedsCore("gambatte"), raReadiness(probed = setOf("snes9x"), ledgerVerified = true))
        assertEquals(Readiness.Ready, raReadiness(probed = setOf("Gambatte"), ledgerVerified = false))
    }

    @Test fun launchConfirmsACoreOnlyThroughTheProbeOrTheLedger() {
        assertFalse(TogetherSupport.coreConfirmed("gambatte", null) { false })
        assertTrue(TogetherSupport.coreConfirmed("gambatte", null) { true })
        assertFalse(TogetherSupport.coreConfirmed("gambatte", emptySet()) { true })
        assertTrue(TogetherSupport.coreConfirmed("gambatte", setOf("gambatte")) { false })
    }

    // RetroArch that was never opened can't be launched directly.
    @Test fun retroArchWithoutFileAccessNeedsOpenOnce() {
        assertEquals(
            Readiness.NeedsOpenOnce(com.gamebex.client.data.Emu.RETROARCH),
            raReadiness(needsFirstRun = true, probed = setOf("gambatte"), ledgerVerified = true),
        )
        assertEquals(Readiness.Blocked("prep"), raReadiness(preflightStop = "prep", probed = setOf("gambatte"), ledgerVerified = true))
    }

    private fun raReadiness(
        needsFirstRun: Boolean = false,
        preflightStop: String? = null,
        probed: Set<String>?,
        ledgerVerified: Boolean,
    ): Readiness = TogetherSupport.retroArchReadiness(needsFirstRun, preflightStop, "gambatte", probed) { ledgerVerified }

    // A RetroArch netplay host offers its core build, so GameBEX must know it.
    @Test fun netplayHostNeedsACoreBuildGameBexInstalled() {
        val probedOnly = TogetherSupport.retroArchReadiness(false, null, "snes9x", setOf("snes9x"), knownBuildRequired = true) { false }
        assertEquals(Readiness.NeedsCore("snes9x"), probedOnly)
        val recorded = TogetherSupport.retroArchReadiness(false, null, "snes9x", setOf("snes9x"), knownBuildRequired = true) { true }
        assertEquals(Readiness.Ready, recorded)
        // Everyone else keeps trusting the probe.
        assertEquals(Readiness.Ready, TogetherSupport.retroArchReadiness(false, null, "snes9x", setOf("snes9x")) { false })
        // The probe still wins when it says the core is missing.
        assertEquals(Readiness.NeedsCore("snes9x"), TogetherSupport.retroArchReadiness(false, null, "snes9x", emptySet(), knownBuildRequired = true) { true })
    }

    // RetroArch builds before 2026-07-28 crash as the netplay client (upstream 5fa3573d88).
    @Test fun netplayNeedsARetroArchBuiltAfterTheClientCrashFix() {
        val stable = 1763607214L  // 1.22.2, built 2025-11-20
        assertEquals(Readiness.NeedsUpdate(Emu.RETROARCH), TogetherSupport.raNetplayBuildGate(TogetherMode.RA_NETPLAY, stable))
        assertNull(TogetherSupport.raNetplayBuildGate(TogetherMode.RA_NETPLAY, 1790521311L))
        // Game Boy link never touches netplay; an unknown version never blocks.
        assertNull(TogetherSupport.raNetplayBuildGate(TogetherMode.GB_LINK, stable))
        assertNull(TogetherSupport.raNetplayBuildGate(TogetherMode.RA_NETPLAY, null))
        assertEquals(
            Problem(TogetherCopy.updateEmulator("RetroArch"), TogetherAction.OPEN_SYSTEM),
            TogetherCopy.problemFor(Readiness.NeedsUpdate(Emu.RETROARCH)),
        )
    }

    // RA modes need Shizuku to really restart RetroArch before a session launch.
    @Test fun retroArchModesNeedShizukuRunningAndAllowed() {
        assertTrue(TogetherCaps.RA_RESTART_NEEDS_SHIZUKU)
        for (mode in listOf(TogetherMode.RA_NETPLAY, TogetherMode.GB_LINK)) {
            assertEquals(Readiness.NeedsShizuku(false), TogetherSupport.raShizukuGate(mode, com.gamebex.client.launch.ShizukuFiles.State.NOT_RUNNING))
            assertEquals(Readiness.NeedsShizuku(true), TogetherSupport.raShizukuGate(mode, com.gamebex.client.launch.ShizukuFiles.State.NOT_GRANTED))
            assertEquals(Readiness.Blocked(TogetherCopy.shizukuRequiredRa), TogetherSupport.raShizukuGate(mode, com.gamebex.client.launch.ShizukuFiles.State.UNAVAILABLE))
            assertNull(TogetherSupport.raShizukuGate(mode, com.gamebex.client.launch.ShizukuFiles.State.READY))
        }
        assertNull(TogetherSupport.raShizukuGate(TogetherMode.PSP_ADHOC, com.gamebex.client.launch.ShizukuFiles.State.NOT_RUNNING))
    }
}
