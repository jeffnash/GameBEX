package com.gamebex.client.stream.guest

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.tan




const val RA_SHOW_INPUT_NONE: Int = 0
const val RA_SHOW_INPUT_TOUCHED: Int = 1
const val RA_SHOW_INPUT_PHYSICAL: Int = 2


data class RaOverlaySettings(
    val opacity: Float = 0.7f,
    val autoScale: Boolean = true,
    val showInputs: Int = RA_SHOW_INPUT_TOUCHED,
    val scaleLandscape: Float = 1f,
    val aspectAdjustLandscape: Float = 0f,
    val xSeparationLandscape: Float = 0f,
    val ySeparationLandscape: Float = 0f,
    val xOffsetLandscape: Float = 0f,
    val yOffsetLandscape: Float = 0f,
    val scalePortrait: Float = 1f,
    val aspectAdjustPortrait: Float = 0f,
    val xSeparationPortrait: Float = 0f,
    val ySeparationPortrait: Float = 0f,
    val xOffsetPortrait: Float = 0f,
    val yOffsetPortrait: Float = 0f,
    val floatingAlpha: Boolean = false,
    val dpadDiagonalSensitivity: Float = 80f,
    val abxyDiagonalSensitivity: Float = 50f,
) {
    companion object {

        fun parse(vararg texts: String): RaOverlaySettings {
            val values = linkedMapOf<String, String>()
            texts.forEach { text -> text.lineSequence().forEach { line ->
                val entry = OverlayCfg.cfgEntry(line)
                if (entry != null) {
                    val (externalKey, value) = entry
                    val key = externalKey.removePrefix("input_overlay_")
                    if (externalKey == "video_driver" && value.isNotEmpty()) values[key] = value
                    else if (key != "video_driver" && (value.toFloatOrNull()?.isFinite() == true || value == "true" || value == "false")) values[key] = value
                }
            } }
            fun number(key: String, default: Float) = values[key]?.toFloatOrNull()?.takeIf { it.isFinite() } ?: default
            return RaOverlaySettings(
                opacity = number("opacity", 0.7f),
                autoScale = values["auto_scale"]?.toBooleanStrictOrNull() ?: true,
                showInputs = number("show_inputs", 1f).toInt().coerceIn(0, 2),
                scaleLandscape = number("scale_landscape", 1f), aspectAdjustLandscape = number("aspect_adjust_landscape", 0f),
                xSeparationLandscape = number("x_separation_landscape", 0f), ySeparationLandscape = number("y_separation_landscape", 0f),
                xOffsetLandscape = number("x_offset_landscape", 0f), yOffsetLandscape = number("y_offset_landscape", 0f),
                scalePortrait = number("scale_portrait", 1f), aspectAdjustPortrait = number("aspect_adjust_portrait", 0f),
                xSeparationPortrait = number("x_separation_portrait", 0f), ySeparationPortrait = number("y_separation_portrait", 0f),
                xOffsetPortrait = number("x_offset_portrait", 0f), yOffsetPortrait = number("y_offset_portrait", 0f),
                floatingAlpha = values["video_driver"] == "gl",
                dpadDiagonalSensitivity = number("dpad_diagonal_sensitivity", 80f).coerceIn(0f, 100f),
                abxyDiagonalSensitivity = number("abxy_diagonal_sensitivity", 50f).coerceIn(0f, 100f),
            )
        }
    }
}

internal fun raAreaDirections(x: Float, y: Float, sensitivity: Float): BooleanArray {
    if (!x.isFinite() || !y.isFinite()) return BooleanArray(4)
    val s = sensitivity.toDouble().coerceIn(0.0, 100.0)
    val fraction = 2 * s / (100 + s)
    val lower = tan(Math.PI * (0.25 - fraction / 8))
    val upper = tan(Math.PI * (0.25 + fraction / 8))
    val horizontal = abs(y.toDouble()) <= upper * abs(x.toDouble())
    val vertical = abs(y.toDouble()) >= lower * abs(x.toDouble())
    return booleanArrayOf(vertical && y < 0f, vertical && y >= 0f, horizontal && x <= 0f, horizontal && x > 0f)
}


