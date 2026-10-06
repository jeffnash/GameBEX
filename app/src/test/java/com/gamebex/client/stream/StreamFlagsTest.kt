package com.gamebex.client.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamFlagsTest {
    @Test fun everyKeyResolvesOnceAndDefaultsMatch() {
        for (def in StreamFlags.ALL) {
            assertEquals(def.default, StreamFlags.resolve(def.key, false, { error("prop") }, { null }))
            assertFalse(StreamFlags.isNonDefault(def.key, false, { null }, { null }))
        }
        assertEquals(StreamFlags.ALL.size, StreamFlags.BY_KEY.size)
    }

    @Test fun adbPropBeatsFlagBeatsDefault() {
        val prop = { name: String -> if (name == "audio_regulator") "1" else null }
        val pref = { name: String -> if (name == "audio_regulator") "0" else null }
        assertEquals("1", StreamFlags.resolve("audio_regulator", true, prop, pref))
        assertEquals("0", StreamFlags.resolve("audio_regulator", false, prop, pref))
        assertEquals("0", StreamFlags.resolve("audio_regulator", false, { null }, { null }))
    }

    @Test fun emptyPropAndPrefFallThroughToDefault() {
        assertEquals("512", StreamFlags.resolve("audio_lead_frames", true, { "" }, { "" }))
        assertFalse(StreamFlags.isNonDefault("audio_lead_frames", true, { "" }, { "" }))
    }

    @Test fun promisingOnPicksFirstNonDefaultChoiceValue() {
        val start = StreamFlags.BY_KEY.getValue("start_level")
        assertEquals("-1", start.default)
        assertEquals("2", StreamFlags.promisingOn(start))
        assertEquals("2", StreamFlags.promisingOn(StreamFlags.BY_KEY.getValue("ra_poll")))
        assertEquals("16", StreamFlags.promisingOn(StreamFlags.BY_KEY.getValue("ra_audio_latency")))
        assertEquals("1", StreamFlags.promisingOn(StreamFlags.BY_KEY.getValue("audio_regulator")))
    }

    @Test fun activeSummaryListsOnlyNonDefaultFlagsOnSide() {
        val pref = { name: String ->
            when (name) {
                "audio_regulator" -> "1"
                "start_level" -> "2"
                "audio_lead_frames" -> "512"
                else -> null
            }
        }
        assertEquals(
            "Flags: audio_regulator=1",
            StreamFlags.activeSummary(false, { null }, pref, setOf(StreamFlagSide.WATCHING, StreamFlagSide.BOTH)),
        )
        assertEquals(
            "Flags: start_level=2, audio_regulator=1",
            StreamFlags.activeSummary(false, { null }, pref),
        )
        assertNull(StreamFlags.activeSummary(false, { null }, { null }))
    }

    @Test fun flippedDefaultsAreOnWithNoPrefStored() {
        // Fresh installs get keyframes-on-request and the host overlay with no setup.
        for (key in listOf("keyframes_on_request", "overlay_26b")) {
            assertEquals("1", StreamFlags.BY_KEY.getValue(key).default)
            assertEquals("1", StreamFlags.resolve(key, false, { null }, { null }))
            assertFalse(StreamFlags.isNonDefault(key, false, { null }, { null }))
            assertEquals("0", StreamFlags.resolve(key, false, { null }, { "0" }))
            assertTrue(StreamFlags.isNonDefault(key, false, { null }, { "0" }))
        }
    }

    @Test fun toggleOnFallsBackToTheBuiltInDefault() {
        assertTrue(StreamFlags.toggleOn("overlay_26b") { null })
        assertTrue(StreamFlags.toggleOn("overlay_26b") { "" })
        assertFalse(StreamFlags.toggleOn("overlay_26b") { "0" })
        assertTrue(StreamFlags.toggleOn("overlay_26b") { "1" })
        assertFalse(StreamFlags.toggleOn("audio_regulator") { null })
        assertTrue(StreamFlags.toggleOn("audio_regulator") { "1" })
    }

    @Test fun choiceDefaultsAreMembers() {
        for (def in StreamFlags.ALL) {
            val type = def.type as? StreamFlagType.Choice ?: continue
            assertTrue("$def", def.default in type.values)
        }
    }

    @Test fun groupsListInOrderWithCompleteMetadata() {
        assertEquals(
            listOf(
                StreamFlagGroup.DEFAULT_ON, StreamFlagGroup.TRY,
                StreamFlagGroup.WIFI, StreamFlagGroup.NO_GAIN,
            ),
            StreamFlags.ALL.map { it.group }.distinct(),
        )
        for (def in StreamFlags.ALL) {
            assertTrue(def.key, def.key.matches(Regex("[a-z0-9_]+")))
            assertTrue(def.key, def.label.isNotBlank())
            assertTrue(def.key, def.description.isNotBlank())
            val p = def.promising
            if (p != null) {
                val type = def.type as StreamFlagType.Choice
                assertTrue(def.key, p in type.values)
                assertTrue(def.key, p != def.default)
            }
        }
    }

    @Test fun everyImprovementHasItsFlagInTheRightGroup() {
        fun keys(group: StreamFlagGroup) = StreamFlags.ALL.filter { it.group == group }.map { it.key }.toSet()
        assertEquals(
            setOf("audio_lead_frames", "keyframes_on_request", "overlay_26b"),
            keys(StreamFlagGroup.DEFAULT_ON),
        )
        assertEquals(
            setOf(
                "start_level", "audio_regulator", "ra_audio_latency", "ra_swapchain",
                "ra_no_smooth", "minimal_postprocess", "ra_poll", "unbuffered_input",
                "encoder_fps", "overlay_autohide_v3",
            ),
            keys(StreamFlagGroup.TRY),
        )
        assertEquals(
            setOf(
                "queue_deadline_ms", "latency_cc", "latest_output", "input_cs6",
                "frame_rate_fixed", "frame_rate_always",
            ),
            keys(StreamFlagGroup.WIFI),
        )
        assertEquals(
            setOf(
                "hud_off", "touch_pad_hidden", "fixed_surface", "surface_opaque",
                "display_mode", "stream_crop",
            ),
            keys(StreamFlagGroup.NO_GAIN),
        )
    }
}
