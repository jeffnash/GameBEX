package com.gamebex.client.data.local

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.migration.Migration
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/** One playable file in the library. Identity = volume + relative path. */
@Entity(
    tableName = "games",
    indices = [
        Index(value = ["volumeId", "relPath"], unique = true),
        Index(value = ["available", "folder"]),
        Index(value = ["artSource"]),
        Index(value = ["sourceId", "sourceItemKey"]),
    ],
)
data class GameEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val folder: String,
    val volumeId: String,
    val relPath: String,
    val fileName: String,
    val sizeBytes: Long,
    val mtimeMs: Long,
    val ext: String,
    val coverPath: String? = null,
    val coverUrl: String? = null,
    val description: String? = null,
    /** USER (imported file) or SOURCE (downloaded through a source). */
    val source: String = "USER",
    val favorite: Boolean = false,
    val playCount: Int = 0,
    val lastPlayedMs: Long = 0,
    val emulatorOverride: String? = null,
    val coreOverride: String? = null,
    val addedAtMs: Long = 0,
    val available: Boolean = true,
    // Multi-art: absolute local paths in ArtStore, or null.
    val artBox: String? = null,
    val artHero: String? = null,
    val artLogo: String? = null,
    val artShot: String? = null,
    /** NONE, SS (ScreenScraper), USER (imported), SOURCE:<id>, MIXED. */
    val artSource: String = "NONE",
    /** ScreenScraper game id, 0 = unknown. */
    val ssGameId: Long = 0,
    // Preparation inputs.
    /** NONE, READY, NEEDS_BIOS, NEEDS_MANUAL, FAILED. */
    val prepState: String = "NONE",
    /** Volume-relative boot target when it differs (extracted cue/m3u). */
    val launchRelPath: String? = null,
    /** One-line human summary of the last prep run. */
    val prepNote: String? = null,
    // Scan generation: last rescan that observed this row.
    /** Monotonic scan id; rows with a stale generation are absent media. */
    val scanGen: Long = 0,
    // Source provenance: null for imported files.
    val sourceId: String? = null,
    val sourceItemKey: String? = null,
    /** JSON-encoded runtime SourceRef. */
    val sourceRefJson: String? = null,
    /** JSON-encoded runtime DownloadFile for the picked variant. */
    val sourceVariantJson: String? = null,
    /** >0 = removed from the phone by the user; row kept for "Download again" (source games only). */
    @ColumnInfo(defaultValue = "0") val deletedAtMs: Long = 0,
)

/** Durable download queue. status: queued/active/paused/parked_sd/needs_assist/failed/done. */
@Entity(
    tableName = "downloads",
    indices = [
        Index(value = ["status", "updatedAtMs"]),
        Index(value = ["sourceId", "sourceItemKey"]),
    ],
)
data class DownloadRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Durable source: file-page URL. NEVER a signed token URL (single-use). */
    val pageUrl: String,
    val title: String,
    val destRelPath: String,
    val volumeId: String,
    val bytes: Long,
    val status: String,
    val error: String? = null,
    val createdAtMs: Long = 0,
    val updatedAtMs: Long = 0,
    /** Known total bytes, 0 = unknown. Re-validated on resume. */
    val totalBytes: Long = 0,
    /** Target file name (partial file is fileName + ".part"). */
    val fileName: String = "",
    val platformFolder: String? = null,
    /** Catalog metadata for post-download ingest. */
    val coverUrl: String? = null,
    val description: String? = null,
    val consoleSlug: String? = null,
    /** Non-null = direct-URL job: skip mint, fetch this URL. */
    val directUrl: String? = null,
    /** WebView-assist cookies ("k=v; k2=v2") for challenge-gated hosts. */
    val cookies: String? = null,
    /** Failed transient attempts; manual resume resets. */
    val attempts: Int = 0,
    /** Raw delivery: absolute dest dir; skips ROM ingest. */
    val destDirAbs: String? = null,
    // Source provenance.
    val sourceId: String? = null,
    val sourceItemKey: String? = null,
    val sourceVariantKey: String? = null,
    val sourceRevision: String? = null,
    /** JSON-encoded runtime DownloadFile used for resolution. */
    val sourceFileJson: String? = null,
    /** INTERNAL, ROM, ART. */
    val transferKind: String = "INTERNAL",
    /** WebView-assist origin allowlist, set by the owning source. */
    val assistOrigin: String? = null,
)