data class ResolvedRaDesc(
    val desc: OverlayDesc,

    val modX: Float,
    val modY: Float,
    val modW: Float,
    val modH: Float,

    val hitX: Float,
    val hitY: Float,
    val hitRangeX: Float,
    val hitRangeY: Float,

    val hitRangeXMod: Float,
    val hitRangeYMod: Float,
) {

    fun pixelRect(screenW: Int, screenH: Int): RaPixelRect = mappedRect(OverlayViewportMapping(screenW, screenH, screenW.toFloat(), screenH.toFloat(), 0f, 0f))


    fun mappedRect(mapping: OverlayViewportMapping, dxPx: Float = 0f, dyPx: Float = 0f): RaPixelRect = mappedRectF(mapping, dxPx, dyPx).loggedRect()


    fun mappedRectF(mapping: OverlayViewportMapping, dxPx: Float = 0f, dyPx: Float = 0f): RaFloatRect = RaFloatRect(
        mapping.offX + modX * mapping.effW + dxPx, mapping.offY + modY * mapping.effH + dyPx,
        mapping.offX + (modX + modW) * mapping.effW + dxPx, mapping.offY + (modY + modH) * mapping.effH + dyPx,
    )
}

data class RaPixelRect(val left: Int, val top: Int, val width: Int, val height: Int)


data class RaFloatRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

private fun RaFloatRect.loggedRect(): RaPixelRect {
    val x = (left + 0.5f).toInt()
    val y = (top + 0.5f).toInt()
    return RaPixelRect(x, y, ((right + 0.5f).toInt() - x).coerceAtLeast(1), ((bottom + 0.5f).toInt() - y).coerceAtLeast(1))
}


data class ResolvedRaPage(
    val page: OverlayPage,
    val modX: Float,
    val modY: Float,
    val modW: Float,
    val modH: Float,
    val descs: List<ResolvedRaDesc>,
) {

    fun mappedRect(mapping: OverlayViewportMapping): RaPixelRect = mappedRectF(mapping).loggedRect()


    fun mappedRectF(mapping: OverlayViewportMapping): RaFloatRect = RaFloatRect(
        mapping.offX + modX * mapping.effW, mapping.offY + modY * mapping.effH,
        mapping.offX + (modX + modW) * mapping.effW, mapping.offY + (modY + modH) * mapping.effH,
    )
}


fun resolveRaPage(page: OverlayPage, settings: RaOverlaySettings, screenW: Int, screenH: Int): ResolvedRaPage {
    var sx = 1f
    var sy = 1f
    var ox = 0f
    var oy = 0f
    var sepX = 0f
    var sepY = 0f
    if (settings.autoScale) {
        val q = screenW.toFloat() / screenH
        val a = page.aspectRatio
        if (q.isFinite() && q > 0f && a.isFinite() && a > 0f) {
            if (q > a) sx = a / q else sy = q / a
            if (page.autoXSeparation && !page.blockXSeparation) sepX = (1f / sx - 1f) / 2f
            if (page.autoYSeparation && !page.blockYSeparation) sepY = (1f / sy - 1f) / 2f
        }
    } else {
        val portrait = screenH > screenW
        val s = if (portrait) settings.scalePortrait else settings.scaleLandscape
        val b = if (portrait) settings.aspectAdjustPortrait else settings.aspectAdjustLandscape
        val adjusted = if (b >= 0f) s * (1f + b) else s / (1f - b)
        sx = if (portrait) s else adjusted
        sy = if (portrait) adjusted else s
        ox = if (portrait) settings.xOffsetPortrait else settings.xOffsetLandscape
        oy = -(if (portrait) settings.yOffsetPortrait else settings.yOffsetLandscape)
        if (!page.blockXSeparation) sepX = if (portrait) settings.xSeparationPortrait else settings.xSeparationLandscape
        if (!page.blockYSeparation) sepY = if (portrait) settings.ySeparationPortrait else settings.ySeparationLandscape
    }
    val centerX = page.rectX + page.rectW / 2f
    val centerY = page.rectY + page.rectH / 2f
    val x = centerX + (page.rectX - centerX) * sx + ox
    val y = centerY + (page.rectY - centerY) * sy + oy
    val w = page.rectW * sx
    val h = page.rectH * sy
    return ResolvedRaPage(page, x, y, w, h, page.descs.map { resolveRaDesc(it, x, y, w, h, sepX, sepY) })
}

private fun resolveRaDesc(
    d: OverlayDesc,
    pageModX: Float,
    pageModY: Float,
    pageModW: Float,
    pageModH: Float,
    xSep: Float,
    ySep: Float,
): ResolvedRaDesc {
    fun shift(v: Float, separation: Float) = v + when { v < 0.4999f -> -separation; v > 0.5001f -> separation; else -> 0f }
    val cx = shift(d.x, xSep)
    val cy = shift(d.y, ySep)
    val hx = d.w * (d.reachLeft + d.reachRight) / 2f
    val hy = d.h * (d.reachUp + d.reachDown) / 2f
    return ResolvedRaDesc(d, pageModX + (cx - d.w) * pageModW, pageModY + (cy - d.h) * pageModH,
        2f * d.w * pageModW, 2f * d.h * pageModH,
        cx + d.w * (d.reachRight - d.reachLeft) / 2f, cy + d.h * (d.reachDown - d.reachUp) / 2f,
        hx, hy, hx * d.rangeMod, hy * d.rangeMod)
}


