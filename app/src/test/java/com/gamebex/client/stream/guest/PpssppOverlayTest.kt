package com.gamebex.client.stream.guest

import kotlinx.serialization.json.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class PpssppOverlayTest {
    private fun vector(name: String) = Json.parseToJsonElement(requireNotNull(javaClass.getResource("/emulator-facts/$name")).readText()).jsonObject
    private fun metadataBytes() = Base64.getDecoder().decode(vector("ppsspp-atlas-resource-facts.json")["compressed_metadata_base64"]!!.jsonPrimitive.content)

    @Test fun installedCompressedMetadataMatchesEveryMeasuredRecord() {
        val bytes = metadataBytes()
        val facts = vector("ppsspp-atlas-resource-facts.json")
        assertEquals(facts["source_sha256"]!!.jsonPrimitive.content, MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
        val parsed = parsePpssppAtlas(bytes)!!
        assertEquals(facts["header"]!!.jsonArray[3].jsonPrimitive.int, parsed.size)
        facts["resource_records"]!!.jsonArray.map { it.jsonObject }.filter { it["atlas"]!!.jsonPrimitive.content == "ui_atlas" }.forEach { row ->
            val image = parsed.getValue(row["resource"]!!.jsonPrimitive.content)
            val dimensions = row["dimensions"]!!.jsonArray.map { it.jsonPrimitive.int }
            assertEquals(dimensions, listOf(image.width, image.height))
            val uv = row["uv"]!!.jsonArray.map { it.jsonPrimitive.float }
            assertEquals(uv, listOf(image.u0, image.v0, image.u1, image.v1))
        }
    }

    @Test fun malformedAndOversizedMetadataNeverProducesGuessedDimensions() {
        assertNull(parsePpssppAtlas(ByteArray(15)))
        assertNull(parsePpssppAtlas(ByteArray(HostOverlayBundle.MAX_IMAGE_BYTES + 1)))
        val original = metadataBytes()
        assertNull(parsePpssppAtlas(original.copyOf(original.size - 1)))
        for ((offset, value) in listOf(0 to 0, 4 to 7, 8 to -1, 12 to Int.MAX_VALUE, 16 to Int.MAX_VALUE)) {
            val bad = original.copyOf()
            ByteBuffer.wrap(bad).order(ByteOrder.LITTLE_ENDIAN).putInt(offset, value)
            assertNull(parsePpssppAtlas(bad))
        }
    }

    @Test fun runtimeConfigurationAndInstalledDimensionsMatchEveryFreshNominalControl() {
        val facts = vector("ppsspp-current-geometry.json")
        val config = facts["control_config"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }
        val window = facts["host_safe_rect"]!!.jsonArray.map { it.jsonPrimitive.int }
        val controls = ppssppLayout(config, parsePpssppAtlas(metadataBytes())!!, window[2], window[3], facts["density_dpi"]!!.jsonPrimitive.int, facts["ui_scale_factor"]!!.jsonPrimitive.float)!!.associateBy { it.id }
        assertEquals(facts["controls"]!!.jsonArray.size, controls.size)
        facts["controls"]!!.jsonArray.forEach { raw ->
            val row = raw.jsonObject
            val c = controls.getValue(row["id"]!!.jsonPrimitive.content)
            assertEquals(row["resource"]!!.jsonPrimitive.content, c.resource)
            val expected = row["rect"]!!.jsonArray.map { it.jsonPrimitive.float }
            listOf(c.x + window[0], c.y + window[1], c.w, c.h).forEachIndexed { index, actual -> assertEquals(c.id, expected[index], actual, 0.001f) }
        }
        assertEquals(67.08333f, controls.getValue("analog").travel, 0.001f)
        assertNull(ppssppLayout(config - "DPadScale", parsePpssppAtlas(metadataBytes())!!, window[2], window[3], 280, 0f))
        assertTrue(ppssppLayout(config + ("ShowTouchControls" to "False"), emptyMap(), window[2], window[3], 280, 0f)!!.isEmpty())
    }

    @Test fun digitalSectorsAndAnalogTravelHonorTheMeasuredModel() {
        assertArrayEquals(BooleanArray(4), PpssppOverlay.dpadDirs(50f, 50f, 100f, 100f))
        assertArrayEquals(booleanArrayOf(true, false, false, true), PpssppOverlay.dpadDirs(90f, 10f, 100f, 100f))
        val cardinal = PpssppOverlay.dpadDirs(90f, 15f, 100f, 100f, true)
        assertEquals(1, cardinal.count { it })
        val stick = PpssppOverlay.stickSample(100f, -20f, 50f)
        assertEquals(1f, stick.nx, 0f); assertEquals(-0.4f, stick.ny, 0f)
        assertEquals(50f, stick.thumbDx, 0f); assertEquals(-20f, stick.thumbDy, 0f)
    }
}
