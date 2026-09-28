package com.gamebex.client.launch

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.gamebex.client.data.local.LibraryDb
import com.gamebex.client.data.local.RomStore
import kotlinx.coroutines.runBlocking
import java.io.File

/** Serves available library files by game ID: `content://<app>.roms/game/<id>[/<file name>]`.
 *  The optional trailing name is cosmetic (lookup is by ID only) for emulators that
 *  sniff the format from the URI's extension, e.g. NetherSX2's bootPath.
 *  Exists because FileProvider cannot serve SD; never exposes the filesystem root. */
class RomProvider : ContentProvider() {
    private val matcher = UriMatcher(UriMatcher.NO_MATCH).apply {
        addURI(AUTH, "game/#", GAME)
        addURI(AUTH, "game/#/*", GAME)
    }

    override fun onCreate(): Boolean = true

    override fun attachInfo(context: android.content.Context?, info: ProviderInfo?) {
        super.attachInfo(context, info)
    }

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?,
    ): Cursor? {
        if (matcher.match(uri) != GAME) return null
        val ctx = context ?: return null
        val id = gameIdOf(uri) ?: return null
        val row: Array<Any?> = runBlocking { lookup(ctx, id) } ?: return null
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(cols).apply {
            newRow().apply {
                cols.forEachIndexed { i, c ->
                    when (c) {
                        OpenableColumns.DISPLAY_NAME -> add(row[0])
                        OpenableColumns.SIZE -> add(row[1])
                        else -> add(null)
                    }
                    @Suppress("UNUSED_EXPRESSION")
                    i
                }
            }
        }
    }

    override fun getType(uri: Uri): String? {
        if (matcher.match(uri) != GAME) return null
        val ctx = context ?: return null
        val id = gameIdOf(uri) ?: return null
        val name: String = runBlocking { lookupName(ctx, id) } ?: return MIME_BIN
        return mimeFor(name)
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (matcher.match(uri) != GAME || mode != "r") return null
        val ctx = context ?: return null
        val id = gameIdOf(uri) ?: return null
        val file: File = runBlocking { lookupFile(ctx, id) } ?: return null
        if (!file.isFile) return null
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri, values: ContentValues?, selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private suspend fun lookup(ctx: android.content.Context, id: Long): Array<Any?>? {
        // Metadata must describe the file openFile actually serves (the
        // prepared launch target, e.g. an extracted .cue), not the archived ROM.
        val file = lookupFile(ctx, id) ?: return null
        return arrayOf(file.name, file.length())
    }

    private suspend fun lookupName(ctx: android.content.Context, id: Long): String? =
        lookup(ctx, id)?.get(0) as? String

    private suspend fun lookupFile(ctx: android.content.Context, id: Long): File? {
        val db = LibraryDb.get(ctx)
        val game = db.games().findById(id)?.takeIf { it.available } ?: return null
        return RomStore.resolveLaunchFile(ctx, game)
    }

    companion object {
        const val AUTH = "com.gamebex.client.roms"
        private const val GAME = 1
        private const val MIME_BIN = "application/octet-stream"

        fun uriString(gameId: Long): String = "content://$AUTH/game/$gameId"

        fun uriFor(gameId: Long): Uri = Uri.parse(uriString(gameId))

        /** URI whose last segment is [fileName], so its extension is visible. */
        fun uriFor(gameId: Long, fileName: String): Uri =
            Uri.parse(uriString(gameId)).buildUpon().appendPath(fileName).build()

        /** Game ID from `game/<id>` or `game/<id>/<name>`. */
        fun gameIdOf(uri: Uri): Long? = uri.pathSegments.getOrNull(1)?.toLongOrNull()

        fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
            "zip" -> "application/zip"
            "7z" -> "application/x-7z-compressed"
            "cue", "m3u", "txt", "srm" -> "text/plain"
            "iso", "ciso", "chd", "gcm", "rvz", "wbfs", "cso", "pbp", "gb", "gbc", "gba",
            "nes", "sfc", "smc", "n64", "z64", "nds", "cci", "3ds", "md", "gen",
            -> MIME_BIN
            else -> MIME_BIN
        }
    }
}
