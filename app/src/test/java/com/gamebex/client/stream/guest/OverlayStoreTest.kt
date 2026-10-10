package com.gamebex.client.stream.guest

import com.gamebex.client.together.LinkMsg
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class OverlayStoreTest {
    @Test fun retroDuplicatePartsDoNotCompleteOrEraseLateArtAndOldRoundsCannotReplaceCurrent() {
        StreamOverlayStore.clear()
        try {
            val cfg = "overlays=1\noverlay0_normalized=true\noverlay0_descs=1\noverlay0_desc0=\"a,0.5,0.5,rect,0.1,0.1\"\noverlay0_desc0_overlay=a.png"
            val base = LinkMsg.StreamOverlay("s", 10, cfg = cfg, partCount = 3)
            StreamOverlayStore.publish(10, base)
            val part = base.copy(cfg = "", partIndex = 1, images = listOf(LinkMsg.OverlayImage("a.png", Base64.getEncoder().encodeToString(byteArrayOf(1)))))
            StreamOverlayStore.publish(10, part)
            StreamOverlayStore.publish(10, part)
            assertFalse(StreamOverlayStore.current()!!.complete)
            assertEquals(2, StreamOverlayStore.current()!!.partsReceived)
            StreamOverlayStore.publish(10, base)
            assertTrue(StreamOverlayStore.current()!!.images.containsKey("a.png"))
            StreamOverlayStore.publish(10, part.copy(partIndex = 2))
            assertTrue(StreamOverlayStore.current()!!.complete)
            StreamOverlayStore.publish(9, base.copy(round = 9))
            assertEquals(10, StreamOverlayStore.current()!!.round)
        } finally { StreamOverlayStore.clear() }
    }

    @Test fun nativeDuplicateMetadataAndArtKeepIdentityWithoutOvercounting() {
        EmuOverlayStore.clear()
        try {
            val control = LinkMsg.EmuControl("A", w = 20, h = 20)
            val base = LinkMsg.StreamEmuOverlay("s", 10, "dolphin", listOf(control), partCount = 3)
            EmuOverlayStore.publish(10, base)
            val part = base.copy(partIndex = 1, controls = listOf(control.copy(art = Base64.getEncoder().encodeToString(byteArrayOf(1)))))
            EmuOverlayStore.publish(10, part)
            EmuOverlayStore.publish(10, part)
            assertEquals(2, EmuOverlayStore.current()!!.partsReceived)
            EmuOverlayStore.publish(10, base)
            assertTrue(EmuOverlayStore.current()!!.art.containsKey(emuArtKey("A", "art")))
            assertTrue(EmuOverlayStore.current()!!.controls.single().art.isNotEmpty())
            EmuOverlayStore.publish(9, base.copy(round = 9))
            assertEquals(10, EmuOverlayStore.current()!!.round)
            EmuOverlayStore.publish(10, part.copy(partIndex = -1))
            assertEquals(2, EmuOverlayStore.current()!!.partsReceived)
        } finally { EmuOverlayStore.clear() }
    }
}
