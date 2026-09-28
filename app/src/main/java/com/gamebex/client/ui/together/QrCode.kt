package com.gamebex.client.ui.together

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix

@Composable
fun QrCode(text: String, dimension: Dp = 300.dp, modifier: Modifier = Modifier) {
    val matrix = remember(text) { qrMatrix(text) }
    Canvas(modifier.size(dimension).background(Color.White)) {
        val columns = matrix.width
        val rows = matrix.height
        if (columns == 0 || rows == 0) return@Canvas
        for (y in 0 until rows) for (x in 0 until columns) {
            if (!matrix[x, y]) continue
            val rect = qrModulePx(x, y, columns, rows, size.width, size.height)
            drawRect(Color.Black, Offset(rect.left.toFloat(), rect.top.toFloat()), Size(rect.width.toFloat(), rect.height.toFloat()), style = Fill)
        }
    }
}

internal data class QrModulePx(val left: Int, val top: Int, val width: Int, val height: Int)

internal fun qrModulePx(x: Int, y: Int, columns: Int, rows: Int, widthPx: Float, heightPx: Float): QrModulePx {
    val left = (x * widthPx / columns).toInt()
    val top = (y * heightPx / rows).toInt()
    val right = ((x + 1) * widthPx / columns).toInt()
    val bottom = ((y + 1) * heightPx / rows).toInt()
    return QrModulePx(left, top, right - left, bottom - top)
}

internal fun qrMatrix(text: String): BitMatrix = MultiFormatWriter().encode(
    text,
    BarcodeFormat.QR_CODE,
    0,
    0,
    mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.CHARACTER_SET to "UTF-8"),
)
