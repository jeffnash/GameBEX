package com.gamebex.client.ui

import com.gamebex.client.MainDispatcherRule
import com.gamebex.client.awaitTrue
import com.gamebex.client.data.CatalogFixtures
import com.gamebex.client.data.local.FakeLibraryDb
import com.gamebex.client.data.local.GameEntity
import com.gamebex.client.net.FakeDownloadController
import com.gamebex.client.sources.FakeRomSource
import com.gamebex.client.sources.FakeSourceAccess
import com.gamebex.client.launch.EmuRouter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LocalDetailViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private fun game() = GameEntity(
        id = 1, title = "Emerald", folder = "gba", volumeId = "sd:x",
        relPath = "gba/emerald.zip", fileName = "emerald.zip",
        sizeBytes = 10, mtimeMs = 20, ext = "zip",
        source = "SOURCE",
        sourceId = "acme", sourceItemKey = "gba/emerald",
        sourceRefJson = kotlinx.serialization.json.Json.encodeToString(
            com.gamebex.source.SourceRef.serializer(),
            CatalogFixtures.ref(sourceId = "acme", itemKey = "gba/emerald"),
        ),
    )

    @Test fun missingPluginDoesNotDisableLocalPlay(): Unit = runBlocking {
        val db = FakeLibraryDb(mutableListOf(game()))
        val access = FakeSourceAccess()
        access.setReady()
        val vm = LocalDetailViewModel(
            database = db, sourceAccess = access, engine = FakeDownloadController(),
        )
        vm.load(1)
        awaitTrue { vm.game.value?.id == 1L }
        assertEquals("Emerald", vm.game.value!!.title)
        // Source-only actions degrade to the install copy; the row stays.
        vm.loadCatalogForGame()
        awaitTrue { vm.catalogBlocked.value != null }
        assertEquals(
            UserErrors.sourceNeededForCatalog("acme"),
            vm.catalogBlocked.value,
        )
        assertEquals(1L, vm.game.value!!.id)
        vm.loadVersions()
        kotlinx.coroutines.delay(200)
        assertEquals(0, vm.versions.value.size)
    }

    @Test fun variantChoiceSurvivesDetailRefresh(): Unit = runBlocking {
        val ref = CatalogFixtures.ref(sourceId = "s", itemKey = "gb/game")
        val f1 = CatalogFixtures.file(ref, "v1", "a.zip")
        val f2 = CatalogFixtures.file(ref, "v2", "b.zip")
        val source = FakeRomSource("s").apply {
            variantsResult = { listOf(f1, f2) }
            detailResult = { CatalogFixtures.detail(CatalogFixtures.entry(sourceId = "s")) }
        }
        val access = FakeSourceAccess()
        access.install(source)
        access.setReady()
        access.select("s")
        val vm = LocalDetailViewModel(
            database = FakeLibraryDb(), sourceAccess = access,
            engine = FakeDownloadController(),
        )
        val entry = CatalogFixtures.entry(sourceId = "s", itemKey = "gb/game", platformId = "gb")
        vm.loadCatalog(entry)
        awaitTrue { vm.catalogFiles.value.size == 2 }
        vm.selectFile(f1)
        vm.loadCatalog(entry)
        kotlinx.coroutines.delay(200)
        assertEquals(f1, vm.selectedFile.value)
        assertEquals(2, vm.catalogFiles.value.size)
    }

    @Test fun progressIsImmediateAndCacheInstallHasAnInstallingPhase() = runTest {
        val vm = LocalDetailViewModel(launchDispatcher = StandardTestDispatcher(testScheduler))
        val n64 = game().copy(folder = "n64")
        var starts = 0
        var emit: ((Long, Long) -> Unit)? = null
        vm.launchGame(n64, launch = { _, _, progress ->
            starts++
            emit = progress
            awaitCancellation()
        })
        assertTrue(vm.launching.value)
        assertEquals("Installing the N64 core…", vm.coreFetch.value?.text)
        runCurrent()
        vm.launchGame(n64, launch = { _, _, _ -> starts++; awaitCancellation() })
        assertEquals(1, starts)
        emit!!(-1, -1)
        assertEquals("Installing in RetroArch…", vm.coreFetch.value?.text)
        assertNull(vm.coreFetch.value?.fraction)
        vm.cancelCoreInstall()
        runCurrent()
        assertFalse(vm.launching.value)
        assertNull(vm.coreFetch.value)
        assertTrue(vm.coreRetryAvailable.value)
        assertTrue(vm.message.value!!.contains("cancelled"))
    }

    @Test fun eightSecondsWithoutForwardProgressOffersRetry() = runTest {
        val vm = LocalDetailViewModel(launchDispatcher = StandardTestDispatcher(testScheduler))
        var emit: ((Long, Long) -> Unit)? = null
        vm.launchGame(game().copy(folder = "n64"), launch = { _, _, progress ->
            emit = progress
            awaitCancellation()
        })
        runCurrent()
        advanceTimeBy(7_999)
        runCurrent()
        assertFalse(vm.coreFetch.value!!.stalled)
        // A duplicate no-byte callback must not restart the stall timer.
        emit!!(0, 0)
        advanceTimeBy(1)
        runCurrent()
        assertTrue(vm.coreFetch.value!!.stalled)
        assertEquals("Still working…", vm.coreFetch.value?.text)
        emit!!(3_355_443, 6_501_171)
        assertFalse(vm.coreFetch.value!!.stalled)
        assertEquals("Downloading 3.2 of 6.2 MB", vm.coreFetch.value?.text)
        emit!!(-1, -1)
        assertEquals(3_355_443L, vm.coreFetch.value?.read)
        assertEquals(6_501_171L, vm.coreFetch.value?.total)
        assertTrue(vm.coreFetch.value!!.installing)
        vm.cancelCoreInstall()
        runCurrent()
    }

    @Test fun retryPreservesGameAndFreshAndFencesCancelledCleanup() = runTest {
        val vm = LocalDetailViewModel(launchDispatcher = StandardTestDispatcher(testScheduler))
        val n64 = game().copy(folder = "n64", coreOverride = "parallel_n64")
        val releaseOld = CompletableDeferred<Unit>()
        val oldCleaningUp = CompletableDeferred<Unit>()
        var starts = 0
        var oldProgress: ((Long, Long) -> Unit)? = null
        var notes = 0
        vm.launchGame(n64, fresh = true, launch = { actual, fresh, progress ->
            assertEquals(n64, actual)
            assertTrue(fresh)
            starts++
            if (starts == 1) {
                oldProgress = progress
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        oldCleaningUp.complete(Unit)
                        releaseOld.await()
                        progress(999, 1000)
                    }
                }
            } else {
                progress(10, 100)
                awaitCancellation()
            }
        }, onLaunched = { _, _ -> notes++ })
        runCurrent()
        advanceTimeBy(8_000)
        runCurrent()
        vm.retryCoreInstall()
        runCurrent()
        assertTrue(oldCleaningUp.isCompleted)
        assertEquals(1, starts) // In-flight gate is released before the new backend starts.
        assertTrue(vm.launching.value)
        assertEquals(0L, vm.coreFetch.value?.read)
        oldProgress!!(900, 1000)
        assertEquals(0L, vm.coreFetch.value?.read)
        assertNull(vm.message.value)
        releaseOld.complete(Unit)
        runCurrent()
        assertEquals(2, starts)
        assertTrue(vm.launching.value)
        assertEquals(10L, vm.coreFetch.value?.read)
        assertFalse(vm.needsRetroArchSetup.value)
        assertEquals(0, notes)
        vm.cancelCoreInstall()
        runCurrent()
    }

    @Test fun timeoutKeepsSetupGuidanceButAnUnreachableGameDoesNotInventSetup() = runTest {
        val vm = LocalDetailViewModel(launchDispatcher = StandardTestDispatcher(testScheduler))
        val n64 = game().copy(folder = "n64")
        val text = "RetroArch did not confirm installation. Open RetroArch and try again."
        var setupAttempts = 0
        vm.launchGame(n64, launch = { _, _, progress ->
            setupAttempts++
            progress(-1, -1)
            EmuRouter.Outcome.NeedsSetup(text)
        })
        runCurrent()
        assertFalse(vm.launching.value)
        assertNull(vm.coreFetch.value)
        assertTrue(vm.needsRetroArchSetup.value)
        assertEquals(text, vm.message.value)
        vm.retryCoreInstall()
        assertTrue(vm.launching.value)
        runCurrent()
        assertEquals(2, setupAttempts)
        assertTrue(vm.needsRetroArchSetup.value)
        vm.launchGame(n64, launch = { _, _, _ -> EmuRouter.Outcome.Failed("Game files aren't reachable right now (SD card?).") })
        runCurrent()
        assertFalse(vm.needsRetroArchSetup.value)
        assertTrue(vm.message.value!!.contains("aren't reachable"))
    }

    @Test fun successClearsProgressOnlyAfterConfirmedLaunchAndNotesTheSameGame() = runTest {
        val vm = LocalDetailViewModel(launchDispatcher = StandardTestDispatcher(testScheduler))
        val result = CompletableDeferred<EmuRouter.Outcome>()
        var noted: Long? = null
        vm.launchGame(game().copy(folder = "n64"), fresh = true,
            launch = { _, _, progress -> progress(-1, -1); result.await() },
            onLaunched = { actual, _ -> noted = actual.id },
        )
        runCurrent()
        assertTrue(vm.launching.value)
        assertNull(noted)
        assertEquals("Installing in RetroArch…", vm.coreFetch.value?.text)
        result.complete(EmuRouter.Outcome.Launched("RetroArch", "com.retroarch.aarch64", coreFetched = true))
        runCurrent()
        assertEquals(1L, noted)
        assertFalse(vm.launching.value)
        assertNull(vm.coreFetch.value)
        assertTrue(vm.message.value!!.startsWith("Started fresh"))
    }

    @Test fun cancelledBackendCannotPublishALateSetupOutcome() = runTest {
        val vm = LocalDetailViewModel(launchDispatcher = StandardTestDispatcher(testScheduler))
        val release = CompletableDeferred<Unit>()
        val cleaningUp = CompletableDeferred<Unit>()
        var attempts = 0
        vm.launchGame(game().copy(folder = "n64"), launch = { _, _, _ ->
            attempts++
            if (attempts == 1) {
                try {
                    awaitCancellation()
                } catch (_: kotlinx.coroutines.CancellationException) {
                    withContext(NonCancellable) {
                        cleaningUp.complete(Unit)
                        release.await()
                    }
                    EmuRouter.Outcome.NeedsSetup("Stale setup failure")
                }
            } else awaitCancellation()
        })
        runCurrent()
        vm.cancelCoreInstall()
        runCurrent()
        assertTrue(cleaningUp.isCompleted)
        vm.retryCoreInstall()
        runCurrent()
        release.complete(Unit)
        runCurrent()
        assertEquals(2, attempts)
        assertTrue(vm.launching.value)
        assertNull(vm.message.value)
        assertFalse(vm.needsRetroArchSetup.value)
        assertEquals("Installing the N64 core…", vm.coreFetch.value?.text)
        vm.cancelCoreInstall()
        runCurrent()
    }

    @Test fun standaloneLaunchDoesNotPretendToInstallARetroArchCore() = runTest {
        val vm = LocalDetailViewModel(launchDispatcher = StandardTestDispatcher(testScheduler))
        vm.launchGame(game().copy(folder = "n64", emulatorOverride = "org.ppsspp.ppsspp"),
            launch = { _, _, _ -> EmuRouter.Outcome.NeedsSetup("Open the emulator to finish setup.") },
        )
        assertNull(vm.coreFetch.value)
        runCurrent()
        assertFalse(vm.needsRetroArchSetup.value)
    }

    @Test fun catalogBlockedOnMissingSource(): Unit = runBlocking {
        val access = FakeSourceAccess()
        access.setReady()
        val vm = LocalDetailViewModel(
            database = FakeLibraryDb(), sourceAccess = access,
            engine = FakeDownloadController(),
        )
        vm.loadCatalog(CatalogFixtures.entry(sourceId = "gone", itemKey = "gb/game"))
        awaitTrue { vm.catalogBlocked.value != null }
        assertEquals(
            UserErrors.sourceNeededForCatalog("gone"),
            vm.catalogBlocked.value,
        )
    }
}
