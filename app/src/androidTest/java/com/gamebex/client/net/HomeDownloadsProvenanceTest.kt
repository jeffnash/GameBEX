package com.gamebex.client.net

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gamebex.client.data.local.GameEntity
import com.gamebex.client.data.local.LibraryDb
import com.gamebex.client.sources.SourceAccess
import com.gamebex.client.sources.SourceSelection
import com.gamebex.client.ui.home.model.DownloadStart
import com.gamebex.client.ui.home.model.HomeCopy
import com.gamebex.source.DownloadFile
import com.gamebex.source.RomEntry
import com.gamebex.source.RomSource
import com.gamebex.source.SourceHttp
import com.gamebex.source.SourceRef
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeDownloadsProvenanceTest {
    private val json = Json { ignoreUnknownKeys = true }

    private class RecordingDownloadController : DownloadController {
        val calls = mutableListOf<String>()
        private val _jobs = MutableStateFlow<Map<Long, DownloadEngine.DlSnapshot>>(emptyMap())
        override val jobs: StateFlow<Map<Long, DownloadEngine.DlSnapshot>> = _jobs

        override suspend fun enqueueSource(
            entry: RomEntry,
            file: DownloadFile,
            description: String?,
            targetFileName: String,
        ): Long {
            calls += targetFileName
            return 1L
        }

        override fun pause(id: Long) = Unit
        override fun resume(id: Long) = Unit
        override fun cancel(id: Long) = Unit
        override fun delete(id: Long) = Unit
    }

    private class UntouchedSourceAccess : SourceAccess {
        override val ready: StateFlow<Boolean> = MutableStateFlow(false)
        override val selected: StateFlow<SourceSelection?> = MutableStateFlow(null)
        override suspend fun open(id: String, revision: String?): RomSource =
            error("source $id must not be opened on this path")

        override fun installedRevision(id: String): String? = null
        override suspend fun httpFor(id: String, revision: String?): SourceHttp =
            error("source $id must not be fetched on this path")

        override fun isAvailable(id: String, revision: String?): Boolean = false
    }

    private val engine = RecordingDownloadController()
    private val sources = UntouchedSourceAccess()

    private fun db(): LibraryDb = Room.inMemoryDatabaseBuilder(
        InstrumentationRegistry.getInstrumentation().targetContext,
        LibraryDb::class.java,
    ).build()

    private fun tombstone(db: LibraryDb, refJson: String?, variantJson: String? = null): Long =
        runBlocking {
            db.games().upsert(
                GameEntity(
                    title = "Tetris", folder = "gb", volumeId = "internal",
                    relPath = "gb/tetris.gb", fileName = "tetris.gb",
                    sizeBytes = 32, mtimeMs = 1, ext = "gb",
                    source = "SOURCE", addedAtMs = 1, available = false,
                    sourceRefJson = refJson, sourceVariantJson = variantJson,
                ),
            )
        }

    private fun ref() = SourceRef(
        sourceId = "acme", itemKey = "gb/tetris",
        pageUrl = "https://acme.com/roms/gb/tetris",
    )

    @Test
    fun corruptSourceRecordSaysDownloadInfoUnreadableNotPluginRemoved() {
        val db = db()
        try {
            val id = tombstone(db, refJson = "{not json")
            val out = runBlocking {
                HomeDownloadsImpl(engine, sources, db).downloadAgain(id)
            }
            assertEquals(
                "a bad record reports a read error, never 'plugin removed'",
                DownloadStart.Failed("Couldn't read this game's download info"),
                out,
            )
            assertTrue("no download is enqueued from a bad record", engine.calls.isEmpty())
        } finally {
            db.close()
        }
    }

    @Test
    fun missingSourceRecordStillSaysPluginRemoved() {
        val db = db()
        try {
            val id = tombstone(db, refJson = null)
            val out = runBlocking {
                HomeDownloadsImpl(engine, sources, db).downloadAgain(id)
            }
            assertEquals(DownloadStart.Failed(HomeCopy.sourceGone), out)
        } finally {
            db.close()
        }
    }

    @Test
    fun validSourceRecordStillRequeuesTheDownload() {
        // Enqueues onto the library's old file name, not the variant's.
        val db = db()
        try {
            val variant = DownloadFile(
                ref = ref(), variantKey = "https://acme.com/dl/1",
                name = "tetris.zip", kind = "ROM", size = "1 MB",
                pageUrl = "https://acme.com/dl/1",
            )
            val id = tombstone(
                db,
                refJson = json.encodeToString(SourceRef.serializer(), ref()),
                variantJson = json.encodeToString(DownloadFile.serializer(), variant),
            )
            val out = runBlocking {
                HomeDownloadsImpl(engine, sources, db).downloadAgain(id)
            }
            assertEquals(DownloadStart.Started(1L), out)
            assertEquals(listOf("tetris.gb"), engine.calls)
        } finally {
            db.close()
        }
    }
}
