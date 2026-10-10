package com.gamebex.client.together.session

import com.gamebex.client.stream.guest.EmuOverlayStore
import com.gamebex.client.stream.guest.OverlayOutput
import com.gamebex.client.stream.proto.PadState
import com.gamebex.client.together.LinkMsg
import com.gamebex.client.together.proto.Frames
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The host's emu part packer (part 0 locates, art follows)
 * and the Flycast atlas PNG scan.
 */
class EmuOverlayPackTest {
    private fun control(id: String, artChars: Int): LinkMsg.EmuControl =
        LinkMsg.EmuControl(
            id = id, x = 10, y = 20, w = 30, h = 40,
            art = "a".repeat(artChars),
            output = OverlayOutput.Buttons(PadState.A),
        )

    @Before fun setUp() {
        EmuOverlayStore.clear()
        EmuOverlayStore.onMissingControls = null
    }

    @After fun tearDown() {
        EmuOverlayStore.clear()
        EmuOverlayStore.onMissingControls = null
    }

    @Test fun partZeroLocatesAndArtFollows() {
        val bundle = EmuOverlayReader.Bundle(
            "dolphin", listOf(control("A", 100), control("B", 200)), 1000, 1000,
        )
        val parts = EmuOverlayReader.packParts("s", 3, bundle)
        assertEquals(2, parts.size)
        val first = parts[0]
        assertEquals(0, first.partIndex)
        assertEquals(2, first.partCount)
        assertEquals(listOf("A", "B"), first.controls.map { it.id })
        assertTrue(first.controls.all { it.art.isEmpty() })
        // Rects + outputs survive the strip.
        assertEquals(10, first.controls[0].x)
        assertEquals(OverlayOutput.Buttons(PadState.A), first.controls[0].output)
        val second = parts[1]
        assertEquals(1, second.partIndex)
        assertEquals(100, second.controls[0].art.length)
        assertEquals(200, second.controls[1].art.length)
    }

    @Test fun bigArtChunksAcrossParts() {
        val big = EmuOverlayReader.MAX_ART_CHARS_PER_PART / 2 + 1
        val bundle = EmuOverlayReader.Bundle(
            "nethersx2", listOf(control("A", big), control("B", big), control("C", big)), 1000, 1000,
        )
        val parts = EmuOverlayReader.packParts("s", 3, bundle)
        // Greedy fill: no two fit together, so one control per follow-up.
        assertEquals(4, parts.size)
        assertEquals(0, parts[0].partIndex)
        assertEquals(4, parts[0].partCount)
        assertEquals(listOf("A"), parts[1].controls.map { it.id })
        assertEquals(listOf("B"), parts[2].controls.map { it.id })
        assertEquals(listOf("C"), parts[3].controls.map { it.id })
    }

    @Test fun artlessControlsNeedNoFollowUp() {
        val bundle = EmuOverlayReader.Bundle(
            "flycast",
            listOf(
                LinkMsg.EmuControl(id = "LEFT_UP", noArt = true),
                LinkMsg.EmuControl(id = "FF", art = "abc"),
            ),
            1000, 1000,
        )
        val parts = EmuOverlayReader.packParts("s", 3, bundle)
        assertEquals(2, parts.size)
        // The hit-only control rides part 0 only.
        assertEquals(listOf("FF"), parts[1].controls.map { it.id })
    }

    @Test fun packedPartsRoundTripThroughTheStore() {
        val bundle = EmuOverlayReader.Bundle(
            "dolphin", listOf(control("A", 100), control("B", 200)), 1604, 720,
        )
        EmuOverlayReader.packParts("s", 3, bundle).forEach { EmuOverlayStore.publish(3, it) }
        val stored = EmuOverlayStore.current()
        assertEquals("dolphin", stored?.backend)
        assertEquals(2, stored?.controls?.size)
        assertEquals(2, stored?.partsReceived)
        assertEquals(100, stored?.controls?.first { it.id == "A" }?.art?.length)
        assertTrue(EmuOverlayStore.degradedIds().isEmpty())
    }

    @Test fun partBudgetFitsTheLinkFrame() {
        // Parts must fit one link frame: b64 art is 1 byte/char and
        // the headroom covers the envelope + metadata.
        assertTrue(EmuOverlayReader.MAX_ART_CHARS_PER_PART + 8_192 <= Frames.MAX_JSON)
    }

    private fun be32(value: Int): ByteArray = byteArrayOf(
        (value shr 24).toByte(), (value shr 16).toByte(), (value shr 8).toByte(), value.toByte(),
    )

    private fun png(w: Int, h: Int): ByteArray {
        val sig = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val ihdr = be32(13) + "IHDR".toByteArray() + be32(w) + be32(h) +
            byteArrayOf(8, 6, 0, 0, 0) + be32(0x12345678)
        val iend = be32(0) + "IEND".toByteArray() + be32(0x9ABCDEF0.toInt())
        return sig + ihdr + iend
    }

    @Test fun scanForPngExtractsSizedPng() {
        val want = png(512, 256)
        val data = ByteArray(100) { 0x41 } + png(64, 64) + ByteArray(50) { 0x42 } + want + ByteArray(10)
        val found = EmuOverlayReader.scanForPng(data, 512, 256)
        assertTrue(found != null && found.contentEquals(want))
    }

    @Test fun scanForPngReturnsNullWithoutMatch() {
        assertNull(EmuOverlayReader.scanForPng(ByteArray(100), 512, 256))
        assertNull(EmuOverlayReader.scanForPng(png(64, 64), 512, 256))
        // Truncated: dims match but chunks never reach IEND.
        assertNull(EmuOverlayReader.scanForPng(png(512, 256).copyOf(20), 512, 256))
    }

    @Test fun packPartsStampsTheCropRectOnEveryPart() {
        val bundle = EmuOverlayReader.Bundle(
            "dolphin", listOf(control("A", 100)), 1000, 1000,
            displayW = 1604, displayH = 720,
            cropX = 36, cropY = 0, cropW = 960, cropH = 720,
        )
        val parts = EmuOverlayReader.packParts("s", 3, bundle)
        assertTrue(parts.isNotEmpty())
        parts.forEach { part ->
            assertEquals(36, part.cropX)
            assertEquals(0, part.cropY)
            assertEquals(960, part.cropW)
            assertEquals(720, part.cropH)
        }
    }
}
