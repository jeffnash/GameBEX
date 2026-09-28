package com.gamebex.client.ui.console

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Console canvas fit, readable-type/touch floors, and palette contrast. */
class ConsoleTokensTest {
    @Test fun scaleFitsBothDimensions() {
        assertEquals(1f, consoleScale(1600f, 720f), 0.0001f)
        // Reduced-height window: width alone would give 1.0 and overflow.
        assertEquals(0.5f, consoleScale(1600f, 360f), 0.0001f)
        assertEquals(0.5f, consoleScale(800f, 720f), 0.0001f)
    }

    @Test fun canvasKeepsHandheldRatioExact() {
        val (w, h) = consoleCanvasDp(0.573f)
        assertEquals(1600f / 720f, w.value / h.value, 0.0001f)
    }

    @Test fun canvasFillsTargetWindow() {
        val (w, h) = consoleCanvasDp(consoleScale(1600f, 720f))
        assertEquals(1600f, w.value, 0.01f)
        assertEquals(720f, h.value, 0.01f)
    }

    @Test fun smallTypeFlooredButHeadingsUntouched() {
        val g = ConsoleGeometry(917f / 1600f) // ~917dp handheld window
        assertEquals(10.sp, g.tMin(12))
        assertEquals(10.sp, g.tMin(13))
        assertEquals((40f * g.scale).sp, g.tMin(40))
    }

    @Test fun touchFloorOnlyRaisesSmallTargets() {
        val g = ConsoleGeometry(1f)
        assertEquals(48.dp, g.hitMin(41.dp))
        assertEquals(72.dp, g.hitMin(72.dp))
    }

    @Test fun consoleTertiaryMeetsContrastOnSurfaces() {
        assertTrue(consoleContrastRatio(ConsoleColors.TextTertiary, ConsoleColors.Surface) >= 4.5)
        assertTrue(consoleContrastRatio(ConsoleColors.TextTertiary, ConsoleColors.Background) >= 4.5)
        assertTrue(consoleContrastRatio(ConsoleColors.TextTertiary, ConsoleColors.HighSurface) >= 4.5)
        assertTrue(consoleContrastRatio(ConsoleColors.TextSecondary, ConsoleColors.Surface) >= 4.5)
    }

    @Test fun accentPinkUnchanged() {
        assertEquals(Color(0xFFFF4D8D), ConsoleColors.Accent)
    }
}
