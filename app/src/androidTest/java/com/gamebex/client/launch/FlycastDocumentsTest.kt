package com.gamebex.client.launch

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.SystemClock
import android.provider.DocumentsContract
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gamebex.client.data.Emu
import com.gamebex.client.data.local.GameEntity
import com.gamebex.client.data.local.GameFolders
import com.gamebex.client.data.local.LibraryDb
import com.gamebex.client.data.local.RomStore
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FlycastDocumentsTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = instrumentation.targetContext
    private val receiverPackage get() = instrumentation.context.packageName
    private val responses = ArrayBlockingQueue<Bundle>(1)
    private var remote: Messenger? = null
    private val connected = CountDownLatch(1)
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) { remote = Messenger(binder); connected.countDown() }
        override fun onServiceDisconnected(name: ComponentName) { remote = null }
    }

    @Before fun bindOwnedReceivingUid() {
        assertTrue(ctx.bindService(Intent().setComponent(ComponentName(receiverPackage, OwnedDocumentReceiverService::class.java.name)), connection, Context.BIND_AUTO_CREATE))
        assertTrue(connected.await(10, TimeUnit.SECONDS))
    }

    @After fun unbindOwnedReceiver() { ctx.unbindService(connection) }

    private fun receive(uri: Uri, operation: String = "read"): Bundle {
        val reply = Messenger(object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(message: Message) { responses.offer(message.data) }
        })
        remote!!.send(Message.obtain().apply { data = Bundle().apply { putString("uri", uri.toString()); putString("operation", operation) }; replyTo = reply })
        val result = requireNotNull(responses.poll(10, TimeUnit.SECONDS)) { "Owned receiving UID did not reply" }
        assertNotEquals(Process.myUid(), result.getInt("uid"))
        assertEquals(ctx.packageManager.getApplicationInfo(receiverPackage, 0).uid, result.getInt("uid"))
        return result
    }

    private inner class GameFixture : AutoCloseable {
        val facts = Json.parseToJsonElement(instrumentation.context.assets.open("emulator-facts/flycast-document-storage-contract.json").bufferedReader().use { it.readText() }).jsonObject
        val root = File(ctx.cacheDir, "owned-documents-${System.nanoTime()}").apply { mkdirs() }
        val prepared = root.resolve("prepared").apply { mkdirs() }
        val launch = prepared.resolve("game.gdi").apply { writeText(facts.getValue("synthetic_gdi_text").jsonPrimitive.content) }
        val original = root.resolve("source.zip").apply { writeBytes(byteArrayOf(20, 21)) }
        val other = prepared.resolve(facts.getValue("synthetic_unlisted_file").jsonPrimitive.content).apply { writeBytes(byteArrayOf(99, 98)) }
        val db = LibraryDb.get(ctx)
        val id: Long
        val otherId: Long
        val tree get() = GameDocumentsProvider.treeUri(id)
        val uri get() = GameDocumentsProvider.uriFor(id, launch.name)
        val rootDocument get() = GameDocumentsProvider.uriFor(id, "")
        init {
            assertTrue(GameFolders.add(ctx, root, RomStore.builtInRoots(ctx).map { it.second }) is GameFolders.AddResult.Added)
            facts.getValue("synthetic_track_bytes").jsonObject.forEach { (name, bytes) -> prepared.resolve(name).writeBytes(bytes.jsonArray.map { it.jsonPrimitive.int.toByte() }.toByteArray()) }
            val volume = GameFolders.volumeIdFor(root.canonicalPath)
            id = runBlocking { db.games().upsert(GameEntity(title = "Owned document game", folder = "dreamcast", volumeId = volume, relPath = original.name,
                fileName = original.name, sizeBytes = original.length(), mtimeMs = original.lastModified(), ext = "zip", prepState = "READY", launchRelPath = "prepared/${launch.name}")) }
            otherId = runBlocking { db.games().upsert(GameEntity(title = "Owned separate game", folder = "dreamcast", volumeId = volume, relPath = "prepared/${other.name}",
                fileName = other.name, sizeBytes = other.length(), mtimeMs = other.lastModified(), ext = "chd")) }
            assertEquals(launch.canonicalPath, RomStore.resolveLaunchFile(ctx, runBlocking { db.games().findById(id) }!!)!!.canonicalPath)
        }
        fun grant() = ctx.grantUriPermission(receiverPackage, tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        override fun close() {
            ctx.revokeUriPermission(receiverPackage, tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            runBlocking { db.games().deleteById(id); db.games().deleteById(otherId) }
            GameFolders.remove(ctx, root.canonicalPath)
            root.deleteRecursively()
        }
    }

    @Test fun frameworkRecognizesTheFlycastLaunchAsADocumentInItsSelectedGameTree() {
        GameFixture().use { game ->
            assertTrue(DocumentsContract.isDocumentUri(ctx, game.uri)); assertTrue(DocumentsContract.isTreeUri(game.uri))
            assertFalse(receive(game.uri).getBoolean("ok"))
            game.grant()
            val files = game.facts.getValue("synthetic_allowed_files").jsonArray.map { it.jsonPrimitive.content }
            assertEquals(files.toSet(), receive(game.rootDocument, "children").getStringArrayList("names")!!.toSet())
            for (name in files) {
                val result = receive(GameDocumentsProvider.uriFor(game.id, name))
                assertTrue(result.getString("failure"), result.getBoolean("ok"))
                assertTrue(result.getBoolean("document")); assertTrue(result.getBoolean("tree"))
                assertEquals(name, result.getString("name")); assertEquals(RomProvider.mimeFor(name), result.getString("type"))
                assertEquals(game.prepared.resolve(name).length(), result.getLong("size"))
                assertEquals(game.prepared.resolve(name).lastModified(), result.getLong("modified"))
                assertEquals(0, result.getInt("flags"))
                assertArrayEquals(game.prepared.resolve(name).readBytes(), result.getByteArray("bytes"))
            }
            val path = receive(game.uri, "path")
            assertTrue(path.getString("failure"), path.getBoolean("ok"))
            assertEquals(listOf(DocumentsContract.getTreeDocumentId(game.uri), DocumentsContract.getDocumentId(game.uri)), path.getStringArrayList("path"))
            val sibling = DocumentsContract.buildDocumentUriUsingTree(game.uri, path.getStringArrayList("path")!![0] + "/track 02.bin")
            assertArrayEquals(byteArrayOf(5, 6, 7, 8), receive(sibling).getByteArray("bytes"))
            ctx.revokeUriPermission(receiverPackage, game.tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            assertFalse(receive(game.uri).getBoolean("ok"))
        }
    }

    @Test fun readPrefixScopeRejectsOtherGamesUnlistedTraversalAndDeclaredSymlinkEscape() {
        GameFixture().use { game ->
            val outside = game.root.resolve("outside.bin").apply { writeBytes(byteArrayOf(8, 9)) }
            Os.symlink(outside.path, game.prepared.resolve("symlink-to-outside.bin").path)
            val badPaths = game.facts.getValue("synthetic_reject_paths").jsonArray.map { it.jsonPrimitive.content }
            game.launch.writeText(game.launch.readText().replaceFirst("2\n", "6\n") + badPaths.mapIndexed { i, name -> "${i + 3} 50000 4 2352 \"$name\" 0\n" }.joinToString(""))
            game.grant()
            for (path in badPaths + game.other.name) assertFalse(path, receive(GameDocumentsProvider.uriFor(game.id, path)).getBoolean("ok"))
            assertFalse(receive(GameDocumentsProvider.uriFor(game.otherId, game.other.name)).getBoolean("ok"))
            val crossed = DocumentsContract.buildDocumentUriUsingTree(game.uri, DocumentsContract.getDocumentId(GameDocumentsProvider.uriFor(game.otherId, game.other.name)))
            assertFalse(receive(crossed).getBoolean("ok"))
            val children = receive(game.rootDocument, "children")
            assertTrue(children.getString("failure"), children.getBoolean("ok"))
            assertEquals(game.facts.getValue("synthetic_allowed_files").jsonArray.map { it.jsonPrimitive.content }.toSet(), children.getStringArrayList("names")!!.toSet())
        }
    }

    @Test fun readonlyDocumentsAdvertiseNoRootsAndSingleFileGamesExposeOnlyTheSelectedFile() {
        GameFixture().use { game ->
            game.grant()
            for (operation in listOf("write", "write-read-fd", "delete", "rename")) assertFalse(operation, receive(game.uri, operation).getBoolean("ok"))
            assertFalse(receive(game.rootDocument, "create").getBoolean("ok"))
            assertTrue(game.launch.isFile); assertFalse(game.prepared.resolve("owned-write-attempt.bin").exists())
            assertEquals(game.facts.getValue("synthetic_gdi_text").jsonPrimitive.content, game.launch.readText())
            ctx.contentResolver.query(DocumentsContract.buildRootsUri(GameDocumentsProvider.AUTH), null, null, null, null)!!.use { assertEquals(0, it.count) }
            val provider = ctx.packageManager.resolveContentProvider(GameDocumentsProvider.AUTH, 0)!!
            assertEquals("android.permission.MANAGE_DOCUMENTS", provider.readPermission)
            assertEquals("android.permission.MANAGE_DOCUMENTS", provider.writePermission)
            ctx.grantUriPermission(receiverPackage, GameDocumentsProvider.treeUri(game.otherId), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            try {
                assertEquals(listOf(game.other.name), receive(GameDocumentsProvider.uriFor(game.otherId, ""), "children").getStringArrayList("names"))
                assertArrayEquals(game.other.readBytes(), receive(GameDocumentsProvider.uriFor(game.otherId, game.other.name)).getByteArray("bytes"))
                assertFalse(receive(GameDocumentsProvider.uriFor(game.otherId, "track01.bin")).getBoolean("ok"))
            } finally { ctx.revokeUriPermission(receiverPackage, GameDocumentsProvider.treeUri(game.otherId), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
    }

    @Test fun repeatedSelectedDocumentStartCreatesAFreshOwnedConsumerAndRevokesThePreviousTree() {
        val prefs = ctx.getSharedPreferences("flycast_document_grant", Context.MODE_PRIVATE)
        val previousRecord = prefs.getString("tree", null)
        prefs.edit().remove("tree").commit()
        val events = mutableListOf<String>()
        val outgoing = mutableListOf<Intent>()
        val context = object : ContextWrapper(ctx) {
            override fun grantUriPermission(toPackage: String, uri: Uri, modeFlags: Int) {
                assertEquals(Emu.FLYCAST.pkg, toPackage)
                assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION, modeFlags)
                events += "grant:$uri"
                // The owned receiving package stands in for the installed selected emulator UID.
                super.grantUriPermission(receiverPackage, uri, modeFlags)
            }
            override fun revokeUriPermission(targetPackage: String, uri: Uri, modeFlags: Int) {
                assertEquals(Emu.FLYCAST.pkg, targetPackage)
                assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, modeFlags)
                events += "revoke:$uri"
                super.revokeUriPermission(receiverPackage, uri, modeFlags)
            }
            override fun startActivity(intent: Intent) {
                assertEquals(receiverPackage, intent.component?.packageName)
                assertEquals(Intent.ACTION_VIEW, intent.action)
                val tree = GameDocumentsProvider.treeForDocument(intent.data!!)!!
                assertEquals("grant:$tree", events.last())
                events += "start:${intent.data}"
                outgoing += Intent(intent)
                super.startActivity(intent)
            }
        }
        fun observationAfterDelivery(uri: Uri): Bundle {
            val deadline = SystemClock.uptimeMillis() + 5000
            var result: Bundle
            do {
                result = receive(uri, "launch-observation")
                if (result.getString("delivered-uri") == uri.toString()) return result
                Thread.sleep(50)
            } while (SystemClock.uptimeMillis() < deadline)
            fail("Owned receiving activity did not receive $uri: $result")
            return result
        }
        try {
            GameFixture().use { first -> GameFixture().use { second ->
                second.launch.appendText("""
# owned second selected document
""")
                val baseline = receive(first.uri, "launch-observation").getInt("created")
                fun launch(uri: Uri) {
                    val intent = requireNotNull(EmuRouter.flycastViewIn(ctx, receiverPackage, uri))
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    EmuRouter.startStandalone(context, Emu.FLYCAST, intent)
                }
                launch(first.uri)
                val createdFirst = observationAfterDelivery(first.uri)
                assertEquals(baseline + 1, createdFirst.getInt("created"))
                assertNull(createdFirst.getString("launch-failure"))
                assertEquals(first.uri.toString(), createdFirst.getString("consumed-uri"))
                assertArrayEquals(first.launch.readBytes(), createdFirst.getByteArray("consumed-bytes"))
                assertTrue(receive(first.uri).getBoolean("ok"))
                launch(second.uri)
                val createdSecond = observationAfterDelivery(second.uri)
                assertEquals("Second START must create a new consumer", baseline + 2, createdSecond.getInt("created"))
                assertNotEquals(createdFirst.getLong("instance"), createdSecond.getLong("instance"))
                val destructionDeadline = SystemClock.uptimeMillis() + 5000
                var destruction = createdSecond
                while (destruction.getLong("destroyed-instance") != createdFirst.getLong("instance") && SystemClock.uptimeMillis() < destructionDeadline) {
                    Thread.sleep(50)
                    destruction = receive(second.uri, "launch-observation")
                }
                assertEquals(createdFirst.getLong("instance"), destruction.getLong("destroyed-instance"))
                assertNull(createdSecond.getString("launch-failure"))
                assertEquals(second.uri.toString(), createdSecond.getString("consumed-uri"))
                assertArrayEquals(second.launch.readBytes(), createdSecond.getByteArray("consumed-bytes"))
                assertFalse(receive(first.uri).getBoolean("ok"))
                assertTrue(receive(second.uri).getBoolean("ok"))
                assertFalse(receive(second.uri, "write").getBoolean("ok"))
                assertEquals(listOf("grant:${first.tree}", "start:${first.uri}", "revoke:${first.tree}", "grant:${second.tree}", "start:${second.uri}"), events)
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                assertTrue(outgoing.all { it.flags == flags })
            } }
        } finally {
            ctx.startActivity(Intent().setComponent(ComponentName(receiverPackage, OwnedFlycastTargetActivity::class.java.name))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK).putExtra(OwnedFlycastTargetActivity.FINISH, true))
            prefs.edit().putString("tree", previousRecord).commit()
        }
    }

}
