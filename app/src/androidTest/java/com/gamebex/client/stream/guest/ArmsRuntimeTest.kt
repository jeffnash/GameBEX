package com.gamebex.client.stream.guest

import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
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
import java.util.Base64
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ArmsRuntimeTest {
    @get:Rule val compose = createComposeRule()
    private fun JsonElement.ints() = jsonArray.map { it.jsonPrimitive.int }.toIntArray()

    @Suppress("DEPRECATION")
    private class Runtime {
        val ctx: Context = InstrumentationRegistry.getInstrumentation().context
        val facts = Json.parseToJsonElement(ctx.assets.open("emulator-facts/arms-full-display-observed.json").bufferedReader().use { it.readText() }).jsonObject
        private val profile = Json.parseToJsonElement(ctx.assets.open("emulator-facts/arms-runtime-profile.json").bufferedReader().use { it.readText() }).jsonObject.getValue("profile").toString()
        val lookups = mutableListOf<String>()
        val reads = mutableListOf<String>()
        val res = object : Resources(ctx.resources.assets, ctx.resources.displayMetrics, ctx.resources.configuration) {
            // Test resources stand in for ARMSX2's resources under the test package.
            @SuppressLint("DiscouragedApi")
            override fun getIdentifier(name: String?, defType: String?, defPackage: String?): Int {
                if (defPackage == Emu.ARMSX2.pkg && name != null) lookups += name
                return super.getIdentifier(name, defType, if (defPackage == Emu.ARMSX2.pkg) ctx.packageName else defPackage)
            }
        }
        private val host = object : ContextWrapper(ctx) {
            override fun createPackageContext(packageName: String, flags: Int): Context {
                assertEquals(Emu.ARMSX2.pkg, packageName)
                return object : ContextWrapper(ctx) { override fun getResources() = res }
            }
        }
        private val root = File("/storage/emulated/0/Android/data")
        val profilePath = File(root, "${Emu.ARMSX2.pkg}/files/inputprofiles/Default.touch.json").path
        private val files = object : EmuFiles by DirectFiles {
            override fun list(path: String): List<String>? = null
            override fun isFile(path: String) = path == profilePath
            override fun readText(path: String): String? { reads += path; return profile.takeIf { path == profilePath } }
            override fun writeTextAtomic(path: String, text: String): Boolean = error("runtime overlay must only read")
        }

        fun read(display: Boolean = true, originY: Int = 0): EmuOverlayReader.Bundle {
            val view = facts.getValue("gamebex_viewport").jsonArray.map { it.jsonPrimitive.int }
            val full = facts.getValue("display").jsonArray.map { it.jsonPrimitive.int }
            return requireNotNull(EmuOverlayReader.read(host, Emu.ARMSX2.pkg, "ps2", null,
                screenW = view[2], screenH = view[3] - originY, screenX = view[0], screenY = originY,
                displayW = if (display) full[0] else 0, displayH = if (display) full[1] else 0,
                density = facts.getValue("density_dpi").jsonPrimitive.int / 160f, dpi = facts.getValue("density_dpi").jsonPrimitive.int,
                files = files, androidData = root))
        }
    }

    private fun stored(bundle: EmuOverlayReader.Bundle): StoredEmuOverlay {
        EmuOverlayStore.clear()
        EmuOverlayReader.packParts("s", 1, bundle).forEach {
            EmuOverlayStore.publish(1, LinkJson.decode(LinkJson.encode(it)) as LinkMsg.StreamEmuOverlay)
        }
        return requireNotNull(EmuOverlayStore.current())
    }

    @Test fun runtimeMetadataAndResolverReconstructAllNativeRectsAndEveryRelativeCell() {
        val runtime = Runtime()
        try {
            for (originY in listOf(0, 13)) {
                val bundle = runtime.read(originY = originY)
                val decoded = stored(bundle)
                val controls = decoded.controls.associateBy { it.id }
                assertEquals(16, controls.size)
                assertFalse(controls.containsKey("ANALOG_EXTRA"))
                assertEquals(1534, decoded.hostScreenW); assertEquals(720 - originY, decoded.hostScreenH)
                runtime.facts.getValue("controls").jsonArray.forEach { raw ->
                    val row = raw.jsonObject; val expected = row.getValue("rect").ints()
                    val control = controls.getValue(row.getValue("id").jsonPrimitive.content)
                    assertArrayEquals(expected.copyOf().apply { this[0] -= 70; this[1] -= originY }, intArrayOf(control.x, control.y, control.w, control.h))
                    val resolved = ResolvedEmuControl(control, 1604f, 720f, decoded.hostScreenW, decoded.hostScreenH, decoded.hostScreenX, decoded.hostScreenY, decoded.hostDisplayW, decoded.hostDisplayH)
                    assertEquals(expected[0].toFloat(), resolved.x, 0f); assertEquals(expected[1].toFloat(), resolved.y, 0f)
                }
                val dpad = controls.getValue("DPAD")
                assertTrue(dpad.x < 0)
                val cells = runtime.facts.getValue("directional_layout").jsonObject.getValue("cells").jsonArray
                cells.zip(dpad.cells).forEach { (raw, cell) ->
                    val expected = raw.jsonObject.getValue("rect").ints()
                    assertArrayEquals(expected.copyOf().apply { this[0] -= 70; this[1] -= originY }, intArrayOf(cell.x, cell.y, cell.w, cell.h))
                }
                assertTrue(dpad.cells[2].x < 0)
            }
            val legacy = stored(runtime.read(display = false))
            val l1 = legacy.controls.single { it.id == "L1" }
            assertEquals(0, legacy.hostDisplayW); assertEquals(0, legacy.hostDisplayH)
            assertArrayEquals(intArrayOf(4, 117, 98, 98), intArrayOf(l1.x, l1.y, l1.w, l1.h))
            val resolved = ResolvedEmuControl(l1, 1534f, 720f, legacy.hostScreenW, legacy.hostScreenH, legacy.hostScreenX, legacy.hostScreenY)
            assertEquals(74f, resolved.x, 0f)
            assertTrue(runtime.reads.all { it == runtime.profilePath })
        } finally { EmuOverlayStore.clear() }
    }

    @Test fun runtimeReadsAllDirectionalResourcesAndRotatesDownArt() {
        val runtime = Runtime()
        val dpad = runtime.read().controls.single { it.id == "DPAD" }
        runtime.facts.getValue("resource_associations").jsonObject.values.forEach { assertTrue(runtime.lookups.contains(it.jsonPrimitive.content)) }
        assertEquals(4, dpad.cells.size)
        fun decode(encoded: String): Bitmap {
            assertTrue(encoded.isNotEmpty())
            val bytes = Base64.getDecoder().decode(encoded)
            return requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        }
        val normal = dpad.cells.map { decode(it.art) }
        val pressed = dpad.cells.map { decode(it.pressedArt) }
        for (images in listOf(normal, pressed)) {
            val up = images[0]; val down = images[1]
            assertEquals(up.width, down.width); assertEquals(up.height, down.height)
            for (y in 0 until up.height) for (x in 0 until up.width) assertEquals(up.getPixel(x, y), down.getPixel(up.width - 1 - x, up.height - 1 - y))
        }
        normal.zip(pressed).forEach { (a, b) -> assertFalse(a.sameAs(b)) }
    }

    // The reference raster uses the same resource names and physical cell rectangles.
    @SuppressLint("DiscouragedApi")
    @Test fun actualGuestDrawsAndCapturesAllDirectionsAcrossTheFullDisplayBeforeTheInset() {
        val runtime = Runtime()
        val bundle = runtime.read()
        val dpad = bundle.controls.single { it.id == "DPAD" }
        val decoded = stored(bundle.copy(controls = listOf(dpad)))
        val cells = runtime.facts.getValue("directional_layout").jsonObject.getValue("cells").jsonArray.map { it.jsonObject }
        val names = runtime.facts.getValue("resource_associations").jsonObject
        val images = cells.mapIndexed { index, cell ->
            listOf(false, true).map { pressed ->
                val direction = cell.getValue("direction").jsonPrimitive.content + if (pressed) "_pressed" else ""
                val id = runtime.ctx.resources.getIdentifier(names.getValue(direction).jsonPrimitive.content, "drawable", runtime.ctx.packageName)
                val src = requireNotNull(BitmapFactory.decodeResource(runtime.ctx.resources, id, BitmapFactory.Options().apply { inScaled = false }))
                val box = cell.getValue("rect").ints()
                val scale = min(box[2].toFloat() / src.width, box[3].toFloat() / src.height)
                val w = (src.width * scale).toInt(); val h = (src.height * scale).toInt()
                val image = Bitmap.createBitmap(box[2], box[3], Bitmap.Config.ARGB_8888)
                android.graphics.Canvas(image).drawBitmap(Bitmap.createScaledBitmap(src, w, h, true), ((box[2] - w) / 2).toFloat(), ((box[3] - h) / 2).toFloat(), null)
                if (index != 1) image.asImageBitmap() else Bitmap.createBitmap(image, 0, 0, image.width, image.height, android.graphics.Matrix().apply { postRotate(180f, image.width / 2f, image.height / 2f) }, true).asImageBitmap()
            }
        }
        var active by mutableStateOf(-1)
        val emitted = mutableListOf<PadState>()
        compose.setContent {
            val density = LocalDensity.current
            val width = with(density) { 320.toDp() }; val height = with(density) { 144.toDp() }
            Row {
                Box(Modifier.size(width, height).background(Color.Black)) { EmuOverlayPad(decoded, { _, _, _ -> }, { _, _, _ -> }, {}, onTouchState = { state, _ -> emitted += state }) }
                Canvas(Modifier.size(width, height).background(Color.Black).testTag("native-arms")) {
                    val sx = size.width / 1604; val sy = size.height / 720
                    cells.forEachIndexed { index, cell ->
                        val box = cell.getValue("rect").ints()
                        drawImage(images[index][if (active == index) 1 else 0], dstOffset = IntOffset((box[0] * sx).roundToInt(), (box[1] * sy).roundToInt()),
                            dstSize = IntSize((box[2] * sx).roundToInt(), (box[3] * sy).roundToInt()), alpha = dpad.alpha / 255f, filterQuality = FilterQuality.Medium)
                    }
                }
            }
        }
        try {
            val candidate = compose.onNodeWithTag(TAG_EMU_PAD)
            fun compare() {
                val a = candidate.captureToImage().toPixelMap(); val e = compose.onNodeWithTag("native-arms").captureToImage().toPixelMap()
                assertEquals(e.width, a.width); assertEquals(e.height, a.height)
                for (y in 0 until e.height) for (x in 0 until e.width) for (shift in listOf(0, 8, 16, 24)) {
                    assertTrue("direction$active pixel($x,$y) channel$shift", kotlin.math.abs((a[x, y].toArgb() ushr shift and 255) - (e[x, y].toArgb() ushr shift and 255)) <= 1)
                }
            }
            compare()
            val points = listOf(Offset(161f, 324f), Offset(161f, 462f), Offset(50f, 397f), Offset(226f, 397f))
            val hats = listOf(PadState.HAT_UP, PadState.HAT_DOWN, PadState.HAT_LEFT, PadState.HAT_RIGHT)
            points.forEachIndexed { index, point ->
                candidate.performTouchInput { down(Offset(point.x * width / 1604, point.y * height / 720)) }
                compose.runOnIdle { active = index; assertEquals(hats[index], emitted.last().hat) }
                compare()
                candidate.performTouchInput { up() }
                compose.runOnIdle { active = -1; assertEquals(PadState.NEUTRAL, emitted.last()) }
                compare()
            }
        } finally { EmuOverlayStore.clear() }
    }
}
