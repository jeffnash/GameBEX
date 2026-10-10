package com.gamebex.client.stream.guest

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gamebex.client.stream.proto.PadState
import com.gamebex.client.together.LinkMsg
import com.gamebex.client.together.proto.LinkJson
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RetroNdsRuntimeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun nativeSizedHostConfigReachesGuestAndDirectionalTouchesHighlightZeroReachArt() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val facts = Json.parseToJsonElement(context.assets.open("emulator-facts/retro-nds-observed.json").bufferedReader().use { it.readText() }).jsonObject
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "owned-retro-nds").apply { mkdirs() }
        StreamOverlayStore.clear()
        try {
            val fields = facts.getValue("all_native_fields").jsonObject
            val compact = fields.entries.joinToString("\n", postfix = "\n") { (k, v) -> "$k=${v.jsonPrimitive.content}" }
            assertEquals(facts.getValue("semantic_compact_characters_observed").jsonPrimitive.int, compact.length)
            val cfg = root.resolve("NDS.cfg").apply { writeText(compact + "#" + "owned padding".padEnd(facts.getValue("full_characters").jsonPrimitive.int - compact.length - 1, ' ')) }
            val image = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply { eraseColor(0x40ffffff) }
            val bytes = ByteArrayOutputStream().also { image.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            fields.filterKeys { it.endsWith("_overlay") }.values.map { it.jsonPrimitive.content }.toSet().forEach { root.resolve(it).writeBytes(bytes) }
            val settings = RaOverlaySettings.parse("video_driver=gl\ninput_overlay_opacity=0.7\ninput_overlay_auto_scale=false")
            val parts = requireNotNull(HostOverlayBundle.packFile(cfg, "s", 1, settings, 320, 180)) { "complete oversized native first page must be packed" }
            parts.forEach { StreamOverlayStore.publish(1, LinkJson.decode(LinkJson.encode(it)) as LinkMsg.StreamOverlay) }
            val stored = requireNotNull(StreamOverlayStore.current())
            val page = requireNotNull(stored.layout.playPage())
            assertEquals(23, page.descs.size)
            assertTrue(stored.complete); assertTrue(stored.truncated)
            assertTrue(stored.missingRefs().isEmpty())
            val resolved = resolveRaPage(page, stored.settings, 320, 180)
            fun center(index: Int): Offset {
                val r = resolved.descs[index]
                return Offset((r.modX + r.modW / 2) * 320, (r.modY + r.modH / 2) * 180)
            }
            val emitted = mutableListOf<PadState>()
            compose.setContent {
                val density = LocalDensity.current
                Box(Modifier.size(with(density) { 320.toDp() }, with(density) { 180.toDp() }).background(Color.Black)) {
                    StreamOverlayPad({ _, _, _ -> }, { _, _, _ -> }, {}, overlay = stored.layout, overlayImages = stored.images,
                        overlaySettings = stored.settings, useHostOverlay = true, onTouchState = { state, _ -> emitted += state })
                }
            }
            val node = compose.onNodeWithTag(TAG_OVERLAY_PAD)
            val buttons = (14..21).toList()
            fun compareHighlights(inputs: Set<String>) {
                val pixels = node.captureToImage().toPixelMap()
                for (index in buttons) {
                    assertTrue(page.descs[index].hitDisabled)
                    val p = center(index)
                    val actual = pixels[p.x.toInt(), p.y.toInt()].toArgb()
                    val expected = if (page.descs[index].input in inputs) 78 else 45
                    for (shift in listOf(0, 8, 16)) assertTrue("${page.descs[index].input} logical$inputs channel$shift", kotlin.math.abs((actual ushr shift and 255) - expected) <= 1)
                }
            }
            compareHighlights(emptySet())
            for (raw in facts.getValue("area_cases").jsonArray) {
                val row = raw.jsonObject
                val index = if (row.getValue("input").jsonPrimitive.content == "dpad_area") 0 else 1
                val delta = row.getValue("normalized_displacement").jsonArray.map { it.jsonPrimitive.float }
                // Exact zero is checked after portable decoding in the guest tester: screen-coordinate
                // injection loses fractional-center precision while adding and subtracting window offsets.
                if (delta.all { it == 0f }) continue
                val r = resolved.descs[index]; val c = center(index)
                val target = Offset(c.x + delta[0] * r.modW * 320 / 2, c.y + delta[1] * r.modH * 180 / 2)
                val expected = OverlayStateAccumulator().apply { row.getValue("native_inputs").jsonArray.forEach { add(overlayOutputFor(it.jsonPrimitive.content)) } }.state()
                node.performTouchInput { down(c); moveTo(target) }
                compose.runOnIdle { assertEquals(row.toString(), expected, emitted.last()) }
                compareHighlights(row.getValue("native_inputs").jsonArray.map { it.jsonPrimitive.content }.toSet())
                node.performTouchInput { up() }
                compose.runOnIdle { assertEquals(PadState.NEUTRAL, emitted.last()) }
                compareHighlights(emptySet())
            }
            node.performTouchInput { down(center(0)); cancel() }
            compose.runOnIdle { assertEquals(PadState.NEUTRAL, emitted.last()) }
            compareHighlights(emptySet())
        } finally { StreamOverlayStore.clear(); root.deleteRecursively() }
    }
}