/** Installed-emulator snapshot (single writer; presence also reads live). */
@Entity(tableName = "emu_state")
data class EmuState(
    @PrimaryKey val pkg: String,
    val versionCode: Long = 0,
    val versionName: String? = null,
    val source: String = "unknown",
    val updatedAtMs: Long = 0,
)

/** ScreenScraper enrich backlog; only unknown files enqueue. */
@Entity(tableName = "enrich_queue")
data class EnrichItem(
    @PrimaryKey val gameId: Long,
    val attempts: Int = 0,
    val lastError: String? = null,
    val updatedAtMs: Long = 0,
)

@Dao
interface GameDao {
    @Query("SELECT * FROM games ORDER BY addedAtMs DESC")
    fun observeAll(): Flow<List<GameEntity>>

    @Query("SELECT * FROM games WHERE id = :id")
    fun observeById(id: Long): Flow<GameEntity?>

    @Query("SELECT * FROM games WHERE volumeId = :vol AND relPath = :rel LIMIT 1")
    suspend fun findByPath(vol: String, rel: String): GameEntity?

    /** Whole-volume snapshot for batched rescan (one query, not one per ROM). */
    @Query("SELECT * FROM games WHERE volumeId = :vol")
    suspend fun listByVolume(vol: String): List<GameEntity>
    @Query("SELECT * FROM games WHERE folder IN (:folders) AND available = 1 AND deletedAtMs = 0 ORDER BY title COLLATE NOCASE")
    suspend fun listPlayableInFolders(folders: List<String>): List<GameEntity>

    @Query("SELECT * FROM games WHERE sourceId = :sourceId AND sourceItemKey = :itemKey AND available = 1 AND deletedAtMs = 0 ORDER BY id DESC")
    suspend fun listPlayableBySourceItem(sourceId: String, itemKey: String): List<GameEntity>

    @Query("SELECT * FROM games WHERE sourceId = :sourceId AND sourceItemKey = :itemKey AND available = 1 AND deletedAtMs = 0 ORDER BY id DESC LIMIT 1")
    suspend fun findPlayableBySourceItem(sourceId: String, itemKey: String): GameEntity?

    /** Stamp observed rows with the current scan generation (caller chunks ids). */
    @Query("UPDATE games SET scanGen = :gen WHERE id IN (:ids)")
    suspend fun markSeenIds(ids: List<Long>, gen: Long)

    /** Absent media: observed by an older scan generation on this volume. */
    @Query("UPDATE games SET available = 0 WHERE volumeId = :vol AND scanGen != :gen")
    suspend fun markStaleUnavailable(vol: String, gen: Long): Int

    /** Whole-volume unavailable (SD eject; remount rescans). */
    @Query("UPDATE games SET available = 0 WHERE volumeId = :vol")
    suspend fun markVolumeUnavailable(vol: String): Int

