package com.gamebex.client.net

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Staged-publish protocol: previous bytes survive until new bytes validate. */
class DownloadIngestTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test fun freshPublishMovesBytesAndCleansScratch() {
        val part = File(tmp.root, "game.gb.part").apply { writeBytes(ByteArray(16) { it.toByte() }) }
        val out = DownloadIngest.publishStaged(part, tmp.root, "game.gb", 16)
        assertTrue(out != null && out.isFile)
        assertEquals(16, out!!.length())
        assertTrue(!part.exists())
        assertTrue(!File(tmp.root, "game.gb.tmp").exists())
        assertTrue(!File(tmp.root, "game.gb.bak").exists())
    }

    @Test fun replacementPublishSwapsContent() {
        File(tmp.root, "game.gb").writeText("old-bytes")
        val part = File(tmp.root, "game.gb.part").apply { writeText("new-bytes!") }
        val out = DownloadIngest.publishStaged(part, tmp.root, "game.gb", 10)
        assertTrue(out != null)
        assertEquals("new-bytes!", File(tmp.root, "game.gb").readText())
        assertTrue(!File(tmp.root, "game.gb.tmp").exists())
        assertTrue(!File(tmp.root, "game.gb.bak").exists())
    }

    @Test fun sizeMismatchAbortsWithoutPublishing() {
        val part = File(tmp.root, "game.gb.part").apply { writeBytes(ByteArray(8)) }
        assertNull(DownloadIngest.publishStaged(part, tmp.root, "game.gb", 16))
        assertTrue(!File(tmp.root, "game.gb").exists())
        assertTrue(!File(tmp.root, "game.gb.tmp").exists())
    }
}
