package com.gamebex.client.stream.guest

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
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
import com.gamebex.client.launch.DirectFiles
import com.gamebex.client.launch.EmuFiles
import com.gamebex.client.stream.proto.PadState
import com.gamebex.client.together.LinkMsg
import com.gamebex.client.together.proto.LinkJson
import com.gamebex.client.together.session.EmuOverlayReader
import kotlin.math.floor
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

internal fun ownedFlycastReference(atlas: Bitmap, boxes: Map<Int, List<Float>>, width: Int, height: Int,
    held: Set<Int>, dx: Float = 0f, dy: Float = 0f, alpha: Int = 161, background: Int = -0x1000000): IntArray {
    val out = IntArray(width * height) { background }
    fun layer(id: Int, box: List<Float>, tint: Float, mx: Float = 0f, my: Float = 0f) {
        val cell = FlycastOverlay.TEX_CELLS[id]
        val left = (box[0] + mx).toDouble() * width / 1604
        val top = (box[1] + my).toDouble() * height / 720
        val w = box[2].toDouble() * width / 1604; val h = box[3].toDouble() * height / 720
        val gray = (tint * 255).roundToInt() / 255.0
        for (y in floor(top).toInt().coerceAtLeast(0) until ceil(top+h).toInt().coerceAtMost(height))
            for (x in floor(left).toInt().coerceAtLeast(0) until ceil(left+w).toInt().coerceAtMost(width)) {
                if (x+0.5 < left || x+0.5 >= left+w || y+0.5 < top || y+0.5 >= top+h) continue
                val u = (cell[0]+1+(x+0.5-left)/w*(cell[2]-2)-0.5).coerceIn(0.0, atlas.width-1.0)
                val v = (cell[1]+1+(y+0.5-top)/h*(cell[3]-2)-0.5).coerceIn(0.0, atlas.height-1.0)
                val ix = floor(u).toInt(); val iy = floor(v).toInt()
                val rgba = DoubleArray(4)
                for (yy in 0..1) for (xx in 0..1) {
                    val color = atlas.getPixel((ix+xx).coerceAtMost(atlas.width-1), (iy+yy).coerceAtMost(atlas.height-1))
                    val weight = (if (xx==0) 1-(u-ix) else u-ix) * (if (yy==0) 1-(v-iy) else v-iy)
                    for ((channel,shift) in listOf(16,8,0,24).withIndex()) rgba[channel] += (color ushr shift and 255) * weight
                }
                val a = rgba[3]/255 * alpha/255
                val previous = out[y*width+x]
                var color = -0x1000000
                for ((channel,shift) in listOf(16,8,0).withIndex()) color = color or
                    ((rgba[channel]*gray*a+(previous ushr shift and 255)*(1-a)).roundToInt().coerceIn(0,255) shl shift)
                out[y*width+x] = color
            }
    }
    for ((id,box) in boxes) {
        layer(id,box, if (id==11) 0.5f else if (id==13 || id in held) 0.25f else 0.5f)
        if (id==11) layer(12,listOf(box[0]+box[2]/4,box[1]+box[3]/4,box[2]/2,box[3]/2),0.25f,dx,dy)
    }
    return out
}

@RunWith(AndroidJUnit4::class)
class FlycastSamplingTest {
    @get:Rule val compose = createComposeRule()
    private val ctx get() = InstrumentationRegistry.getInstrumentation().context
    private fun facts(name: String) = Json.parseToJsonElement(ctx.assets.open("emulator-facts/$name").bufferedReader().use { it.readText() }).jsonObject

