package com.gamebex.client.stream.guest

import com.gamebex.client.together.LinkMsg
import com.gamebex.client.together.proto.LinkJson
import java.nio.file.Files
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RetroAlphaTest {
    private val facts get() = Json.parseToJsonElement(requireNotNull(javaClass.getResource("/emulator-facts/retro-held-floating-alpha-observed.json")).readText()).jsonObject
    private val desc get() = OverlayDesc("a", 0.5f, 0.5f, OverlayShape.RECT, 0.5f, 0.5f, alphaMod = facts.getValue("highlight_multiplier").jsonPrimitive.float)

    @Test fun floatingRasterMultipliesInterpolatedAlphaBeforeClippingAndInvalidGainsAreTransparent() {
        val src = intArrayOf(0x40ffffff, 0xc0ffffff.toInt())
        val gain = facts.getValue("held_global_multiplier").jsonPrimitive.float
        val floating = renderStraightFrac(src, 2, 1, 3, 1, 0, 0, 0f, 0f, 3f, 1f, gain)
        assertArrayEquals(intArrayOf(90, 179, 255), floating.map { it ushr 24 }.toIntArray())
        assertArrayEquals(intArrayOf(64, 128, 192), renderStraightFrac(src, 2, 1, 3, 1, 0, 0, 0f, 0f, 3f, 1f).map { it ushr 24 }.toIntArray())
        for (invalid in listOf(-1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertTrue(renderStraightFrac(src, 2, 1, 3, 1, 0, 0, 0f, 0f, 3f, 1f, invalid).all { it ushr 24 == 0 })
        }
        val settings = RaOverlaySettings.parse("video_driver=gl\ninput_overlay_opacity=0.7", "input_overlay_video_driver=gl3")
        assertEquals(gain, raAlphaFor(desc, settings, true), 0f)
        assertEquals(0f, raAlphaFor(desc.copy(alphaMod = -2f), settings.copy(opacity = -0.7f), true), 0f)
        assertEquals(0f, raAlphaFor(desc.copy(alphaMod = Float.NaN), settings, true), 0f)
        assertEquals(0f, raAlphaFor(desc, settings.copy(opacity = Float.POSITIVE_INFINITY), true), 0f)
    }

    @Test fun effectiveDriverFollowsMainSessionAndCorePrecedence() {
        val root = Files.createTempDirectory("retro-driver").toFile()
        try {
            root.resolve("retroarch.cfg").writeText("video_driver = \"${facts.getValue("effective_config_driver").jsonPrimitive.content}\"\ninput_overlay_opacity = ${facts.getValue("opacity")}")
            val gain = facts.getValue("held_global_multiplier").jsonPrimitive.float
            assertEquals(gain, raAlphaFor(desc, HostOverlayBundle.effectiveSettings(root, "test", null), true), 0f)
            val session = "video_driver=\"gl3\""
            assertEquals(1f, raAlphaFor(desc, HostOverlayBundle.effectiveSettings(root, "test", session), true), 0f)
            val core = root.resolve("config/test/test.cfg").apply { parentFile!!.mkdirs(); writeText("video_driver = \"gl\"") }
            assertEquals(gain, raAlphaFor(desc, HostOverlayBundle.effectiveSettings(root, "test", session), true), 0f)
            core.writeText("input_overlay_scale_landscape = 1")
            assertEquals(1f, raAlphaFor(desc, HostOverlayBundle.effectiveSettings(root, "test", session), true), 0f)
        } finally { root.deleteRecursively() }
    }

    @Test fun driverBehaviorSurvivesPortableMetadataAndOldPayloadKeepsPackedDefault() {
        val settings = RaOverlaySettings.parse("video_driver=gl\ninput_overlay_opacity=${facts.getValue("opacity")}")
        val message = LinkMsg.StreamOverlay("s", 1, settings = settings.toMessage(320, 240))
        val decoded = LinkJson.decode(LinkJson.encode(message)) as LinkMsg.StreamOverlay
        assertEquals(facts.getValue("held_global_multiplier").jsonPrimitive.float, raAlphaFor(desc, decoded.settings.toSettings(), true), 0f)
        StreamOverlayStore.clear()
        try {
            StreamOverlayStore.publish(1, decoded.copy(cfg = "overlays=1\noverlay0_descs=0", partCount = 2))
            StreamOverlayStore.publish(1, decoded.copy(cfg = "", settings = LinkMsg.OverlaySettingsMsg(), partIndex = 1, partCount = 2))
            assertEquals(facts.getValue("held_global_multiplier").jsonPrimitive.float, raAlphaFor(desc, StreamOverlayStore.current()!!.settings, true), 0f)
        } finally { StreamOverlayStore.clear() }
        val legacy = LinkJson.decode("""{"t":"stream_overlay","sessionId":"s","round":1,"settings":{"opacity":0.7}}""") as LinkMsg.StreamOverlay
        assertEquals(1f, raAlphaFor(desc, legacy.settings.toSettings(), true), 0f)
    }
}
