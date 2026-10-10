package com.gamebex.client.stream.guest

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gamebex.client.stream.proto.PadState
import com.gamebex.client.test.R as TestR
import com.gamebex.client.together.LinkMsg
import com.gamebex.client.together.session.EmuOverlayReader
import java.io.ByteArrayOutputStream
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

@RunWith(AndroidJUnit4::class)
class PhysicalRenderTest {
    @get:Rule val compose = createComposeRule()

    private fun pixels(bitmap: Bitmap) = IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }

    @Test fun armsSourcePressedImageKeepsContinuousCenteredShrinkAndRestoresOnRelease() {
        checkArmsHeldImage("L1", 0.92f, tinted = false)
    }

    @Test fun armsTintedImageKeepsContinuousCenteredShrinkAndRestoresOnRelease() {
        checkArmsHeldImage("CIRCLE", 0.85f, tinted = true)
    }

    private fun checkArmsHeldImage(id: String, scale: Float, tinted: Boolean) {
        val context = InstrumentationRegistry.getInstrumentation().context
        val facts = Json.parseToJsonElement(context.assets.open("emulator-facts/arms-held-floating-scale-observed.json").bufferedReader().use { it.readText() }).jsonObject
        val box = facts.getValue("native_widget").jsonArray.map { it.jsonPrimitive.int }
        val point = facts.getValue("native_point").jsonArray.map { it.jsonPrimitive.float }
        if (!tinted) {
            assertEquals(facts.getValue("pressed_scale").jsonPrimitive.float, scale, 0f)
            val expected = facts.getValue("held_rect_float").jsonArray.map { it.jsonPrimitive.double }
            val w = box[2] * scale; val h = box[3] * scale
            listOf(box[0] + box[2] / 2f - w / 2f, box[1] + box[3] / 2f - h / 2f, w, h).zip(expected).forEach { (a, e) -> assertEquals(e, a.toDouble(), 0.0) }
        }
        val source = Bitmap.createBitmap(31, 17, Bitmap.Config.ARGB_8888).apply {
            val pattern = IntArray(width * height) { i ->
                val x = i % width; val y = i / width
                if (x < 2 || y < 2 || x >= width - 2 || y >= height - 2) 0 else
                    android.graphics.Color.argb(64 + (x * 7 + y * 13) % 192, 30 + x * 6, 40 + y * 11, 220 - x * 4)
            }
            setPixels(pattern, 0, width, 0, 0, width, height)
        }
        val selected = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888).apply {
            val pattern = pixels(source).map { p ->
                if (tinted) (p and -0x1000000) or (((p ushr 16 and 255) * 176 / 255) shl 16) or
                    (((p ushr 8 and 255) * 176 / 255) shl 8) or ((p and 255) * 176 / 255)
                else p xor 0x00ffffff
            }.toIntArray()
            setPixels(pattern, 0, width, 0, 0, width, height)
        }
        fun fit(src: Bitmap): Bitmap {
            val ratio = minOf(box[2].toFloat() / src.width, box[3].toFloat() / src.height)
            val w = (src.width * ratio).toInt(); val h = (src.height * ratio).toInt()
            return Bitmap.createBitmap(box[2], box[3], Bitmap.Config.ARGB_8888).also {
                android.graphics.Canvas(it).drawBitmap(Bitmap.createScaledBitmap(src, w, h, true), ((box[2] - w) / 2).toFloat(), ((box[3] - h) / 2).toFloat(), null)
            }
        }
        val normal = fit(source); val pressed = fit(selected)
        fun png(bitmap: Bitmap) = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val control = LinkMsg.EmuControl(id, 0, x = box[0], y = box[1], w = box[2], h = box[3], alpha = 140, output = ArmsOverlay.outputFor(id))
        val stored = StoredEmuOverlay(0, "armsx2", listOf(control), mapOf(emuArtKey(id, "art") to png(normal), emuArtKey(id, "pressed") to png(pressed)),
            240, 240, partsReceived = 1, partCount = 1, truncated = false)
        var held by androidx.compose.runtime.mutableStateOf(false)
        val emitted = mutableListOf<PadState>()
        val references = listOf(normal, pressed).map { BitmapDrawable(context.resources, it).apply {
            alpha = control.alpha
            setBounds(box[0], box[1], box[0] + box[2], box[1] + box[3])
        } }
        compose.setContent {
            val side = with(LocalDensity.current) { 240.toDp() }
            Row {
                Box(Modifier.size(side).background(Color.Black)) { EmuOverlayPad(stored, { _, _, _ -> }, { _, _, _ -> }, {}, onTouchState = { state, _ -> emitted += state }) }
                Canvas(Modifier.size(side).background(Color.Black).testTag("native-arms-held")) {
                    // The platform reference transforms the already-fitted sprite around the original center.
                    drawIntoCanvas {
                        val canvas = it.nativeCanvas
                        val saved = canvas.save()
                        if (held) canvas.scale(scale, scale, box[0] + box[2] / 2f, box[1] + box[3] / 2f)
                        references[if (held) 1 else 0].draw(canvas)
                        canvas.restoreToCount(saved)
                    }
                }
            }
        }
        val candidate = compose.onNodeWithTag(TAG_EMU_PAD)
        fun compare() {
            val actual = candidate.captureToImage().toPixelMap()
            val expected = compose.onNodeWithTag("native-arms-held").captureToImage().toPixelMap()
            assertEquals(expected.width, actual.width); assertEquals(expected.height, actual.height)
            for (y in 0 until expected.height) for (x in 0 until expected.width) for (shift in listOf(0, 8, 16, 24)) {
                val a = actual[x, y].toArgb() ushr shift and 255; val e = expected[x, y].toArgb() ushr shift and 255
                assertTrue("$id held$held pixel($x,$y) channel$shift actual$a expected$e", kotlin.math.abs(a - e) <= 1)
            }
        }
        compare()
        candidate.performTouchInput { down(Offset(point[0], point[1])) }
        compose.runOnIdle { held = true; assertNotEquals(PadState.NEUTRAL, emitted.last()) }
        compare()
        candidate.performTouchInput { up() }
        compose.runOnIdle { held = false; assertEquals(PadState.NEUTRAL, emitted.last()) }
        compare()
    }

    @Test fun retroFloatingHeldAlphaMultipliesBeforePixelClampAndPackedModeKeepsUnitClamp() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val facts = Json.parseToJsonElement(context.assets.open("emulator-facts/retro-held-floating-alpha-observed.json").bufferedReader().use { it.readText() }).jsonObject
        val opacity = facts.getValue("opacity").jsonPrimitive.double
        val multiplier = facts.getValue("highlight_multiplier").jsonPrimitive.double
        val sourceAlpha = intArrayOf(64, 128, 192, 255)
        val image = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply {
            setPixels(sourceAlpha.map { (it shl 24) or 0x00ffffff }.toIntArray(), 0, 2, 0, 0, 2, 2)
        }
        val bytes = ByteArrayOutputStream().also { image.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val layout = OverlayLayout(pages = listOf(OverlayPage(0, name = "landscape", normalized = true, autoXSeparation = false,
            descs = listOf(OverlayDesc("a", 0.5f, 0.5f, OverlayShape.RECT, 0.5f, 0.5f, image = "alpha.png", alphaMod = multiplier.toFloat())))))
        var driver by androidx.compose.runtime.mutableStateOf(facts.getValue("effective_config_driver").jsonPrimitive.content)
        var held by androidx.compose.runtime.mutableStateOf(false)
        compose.setContent {
            val density = LocalDensity.current
            val width = with(density) { 64.toDp() }; val height = with(density) { 32.toDp() }
            Row {
                Box(Modifier.size(width, height).background(Color.Black)) {
                    StreamOverlayPad({ _, _, _ -> }, { _, _, _ -> }, {}, overlay = layout, overlayImages = mapOf("alpha.png" to bytes),
                        overlaySettings = RaOverlaySettings.parse("video_driver=$driver\ninput_overlay_opacity=$opacity\ninput_overlay_auto_scale=false"), useHostOverlay = true)
                }
                Canvas(Modifier.size(width, height).background(Color.Black).testTag("native-retro-alpha")) {
                    val gain = opacity * if (held) multiplier else 1.0
                    val packed = driver != "gl"
                    val global = if (packed) kotlin.math.floor(gain.coerceIn(0.0, 1.0) * 255 + 0.5) / 255 else gain
                    val expected = IntArray(64 * 32) { i ->
                        val sx = ((i % 64 + 0.5) * 2 / 64 - 0.5).coerceIn(0.0, 1.0)
                        val sy = ((i / 64 + 0.5) * 2 / 32 - 0.5).coerceIn(0.0, 1.0)
                        val a = (sourceAlpha[0] * (1 - sx) + sourceAlpha[1] * sx) * (1 - sy) + (sourceAlpha[2] * (1 - sx) + sourceAlpha[3] * sx) * sy
                        val sample = if (packed) kotlin.math.floor(a + 0.5) else a
                        val channel = kotlin.math.floor((sample * global).coerceIn(0.0, 255.0) + 0.5).toInt()
                        android.graphics.Color.rgb(channel, channel, channel)
                    }
                    val reference = Bitmap.createBitmap(expected, 64, 32, Bitmap.Config.ARGB_8888)
                    drawIntoCanvas { it.nativeCanvas.drawBitmap(reference, 0f, 0f, null) }
                }
            }
        }
        val candidate = compose.onNodeWithTag(TAG_OVERLAY_PAD)
        fun compare() {
            val actual = candidate.captureToImage().toPixelMap()
            val expected = compose.onNodeWithTag("native-retro-alpha").captureToImage().toPixelMap()
            assertEquals(expected.width, actual.width); assertEquals(expected.height, actual.height)
            for (y in 0 until expected.height) for (x in 0 until expected.width) {
                val a = actual[x, y].toArgb(); val e = expected[x, y].toArgb()
                for (shift in listOf(0, 8, 16, 24)) assertTrue("driver$driver held$held pixel($x,$y) channel$shift actual$a expected$e", kotlin.math.abs((a ushr shift and 255) - (e ushr shift and 255)) <= 1)
            }
        }
        for (mode in listOf("gl", "gl3")) {
            compose.runOnIdle { driver = mode }
            compare()
            candidate.performTouchInput { down(Offset(32f, 16f)) }
            compose.runOnIdle { held = true }
            compare()
            candidate.performTouchInput { up() }
            compose.runOnIdle { held = false }
            compare()
        }
    }

    @Test fun dolphinStickLayersAtRestHeldMoveAndReleaseMatchPlatformComposition() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val facts = Json.parseToJsonElement(context.assets.open("emulator-facts/dolphin-held-composition-observed.json").bufferedReader().use { it.readText() }).jsonObject
        val precision = Json.parseToJsonElement(context.assets.open("emulator-facts/dolphin-visual-precision-observed.json").bufferedReader().use { it.readText() }).jsonObject
        val opacity = facts.getValue("per_layer_opacity_byte").jsonPrimitive.int
        assertEquals(1, facts.getValue("outer_draw_count").jsonPrimitive.int)
        val res = context.createConfigurationContext(Configuration(context.resources.configuration).apply { densityDpi = 280 }).resources
        val normal = EmuOverlayReader.dolphinThumbSource(res, TestR.drawable.density_edge)!!
        assertEquals(14, normal.width)
        val pressed = Bitmap.createBitmap(normal.width, normal.height, Bitmap.Config.ARGB_8888)
        val changed = pixels(normal).map { it xor 0x00ffffff }.toIntArray()
        pressed.setPixels(changed, 0, normal.width, 0, 0, normal.width, normal.height)
        val outer = Bitmap.createBitmap(198, 198, Bitmap.Config.ARGB_8888).apply {
            val pattern = IntArray(width * height) { i ->
                val x = i % width; val y = i / width
                android.graphics.Color.argb(96 + (x + y) % 128, 20 + x % 64, 180, 40 + y % 64)
            }
            setPixels(pattern, 0, width, 0, 0, width, height)
        }
        fun png(bitmap: Bitmap) = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val art = mapOf(emuArtKey("STICK_MAIN", "art") to png(outer), emuArtKey("STICK_MAIN", "knob") to png(normal), emuArtKey("STICK_MAIN", "knobPressed") to png(pressed))
        val c = LinkMsg.EmuControl("STICK_MAIN", 2, x = 24, y = 12, w = 198, h = 198, alpha = opacity, knobSize = 145, recenter = true, output = OverlayOutput.Stick(false))
        val stored = StoredEmuOverlay(0, "dolphin", listOf(c), art, 320, 240, partsReceived = 1, partCount = 1, truncated = false)
        var held by androidx.compose.runtime.mutableStateOf(false)
        var dx by androidx.compose.runtime.mutableIntStateOf(0)
        var origin by androidx.compose.runtime.mutableStateOf(Offset.Zero)
        val references = listOf(normal, pressed).map { BitmapDrawable(res, it) }
        val outerReference = BitmapDrawable(res, outer)
        compose.setContent {
            val density = LocalDensity.current
            val width = with(density) { 320.toDp() }; val height = with(density) { 240.toDp() }
            Row {
                Box(Modifier.size(width, height).background(Color.Black)) { EmuOverlayPad(stored, { _, _, _ -> }, { _, _, _ -> }, {}) }
                Canvas(Modifier.size(width, height).background(Color.Black).testTag("native-thumb")) {
                    val drawable = references[if (held) 1 else 0]
                    val ox = origin.x.toInt(); val oy = origin.y.toInt()
                    drawable.alpha = opacity
                    drawable.setBounds(51 + ox + dx, 39 + oy, 195 + ox + dx, 183 + oy)
                    outerReference.alpha = opacity
                    outerReference.setBounds(24 + ox, 12 + oy, 222 + ox, 210 + oy)
                    drawIntoCanvas {
                        if (held) {
                            drawable.draw(it.nativeCanvas)
                            outerReference.draw(it.nativeCanvas)
                        } else {
                            outerReference.draw(it.nativeCanvas)
                            drawable.draw(it.nativeCanvas)
                        }
                    }
                }
            }
        }
        val candidate = compose.onNodeWithTag(TAG_EMU_PAD)
        fun compare() {
            val actual = candidate.captureToImage().toPixelMap()
            val expected = compose.onNodeWithTag("native-thumb").captureToImage().toPixelMap()
            assertEquals(expected.width, actual.width); assertEquals(expected.height, actual.height)
            for (y in 0 until expected.height) for (x in 0 until expected.width) {
                val a = actual[x, y].toArgb(); val e = expected[x, y].toArgb()
                for (shift in listOf(0, 8, 16, 24)) assertTrue("pixel($x,$y) channel$shift actual$a expected$e", kotlin.math.abs((a ushr shift and 255) - (e ushr shift and 255)) <= 1)
            }
        }
        compare()
        candidate.performTouchInput { down(Offset(134f, 122f)) }
        compose.runOnIdle { held = true; origin = Offset(11f, 11f) }
        compare()
        precision.getValue("rows").jsonArray.forEach { raw ->
            val row = raw.jsonObject
            val movement = row.getValue("raw_pixel_displacement").jsonPrimitive.float
            if (kotlin.math.abs(movement) <= 49f) {
                candidate.performTouchInput { moveTo(Offset(134f + movement, 122f)) }
                compose.runOnIdle { dx = row.getValue("visual_integer_displacement").jsonPrimitive.int }
                compare()
            }
        }
        candidate.performTouchInput { up() }
        compose.runOnIdle { held = false; dx = 0; origin = Offset.Zero }
        compare()
    }

    @Test fun densityAwareResourceDecodePrecedesFinalResizeAndPreservesActualRasterPixels() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val configured = context.createConfigurationContext(Configuration(context.resources.configuration).apply { densityDpi = 280 })
        val res = configured.resources
        val id = TestR.drawable.density_edge
        val decoded = BitmapFactory.decodeResource(res, id)
        val raw = BitmapFactory.decodeResource(res, id, BitmapFactory.Options().apply { inScaled = false })
        assertEquals(280, res.displayMetrics.densityDpi)
        assertEquals(14, decoded.width); assertEquals(14, decoded.height)
        assertEquals(24, raw.width); assertEquals(24, raw.height)
        val expected = Bitmap.createScaledBitmap(decoded, 43, 39, true)
        val actual = EmuOverlayReader.rasterDensityAwareResource(res, id, 43, 39)!!
        assertArrayEquals(pixels(expected), pixels(actual))
        val bypassed = Bitmap.createScaledBitmap(raw, 43, 39, true)
        assertTrue(pixels(expected).zip(pixels(bypassed)).count { it.first != it.second } > 100)
        assertArrayEquals(pixels(decoded), pixels(EmuOverlayReader.rasterDensityAwareResource(res, id, 14, 14)!!))
    }

    @Test fun negativeViewportCoordinatesDrawAcrossTheFullDisplayAndKeepTouchAndVideoExclusion() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val facts = Json.parseToJsonElement(context.assets.open("emulator-facts/dolphin-physical-clipping-observed.json").bufferedReader().use { it.readText() }).jsonObject
        fun JsonElement.ints() = jsonArray.map { it.jsonPrimitive.int }
        val display = facts["host_display"]!!.ints()
        val viewport = facts["host_viewport"]!!.ints()
        val controls = facts["controls"]!!.jsonArray.map { raw ->
            val row = raw.jsonObject
            val box = row["viewport_relative_rect"]!!.ints()
            val id = row["id"]!!.jsonPrimitive.content
            LinkMsg.EmuControl(id, if (id == "L") 0 else 2, x = box[0], y = box[1], w = box[2], h = box[3], output = if (id == "L") OverlayOutput.Trigger(true) else OverlayOutput.Stick(false))
        }
        val image = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.YELLOW) }
        val bytes = ByteArrayOutputStream().also { image.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val stored = StoredEmuOverlay(0, "dolphin", controls, controls.associate { emuArtKey(it.id, "art") to bytes }, viewport[2], viewport[3], viewport[0], viewport[1], display[0], display[1], partsReceived = 1, partCount = 1, truncated = false)
        val emitted = mutableListOf<PadState>()
        compose.setContent {
            Box(Modifier.size(320.dp, 180.dp).background(Color.Black)) {
                EmuOverlayPad(stored, { _, _, _ -> }, { _, _, _ -> }, {}, onTouchState = { state, _ -> emitted += state },
                    videoCrop = VideoCropSignal(90, 270, 80, 70, display[0], display[1], 80, 70))
            }
        }
        val node = compose.onNodeWithTag(TAG_EMU_PAD)
        val rest = node.captureToImage()
        fun at(x: Int, y: Int) = Offset(x * rest.width.toFloat() / display[0], y * rest.height.toFloat() / display[1])
        fun colorAt(x: Int, y: Int, pressed: Boolean = false): Int {
            val position = at(x, y)
            val picture = if (pressed) node.captureToImage() else rest
            return picture.toPixelMap()[position.x.toInt(), position.y.toInt()].toArgb()
        }
        assertEquals(Color.Yellow.toArgb(), colorAt(50, 300))
        assertEquals(Color.Yellow.toArgb(), colorAt(50, 500))
        assertEquals(Color.Black.toArgb(), colorAt(120, 300))
        node.performTouchInput { down(at(50, 300)) }
        compose.runOnIdle { assertEquals(255, emitted.last().lt) }
        assertEquals(Color.Yellow.toArgb(), colorAt(120, 300, true))
        node.performTouchInput { up() }
        compose.runOnIdle { assertEquals(PadState.NEUTRAL, emitted.last()) }
    }
}
