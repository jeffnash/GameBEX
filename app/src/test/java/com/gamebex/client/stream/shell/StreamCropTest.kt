package com.gamebex.client.stream.shell

import com.gamebex.client.stream.VideoLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamCropTest {
    @Test fun disabledByDefaultAndWithoutDebug() {
        assertEquals(StreamCropOptions(), StreamCropOptions.sample(false) { "1" })
        assertEquals(StreamCropOptions(), StreamCropOptions.sample(true) { null })
        assertEquals(StreamCropOptions(), StreamCropOptions.sample(true) { "0" })
    }

    @Test fun aspectDefaultsToFourThree() {
        assertEquals(StreamCropOptions(true, 4, 3), StreamCropOptions.sample(true) {
            when (it) { "stream_crop" -> "1"; else -> null }
        })
        assertEquals(StreamCropOptions(true, 16, 9), StreamCropOptions.sample(true) {
            when (it) { "stream_crop" -> "1"; "stream_crop_aspect" -> "16:9"; else -> null }
        })
        assertEquals(StreamCropOptions(true, 4, 3), StreamCropOptions.sample(true) {
            when (it) { "stream_crop" -> "1"; "stream_crop_aspect" -> "bogus"; else -> null }
        })
    }

    @Test fun palLevelCropsToFourThreeCenter() {
        assertEquals(CropRect(256, 0, 768, 576), streamCropRect(1280, 576, 4, 3))
        assertEquals(768 to 576, streamCropEncodeSize(CropRect(256, 0, 768, 576)))
    }

    @Test fun fullHdLevelCropsToFourThreeCenter() {
        assertEquals(CropRect(320, 0, 960, 720), streamCropRect(1600, 720, 4, 3))
        assertEquals(960 to 720, streamCropEncodeSize(CropRect(320, 0, 960, 720)))
    }

    @Test fun sixteenNineCropOfPalLevel() {
        assertEquals(CropRect(128, 0, 1024, 576), streamCropRect(1280, 576, 16, 9))
    }

    @Test fun oddSizesStayEven() {
        val rect = streamCropRect(1279, 575, 4, 3)
        assertEquals(0, rect.left % 2)
        assertEquals(0, rect.top % 2)
        assertEquals(0, rect.width % 2)
        assertEquals(0, rect.height % 2)
    }

    @Test fun matchingAspectDisablesCrop() {
        val level = VideoLevel(9, 1024, 576, 60, 3_000_000)
        val sixteenNine = streamCropEncodeLevel(level, StreamCropOptions(true, 16, 9))
        assertEquals(level, sixteenNine.first)
        assertNull(sixteenNine.second)
    }

    @Test fun encodeLevelKeepsIndexFpsAndBitrate() {
        val level = VideoLevel(1, 1280, 576, 60, 4_000_000)
        val (encode, rect) = streamCropEncodeLevel(level, StreamCropOptions(true, 4, 3))
        assertEquals(VideoLevel(1, 768, 576, 60, 4_000_000), encode)
        assertEquals(CropRect(256, 0, 768, 576), rect)
    }

    @Test fun disabledOptionsLeaveLevelUntouched() {
        val level = VideoLevel(1, 1280, 576, 60, 4_000_000)
        val (encode, rect) = streamCropEncodeLevel(level, StreamCropOptions())
        assertEquals(level, encode)
        assertNull(rect)
    }
}