fun raHitTest(
    resolved: ResolvedRaDesc,
    page: ResolvedRaPage,
    screenX: Float,
    screenY: Float,
    useRangeMod: Boolean = false,
): Boolean {
    if (resolved.desc.hitDisabled || !page.modW.isFinite() || !page.modH.isFinite() || page.modW <= 0f || page.modH <= 0f) return false
    val (x, y) = toOverlayCoords(page, screenX, screenY)
    val w = if (useRangeMod) resolved.hitRangeXMod else resolved.hitRangeX
    val h = if (useRangeMod) resolved.hitRangeYMod else resolved.hitRangeY
    if (!w.isFinite() || !h.isFinite() || w <= 0f || h <= 0f || !x.isFinite() || !y.isFinite()) return false
    val dx = abs(x - resolved.hitX) / w
    val dy = abs(y - resolved.hitY) / h
    return when (resolved.desc.shape) { OverlayShape.RECT -> dx <= 1f && dy <= 1f; OverlayShape.RADIAL -> dx * dx + dy * dy <= 1f }
}


fun raAlphaFor(desc: OverlayDesc, settings: RaOverlaySettings, pressed: Boolean): Float {
    val multiplier = if (pressed && settings.showInputs == RA_SHOW_INPUT_TOUCHED) desc.alphaMod else 1f
    if (!settings.opacity.isFinite() || settings.opacity < 0f || !multiplier.isFinite() || multiplier < 0f) return 0f
    val alpha = settings.opacity * multiplier
    return if (!alpha.isFinite()) 0f else if (settings.floatingAlpha) alpha else alpha.coerceIn(0f, 1f)
}


fun raMovableDelta(
    resolved: ResolvedRaDesc,
    page: ResolvedRaPage,
    overlayX: Float,
    overlayY: Float,
): Pair<Float, Float> {
    if (!resolved.desc.movable) return 0f to 0f
    val cx = (resolved.modX - page.modX) / page.modW + resolved.desc.w
    val cy = (resolved.modY - page.modY) / page.modH + resolved.desc.h
    return (overlayX - cx).coerceIn(-resolved.desc.w, resolved.desc.w) * page.modW to
        (overlayY - cy).coerceIn(-resolved.desc.h, resolved.desc.h) * page.modH
}


fun toOverlayCoords(page: ResolvedRaPage, screenX: Float, screenY: Float): Pair<Float, Float> =
    (screenX - page.modX) / page.modW to (screenY - page.modY) / page.modH


fun scaleStraightBilinear(src: IntArray, w: Int, h: Int, dstW: Int, dstH: Int): IntArray =
    renderStraightFrac(src, w, h, dstW, dstH, 0, 0, 0f, 0f, dstW.toFloat(), dstH.toFloat())


fun renderStraightFrac(
    src: IntArray, w: Int, h: Int,
    dstW: Int, dstH: Int, ox: Int, oy: Int,
    fx0: Float, fy0: Float, fw: Float, fh: Float,
): IntArray = renderStraightFrac(src, w, h, dstW, dstH, ox, oy, fx0, fy0, fw, fh, 1f)

