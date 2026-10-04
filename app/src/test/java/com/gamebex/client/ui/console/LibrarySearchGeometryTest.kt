package com.gamebex.client.ui.console

import com.gamebex.client.ui.librarySearchGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibrarySearchGeometryTest {
    @Test fun compressedPhoneKeeps48dpSearchAboveFiltersAndList() {
        val density = 1.75f
        val g = ConsoleGeometry(consoleScale(1604f / density, 720f / density))
        val searchHeight = g.hitMin(g.x(56))
        val geometry = librarySearchGeometry(g.scale, searchHeight.value)
        val searchBottom = g.x(92).value + searchHeight.value

        assertEquals(48f, searchHeight.value, 0.0001f)
        assertTrue(g.x(164).value < searchBottom)
        assertEquals(176f, searchBottom * density, 0.001f)
        assertEquals(184f, geometry.chipTop * density, 0.001f)
        assertEquals(236f, geometry.listTop * density, 0.001f)
        assertEquals(648f, (geometry.listTop + geometry.listHeight) * density, 0.001f)
        assertEquals(g.x(8).value, geometry.chipTop - searchBottom, 0.0001f)
        assertEquals(
            g.x(16).value,
            geometry.listTop - geometry.chipTop - g.x(36).value,
            0.0001f,
        )
    }

    @Test fun fullSizeCanvasKeepsOriginalPositionsAndListBottom() {
        val g = ConsoleGeometry(1f)
        val searchHeight = g.hitMin(g.x(56))
        val geometry = librarySearchGeometry(g.scale, searchHeight.value)

        assertEquals(56f, searchHeight.value, 0f)
        assertEquals(164f, geometry.chipTop, 0f)
        assertEquals(220f, geometry.listTop, 0f)
        assertEquals(428f, geometry.listHeight, 0f)
    }

    @Test fun landscapeWindowsAndTextScalesKeepMinimumGapsAndTouchTarget() {
        val windows = listOf(
            Triple(1604f, 720f, 1.75f),
            Triple(1280f, 720f, 2f),
            Triple(1920f, 1080f, 1.25f),
            Triple(2560f, 1440f, 2f),
            Triple(1600f, 720f, 1f),
        )
        for ((widthPx, heightPx, density) in windows) {
            for (textScale in listOf(1f, 1.25f, 1.5f)) {
                val g = ConsoleGeometry(consoleScale(widthPx / density, heightPx / density), textScale)
                val searchHeight = g.hitMin(g.x(56))
                val geometry = librarySearchGeometry(g.scale, searchHeight.value)
                val searchBottom = g.x(92).value + searchHeight.value
                val chipBottom = geometry.chipTop + g.x(36).value
                val context = "${widthPx}x$heightPx density=$density textScale=$textScale"

                assertTrue(context, searchHeight.value >= 48f)
                assertTrue(context, geometry.chipTop >= g.x(164).value)
                assertTrue(context, geometry.listTop >= g.x(220).value)
                assertTrue(context, geometry.chipTop - searchBottom >= g.x(8).value - 0.0001f)
                assertTrue(context, geometry.listTop - chipBottom >= g.x(16).value - 0.0001f)
                assertTrue(context, geometry.listHeight > 0f)
                assertEquals(context, g.x(648).value, geometry.listTop + geometry.listHeight, 0.0001f)
            }
        }
    }

    @Test fun positionsFollowTheProvidedSearchHeight() {
        val scale = 0.5f
        val searchHeight = 64f
        val geometry = librarySearchGeometry(scale, searchHeight)

        assertEquals(114f, geometry.chipTop, 0f)
        assertEquals(140f, geometry.listTop, 0f)
        assertEquals(184f, geometry.listHeight, 0f)
    }
}
