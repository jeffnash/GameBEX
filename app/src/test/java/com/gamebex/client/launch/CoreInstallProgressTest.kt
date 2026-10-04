package com.gamebex.client.launch

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CoreInstallProgressTest {
    @Test
    fun immediateStateNamesTheN64Core() {
        val progress = CoreInstallProgress("mupen64plus_next_gles3")
        assertEquals("Installing the N64 core…", progress.text)
        assertNull(progress.fraction)
    }

    @Test
    fun downloadReportsBytesAndBoundsItsFraction() {
        val progress = CoreInstallProgress("mupen64plus_next_gles3", 3_355_443, 6_501_171)
        assertEquals("Downloading 3.2 of 6.2 MB", progress.text)
        assertEquals(0.516129f, progress.fraction!!, 0.000001f)
        assertEquals(1f, progress.copy(read = Long.MAX_VALUE).fraction!!, 0f)
        assertEquals(0f, progress.copy(read = -100).fraction!!, 0f)
    }

    @Test
    fun installingAndStalledRemainIndeterminate() {
        val progress = CoreInstallProgress("mupen64plus_next_gles3", 10, 10, installing = true)
        assertEquals("Installing in RetroArch…", progress.text)
        assertNull(progress.fraction)
        assertEquals("Still working…", progress.copy(stalled = true).text)
    }

    @Test
    fun unknownLengthAndLocaleAreHandled() {
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val progress = CoreInstallProgress("mupen64plus_next_gles3", 3_355_443)
            assertEquals("Downloading 3.2 MB", progress.text)
            assertNull(progress.fraction)
        } finally {
            Locale.setDefault(before)
        }
    }
}
