package com.gamebex.client.data.local

import androidx.room.DatabaseConfiguration
import androidx.room.InvalidationTracker
import androidx.sqlite.db.SupportSQLiteOpenHelper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * JVM test double for [LibraryDb]: in-memory rows behind the real DAO
 * interfaces. Flows emit snapshots (tests set rows before acting).
 */
class FakeLibraryDb(
    val gamesList: MutableList<GameEntity> = mutableListOf(),
    val downloadsList: MutableList<DownloadRecord> = mutableListOf(),
) : LibraryDb() {
    private var gameSeq = (gamesList.maxOfOrNull { it.id } ?: 0) + 1
    private var dlSeq = (downloadsList.maxOfOrNull { it.id } ?: 0) + 1

    private val gameDao = object : GameDao {
        private fun update(id: Long, f: (GameEntity) -> GameEntity) {
            val i = gamesList.indexOfFirst { it.id == id }
            if (i >= 0) gamesList[i] = f(gamesList[i])
        }

        override fun observeAll(): Flow<List<GameEntity>> = flowOf(gamesList.toList())
        override fun observeById(id: Long): Flow<GameEntity?> =
            flowOf(gamesList.firstOrNull { it.id == id })

        override suspend fun findByPath(vol: String, rel: String): GameEntity? =
            gamesList.firstOrNull { it.volumeId == vol && it.relPath == rel }

        override suspend fun listByVolume(vol: String): List<GameEntity> =
            gamesList.filter { it.volumeId == vol }

        override suspend fun markSeenIds(ids: List<Long>, gen: Long) {
            ids.forEach { update(it) { g -> g.copy(scanGen = gen) } }
        }

        override suspend fun markStaleUnavailable(vol: String, gen: Long): Int {
            var n = 0
            gamesList.filter { it.volumeId == vol && it.scanGen != gen }.forEach {
                update(it.id) { g -> g.copy(available = false) }
                n++
            }
            return n
        }

        override suspend fun markVolumeUnavailable(vol: String): Int {
            var n = 0
            gamesList.filter { it.volumeId == vol }.forEach {
                update(it.id) { g -> g.copy(available = false) }
                n++
            }
            return n
        }

        override suspend fun findById(id: Long): GameEntity? =
            gamesList.firstOrNull { it.id == id }
        override suspend fun listPlayableInFolders(folders: List<String>): List<GameEntity> =
            gamesList.filter { it.folder in folders && it.available && it.deletedAtMs == 0L }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })

        override suspend fun listPlayableBySourceItem(sourceId: String, itemKey: String): List<GameEntity> =
            gamesList.filter { it.sourceId == sourceId && it.sourceItemKey == itemKey && it.available && it.deletedAtMs == 0L }
                .sortedByDescending { it.id }

        override suspend fun findPlayableBySourceItem(sourceId: String, itemKey: String): GameEntity? =
            listPlayableBySourceItem(sourceId, itemKey).firstOrNull()


        override suspend fun idsMissingArt(): List<Long> =
            gamesList.filter { it.artSource == "NONE" && it.deletedAtMs == 0L }.map { it.id }

        override fun observeCountsByFolder(): Flow<List<FolderCount>> = flowOf(emptyList())
        override suspend fun prepCounts(): List<PrepCount> = emptyList()

        override suspend fun upsert(game: GameEntity): Long {
            if (game.id == 0L) {
                val id = gameSeq++
                gamesList += game.copy(id = id)
                return id
            }
            val i = gamesList.indexOfFirst { it.id == game.id }
            if (i >= 0) gamesList[i] = game else gamesList += game
            return game.id
        }

        override suspend fun deleteById(id: Long) {
            gamesList.removeAll { it.id == id }
        }

        override suspend fun upsertAll(games: List<GameEntity>): List<Long> =
            games.map { upsert(it) }

        override suspend fun setFavorite(id: Long, fav: Boolean) {
            update(id) { it.copy(favorite = fav) }
        }

        override suspend fun recordPlay(id: Long, now: Long) {
            update(id) { it.copy(playCount = it.playCount + 1, lastPlayedMs = now) }
        }

        override suspend fun setCoreOverride(id: Long, core: String?) {
            update(id) { it.copy(coreOverride = core) }
        }

        override suspend fun setEmulatorOverride(id: Long, emu: String?) {
            update(id) { it.copy(emulatorOverride = emu) }
        }

        override suspend fun setPrep(id: Long, state: String, launch: String?, note: String?) {
            update(id) { it.copy(prepState = state, launchRelPath = launch, prepNote = note) }
        }

        override suspend fun setArtSlots(
            id: Long, box: String?, hero: String?, logo: String?, shot: String?, source: String, ss: Long,
        ) {
            update(id) {
                it.copy(artBox = box, artHero = hero, artLogo = logo, artShot = shot, artSource = source, ssGameId = ss)
            }
        }

        override suspend fun setScanRefreshed(
            id: Long, title: String, size: Long, mtime: Long, avail: Boolean, ss: Long,
        ) {
            update(id) {
                it.copy(
                    title = title, sizeBytes = size, mtimeMs = mtime, available = avail,
                    deletedAtMs = if (avail) 0 else it.deletedAtMs, ssGameId = ss,
                )
            }
        }

        override suspend fun setAvailable(id: Long, avail: Boolean) {
            update(id) { it.copy(available = avail) }
        }

        override suspend fun markDeleted(id: Long, now: Long) {
            update(id) { it.copy(available = false, deletedAtMs = now) }
        }

        override suspend fun setAvailableAll(ids: List<Long>) {
            ids.forEach { update(it) { g -> g.copy(available = true, deletedAtMs = 0) } }
        }

        override suspend fun setIngested(
            id: Long, title: String, size: Long, mtime: Long,
            coverUrl: String?, desc: String?, source: String,
            sourceId: String?, itemKey: String?, refJson: String?, variantJson: String?,
        ) {
            update(id) {
                it.copy(
                    title = title, sizeBytes = size, mtimeMs = mtime, available = true, deletedAtMs = 0,
                    coverUrl = coverUrl, description = desc, source = source,
                    sourceId = sourceId,
                    sourceItemKey = itemKey, sourceRefJson = refJson, sourceVariantJson = variantJson,
                )
            }
        }

        override suspend fun setCoverPath(id: Long, path: String) {
            update(id) { it.copy(coverPath = path) }
        }

        override suspend fun setArtHero(id: Long, path: String) {
            update(id) { it.copy(artHero = path) }
        }

        override fun observeBySourceItem(sourceId: String, itemKey: String): Flow<GameEntity?> =
            flowOf(
                gamesList.filter { it.sourceId == sourceId && it.sourceItemKey == itemKey }
                    .sortedWith(compareByDescending<GameEntity> { it.available }.thenByDescending { it.id })
                    .firstOrNull(),
            )

        override suspend fun setSourceReference(
            id: Long, sourceId: String, itemKey: String, refJson: String, variantJson: String?,
        ) {
            update(id) {
                it.copy(sourceId = sourceId, sourceItemKey = itemKey, sourceRefJson = refJson, sourceVariantJson = variantJson)
            }
        }
    }

    private val downloadDao = object : DownloadDao {
        override suspend fun insert(rec: DownloadRecord): Long {
            val id = dlSeq++
            downloadsList += rec.copy(id = id)
            return id
        }

        override fun observeRecent(limit: Int): Flow<List<DownloadRecord>> =
            flowOf(downloadsList.sortedByDescending { it.updatedAtMs }.take(limit))

        override suspend fun update(rec: DownloadRecord) {
            val i = downloadsList.indexOfFirst { it.id == rec.id }
            if (i >= 0) downloadsList[i] = rec
        }

        override suspend fun deleteById(id: Long) {
            downloadsList.removeAll { it.id == id }
        }

        override suspend fun getById(id: Long): DownloadRecord? =
            downloadsList.firstOrNull { it.id == id }

        override suspend fun getByStatuses(statuses: List<String>): List<DownloadRecord> =
            downloadsList.filter { it.status in statuses }.sortedBy { it.updatedAtMs }

        override suspend fun getRunnable(): List<DownloadRecord> =
            downloadsList.filter { it.status == "queued" || it.status == "active" }.sortedBy { it.createdAtMs }

        override suspend fun findLiveBySourceItem(sourceId: String, itemKey: String): DownloadRecord? =
            downloadsList.filter { it.sourceId == sourceId && it.sourceItemKey == itemKey && it.status != "done" }
                .maxByOrNull { it.updatedAtMs }

        override suspend fun findBySourceItem(sourceId: String, itemKey: String): List<DownloadRecord> =
            downloadsList.filter { it.sourceId == sourceId && it.sourceItemKey == itemKey }
                .sortedByDescending { it.updatedAtMs }

        override suspend fun revisionPins(): List<com.gamebex.client.sources.RevisionPin> =
            downloadsList.filter { it.sourceId != null && it.sourceRevision != null && it.status != "done" }
                .map { com.gamebex.client.sources.RevisionPin(it.sourceId!!, it.sourceRevision!!) }
                .distinct()

        override suspend fun countLiveBySource(sourceId: String): Int =
            downloadsList.count { it.sourceId == sourceId && it.status != "done" }
    }

    private val enrichDao = object : EnrichDao {
        override suspend fun enqueue(item: EnrichItem) = TODO()
        override suspend fun enqueueAll(items: List<EnrichItem>) = TODO()
        override suspend fun next(): EnrichItem? = TODO()
        override suspend fun remove(gameId: Long) = TODO()
        override suspend fun noteFailure(gameId: Long, err: String, now: Long) = TODO()
        override suspend fun reset(gameId: Long, now: Long) = TODO()
        override suspend fun count(): Int = TODO()
    }

    private val emuStateDao = object : EmuStateDao {
        override suspend fun upsert(state: EmuState) = TODO()
        override suspend fun get(pkg: String): EmuState? = TODO()
        override fun observeAll(): Flow<List<EmuState>> = TODO()
    }

    override fun games(): GameDao = gameDao
    override fun downloads(): DownloadDao = downloadDao
    override fun enrich(): EnrichDao = enrichDao
    override fun emuStates(): EmuStateDao = emuStateDao
    override fun clearAllTables() = TODO("test double")

    /** Real tracker over no tables: RoomDatabase's constructor requires one. */
    override fun createInvalidationTracker(): InvalidationTracker =
        InvalidationTracker(this, emptyMap(), emptyMap())
    override fun createOpenHelper(config: DatabaseConfiguration): SupportSQLiteOpenHelper =
        TODO("test double")
}
