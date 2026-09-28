package com.gamebex.client.launch

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gamebex.client.data.local.GameEntity
import com.gamebex.client.data.local.LibraryDb
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EmuRouterPreflightTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val db by lazy { Room.inMemoryDatabaseBuilder(ctx, LibraryDb::class.java).allowMainThreadQueries().build() }

    @After fun closeDb() = db.close()

    @Test fun unavailableGameStopsWithReachabilityMessage() = runBlocking {
        val result = EmuRouter.preflight(ctx, game(available = false), db)
        assertEquals(EmuRouter.Preflight.Stop(EmuRouter.Outcome.Failed("Game files aren't reachable right now (SD card?).")), result)
    }

    @Test fun failedPrepStopsWithNote() = runBlocking {
        val result = EmuRouter.preflight(ctx, game(prepState = "FAILED", prepNote = "Needs repair"), db)
        assertEquals(EmuRouter.Preflight.Stop(EmuRouter.Outcome.Blocked("Needs repair")), result)
    }

    @Test fun normalGamePasses() = runBlocking {
        assertTrue(EmuRouter.preflight(ctx, game(), db) is EmuRouter.Preflight.Ok)
    }

    private fun game(
        available: Boolean = true,
        prepState: String = "NONE",
        prepNote: String? = null,
    ) = GameEntity(
        title = "Test", folder = "snes", volumeId = "internal", relPath = "snes/test.sfc",
        fileName = "test.sfc", sizeBytes = 1, mtimeMs = 1, ext = "sfc",
        available = available, prepState = prepState, prepNote = prepNote,
    )
}