internal fun renderStraightFrac(
    src: IntArray, w: Int, h: Int,
    dstW: Int, dstH: Int, ox: Int, oy: Int,
    fx0: Float, fy0: Float, fw: Float, fh: Float, alphaGain: Float,
    sourceInset: Int = 0, grayByte: Int = 255, opacityByte: Int = 255, premultiplyOutput: Boolean = false,
): IntArray {
    require(w > 0 && h > 0 && dstW > 0 && dstH > 0 && w.toLong() * h <= src.size)
    require(fw.isFinite() && fh.isFinite() && fx0.isFinite() && fy0.isFinite() && fw > 0f && fh > 0f)
    require(dstW.toLong() * dstH <= Int.MAX_VALUE)
    require(sourceInset >= 0 && w > sourceInset * 2 && h > sourceInset * 2)
    require(grayByte in 0..255 && opacityByte in 0..255)
    val gain = alphaGain.takeIf { it.isFinite() && it >= 0f } ?: 0f
    val out = IntArray(dstW * dstH)
    for (y in 0 until dstH) for (x in 0 until dstW) {
        val px = ox + x + 0.5f
        val py = oy + y + 0.5f
        if (px < fx0 || py < fy0 || px >= fx0 + fw || py >= fy0 + fh) continue
        val sx = (sourceInset + (px - fx0) * (w - 2 * sourceInset) / fw - 0.5f).coerceIn(0f, (w - 1).toFloat())
        val sy = (sourceInset + (py - fy0) * (h - 2 * sourceInset) / fh - 0.5f).coerceIn(0f, (h - 1).toFloat())
        val ix = floor(sx).toInt()
        val iy = floor(sy).toInt()
        val tx = sx - ix
        val ty = sy - iy
        val a = src[iy * w + ix]
        val b = src[iy * w + (ix + 1).coerceAtMost(w - 1)]
        val c = src[(iy + 1).coerceAtMost(h - 1) * w + ix]
        val d = src[(iy + 1).coerceAtMost(h - 1) * w + (ix + 1).coerceAtMost(w - 1)]
        fun interpolated(shift: Int): Float {
            fun channel(v: Int) = (v ushr shift and 255).toFloat()
            val top = channel(a) * (1f - tx) + channel(b) * tx
            val bottom = channel(c) * (1f - tx) + channel(d) * tx
            return top * (1f - ty) + bottom * ty
        }
        var color = 0
        if (premultiplyOutput) {
            val alpha = (interpolated(24) * gain * opacityByte / 255f).coerceIn(0f, 255f)
            color = floor(alpha + 0.5f).toInt() shl 24
            for (shift in intArrayOf(16, 8, 0)) {
                val value = interpolated(shift) * grayByte / 255f * alpha / 255f
                color = color or (floor(value + 0.5f).toInt().coerceIn(0, 255) shl shift)
            }
        } else {
            for (shift in intArrayOf(24, 16, 8, 0)) {
                val value = interpolated(shift) * if (shift == 24) gain else 1f
                color = color or (floor(value + 0.5f).toInt().coerceIn(0, 255) shl shift)
            }
        }
        out[y * dstW + x] = color
    }
    return out
}


data class OverlayViewportMapping(
    val geomW: Int,
    val geomH: Int,
    val effW: Float,
    val effH: Float,
    val offX: Float,
    val offY: Float,
)


fun overlayViewportMapping(
    canvasW: Float,
    canvasH: Float,
    hostViewportW: Int,
    hostViewportH: Int,
    hostOriginX: Int = 0,
    hostOriginY: Int = 0,
    hostDisplayW: Int = 0,
    hostDisplayH: Int = 0,
): OverlayViewportMapping {
    if (hostViewportW <= 0 || hostViewportH <= 0) {
        return OverlayViewportMapping(canvasW.toInt(), canvasH.toInt(), canvasW, canvasH, 0f, 0f)
    }
    val dw = if (hostDisplayW > 0) hostDisplayW else hostViewportW
    val dh = if (hostDisplayH > 0) hostDisplayH else hostViewportH
    val sx = if (dw > 0) canvasW / dw else 1f
    val sy = if (dh > 0) canvasH / dh else 1f
    return OverlayViewportMapping(
        geomW = hostViewportW,
        geomH = hostViewportH,
        effW = hostViewportW * sx,
        effH = hostViewportH * sy,
        offX = hostOriginX * sx,
        offY = hostOriginY * sy,
    )
}


data class VideoCropPlacement(val x: Int, val y: Int, val w: Int, val h: Int)


internal data class VideoCropSignal(
    val cropX: Int,
    val cropY: Int,
    val cropW: Int,
    val cropH: Int,
    val hostDisplayW: Int,
    val hostDisplayH: Int,
    val infoW: Int,
    val infoH: Int,
)


fun videoCropPlacement(
    cropX: Int,
    cropY: Int,
    cropW: Int,
    cropH: Int,
    hostDisplayW: Int,
    hostDisplayH: Int,
    canvasW: Int,
    canvasH: Int,
    infoW: Int,
    infoH: Int,
): VideoCropPlacement? {
    if (cropW <= 0 || cropH <= 0) return null
    if (hostDisplayW <= 0 || hostDisplayH <= 0) return null
    if (canvasW <= 0 || canvasH <= 0) return null
    if (infoW <= 0 || infoH <= 0) return null
    if (cropX < 0 || cropY < 0) return null
    if (cropX + cropW > hostDisplayW || cropY + cropH > hostDisplayH) return null



    val lhs = cropW.toLong() * infoH
    val rhs = cropH.toLong() * infoW
    val diff = abs(lhs - rhs)
    if (diff * 20 > rhs) return null
    val x = (cropX.toLong() * canvasW / hostDisplayW).toInt()
    val y = (cropY.toLong() * canvasH / hostDisplayH).toInt()
    val w = (cropW.toLong() * canvasW / hostDisplayW).toInt()
    val h = (cropH.toLong() * canvasH / hostDisplayH).toInt()
    if (w <= 0 || h <= 0) return null
    if (x + w > canvasW || y + h > canvasH) return null
    return VideoCropPlacement(x, y, w, h)
}
