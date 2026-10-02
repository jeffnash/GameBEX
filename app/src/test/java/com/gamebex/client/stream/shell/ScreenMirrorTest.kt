package com.gamebex.client.stream.shell

import org.junit.Assert.assertArrayEquals
import org.junit.Test

/** The aspect-ratio box the SurfaceControl fallback projects into. */
class ScreenMirrorTest {
    @Test
    fun theMotoScreenFillsLevelZeroWithAThinBand() {
        assertArrayEquals(intArrayOf(0, 1, 1600, 719), ScreenMirror.fit(1604, 720, 1600, 720))
    }

    @Test
    fun aPortraitScreenIsPillarboxed() {
        assertArrayEquals(intArrayOf(638, 0, 962, 720), ScreenMirror.fit(1080, 2400, 1600, 720))
    }

    @Test
    fun aWideSourceIsLetterboxed() {
        assertArrayEquals(intArrayOf(0, 60, 1280, 515), ScreenMirror.fit(2560, 910, 1280, 576))
    }

    @Test
    fun anUnknownSourceFillsTheBox() {
        assertArrayEquals(intArrayOf(0, 0, 1280, 576), ScreenMirror.fit(0, 0, 1280, 576))
    }
}
