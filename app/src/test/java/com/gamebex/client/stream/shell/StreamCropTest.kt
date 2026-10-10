package com.gamebex.client.stream.shell

import com.gamebex.client.stream.VideoLevel
import org.junit.Assert.assertArrayEquals
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

    @Test fun aspectParsesAnySmallRatioAndRejectsGarbage() {
        assertArrayEquals(intArrayOf(3, 2), StreamCropOptions.aspectOrNull("3:2"))
        assertArrayEquals(intArrayOf(10, 9), StreamCropOptions.aspectOrNull("10:9"))
        assertArrayEquals(intArrayOf(16, 9), StreamCropOptions.aspectOrNull("16:9"))
        assertNull(StreamCropOptions.aspectOrNull(null))
        assertNull(StreamCropOptions.aspectOrNull("bogus"))
        assertNull(StreamCropOptions.aspectOrNull("4"))
        assertNull(StreamCropOptions.aspectOrNull("4:3:2"))
        assertNull(StreamCropOptions.aspectOrNull("0:3"))
        assertNull(StreamCropOptions.aspectOrNull("4:0"))
        assertNull(StreamCropOptions.aspectOrNull("64:3"))
        assertArrayEquals(intArrayOf(4, 3), StreamCropOptions.aspectOf("bogus"))
        assertArrayEquals(intArrayOf(4, 3), StreamCropOptions.aspectOf(null))
    }

    @Test fun sampleHonorsNonFourThreeAspects() {
        assertEquals(StreamCropOptions(true, 3, 2), StreamCropOptions.sample(true) {
            when (it) { "stream_crop" -> "1"; "stream_crop_aspect" -> "3:2"; else -> null }
        })
        assertEquals(StreamCropOptions(true, 10, 9), StreamCropOptions.sample(true) {
            when (it) { "stream_crop" -> "1"; "stream_crop_aspect" -> "10:9"; else -> null }
        })
    }

    @Test fun gbaLevelCropsToThreeTwoCenter() {
        // 1604x720 lab display, 1534-wide RA viewport: the 3:2 game
        // picture is 1080 wide starting at x=227.
        val rect = streamCropRect(1604, 720, 3, 2, "0,0,1534,720,1604,720")
        assertEquals(CropRect(227, 0, 1080, 720), rect)
        // Legacy path (no viewport): centered in the display.
        assertEquals(CropRect(262, 0, 1080, 720), streamCropRect(1604, 720, 3, 2))
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

    @Test fun labViewportCropsDisplayNative() {
        // No viewport: legacy level-centered rect (even origin).
        assertEquals(CropRect(256, 0, 768, 576), streamCropRect(1280, 576, 4, 3))
        // Landscape viewport: display-px rect against the letterboxed
        // window (0,0,1534x720 in 1604x720) — the capture runs native so
        // the game rect lands exact, odd origin allowed.
        assertEquals(
            CropRect(287, 0, 960, 720),
            streamCropRect(1280, 576, 4, 3, "0,0,1534,720,1604,720"),
        )
        assertEquals(
            CropRect(287, 0, 960, 720),
            streamCropRect(1604, 720, 4, 3, "0,0,1534,720,1604,720"),
        )
    }

    @Test fun portraitViewportFallsBackToLegacy() {
        // A portrait-time viewport (the lobby, before the game rotates
        // the display) must not portrait-size the capture: legacy
        // level-centered rect until the landscape re-pin.
        assertEquals(
            CropRect(256, 0, 768, 576),
            streamCropRect(1280, 576, 4, 3, "0,0,720,1604,720,1604"),
        )
    }

    @Test fun captureSizeIsNativeOnLandscapeViewport() {
        val level = VideoLevel(1, 1280, 576, 60, 4_000_000)
        assertEquals(
            1604 to 720,
            streamCropCaptureSize(level, StreamCropOptions(true, 4, 3, "0,0,1534,720,1604,720")),
        )
        // Legacy: portrait viewport, no viewport, or crop off.
        assertEquals(
            1280 to 576,
            streamCropCaptureSize(level, StreamCropOptions(true, 4, 3, "0,0,720,1604,720,1604")),
        )
        assertEquals(1280 to 576, streamCropCaptureSize(level, StreamCropOptions(true, 4, 3)))
        assertEquals(1280 to 576, streamCropCaptureSize(level, StreamCropOptions()))
    }

    @Test fun shellCutoutViewportMirrorsTheHostMath() {
        // No cutout: full display.
        assertArrayEquals(
            intArrayOf(0, 0, 1604, 720),
            shellCutoutViewport(1604, 720, emptyList()),
        )
        // Right-edge cutout strip: letterboxed off the right.
        assertArrayEquals(
            intArrayOf(0, 0, 1534, 720),
            shellCutoutViewport(1604, 720, listOf(intArrayOf(1534, 100, 1604, 200))),
        )
        // Floating hole-punch: letterboxes nothing.
        assertArrayEquals(
            intArrayOf(0, 0, 1604, 720),
            shellCutoutViewport(1604, 720, listOf(intArrayOf(700, 100, 760, 140))),
        )
        // Degenerate: falls back to the full display.
        assertArrayEquals(
            intArrayOf(0, 0, 1604, 720),
            shellCutoutViewport(1604, 720, listOf(intArrayOf(0, 0, 1604, 720))),
        )
    }

    @Test fun rotationWaitSeesLandscapeImmediately() {
        assertEquals(true, waitForLandscapeRotation(null, rotation = { android.view.Surface.ROTATION_90 }))
        assertEquals(true, waitForLandscapeRotation(null, rotation = { android.view.Surface.ROTATION_270 }))
        assertEquals(false, waitForLandscapeRotation(null, timeoutMs = 10, pollMs = 5, rotation = { android.view.Surface.ROTATION_0 }))
        assertEquals(false, waitForLandscapeRotation(null, timeoutMs = 10, pollMs = 5, rotation = { null }))
    }

    @Test fun unreadableMetricsYieldNoViewport() {
        assertEquals("", currentCropViewport(null))
    }

    @Test fun cropUpdateIsANoOpWhenIdle() {
        val source = AvcScreenSource(config = null)
        source.updateCropViewport("0,0,1534,720,1604,720")
        source.updateCropViewport("bogus")
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
