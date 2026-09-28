package com.gamebex.client.net

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gamebex.client.data.local.DownloadRecord
import com.gamebex.client.data.local.LibraryDb
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * An empty published file can never attach to the library, so it fails terminally instead
 * of waiting for a library retry. Runs over the real Room database and the app's private dir.
 */
@RunWith(AndroidJUnit4::class)
class DownloadEmptyFileIngestTest {
    private val emptyCopy = "This file is empty. Download it again."
    private val retryCopy = "Saved, waiting for library retry."

    private fun ctx() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun scratchDir(): File =
        File(ctx().cacheDir, "g6_empty_dl").apply { deleteRecursively(); mkdirs() }

    private fun ingestRow(dir: File, fileName: String, bytes: Long): DownloadRecord = DownloadRecord(
        pageUrl = "https://example.test/g6/$fileName",
        title = "G6 Empty Test",
        destRelPath = "gb/$fileName",
        volumeId = "internal",
        bytes = bytes,
        status = DownloadEngine.ST_INGEST,
        fileName = fileName,
        platformFolder = "gb",
        transferKind = "ROM",
        destDirAbs = dir.absolutePath,
        createdAtMs = System.currentTimeMillis(),
        updatedAtMs = System.currentTimeMillis(),
    )

    /** No raw dest-dir: raw-dest rows are hidden from Downloads as art transfers (isArtTransfer). */
    private fun romIngestRow(fileName: String): DownloadRecord = DownloadRecord(
        pageUrl = "https://example.test/g6/$fileName",
        title = "G6 Empty Test",
        destRelPath = "gb/$fileName",
        volumeId = "internal",
        bytes = 0,
        status = DownloadEngine.ST_INGEST,
        fileName = fileName,
        platformFolder = "gb",
        transferKind = "ROM",
        createdAtMs = System.currentTimeMillis(),
        updatedAtMs = System.currentTimeMillis(),
    )

    private fun romDestDir(): File =
        com.gamebex.client.data.local.RomStore.internalRoot().apply { mkdirs() }
            .let { File(it, "gb").apply { mkdirs() } }

    /** Polls the row until [done] or [timeoutMs]; on timeout returns the last state seen. */
    private fun awaitRow(db: LibraryDb, id: Long, timeoutMs: Long = 15_000, done: (DownloadRecord) -> Boolean): DownloadRecord {
        val deadline = System.currentTimeMillis() + timeoutMs
        var row = runBlocking { db.downloads().getById(id) }
        while (row != null && !done(row) && System.currentTimeMillis() < deadline) {
            Thread.sleep(100)
            row = runBlocking { db.downloads().getById(id) }
        }
        return assertNotNull("download row $id vanished", row).let { row!! }
    }

    @Test
    fun zeroBytePublishFailsTerminallyWithTheEmptyFileMessage() {
        val db = LibraryDb.get(ctx())
        val dir = scratchDir()
        val file = File(dir, "empty.gb").apply { writeBytes(ByteArray(0)) }
        val id = runBlocking { db.downloads().insert(ingestRow(dir, "empty.gb", 0)) }
        try {
            DownloadEngine(ctx(), null).resume(id)
            val row = awaitRow(db, id) { it.error != null }
            assertEquals("0-byte publish is a terminal failure", DownloadEngine.ST_FAILED, row.status)
            assertEquals("the card line is the empty-file message", emptyCopy, row.error)
        } finally {
            runBlocking { db.downloads().deleteById(id) }
            file.delete()
            dir.delete()
        }
    }

    @Test
    fun nonEmptyFailedAttachStillShowsTheLibraryRetryCopy() {
        // Non-empty, but the raw dest is outside the library roots, so the attach fails and stays retryable.
        val db = LibraryDb.get(ctx())
        val dir = scratchDir()
        val file = File(dir, "kept.gb").apply { writeBytes(ByteArray(16)) }
        val id = runBlocking { db.downloads().insert(ingestRow(dir, "kept.gb", 16)) }
        try {
            DownloadEngine(ctx(), null).resume(id)
            val row = awaitRow(db, id) { it.error != null }
            assertEquals("failed attach stays retryable", DownloadEngine.ST_INGEST, row.status)
            assertEquals("failed attach keeps the retry copy", retryCopy, row.error)
        } finally {
            runBlocking { db.downloads().deleteById(id) }
            file.delete()
            dir.delete()
        }
    }

