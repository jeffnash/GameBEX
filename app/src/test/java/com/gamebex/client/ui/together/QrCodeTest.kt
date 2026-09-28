package com.gamebex.client.ui.together

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QrCodeTest {
    @Test fun matrixDecodesBackToInput() {
        val input = "gamebex://join?v=1&h=10.42.1.20&p=55440&n=moto%20g%202025"
        val matrix = qrMatrix(input)
        val scale = 4
        val scaledWidth = matrix.width * scale
        val scaledHeight = matrix.height * scale
        val pixels = IntArray(scaledWidth * scaledHeight) { index ->
            val x = (index % scaledWidth) / scale
            val y = (index / scaledWidth) / scale
            if (matrix[x, y]) 0xff000000.toInt() else 0xffffffff.toInt()
        }
        val source = RGBLuminanceSource(scaledWidth, scaledHeight, pixels)
        val result = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), mapOf(DecodeHintType.TRY_HARDER to true))
        assertEquals(input, result.text)
        assertTrue(matrix.width == matrix.height && matrix.width < 128)
    }

    @Test fun modulesTileIntegerPixels() {
        val width = 250f
        val count = 33
        var cursor = 0
        for (i in 0 until count) {
            val rect = qrModulePx(i, 0, count, count, width, width)
            assertEquals(cursor, rect.left)
            assertTrue(rect.width > 0)
            cursor += rect.width
        }
        assertEquals(width.toInt(), cursor)
    }
}
