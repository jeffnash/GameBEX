package com.gamebex.client.launch

import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.os.Process
import android.content.pm.PackageManager
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import com.gamebex.client.data.local.LibraryDb
import com.gamebex.client.data.local.PrepPipeline
import com.gamebex.client.data.local.RomStore
import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.runBlocking

class GameDocumentsProvider : DocumentsProvider() {
    override fun onCreate() = true

    override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(projection ?: arrayOf(DocumentsContract.Root.COLUMN_ROOT_ID))

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val (scope, relative) = document(documentId)
        return MatrixCursor(projection ?: COLUMNS).apply { addDocument(scope, relative) }
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        val (scope, parent) = document(parentDocumentId)
        if (!scope.directory(parent)) throw FileNotFoundException("Not a game directory")
        val prefix = if (parent.isEmpty()) "" else "$parent/"
        val children = scope.files.keys.filter { it.startsWith(prefix) }.map { prefix + it.removePrefix(prefix).substringBefore('/') }.distinct()
        return MatrixCursor(projection ?: COLUMNS).apply { children.forEach { addDocument(scope, it) } }
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Game documents are read only")
        signal?.throwIfCanceled()
        val (scope, relative) = document(documentId)
        val file = scope.files[relative] ?: throw FileNotFoundException("Not a game file")
        if (!RomStore.isInside(scope.base, file) || !file.isFile) throw FileNotFoundException("Game file unavailable")
        return ParcelFileDescriptor.open(file.canonicalFile, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean = runCatching {
        val parent = parseId(parentDocumentId)
        val child = parseId(documentId)
        if (parent.first != child.first) return false
        val (scope, path) = document(parentDocumentId)
        if (!scope.directory(path) || !scope.exists(child.second)) return false
        path.isEmpty() || child.second.startsWith("$path/")
    }.getOrDefault(false)

    override fun findDocumentPath(parentDocumentId: String?, childDocumentId: String): DocumentsContract.Path {
        val (scope, path) = document(childDocumentId)
        if (parentDocumentId != null && parentDocumentId != childDocumentId && !isChildDocument(parentDocumentId, childDocumentId)) {
            throw FileNotFoundException("Document outside the selected game")
        }
        val ids = mutableListOf(rootId(scope.id))
        var relative = ""
        path.split('/').filter { it.isNotEmpty() }.forEach {
            relative = if (relative.isEmpty()) it else "$relative/$it"
            ids += docId(scope.id, relative)
        }
        return DocumentsContract.Path(null, ids)
    }

    private data class Scope(val id: Long, val name: String, val base: File, val launch: File, val files: Map<String, File>) {
        fun directory(path: String) = path.isEmpty() || files.keys.any { it.startsWith("$path/") }
        fun exists(path: String) = path in files || directory(path)
    }

    private fun document(documentId: String): Pair<Scope, String> {
        val (id, relative) = parseId(documentId)
        val ctx = requireNotNull(context)
        val uid = Binder.getCallingUid()
        if (uid != Process.myUid() && ctx.checkUriPermission(treeUri(id), Binder.getCallingPid(), uid, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("Selected game tree read grant required")
        }
        val game = runBlocking { LibraryDb.get(ctx).games().findById(id) }?.takeIf { it.available }
            ?: throw FileNotFoundException("Game unavailable")
        val launch = RomStore.resolveLaunchFile(ctx, game)?.takeIf { it.isFile }
            ?: throw FileNotFoundException("Prepared game unavailable")
        val base = launch.parentFile?.canonicalFile ?: throw FileNotFoundException("Game directory unavailable")
        if (!RomStore.isInside(base, launch)) throw FileNotFoundException("Game file outside its directory")
        val files = linkedMapOf(launch.name to launch.canonicalFile)
        if (launch.extension.equals("gdi", true)) {
            PrepPipeline.parseSheetRefs(launch).orEmpty().forEach { ref ->
                if (safeRelative(ref)) {
                    val file = File(base, ref)
                    if (file.isFile && RomStore.isInside(base, file)) files[ref] = file.canonicalFile
                }
            }
        }
        val scope = Scope(id, game.title, base, launch, files)
        if (!scope.exists(relative)) throw FileNotFoundException("Document not declared by this game")
        return scope to relative
    }

    private fun MatrixCursor.addDocument(scope: Scope, relative: String) {
        val file = scope.files[relative]
        val dir = file == null && scope.directory(relative)
        val values = mapOf<String, Any>(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID to docId(scope.id, relative),
            DocumentsContract.Document.COLUMN_DISPLAY_NAME to if (relative.isEmpty()) scope.name else relative.substringAfterLast('/'),
            DocumentsContract.Document.COLUMN_MIME_TYPE to if (dir) DocumentsContract.Document.MIME_TYPE_DIR else RomProvider.mimeFor(relative),
            DocumentsContract.Document.COLUMN_SIZE to (file?.length() ?: 0L),
            DocumentsContract.Document.COLUMN_LAST_MODIFIED to (file?.lastModified() ?: scope.launch.lastModified()),
            DocumentsContract.Document.COLUMN_FLAGS to 0,
        )
        addRow(columnNames.map { values[it] }.toTypedArray())
    }

    companion object {
        const val AUTH = "com.gamebex.client.game-documents"
        private val COLUMNS = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED, DocumentsContract.Document.COLUMN_FLAGS)
        private fun rootId(id: Long) = "game:$id"
        private fun docId(id: Long, relative: String) = rootId(id) + if (relative.isEmpty()) "" else "/$relative"
        fun treeUri(id: Long): Uri = DocumentsContract.buildTreeDocumentUri(AUTH, rootId(id))
        fun uriFor(id: Long, relative: String): Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri(id), docId(id, relative))
        fun treeForDocument(uri: Uri): Uri? = runCatching {
            if (uri.authority != AUTH || !DocumentsContract.isTreeUri(uri)) return null
            val root = DocumentsContract.getTreeDocumentId(uri)
            val parsedRoot = parseId(root)
            if (parsedRoot.second.isNotEmpty() || parseId(DocumentsContract.getDocumentId(uri)).first != parsedRoot.first) return null
            treeUri(parsedRoot.first)
        }.getOrNull()

        private fun parseId(id: String): Pair<Long, String> {
            val root = id.substringBefore('/')
            val game = root.removePrefix("game:").toLongOrNull()?.takeIf { it > 0 && root == rootId(it) }
                ?: throw FileNotFoundException("Invalid game document")
            val path = id.substringAfter('/', "")
            if (id != docId(game, path) || path.isNotEmpty() && !safeRelative(path)) throw FileNotFoundException("Invalid relative document")
            return game to path
        }

        private fun safeRelative(path: String): Boolean {
            var value = path
            repeat(4) {
                if (value.isEmpty() || value.startsWith('/') || '\\' in value || ':' in value || '\u0000' in value ||
                    value.split('/').any { it.isEmpty() || it == "." || it == ".." }) return false
                val decoded = Uri.decode(value)
                if (decoded == value) return true
                value = decoded
            }
            return false
        }
    }
}
