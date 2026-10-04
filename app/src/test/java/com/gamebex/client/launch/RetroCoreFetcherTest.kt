package com.gamebex.client.launch

import android.content.ContextWrapper
import android.content.Intent
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Core auto-fetch pure pieces: URL shape and .so picking. */
class RetroCoreFetcherTest {
    @Test fun coreZipUrlPointsAtNightlyArm64() {
        assertEquals(
            "https://buildbot.libretro.com/nightly/android/latest/arm64-v8a/mupen64plus_next_gles3_libretro_android.so.zip",
            RetroCoreFetcher.coreZipUrl("mupen64plus_next_gles3"),
        )
    }

    @Test fun pickSoEntryPrefersExact() {
        val names = listOf(
            "mupen64plus_next_gles3_libretro_android.so",
            "mupen64plus_next_gles3_libretro.info",
        )
        assertEquals(
            "mupen64plus_next_gles3_libretro_android.so",
            RetroCoreFetcher.pickSoEntry(names, "mupen64plus_next_gles3"),
        )
    }

    @Test fun pickSoEntryIgnoresDirectoryPrefix() {
        val names = listOf("cores/mupen64plus_next_gles3_libretro_android.so")
        assertEquals(
            "cores/mupen64plus_next_gles3_libretro_android.so",
            RetroCoreFetcher.pickSoEntry(names, "mupen64plus_next_gles3"),
        )
    }

    @Test fun pickSoEntryFallsBackToLoneSo() {
        assertEquals(
            "other_libretro_android.so",
            RetroCoreFetcher.pickSoEntry(listOf("other_libretro_android.so"), "mupen64plus_next_gles3"),
        )
    }

    @Test fun pickSoEntryRejectsAmbiguousAndEmpty() {
        assertNull(
            RetroCoreFetcher.pickSoEntry(
                listOf("a_libretro_android.so", "b_libretro_android.so"),
                "mupen64plus_next_gles3",
            ),
        )
        assertNull(RetroCoreFetcher.pickSoEntry(emptyList(), "mupen64plus_next_gles3"))
    }

    @Test fun elfHeaderDetectsMagic() {
        assertTrue(RetroCoreFetcher.hasElfHeader(byteArrayOf(0x7F, 0x45, 0x4C, 0x46, 2, 1, 1, 0)))
        assertFalse(RetroCoreFetcher.hasElfHeader("PK\u0003\u0004".toByteArray())) // zip
        assertFalse(RetroCoreFetcher.hasElfHeader("<htm".toByteArray())) // error page
        assertFalse(RetroCoreFetcher.hasElfHeader(byteArrayOf(0x7F, 0x45)))
    }

    @Test fun usableCoreNeedsSizeAndElf() {
        val elf = byteArrayOf(0x7F, 0x45, 0x4C, 0x46)
        assertTrue(RetroCoreFetcher.isUsableCore(100_001, elf))
        assertFalse(RetroCoreFetcher.isUsableCore(100_001, "oops".toByteArray())) // big but not ELF
        assertFalse(RetroCoreFetcher.isUsableCore(100_001, null)) // unreadable header
        assertFalse(RetroCoreFetcher.isUsableCore(50_000, elf)) // small even with ELF
        assertFalse(RetroCoreFetcher.isUsableCore(100_000, elf)) // boundary is exclusive
    }

    @Test fun detectsAarch64ElfOnly() {
        val arm = ByteArray(20).also {
            it[0] = 0x7F
            it[1] = 0x45
            it[2] = 0x4C
            it[3] = 0x46
            it[18] = 0xB7.toByte()
        }
        val x86 = arm.copyOf().also { it[18] = 0x3E }
        assertTrue(RetroCoreFetcher.isAarch64Elf(arm))
        assertFalse(RetroCoreFetcher.isAarch64Elf(x86))
        assertFalse(RetroCoreFetcher.isAarch64Elf(arm.copyOf(19)))
    }

    @Test fun gles3BaseNameParses() {
        assertEquals(
            "mupen64plus_next_gles3",
            RetroCoreProbe.coreBaseName("mupen64plus_next_gles3_libretro_android.so"),
        )
    }

    @Test fun urlHostNormalizesForRedirectPolicy() {
        assertEquals(
            "buildbot.libretro.com",
            RetroCoreFetcher.urlHost("https://BuildBot.Libretro.com/nightly/x.zip"),
        )
        assertEquals("evil.test", RetroCoreFetcher.urlHost("https://evil.test:8443/y.zip?z=1"))
        assertNull(RetroCoreFetcher.urlHost("not a url"))
    }