    @Test
    fun failedEmptyFileNeverReentersTheRetryState() {
        // The automatic retry pass only picks ST_INGEST rows, so ST_FAILED ends the retries.
        val db = LibraryDb.get(ctx())
        val dir = scratchDir()
        val file = File(dir, "empty2.gb").apply { writeBytes(ByteArray(0)) }
        val id = runBlocking { db.downloads().insert(ingestRow(dir, "empty2.gb", 0)) }
        try {
            DownloadEngine(ctx(), null).resume(id)
            val row = awaitRow(db, id) { it.error != null }
            assertEquals(DownloadEngine.ST_FAILED, row.status)

            val direct = DownloadIngest(db, ctx(), CoroutineScope(Dispatchers.IO)) { rec ->
                rec.destDirAbs?.let { File(it) }
            }
            runBlocking { direct.ingest(row) }
            val again = runBlocking { db.downloads().getById(id) }!!
            assertEquals("a retry never resurrects the retry state", DownloadEngine.ST_FAILED, again.status)
            assertEquals("a retry repeats the same message", emptyCopy, again.error)
        } finally {
            runBlocking { db.downloads().deleteById(id) }
            file.delete()
            dir.delete()
        }
    }

    @Test
    fun realAppHomeShowsTheEmptyFileFailureCard() {
        // The screenshot goes to the external files dir, which survives the post-run app data
        // restore, so it can be pulled afterwards.
        val db = LibraryDb.get(ctx())
        val dir = romDestDir()
        val file = File(dir, "shown.gb").apply { writeBytes(ByteArray(0)) }
        val id = runBlocking { db.downloads().insert(romIngestRow("shown.gb")) }
        var scenario: androidx.test.core.app.ActivityScenario<com.gamebex.client.MainActivity>? = null
        try {
            // The app's own engine instance: its snapshot stream is what
            // Home and the Downloads screen render from.
            (ctx().applicationContext as com.gamebex.client.GameBexApp).engine.resume(id)
            val row = awaitRow(db, id) { it.error != null }
            assertEquals(DownloadEngine.ST_FAILED, row.status)

            scenario = androidx.test.core.app.ActivityScenario.launch(
                Intent(ctx(), com.gamebex.client.MainActivity::class.java),
            )
            val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
            var found = false
            val deadline = System.currentTimeMillis() + 30_000
            while (!found && System.currentTimeMillis() < deadline) {
                val root = ui.rootInActiveWindow
                if (root != null && nodeContainsText(root, emptyCopy)) found = true
                if (!found) Thread.sleep(500)
            }
            val shot = ui.takeScreenshot()
            assertNotNull("screenshot captured", shot)
            val out = File(ctx().getExternalFilesDir(null), "g6-empty-card.png")
            out.outputStream().use { shot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            assertTrue(
                "real Home shows the card line '$emptyCopy' (screenshot: ${out.absolutePath})",
                found,
            )
        } finally {
            runCatching { scenario?.close() }
            runBlocking { db.downloads().deleteById(id) }
            file.delete()
            dir.delete()
        }
    }

    @Test
    fun realAppDownloadsRowDetailShowsTheEmptyFileCopy() {
        // SelectedJob draws row.error verbatim, so the pane must show the empty-file copy.
        val db = LibraryDb.get(ctx())
        val dir = romDestDir()
        val file = File(dir, "rowdetail.gb").apply { writeBytes(ByteArray(0)) }
        val id = runBlocking { db.downloads().insert(romIngestRow("rowdetail.gb")) }
        var scenario: androidx.test.core.app.ActivityScenario<com.gamebex.client.MainActivity>? = null
        try {
            (ctx().applicationContext as com.gamebex.client.GameBexApp).engine.resume(id)
            val row = awaitRow(db, id) { it.error != null }
            assertEquals(DownloadEngine.ST_FAILED, row.status)

            scenario = androidx.test.core.app.ActivityScenario.launch(
                Intent(ctx(), com.gamebex.client.MainActivity::class.java),
            )
            val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
            val deadline = System.currentTimeMillis() + 30_000
            assertTrue("Downloads opens from the rail", clickNodeText(ui, "Downloads", deadline))
            Thread.sleep(1_500)
            val rowClicked = clickNodeTextContaining(ui, "G6 Empty Test", deadline)
            val shot = ui.takeScreenshot()
            assertNotNull("screenshot captured", shot)
            val out = File(ctx().getExternalFilesDir(null), "g6-empty-row.png")
            out.outputStream().use { shot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            var found = false
            var copyInTree = false
            val deadline2 = System.currentTimeMillis() + 20_000
            while (!found && System.currentTimeMillis() < deadline2) {
                val root = ui.rootInActiveWindow
                if (root != null &&
                    nodeContainsText(root, "SELECTED JOB") &&
                    nodeContainsText(root, "rowdetail.gb") &&
                    nodeContainsText(root, "Failed")
                ) {
                    found = true
                    copyInTree = nodeContainsText(root, emptyCopy)
                }
                if (!found) Thread.sleep(500)
            }
            assertEquals("the row's own error copy is what the pane shows", emptyCopy, row.error)
            assertTrue(
                "Downloads shows the failed row's detail pane " +
                    "(row clicked: $rowClicked, copy in a11y tree: $copyInTree, " +
                    "screenshot: ${out.absolutePath})",
                found,
            )
        } finally {
            runCatching { scenario?.close() }
            runBlocking { db.downloads().deleteById(id) }
            file.delete()
            dir.delete()
        }
    }

    private fun clickNodeTextContaining(
        ui: android.app.UiAutomation,
        text: String,
        deadlineMs: Long,
    ): Boolean {
        while (System.currentTimeMillis() < deadlineMs) {
            val root = ui.rootInActiveWindow
            val node = root?.let { findNodeContaining(it, text) }
            if (node != null && clickNodeOrAncestor(node)) return true
            Thread.sleep(300)
        }
        return false
    }

    private fun findNodeContaining(
        node: android.view.accessibility.AccessibilityNodeInfo,
        text: String,
    ): android.view.accessibility.AccessibilityNodeInfo? {
        if (node.text?.contains(text) == true) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findNodeContaining(child, text)?.let { return it }
        }
        return null
    }

    private fun clickNodeText(
        ui: android.app.UiAutomation,
        text: String,
        deadlineMs: Long,
    ): Boolean {
        while (System.currentTimeMillis() < deadlineMs) {
            val root = ui.rootInActiveWindow
            val node = root?.let { findNode(it, text) }
            if (node != null && clickNodeOrAncestor(node)) return true
            Thread.sleep(300)
        }
        return false
    }

    private fun findNode(
        node: android.view.accessibility.AccessibilityNodeInfo,
        text: String,
    ): android.view.accessibility.AccessibilityNodeInfo? {
        if (node.text?.toString() == text) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findNode(child, text)?.let { return it }
        }
        return null
    }

    /** Walks the tree by hand; the a11y node search is unreliable here. */
    private fun nodeContainsText(
        node: android.view.accessibility.AccessibilityNodeInfo,
        text: String,
    ): Boolean {
        if (node.text?.contains(text) == true) return true
        if (node.contentDescription?.contains(text) == true) return true
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (nodeContainsText(child, text)) return true
        }
        return false
    }

    /** The label node often sits inside the clickable cell. */
    private fun clickNodeOrAncestor(
        node: android.view.accessibility.AccessibilityNodeInfo,
    ): Boolean {
        var cur: android.view.accessibility.AccessibilityNodeInfo? = node
        while (cur != null) {
            if (cur.isClickable &&
                cur.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
            ) {
                return true
            }
            cur = cur.parent
        }
        return false
    }

}