    @Test fun actualReaderPortableGuestSamplesStraightEdgesAndMarginsAtFractionalNormalHeldAndMovingBounds() {
        val sampling = facts("flycast-straight-texture-sampling-observed.json")
        val physical = facts("flycast-physical-density-render-observed.json")
        val texels = sampling.getValue("source_texels_rgba").jsonArray.map { it.jsonArray.map { v -> v.jsonPrimitive.int } }
        val atlas = Bitmap.createBitmap(512,256,Bitmap.Config.ARGB_8888).apply {
            val pixels = IntArray(width*height) { i ->
                val rgba = texels[if (i%width%4 in 1..2) 1 else 0]
                android.graphics.Color.argb(rgba[3],rgba[0],rgba[1],rgba[2])
            }
            setPixels(pixels,0,width,0,0,width,height)
        }
        val files = object : EmuFiles by DirectFiles {
            override fun readText(path: String): String = "[config]\nVirtualGamepadTransparency=37"
            override fun writeTextAtomic(path: String,text: String): Boolean = error("overlay reads must not write")
        }
        val bundle = requireNotNull(EmuOverlayReader.readFlycast(ctx,"dreamcast",1604,720,
            sampling.getValue("noninteger_density_dpi").jsonPrimitive.float,files,"owned-config",atlas))
        EmuOverlayStore.clear()
        try {
            EmuOverlayReader.packParts("s",1,bundle.copy(controls=bundle.controls.filter { it.id in setOf("0","11") })).forEach {
                EmuOverlayStore.publish(1,LinkJson.decode(LinkJson.encode(it)) as LinkMsg.StreamEmuOverlay)
            }
            val decoded = requireNotNull(EmuOverlayStore.current())
            val boxes = physical.getValue("expected_controls").jsonArray.filter { it.jsonObject.getValue("id").jsonPrimitive.content in setOf("0","11") }
                .associate { it.jsonObject.getValue("id").jsonPrimitive.int to it.jsonObject.getValue("rect").jsonArray.map { v -> v.jsonPrimitive.float } }
            val states = mutableListOf<PadState>()
            var background by mutableStateOf(Color.Black)
            compose.setContent {
                val local = LocalDensity.current
                Box(Modifier.size(with(local) { 800.toDp() },with(local) { 360.toDp() }).background(background)) {
                    EmuOverlayPad(decoded,{_,_,_->},{_,_,_->},{},onTouchState={state,_->states+=state})
                }
            }
            val node = compose.onNodeWithTag(TAG_EMU_PAD)
            fun compare(held: Set<Int> = emptySet(),dx: Float = 0f,dy: Float = 0f) {
                val actual = node.captureToImage().toPixelMap()
                val expected = ownedFlycastReference(atlas,boxes,actual.width,actual.height,held,dx,dy,background=background.toArgb())
                for (y in 0 until actual.height) for (x in 0 until actual.width) for (shift in listOf(16,8,0)) {
                    val a = actual[x,y].toArgb() ushr shift and 255; val e = expected[y*actual.width+x] ushr shift and 255
                    assertTrue("held$held delta($dx,$dy) pixel($x,$y) channel$shift actual$a expected$e",kotlin.math.abs(a-e)<=1)
                }
            }
            compare()
            val left = boxes.getValue(0)
            node.performTouchInput { down(Offset((left[0]+left[2]/2)*800/1604,(left[1]+left[3]/2)/2)) }
            compare(setOf(0)); node.performTouchInput { up() }; compare()
            val motion = physical.getValue("analog_motion").jsonObject
            val down = motion.getValue("down_point").jsonArray.map { it.jsonPrimitive.float }
            node.performTouchInput { down(Offset(down[0]*800/1604,down[1]/2)) }
            compare(setOf(11))
            for (raw in motion.getValue("steps").jsonArray.drop(1)) {
                val row = raw.jsonObject
                val point = row.getValue("point").jsonArray.map { it.jsonPrimitive.float }
                val delta = row.getValue("visual_displacement").jsonArray.map { it.jsonPrimitive.float }
                node.performTouchInput { moveTo(Offset(point[0]*800/1604,point[1]/2)) }
                compare(setOf(11),delta[0],delta[1])
            }
            node.performTouchInput { up() }; compare()
            compose.runOnIdle { assertEquals(PadState.NEUTRAL,states.last()); background=Color(android.graphics.Color.rgb(17,43,89)) }
            compare()
        } finally { EmuOverlayStore.clear() }
    }

    @Test fun actualReaderPortableRawPngPreservesTransparentColorAndBothCellMargins() {
        val atlas = Bitmap.createBitmap(512,256,Bitmap.Config.ARGB_8888).apply { setPremultiplied(false) }
        val source = IntArray(64*64) { -1 }.apply { this[0]=0x001ec83c; this[lastIndex]=0x003cdc1e }
        atlas.setPixels(source,0,64,320,0,64,64)
        val files = object : EmuFiles by DirectFiles { override fun readText(path: String)="[config]" }
        val bundle = requireNotNull(EmuOverlayReader.readFlycast(ctx,"dreamcast",1604,720,264.555f,files,"owned-config",atlas))
        EmuOverlayStore.clear()
        try {
            EmuOverlayReader.packParts("s",1,bundle.copy(controls=bundle.controls.filter { it.id=="4" })).forEach {
                EmuOverlayStore.publish(1,LinkJson.decode(LinkJson.encode(it)) as LinkMsg.StreamEmuOverlay)
            }
            val stored=requireNotNull(EmuOverlayStore.current())
            assertEquals(1,stored.controls.single().textureInset)
            val bytes=stored.art.getValue(emuArtKey("4","art"))
            val bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply { inPremultiplied=false })
            assertEquals(64,bitmap.width); assertEquals(64,bitmap.height)
            assertEquals(source[0],bitmap.getPixel(0,0)); assertEquals(source.last(),bitmap.getPixel(63,63))
            assertEquals(-1,bitmap.getPixel(1,1))
        } finally { EmuOverlayStore.clear() }
    }

}