    @Query("SELECT * FROM games WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): GameEntity?

    /** Tombstoned rows never fetch art. */
    @Query("SELECT id FROM games WHERE artSource = 'NONE' AND deletedAtMs = 0")
    suspend fun idsMissingArt(): List<Long>

    @Query("SELECT folder, COUNT(*) AS n FROM games WHERE available = 1 GROUP BY folder")
    fun observeCountsByFolder(): Flow<List<FolderCount>>

    @Query("SELECT folder, prepState, COUNT(*) AS n FROM games WHERE available = 1 GROUP BY folder, prepState")
    suspend fun prepCounts(): List<PrepCount>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(game: GameEntity): Long

    @Query("DELETE FROM games WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** Batch insert for scans: one statement set, rowIds in input order. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(games: List<GameEntity>): List<Long>

    @Query("UPDATE games SET favorite = :fav WHERE id = :id")
    suspend fun setFavorite(id: Long, fav: Boolean)

    @Query("UPDATE games SET playCount = playCount + 1, lastPlayedMs = :now WHERE id = :id")
    suspend fun recordPlay(id: Long, now: Long)

    @Query("UPDATE games SET coreOverride = :core WHERE id = :id")
    suspend fun setCoreOverride(id: Long, core: String?)

    @Query("UPDATE games SET emulatorOverride = :emu WHERE id = :id")
    suspend fun setEmulatorOverride(id: Long, emu: String?)

    /** Targeted prep write: favorites/plays/overrides/art made meanwhile survive. */
    @Query("UPDATE games SET prepState = :state, launchRelPath = :launch, prepNote = :note WHERE id = :id")
    suspend fun setPrep(id: Long, state: String, launch: String?, note: String?)

    /** Targeted artwork write (slots + source + search id only). */
    @Query("UPDATE games SET artBox = :box, artHero = :hero, artLogo = :logo, artShot = :shot, artSource = :source, ssGameId = :ss WHERE id = :id")
    suspend fun setArtSlots(
        id: Long, box: String?, hero: String?, logo: String?, shot: String?, source: String, ss: Long,
    )

    /**
     * Scanner refresh of scan-owned fields only. A row refreshed as
     * present revives a tombstone: the file is back at its path.
     */
    @Query("UPDATE games SET title = :title, sizeBytes = :size, mtimeMs = :mtime, available = :avail, deletedAtMs = CASE WHEN :avail = 1 THEN 0 ELSE deletedAtMs END, ssGameId = :ss WHERE id = :id")
    suspend fun setScanRefreshed(id: Long, title: String, size: Long, mtime: Long, avail: Boolean, ss: Long)

    @Query("UPDATE games SET available = :avail WHERE id = :id")
    suspend fun setAvailable(id: Long, avail: Boolean)

    /** Tombstone: the row stays for "Download again", files are already gone. */
    @Query("UPDATE games SET available = 0, deletedAtMs = :now WHERE id = :id")
    suspend fun markDeleted(id: Long, now: Long)

    /**
     * Revive reappeared rows (a restored file revives its tombstone);
     * caller chunks to respect bind limits.
     */
    @Query("UPDATE games SET available = 1, deletedAtMs = 0 WHERE id IN (:ids)")
    suspend fun setAvailableAll(ids: List<Long>)

    /** Ingest file facts + provenance (never touches favorites/plays/overrides). Revives a tombstone. */
    @Query("UPDATE games SET title = :title, sizeBytes = :size, mtimeMs = :mtime, available = 1, deletedAtMs = 0, coverUrl = :coverUrl, description = :desc, source = :source, sourceId = :sourceId, sourceItemKey = :itemKey, sourceRefJson = :refJson, sourceVariantJson = :variantJson WHERE id = :id")
    suspend fun setIngested(
        id: Long, title: String, size: Long, mtime: Long,
        coverUrl: String?, desc: String?, source: String,
        sourceId: String? = null, itemKey: String? = null,
        refJson: String? = null, variantJson: String? = null,
    )

    /** Catalog continuity, source-qualified: fires when ingest lands. */
    @Query("SELECT * FROM games WHERE sourceId = :sourceId AND sourceItemKey = :itemKey ORDER BY available DESC, id DESC LIMIT 1")
    fun observeBySourceItem(sourceId: String, itemKey: String): Flow<GameEntity?>

    /** Targeted source-reference write; never touches user state. */
    @Query("UPDATE games SET sourceId = :sourceId, sourceItemKey = :itemKey, sourceRefJson = :refJson, sourceVariantJson = :variantJson WHERE id = :id")
    suspend fun setSourceReference(id: Long, sourceId: String, itemKey: String, refJson: String, variantJson: String?)

    @Query("UPDATE games SET coverPath = :path WHERE id = :id")
    suspend fun setCoverPath(id: Long, path: String)

    @Query("UPDATE games SET artHero = :path WHERE id = :id")
    suspend fun setArtHero(id: Long, path: String)
}

data class FolderCount(val folder: String, val n: Int)

data class PrepCount(val folder: String, val prepState: String, val n: Int)

@Dao
interface DownloadDao {
    @Insert suspend fun insert(rec: DownloadRecord): Long
    @Query("SELECT * FROM downloads ORDER BY updatedAtMs DESC LIMIT :limit")
    fun observeRecent(limit: Int = 100): Flow<List<DownloadRecord>>
    @Update suspend fun update(rec: DownloadRecord)
    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun deleteById(id: Long)
    @Query("SELECT * FROM downloads WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): DownloadRecord?
    @Query("SELECT * FROM downloads WHERE status IN (:statuses) ORDER BY updatedAtMs ASC")
    suspend fun getByStatuses(statuses: List<String>): List<DownloadRecord>
    @Query("SELECT * FROM downloads WHERE status IN ('queued','active') ORDER BY createdAtMs ASC")
    suspend fun getRunnable(): List<DownloadRecord>
    /** Reattach to a live job when its catalog detail reopens (source-qualified). */
    @Query("SELECT * FROM downloads WHERE sourceId = :sourceId AND sourceItemKey = :itemKey AND status != 'done' ORDER BY updatedAtMs DESC LIMIT 1")
    suspend fun findLiveBySourceItem(sourceId: String, itemKey: String): DownloadRecord?
    /** Every version ever queued for a source entry, newest first. */
    @Query("SELECT * FROM downloads WHERE sourceId = :sourceId AND sourceItemKey = :itemKey ORDER BY updatedAtMs DESC")
    suspend fun findBySourceItem(sourceId: String, itemKey: String): List<DownloadRecord>
    /** Nonterminal source jobs' revision pins for definition GC retention. */
    @Query("SELECT DISTINCT sourceId AS id, sourceRevision AS revision FROM downloads WHERE sourceId IS NOT NULL AND sourceRevision IS NOT NULL AND status != 'done'")
    suspend fun revisionPins(): List<com.gamebex.client.sources.RevisionPin>
    /** Nonterminal jobs owned by one source (disable/remove impact count). */
    @Query("SELECT COUNT(*) FROM downloads WHERE sourceId = :sourceId AND status != 'done'")
    suspend fun countLiveBySource(sourceId: String): Int
}

@Dao
interface EnrichDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun enqueue(item: EnrichItem)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun enqueueAll(items: List<EnrichItem>)

    /** Oldest item still under the attempt budget. */
    @Query("SELECT * FROM enrich_queue WHERE attempts < 3 ORDER BY updatedAtMs ASC LIMIT 1")
    suspend fun next(): EnrichItem?

    @Query("DELETE FROM enrich_queue WHERE gameId = :gameId")
    suspend fun remove(gameId: Long)

    @Query("UPDATE enrich_queue SET attempts = attempts + 1, lastError = :err, updatedAtMs = :now WHERE gameId = :gameId")
    suspend fun noteFailure(gameId: Long, err: String, now: Long)

    @Query("UPDATE enrich_queue SET attempts = 0, lastError = NULL, updatedAtMs = :now WHERE gameId = :gameId")
    suspend fun reset(gameId: Long, now: Long)

    @Query("SELECT COUNT(*) FROM enrich_queue")
    suspend fun count(): Int
}

@Dao
interface EmuStateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: EmuState)

