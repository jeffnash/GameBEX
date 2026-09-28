package com.gamebex.client.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

/** ArtStore guards: magic sniff, bounded streaming, staging, ownership. */
class ArtStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun padded(vararg head: Byte): ByteArray {
        val out = ByteArray(128)
        head.forEachIndexed { i, b -> out[i] = b }
        return out
    }

    @Test fun isImageAcceptsJpegPngWebp() {
        assertTrue(ArtStore.isImage(padded(0xFF.toByte(), 0xD8.toByte())))
        assertTrue(ArtStore.isImage(padded(0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte())))
        assertTrue(
            ArtStore.isImage(
                padded(
                    0x52.toByte(), 0x49.toByte(), 0x46.toByte(), 0x46.toByte(),
                    0, 0, 0, 0,
                    0x57.toByte(), 0x45.toByte(), 0x42.toByte(), 0x50.toByte(),
                ),
            ),
        )
    }

    @Test fun isImageRejectsGarbageAndShort() {
        assertFalse(ArtStore.isImage(ByteArray(128))) // zeros
        assertFalse(ArtStore.isImage("not an image at all....".toByteArray()))
        assertFalse(ArtStore.isImage(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))) // short
        assertFalse(ArtStore.isImage(ByteArray(0)))
    }

    @Test fun copyBoundedRejectsOverLimitStreams() {
        val dest = File(tmp.root, "out.bin")
        assertFalse(ArtStore.copyBounded(ByteArrayInputStream(ByteArray(101)), dest, 100))
        assertTrue(ArtStore.copyBounded(ByteArrayInputStream(ByteArray(100)), dest, 100))
        assertEquals(100, dest.length())
    }

    @Test fun copyBoundedRejectsTinyStreams() {
        val dest = File(tmp.root, "tiny.bin")
        assertFalse(ArtStore.copyBounded(ByteArrayInputStream(ByteArray(10)), dest, 100))
    }

    @Test fun isImageFileReadsHeaderOnly() {
        val good = File(tmp.root, "good.jpg").apply {
            writeBytes(padded(0xFF.toByte(), 0xD8.toByte()))
        }
        assertTrue(ArtStore.isImageFile(good))
        val bad = File(tmp.root, "bad.jpg").apply { writeBytes(ByteArray(128)) }
        assertFalse(ArtStore.isImageFile(bad))
        assertFalse(ArtStore.isImageFile(File(tmp.root, "missing.jpg")))
    }

    @Test fun isValidImageFileAcceptsMagicWhenDecoderIsStub() {
        // Unit tests run against android.jar stubs (BitmapFactory throws);
        // magic-valid files must still pass, garbage must fail.
        val good = File(tmp.root, "v.jpg").apply {
            writeBytes(padded(0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte()))
        }
        assertTrue(ArtStore.isValidImageFile(good))
        val bad = File(tmp.root, "x.jpg").apply { writeBytes(ByteArray(128)) }
        assertFalse(ArtStore.isValidImageFile(bad))
    }

    @Test fun moveFileReplacesDestination() {
        val src = File(tmp.root, "a.tmp").apply { writeText("new") }
        val dest = File(tmp.root, "b.jpg").apply { writeText("old") }
        assertTrue(ArtStore.moveFile(src, dest))
        assertEquals("new", dest.readText())
        assertFalse(src.exists())
    }

    @Test fun ownerMarkersRoundTrip() {
        val dir = tmp.root
        assertFalse(ArtStore.isUserOwned(dir, ArtStore.SLOT_BOX))
        ArtStore.markUserOwned(dir, ArtStore.SLOT_BOX)
        assertTrue(ArtStore.isUserOwned(dir, ArtStore.SLOT_BOX))
        assertFalse(ArtStore.isUserOwned(dir, ArtStore.SLOT_HERO))
        ArtStore.clearOwner(dir, ArtStore.SLOT_BOX)
        assertFalse(ArtStore.isUserOwned(dir, ArtStore.SLOT_BOX))
    }

    @Test fun stageFileDoesNotCollideWithSlot() {
        val stage = ArtStore.stageFileFor(tmp.root, ArtStore.SLOT_BOX)
        assertEquals("box.fetch.tmp", stage.name)
    }
}
