package com.gamebex.client.stream.guest

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import android.graphics.Bitmap
import android.util.DisplayMetrics
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
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
import java.io.File
import kotlin.math.roundToInt
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FlycastPhysicalRuntimeTest {
    @get:Rule val compose = createComposeRule()
    private val ctx get() = InstrumentationRegistry.getInstrumentation().context
    private val facts get() = Json.parseToJsonElement(ctx.assets.open("emulator-facts/flycast-physical-density-render-observed.json").bufferedReader().use { it.readText() }).jsonObject
    private val input get() = facts.getValue("native_observation").jsonObject
    private val dir = "/storage/emulated/0/Android/data/${Emu.FLYCAST.pkg}/files"
    private val files = object : EmuFiles by DirectFiles {
        override fun readText(path: String): String? = "[config]\nVirtualGamepadTransparency=37".takeIf { path == "$dir/emu.cfg" }
        override fun writeTextAtomic(path: String, text: String): Boolean = error("overlay reads must not write")
    }
    @Suppress("DEPRECATION")
    private fun host(xdpi: Float, ydpi: Float): Context {
        val metrics = DisplayMetrics().apply {
            setTo(ctx.resources.displayMetrics)
            this.xdpi = xdpi; this.ydpi = ydpi
            densityDpi = input.getValue("logical_density_dpi").jsonPrimitive.int
            widthPixels = 1604; heightPixels = 720
        }
        val resources = object : Resources(ctx.resources.assets, metrics, ctx.resources.configuration) {
            override fun getDisplayMetrics() = metrics
        }
        return object : ContextWrapper(ctx) { override fun getResources() = resources }
    }
    private fun read(x: Float = input.getValue("physical_xdpi").jsonPrimitive.float, y: Float = input.getValue("physical_ydpi").jsonPrimitive.float) = EmuOverlayReader.read(host(x, y), Emu.FLYCAST.pkg, "dreamcast", null,
        screenW = 1534, screenH = 720, screenX = 70, displayW = 1604, displayH = 720, density = 1.75f, dpi = 280, files = files, androidData = File("/storage/emulated/0/Android/data"))
    private fun stored(bundle: EmuOverlayReader.Bundle): StoredEmuOverlay {
        EmuOverlayStore.clear()
        EmuOverlayReader.packParts("s", 1, bundle).forEach { EmuOverlayStore.publish(1, LinkJson.decode(LinkJson.encode(it)) as LinkMsg.StreamEmuOverlay) }
        return requireNotNull(EmuOverlayStore.current())
    }

    @Test fun actualReaderUsesFractionalRuntimePhysicalMetricsAndFullDisplayThroughPortableGeometry() {
        try {
            val bundle = requireNotNull(read())
            val decoded = stored(bundle)
            val expected = facts.getValue("expected_controls").jsonArray.map { it.jsonObject }
            assertEquals(expected.size, decoded.controls.size)
            assertEquals(1534, decoded.hostScreenW); assertEquals(70, decoded.hostScreenX); assertEquals(1604, decoded.hostDisplayW)
            for (row in expected) {
                val c = decoded.controls.single { it.id == row.getValue("id").jsonPrimitive.content }
                val rect = row.getValue("rect").jsonArray.map { it.jsonPrimitive.float }
                assertArrayEquals(intArrayOf(rect[0].roundToInt() - 70, rect[1].roundToInt(), (rect[0] + rect[2]).roundToInt() - rect[0].roundToInt(), (rect[1] + rect[3]).roundToInt() - rect[1].roundToInt()), intArrayOf(c.x, c.y, c.w, c.h))
                assertEquals(4, c.renderRect.size)
                listOf(c.renderRect[0] + 70, c.renderRect[1], c.renderRect[2], c.renderRect[3]).zip(rect).forEach { (a, e) -> assertEquals(e, a, 0.002f) }
                if (c.id.toInt() >= 18) { assertTrue(c.noArt); assertTrue(c.art.isEmpty()) }
            }
            assertTrue(decoded.controls.single { it.id == "0" }.renderRect[0] < 0f)
            val swapped = requireNotNull(read(264.555f, 261.257f))
            assertEquals(bundle.controls.map { it.renderRect }, swapped.controls.map { it.renderRect })
            assertNull(read(Float.NaN, Float.NaN))
        } finally { EmuOverlayStore.clear() }
    }

    @Test fun actualGuestUsesFractionalArtAtRestHeldAnalogMoveAndRelease() {
        val atlas = Bitmap.createBitmap(512, 256, Bitmap.Config.ARGB_8888).apply {
            val pixels = IntArray(width * height) { i ->
                val x = i % width; val y = i / width
                android.graphics.Color.argb(80 + (x * 3 + y * 5) % 176, 120 + x % 100, 80 + y % 140, 210 - x % 80)
            }
            setPixels(pixels, 0, width, 0, 0, width, height)
        }
        val density = input.getValue("selected_physical_dpi").jsonPrimitive.float
        val bundle = requireNotNull(EmuOverlayReader.readFlycast(host(261.257f, 264.555f), "dreamcast", 1604, 720, density, files, dir, atlas))
        val controls = bundle.controls.filter { it.id in setOf("0", "7", "9", "11", "13") }
        val decoded = stored(bundle.copy(controls = controls.map { c -> c.copy(x = c.x - 70, renderRect = c.renderRect.mapIndexed { i, v -> if (i == 0) v - 70 else v }, knobRect = c.knobRect.mapIndexed { i, v -> if (i == 0) v - 70 else v }) }, screenW = 1534, screenX = 70, displayW = 1604, displayH = 720))
        val boxes = facts.getValue("expected_controls").jsonArray.associate { r -> r.jsonObject.getValue("id").jsonPrimitive.content to r.jsonObject.getValue("rect").jsonArray.map { it.jsonPrimitive.float } }
        var held by mutableStateOf("")
        var knobMove by mutableStateOf(Offset.Zero)
        val emitted = mutableListOf<PadState>()
        compose.setContent {
            val local = LocalDensity.current
            val w = with(local) { 320.toDp() }; val h = with(local) { 144.toDp() }
            Row {
                Box(Modifier.size(w, h).background(Color.Black)) { EmuOverlayPad(decoded, { _, _, _ -> }, { _, _, _ -> }, {}, onTouchState = { state, _ -> emitted += state }) }
                Canvas(Modifier.size(w, h).background(Color.Black).testTag("native-flycast")) {
                    val selectedBoxes = boxes.filterKeys { it in setOf("0", "7", "9", "11", "13") }.mapKeys { it.key.toInt() }
                    val pixels = ownedFlycastReference(atlas, selectedBoxes, size.width.toInt(), size.height.toInt(),
                        setOfNotNull(held.toIntOrNull()), knobMove.x, knobMove.y)
                    drawImage(Bitmap.createBitmap(pixels, size.width.toInt(), size.height.toInt(), Bitmap.Config.ARGB_8888).asImageBitmap(),
                        filterQuality = FilterQuality.None)
                }
            }
        }
        try {
            val node = compose.onNodeWithTag(TAG_EMU_PAD)
            fun compare() {
                val actual = node.captureToImage().toPixelMap(); val expected = compose.onNodeWithTag("native-flycast").captureToImage().toPixelMap()
                assertEquals(expected.width, actual.width); assertEquals(expected.height, actual.height)
                for (y in 0 until actual.height) for (x in 0 until actual.width) for (shift in listOf(0, 8, 16, 24)) {
                    val a = actual[x,y].toArgb() ushr shift and 255; val e = expected[x,y].toArgb() ushr shift and 255
                    assertTrue("held$held move$knobMove pixel($x,$y) channel$shift actual$a expected$e", kotlin.math.abs(a-e) <= 1)
                }
            }
            compare()
            for (id in listOf("0", "7", "9", "11", "13")) {
                val c = decoded.controls.single { it.id == id }
                val motion = facts.getValue("analog_motion").jsonObject
                val origin = motion.getValue("down_point").jsonArray.map { it.jsonPrimitive.float }
                val x = (if (id == "11") origin[0] else c.x + 70 + c.w / 2f) * 320 / 1604
                val y = (if (id == "11") origin[1] else c.y + c.h / 2f) * 144 / 720
                node.performTouchInput { down(Offset(x, y)) }
                compose.runOnIdle { held = id }
                compare()
                if (id == "11") {
                    for (raw in motion.getValue("steps").jsonArray.drop(1)) {
                        val step = raw.jsonObject
                        val point = step.getValue("point").jsonArray.map { it.jsonPrimitive.float }
                        val delta = step.getValue("visual_displacement").jsonArray.map { it.jsonPrimitive.float }
                        node.performTouchInput { moveTo(Offset(point[0] * 320 / 1604, point[1] * 144 / 720)) }
                        compose.runOnIdle { knobMove = Offset(delta[0], delta[1]) }
                        compare()
                    }
                }
                node.performTouchInput { up() }
                compose.runOnIdle { held = ""; knobMove = Offset.Zero; assertEquals(PadState.NEUTRAL, emitted.last()) }
                compare()
            }
        } finally { EmuOverlayStore.clear() }
    }

    @Test fun motionCapturesOffCenterDownAndKeepsVisualPrecisionBeforeBoundedWireAxes() {
        try {
            val decoded = stored(requireNotNull(read()))
            val c = decoded.controls.single { it.id == "11" }
            val motion = facts.getValue("analog_motion").jsonObject
            val width = motion.getValue("knob_width").jsonPrimitive.float
            assertEquals(width, c.knobRect[2], 0.002f)
            val r = ResolvedEmuControl(c, 1604f, 720f, decoded.hostScreenW, decoded.hostScreenH, decoded.hostScreenX, decoded.hostScreenY, decoded.hostDisplayW, decoded.hostDisplayH)
            val tester = EmuPadTester("flycast", listOf(r))
            val origin = motion.getValue("down_point").jsonArray.map { it.jsonPrimitive.float }
            val down = tester.onDown(1, origin[0], origin[1])
            assertEquals(0, down.lx); assertEquals(0, down.ly)
            for (raw in motion.getValue("steps").jsonArray) {
                val step = raw.jsonObject
                val point = step.getValue("point").jsonArray.map { it.jsonPrimitive.float }
                val normalized = step.getValue("normalized").jsonArray.map { it.jsonPrimitive.float }
                val delta = step.getValue("visual_displacement").jsonArray.map { it.jsonPrimitive.float }
                val state = tester.onMove(1, point[0], point[1])
                assertEquals(PadState.axisFromFloat(normalized[0]), state.lx)
                assertEquals(PadState.axisFromFloat(normalized[1]), state.ly)
                val visual = tester.visual()
                assertTrue(visual.outerDxPx.isEmpty())
                assertEquals(delta[0], visual.knobDxPx.getValue(0).x, 0.002f)
                assertEquals(delta[1], visual.knobDxPx.getValue(0).y, 0.002f)
            }
            assertEquals(PadState.NEUTRAL, tester.onUp(1, 0f, 0f)); assertTrue(tester.visual().knobDxPx.isEmpty())
            tester.onDown(2, origin[0], origin[1]); tester.onMove(2, origin[0] + 20.125f, origin[1] - 5.25f)
            tester.onCancel(2)
            assertEquals(PadState.NEUTRAL, tester.state()); assertTrue(tester.visual().knobDxPx.isEmpty())
        } finally { EmuOverlayStore.clear() }
    }

    @Test fun portableFractionalFactsDefaultForLegacyPayloadAndRejectMalformedGeometryOrTint() {
        val legacy = LinkMsg.EmuControl("7", x = 10, y = 20, w = 90, h = 91)
        val message = LinkMsg.StreamEmuOverlay("s", 1, "flycast", listOf(legacy), screenW = 1604, screenH = 720)
        val encoded = LinkJson.encode(message)
        assertFalse(encoded.contains("renderRect")); assertFalse(encoded.contains("knobRect")); assertFalse(encoded.contains("renderGray")); assertFalse(encoded.contains("textureInset"))
        try {
            val decoded = LinkJson.decode(encoded) as LinkMsg.StreamEmuOverlay
            assertEquals(legacy, decoded.controls.single())
            EmuOverlayStore.publish(1, decoded)
            val r = ResolvedEmuControl(requireNotNull(EmuOverlayStore.current()).controls.single(), 1604f, 720f, 1604, 720)
            assertNull(r.renderRect); assertNull(r.knobRect)
            assertEquals(10f, r.x, 0f); assertEquals(90f, r.w, 0f)
            val bad = listOf(
                legacy.copy(renderRect = listOf(1f, 2f, 3f)),
                legacy.copy(renderRect = listOf(1f, 2f, 0f, 3f)),
                legacy.copy(knobRect = listOf(1f, 2f, 3f, Float.NaN)),
                legacy.copy(renderRect = listOf(1f, 2f, 16385f, 3f)),
                legacy.copy(textureInset = -1), legacy.copy(textureInset = 2), legacy.copy(textureInset = 1),
                legacy.copy(renderGray = Float.NaN), legacy.copy(pressedRenderGray = 1.1f), legacy.copy(knobRenderGray = -0.1f),
            )
            for (control in bad) {
                EmuOverlayStore.clear(); EmuOverlayStore.publish(1, message.copy(controls = listOf(control)))
                assertNull(EmuOverlayStore.current())
            }
        } finally { EmuOverlayStore.clear() }
    }

}