    @Query("SELECT * FROM emu_state WHERE pkg = :pkg LIMIT 1")
    suspend fun get(pkg: String): EmuState?

    @Query("SELECT * FROM emu_state")
    fun observeAll(): Flow<List<EmuState>>
}

@Database(
    entities = [GameEntity::class, DownloadRecord::class, EmuState::class, EnrichItem::class],
    version = 2,
    exportSchema = true,
)
abstract class LibraryDb : RoomDatabase() {
    abstract fun games(): GameDao
    abstract fun downloads(): DownloadDao
    abstract fun enrich(): EnrichDao
    abstract fun emuStates(): EmuStateDao

    companion object {
        const val NAME = "gamebex-library.db"

        /** 1→2: tombstones for "Download again". */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE games ADD COLUMN deletedAtMs INTEGER NOT NULL DEFAULT 0")
            }
        }

        @Volatile private var instance: LibraryDb? = null
        fun get(context: Context): LibraryDb = instance ?: synchronized(this) {
            instance ?: builder(context).build().also { instance = it }
        }

        /**
         * The production builder (tests open their own [name] with the
         * same policy). 1→2 migrates: play counts, favorites and source
         * refs survive. Any other schema jump, up or down, is dropped and
         * recreated; the library is rebuilt by rescanning ROMs + sidecars.
         */
        fun builder(context: Context, name: String = NAME): RoomDatabase.Builder<LibraryDb> =
            Room.databaseBuilder(context.applicationContext, LibraryDb::class.java, name)
                .addMigrations(MIGRATION_1_2)
                .fallbackToDestructiveMigration()
                .fallbackToDestructiveMigrationOnDowngrade()
    }
}
