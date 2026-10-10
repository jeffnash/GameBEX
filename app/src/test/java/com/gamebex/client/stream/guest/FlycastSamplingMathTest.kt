package com.gamebex.client.stream.guest

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class FlycastSamplingMathTest {
    private val facts get() = Json.parseToJsonElement(requireNotNull(javaClass.getResource("/emulator-facts/flycast-straight-texture-sampling-observed.json")).readText()).jsonObject
    private fun texel(raw: JsonElement): Int {
        val v = raw.jsonArray.map { it.jsonPrimitive.int }
        return v[3] shl 24 or (v[0] shl 16) or (v[1] shl 8) or v[2]
    }
    private fun rgb(color: Int) = intArrayOf(color ushr 16 and 255,color ushr 8 and 255,color and 255)
    @Test fun measuredTransparentWeightsInterpolateStraightChannelsBeforeByteModulationAndComposition() {
        val src = facts.getValue("source_texels_rgba").jsonArray.map(::texel).toIntArray()
        for (raw in facts.getValue("samples").jsonArray) {
            val row = raw.jsonObject
            val weight = row.getValue("opaque_weight").jsonPrimitive.float
            val result = renderStraightFrac(src,2,1,1,1,0,0,-weight,0f,2f,1f,1f,
                grayByte=facts.getValue("gray_byte").jsonPrimitive.int,opacityByte=facts.getValue("opacity_byte").jsonPrimitive.int,premultiplyOutput=true)
            assertArrayEquals(row.getValue("displayed_rgb").jsonArray.map { it.jsonPrimitive.int }.toIntArray(),rgb(result.single()))
        }
    }
    @Test fun marginTexelsRemainInTheBilinearFootprint() {
        val margin = facts.getValue("margin_case").jsonObject
        val row = margin.getValue("horizontal_texels_rgba").jsonArray.map(::texel)
        val src = (row+row+row).toIntArray()
        val result = renderStraightFrac(src,4,3,1,1,0,0,0f,0f,10f,1f,1f,sourceInset=1,
            grayByte=128,opacityByte=161,premultiplyOutput=true)
        assertArrayEquals(margin.getValue("displayed_rgb").jsonArray.map { it.jsonPrimitive.int }.toIntArray(),rgb(result.single()))
    }
    @Test fun colorUnderZeroAlphaSurvivesUntilStraightSampling() {
        val src = intArrayOf(0x001ec83c,-1)
        val result = renderStraightFrac(src,2,1,1,1,0,0,-0.5f,0f,2f,1f,1f,grayByte=128,opacityByte=161,premultiplyOutput=true)
        assertArrayEquals(intArrayOf(23,36,25),rgb(result.single()))
    }
    @Test fun fractionalCoverageUsesPixelCentersAndLegacyDefaultSamplingStaysUnchanged() {
        val outside = renderStraightFrac(intArrayOf(-1),1,1,1,1,0,0,0.51f,0f,1f,1f,1f,grayByte=128,opacityByte=161,premultiplyOutput=true)
        assertEquals(0,outside.single())
        val inside = renderStraightFrac(intArrayOf(-1),1,1,1,1,0,0,0.49f,0f,1f,1f,1f,grayByte=128,opacityByte=161,premultiplyOutput=true)
        assertArrayEquals(intArrayOf(81,81,81),rgb(inside.single()))
        assertEquals(0x80808080.toInt(),renderStraightFrac(intArrayOf(0,-1),2,1,1,1,0,0,-0.5f,0f,2f,1f).single())
    }
}
