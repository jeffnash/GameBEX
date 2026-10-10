package com.gamebex.client.stream.guest

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import com.gamebex.client.data.Emu
import com.gamebex.client.launch.DirectFiles
import com.gamebex.client.launch.EmuFiles
import com.gamebex.client.stream.proto.PadState
import com.gamebex.client.together.LinkMsg
import com.gamebex.client.together.proto.LinkJson
import com.gamebex.client.together.session.EmuOverlayReader
import kotlin.math.roundToInt
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FlycastFaceBindingTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun facts(name: String) = Json.parseToJsonElement(instrumentation.context.assets.open("emulator-facts/$name").bufferedReader().use { it.readText() }).jsonObject

    @Test fun actualReaderPortableGuestUsesUpperYAndLeftXRuntimeCellsWithoutChangingGeometryOrInput() {
        val facts = facts("flycast-owned-face-art-binding-observed.json")
        val physical = facts("flycast-physical-density-render-observed.json")
        val display = physical.getValue("native_observation").jsonObject
        val width = display.getValue("width").jsonPrimitive.int
        val height = display.getValue("height").jsonPrimitive.int
        val colors = facts.getValue("synthetic_colors").jsonObject
        val bindings = facts.getValue("owned_bindings").jsonArray.map { it.jsonObject }
        val expected = bindings.associate { binding ->
            val id = binding.getValue("owned_id").jsonPrimitive.content
            val rect = binding.getValue("atlas_rect").jsonArray.map { it.jsonPrimitive.int }
            id to colors.getValue("cell_at_${rect[0]}").jsonArray.map { it.jsonPrimitive.int }
        }
        val atlas = Bitmap.createBitmap(512, 256, Bitmap.Config.ARGB_8888)
        for (binding in bindings) {
            val id = binding.getValue("owned_id").jsonPrimitive.content
            val rect = binding.getValue("atlas_rect").jsonArray.map { it.jsonPrimitive.int }
            val rgba = expected.getValue(id)
            val color = android.graphics.Color.argb(rgba[3], rgba[0], rgba[1], rgba[2])
            atlas.setPixels(IntArray(rect[2] * rect[3]) { color }, 0, rect[2], rect[0], rect[1], rect[2], rect[3])
        }
        val dir = "/storage/emulated/0/Android/data/${Emu.FLYCAST.pkg}/files"
        val files = object : EmuFiles by DirectFiles {
            override fun readText(path: String): String? = "[config]\nVirtualGamepadTransparency=0".takeIf { path == "$dir/emu.cfg" }
            override fun writeTextAtomic(path: String, text: String): Boolean = error("overlay reads must not write")
        }
        val bundle = requireNotNull(EmuOverlayReader.readFlycast(instrumentation.targetContext, "dreamcast", width, height,
            display.getValue("selected_physical_dpi").jsonPrimitive.float, files, dir, atlas))
        EmuOverlayStore.clear()
        try {
            EmuOverlayReader.packParts("s", 1, bundle.copy(controls = bundle.controls.filter { it.id in expected.keys })).forEach {
                EmuOverlayStore.publish(1, LinkJson.decode(LinkJson.encode(it)) as LinkMsg.StreamEmuOverlay)
            }
            val decoded = requireNotNull(EmuOverlayStore.current())
            assertEquals(setOf("4", "5"), decoded.controls.map { it.id }.toSet())
            val boxes = physical.getValue("expected_controls").jsonArray.associate {
                it.jsonObject.getValue("id").jsonPrimitive.content to it.jsonObject.getValue("rect").jsonArray.map { v -> v.jsonPrimitive.float }
            }
            for (c in decoded.controls) c.renderRect.zip(boxes.getValue(c.id)).forEach { (actual, sourced) -> assertEquals(sourced, actual, 0.002f) }
            val y = decoded.controls.single { it.id == "4" }; val x = decoded.controls.single { it.id == "5" }
            assertTrue(y.renderRect[1] < x.renderRect[1]); assertTrue(y.renderRect[0] > x.renderRect[0])
            val emitted = mutableListOf<PadState>()
            compose.setContent {
                val local = LocalDensity.current
                Box(Modifier.size(with(local) { 320.toDp() }, with(local) { 144.toDp() }).background(Color.Black)) {
                    EmuOverlayPad(decoded, { _, _, _ -> }, { _, _, _ -> }, {}, onTouchState = { state, _ -> emitted += state })
                }
            }
            val node = compose.onNodeWithTag(TAG_EMU_PAD)
            fun point(id: String): Offset {
                val box = boxes.getValue(id)
                return Offset((box[0] + box[2] / 2) * 320 / width, (box[1] + box[3] / 2) * 144 / height)
            }
            fun assertGuestColors(held: String = "") {
                val pixels = node.captureToImage().toPixelMap()
                for ((id, rgba) in expected) {
                    val point = point(id)
                    val color = pixels[point.x.toInt(), point.y.toInt()].toArgb()
                    val gray = if (id == held) 0.25f else 0.5f
                    for ((channel, shift) in listOf(16, 8, 0).withIndex()) {
                        val actual = color ushr shift and 255
                        val target = (rgba[channel] * gray).roundToInt()
                        assertTrue("owned$id ${if (id == "4") "Y upper" else "X left"} held$held channel$shift actual$actual expected$target", kotlin.math.abs(actual - target) <= 1)
                    }
                }
            }
            assertGuestColors()
            for ((id, button) in listOf("4" to PadState.Y, "5" to PadState.X)) {
                val source = BitmapFactory.decodeByteArray(decoded.art.getValue(emuArtKey(id, "art")), 0, decoded.art.getValue(emuArtKey(id, "art")).size)
                val rgba = expected.getValue(id)
                assertEquals(android.graphics.Color.argb(rgba[3], rgba[0], rgba[1], rgba[2]), source.getPixel(source.width / 2, source.height / 2))
                assertEquals(64, source.width); assertEquals(64, source.height)
                node.performTouchInput { down(point(id)) }
                compose.runOnIdle { assertEquals(PadState(buttons = button), emitted.last()) }
                assertGuestColors(id)
                node.performTouchInput { up() }
                compose.runOnIdle { assertEquals(PadState.NEUTRAL, emitted.last()) }
                assertGuestColors()
            }
        } finally { EmuOverlayStore.clear() }
    }
}