    @Test fun coreZipCapIsSane() {
        // Cores ship ~1-30 MB zipped; the cap must bound RAM/disk abuse
        // without rejecting real packages.
        assertTrue(RetroCoreFetcher.MAX_CORE_ZIP_BYTES >= 50L * 1024 * 1024)
        assertTrue(RetroCoreFetcher.MAX_CORE_ZIP_BYTES <= 256L * 1024 * 1024)
    }

    @Test fun ensureCoreRefusesMissingRomAndSideloadNeverAttachesRom() = runBlocking {
        val ctx = object : ContextWrapper(null) {
            var started = 0
            override fun startActivity(intent: Intent) { started += 1 }
        }
        val missing = File(createTempDirectory("ensure-core-rom-gate").toFile(), "gone.sfc")
        assertEquals(
            RetroCoreFetcher.Result.Failed("ROM file is no longer available."),
            RetroCoreFetcher.ensureCore(ctx, "snes9x", null),
        )
        assertEquals(
            RetroCoreFetcher.Result.Failed("ROM file is no longer available."),
            RetroCoreFetcher.ensureCore(ctx, "snes9x", missing),
        )
        assertEquals(0, ctx.started)
        val so = File("/stage/snes9x_libretro_android.so")
        val intent = object : Intent() {
            val sideloadExtras = linkedMapOf<String, String>()
            override fun putExtra(name: String, value: String?): Intent {
                if (value != null) sideloadExtras[name] = value
                return this
            }
        }
        RetroCoreFetcher.applySideloadExtras(intent, so)
        assertEquals(mapOf("LIBRETRO" to so.absolutePath), intent.sideloadExtras)
        assertFalse(intent.sideloadExtras.containsKey("ROM"))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun installConfirmsOnlyAfterDelayedMatchingProbeAndThenRecordsOnce() = runTest {
        var queries = 0
        var ledgerWrites = 0
        val starts = mutableListOf<Long>()
        val confirmed = RetroCoreFetcher.verifyCoreInstall(
            core = "snes9x",
            nowMs = { testScheduler.currentTime },
            probe = { timeout ->
                assertEquals(1_000L, timeout)
                assertEquals(0, ledgerWrites)
                starts += testScheduler.currentTime
                when (++queries) {
                    1 -> null
                    2 -> emptySet()
                    3 -> setOf("gambatte")
                    else -> setOf("SNES9X")
                }
            },
            onConfirmed = { ledgerWrites++ },
        )
        assertTrue(confirmed)
        assertEquals(listOf(0L, 1_000L, 2_000L, 3_000L), starts)
        assertEquals(1, ledgerWrites)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun installTimeoutNeverRecordsOrTreatsEmptyProbeAsSuccess() = runTest {
        var queries = 0
        var ledgerWrites = 0
        val confirmed = RetroCoreFetcher.verifyCoreInstall(
            core = "snes9x",
            nowMs = { testScheduler.currentTime },
            probe = {
                if (++queries % 2 == 0) emptySet() else null
            },
            onConfirmed = { ledgerWrites++ },
        )
        assertFalse(confirmed)
        assertEquals(20, queries)
        assertEquals(20_000L, testScheduler.currentTime)
        assertEquals(0, ledgerWrites)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun nonresponsiveQueriesAreIndividuallyBoundedToOneSecond() = runTest {
        val starts = mutableListOf<Long>()
        var ledgerWrites = 0
        val confirmed = RetroCoreFetcher.verifyCoreInstall(
            core = "snes9x",
            nowMs = { testScheduler.currentTime },
            probe = { timeout ->
                assertTrue(timeout in 1L..1_000L)
                starts += testScheduler.currentTime
                delay(60_000L)
                setOf("snes9x")
            },
            onConfirmed = { ledgerWrites++ },
        )
        assertFalse(confirmed)
        assertEquals((0 until 20).map { it * 1_000L }, starts)
        assertEquals(20_000L, testScheduler.currentTime)
        assertEquals(0, ledgerWrites)
    }

    @Test fun matchingReplyAtDeadlineStillCannotPublishLedger() = runBlocking {
        var now = 0L
        var ledgerWrites = 0
        val confirmed = RetroCoreFetcher.verifyCoreInstall(
            core = "snes9x",
            nowMs = { now },
            probe = {
                now = RetroCoreFetcher.INSTALL_TIMEOUT_MS
                setOf("snes9x")
            },
            onConfirmed = { ledgerWrites++ },
        )
        assertFalse(confirmed)
        assertEquals(0, ledgerWrites)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun cancellationDuringInstallPollPropagatesAndNeverRecords() = runTest {
        var queries = 0
        var ledgerWrites = 0
        var returned = false
        var cancellationCaught = false
        val job = launch {
            try {
                RetroCoreFetcher.verifyCoreInstall(
                    core = "snes9x",
                    nowMs = { testScheduler.currentTime },
                    probe = { queries++; emptySet() },
                    onConfirmed = { ledgerWrites++ },
                )
                returned = true
            } catch (e: CancellationException) {
                cancellationCaught = true
                throw e
            }
        }
        runCurrent()
        assertEquals(1, queries)
        job.cancelAndJoin()
        assertTrue(cancellationCaught)
        assertFalse(returned)
        assertEquals(0, ledgerWrites)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun cancellationDuringProbePropagatesRatherThanBecomingTimeout() = runTest {
        var ledgerWrites = 0
        var probing = false
        var returned = false
        val job = launch {
            RetroCoreFetcher.verifyCoreInstall(
                core = "snes9x",
                nowMs = { testScheduler.currentTime },
                probe = {
                    probing = true
                    delay(60_000L)
                    setOf("snes9x")
                },
                onConfirmed = { ledgerWrites++ },
            )
            returned = true
        }
        runCurrent()
        assertTrue(probing)
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertFalse(returned)
        assertEquals(0, ledgerWrites)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun cancellingInMatchingProbeDoesNotPublishLedger() = runTest {
        var ledgerWrites = 0
        var returned = false
        val job = launch {
            RetroCoreFetcher.verifyCoreInstall(
                core = "snes9x",
                nowMs = { testScheduler.currentTime },
                probe = {
                    kotlinx.coroutines.currentCoroutineContext().cancel()
                    setOf("snes9x")
                },
                onConfirmed = { ledgerWrites++ },
            )
            returned = true
        }
        job.join()
        assertTrue(job.isCancelled)
        assertFalse(returned)
        assertEquals(0, ledgerWrites)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun foregroundReturnWaitsUntilSideloadCanStart() = runTest {
        var background = false
        var returnedAt = -1L
        RetroCoreFetcher.restoreAfterSideloadStart(
            waitForBackground = { delay(350L); background = true },
            restore = { assertTrue(background); returnedAt = testScheduler.currentTime },
        )
        assertEquals(550L, returnedAt)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun missingPauseSignalCannotStrandProgressForegroundReturn() = runTest {
        var returnedAt = -1L
        RetroCoreFetcher.restoreAfterSideloadStart(
            waitForBackground = { delay(60_000L) },
            restore = { returnedAt = testScheduler.currentTime },
        )
        assertEquals(2_200L, returnedAt)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun cancelledLaunchWaitDoesNotReturnAsSuccessfulInstall() = runTest {
        var restored = false
        val job = launch {
            RetroCoreFetcher.restoreAfterSideloadStart(
                waitForBackground = { delay(60_000L) },
                restore = { restored = true },
            )
        }
        runCurrent()
        job.cancelAndJoin()
        assertFalse(restored)
        assertTrue(job.isCancelled)
    }

    @Test fun accessibleMissingSetupPathsOnlyAndUnknownPrivatePathsDoNotBlock() {
        assertFalse(RetroCoreProbe.setupPathsMissing(null as List<String>?))
        assertFalse(RetroCoreProbe.setupPathsMissing(listOf("cores", "assets")))
        assertTrue(RetroCoreProbe.setupPathsMissing(emptyList()))
        assertTrue(RetroCoreProbe.setupPathsMissing(listOf("assets")))
        assertTrue(RetroCoreProbe.setupPathsMissing(listOf("cores")))
    }

    @Test fun installTimeoutGuidanceExplicitlyOpensRetroArchSetup() {
        assertEquals(
            "RetroArch couldn't install the core. Open RetroArch once to finish its setup, then try again.",
            RetroCoreFetcher.INSTALL_SETUP_GUIDANCE,
        )
    }
}
