package com.gamebex.client.stream.guest

import com.gamebex.client.stream.proto.PadState
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.roundToInt
import kotlinx.serialization.json.*




object DolphinOverlay {
    internal fun gcPositionResource(id: String): String? = when (id) {
        "A", "B", "X", "Y", "Z" -> "BUTTON_$id"
        "START" -> "BUTTON_START"
        "L", "R" -> "TRIGGER_$id"
        "DPAD" -> "BUTTON_UP"
        "STICK_MAIN", "STICK_C" -> id
        else -> null
    }

    data class GcEntry(
        val id: String,
        val positionKey: Int,
        val permilleX: Int,
        val permilleY: Int,
        val baseScale: Float,
        val drawable: String,
        val pressedDrawable: String,
        val toggleKey: String,
        val latchKey: String?,
        val output: OverlayOutput,
    )

     const val TOGGLE_SECTION: String = "AndroidOverlayButtons"
     const val SCALE_KEY: String = "ControlScale"
     const val OPACITY_KEY: String = "ControlOpacity"
     const val REL_CENTER_KEY: String = "JoystickRelCenter"
     const val SHOW_OVERLAY_KEY: String = "ShowInputOverlay"

    fun rasterDimensions(srcW: Int, srcH: Int, scale: Float, minScreen: Int): Pair<Int, Int> {
        if (srcW <= 0 || srcH <= 0 || minScreen <= 0 || !scale.isFinite() || scale <= 0f) return 0 to 0
        val factor = minScreen * scale / max(srcW, srcH)
        return (srcW * factor).toInt() to (srcH * factor).toInt()
    }

    fun defaultPos(permillle: Int, max: Int): Int = (permillle.toLong() * max / 1000).toInt()

    internal fun resourcePosition(name: String, axis: String, width: Int, height: Int, read: (String) -> Int?): Int? {
        val key = name + (if (height > width) "_PORTRAIT" else "") + "_$axis"
        return read(key)?.let { defaultPos(it, if (axis == "X") width else height) }
    }


    fun effectiveScale(base: Float, controlScale: Int): Float = base * (controlScale + 50) / 100f

    fun controlAlpha(opacity: Int): Int = opacity.coerceIn(0, 100) * 255 / 100


    fun dpadDirs(x: Int, y: Int, left: Int, top: Int, right: Int, bottom: Int, w: Int, h: Int): BooleanArray =
        booleanArrayOf(y < top + h / 3, y > bottom - h / 3, x < left + w / 3, x > right - w / 3)

    fun stickAxis(touch: Float, center: Float, half: Float): Float = if (half > 0f && touch.isFinite()) ((touch - center) / half).coerceIn(-1f, 1f) else 0f


    fun octagonGateMaxR(angleRad: Double): Double {
        val sector = PI / 4
        val delta = ((angleRad % sector) + sector) % sector - PI / 8
        return cos(PI / 8) / cos(delta)
    }


    fun clampStickGate(x: Double, y: Double, gateRadius: Double = 1.0): Pair<Float, Float> =
        clampStickGateDouble(x, y, gateRadius).let { it.first.toFloat() to it.second.toFloat() }

    private fun clampStickGateDouble(x: Double, y: Double, gateRadius: Double): Pair<Double, Double> {
        if (!x.isFinite() || !y.isFinite()) return 0.0 to 0.0
        val radius = hypot(x, y)
        val limit = octagonGateMaxR(atan2(y, x)) * gateRadius
        val factor = if (radius > limit) limit / radius else 1.0
        return x * factor to y * factor
    }

    internal fun stickVisualDisplacement(dx: Float, dy: Float, width: Int, height: Int, gateRadius: Double): Pair<Int, Int> {
        val halfW = width / 2; val halfH = height / 2
        if (halfW <= 0 || halfH <= 0) return 0 to 0
        val axes = clampStickGateDouble((dx / halfW).toDouble(), (dy / halfH).toDouble(), gateRadius)
        return (axes.first * halfW).toInt() to (axes.second * halfH).toInt()
    }


    fun knobCenter(center: Int, value: Float, half: Int): Int = center + (value * half).toInt()

    internal fun thumbRect(left: Int, top: Int, width: Int, height: Int, nominalSide: Int, dx: Float, dy: Float): RaPixelRect {
        val half = nominalSide / 2
        return RaPixelRect(left + width / 2 + dx.toInt() - half, top + height / 2 + dy.toInt() - half, half * 2, half * 2)
    }

    data class DolphinSettings(
        val controlScale: Int = 50,
        val controlOpacity: Int = 65,
        val relCenter: Boolean = true,
        val toggles: Map<String, Boolean> = emptyMap(),
        val latching: Map<String, Boolean> = emptyMap(),

        val showOverlay: Boolean = true,
    ) {
        fun toggle(key: String): Boolean = toggles[key] ?: true
        fun latch(key: String?): Boolean = key?.let { latching[it] } ?: false
    }


    fun parseIni(text: String): DolphinSettings {
        val values = effectiveIni(text) { key, value ->
            when (key) {
                "Android/$SCALE_KEY", "Android/$OPACITY_KEY" -> value.toIntOrNull() != null
                "Android/$REL_CENTER_KEY", "Android/$SHOW_OVERLAY_KEY" -> parseBool(value) != null
                else -> if (key.startsWith("$TOGGLE_SECTION/ButtonToggle") || key.startsWith("$TOGGLE_SECTION/ButtonLatching")) parseBool(value) != null else true
            }
        }
        fun number(key: String, default: Int) = values["Android/$key"]?.toIntOrNull() ?: default
        fun flag(key: String, default: Boolean) = values["Android/$key"]?.let(::parseBool) ?: default
        val toggles = values.filterKeys { it.startsWith("$TOGGLE_SECTION/ButtonToggle") }
            .mapNotNull { (key, value) -> parseBool(value)?.let { key.substringAfterLast('/') to it } }.toMap()
        val latches = values.filterKeys { it.startsWith("$TOGGLE_SECTION/ButtonLatching") }
            .mapNotNull { (key, value) -> parseBool(value)?.let { key.substringAfterLast('/') to it } }.toMap()
        return DolphinSettings(number(SCALE_KEY, 50), number(OPACITY_KEY, 65), flag(REL_CENTER_KEY, true), toggles, latches, flag(SHOW_OVERLAY_KEY, true))
    }

    internal fun parseBool(value: String): Boolean? = when (value.trim().lowercase()) { "true", "1", "yes" -> true; "false", "0", "no" -> false; else -> null }
}


object NetherOverlay {
    internal const val ANALOG_BASE_DRAWABLE = "ic_controller_analog_base"
    internal const val ANALOG_STICK_DRAWABLE = "ic_controller_analog_stick"
    fun pressedGrowth(density: Float): Int = if (density.isFinite() && density > 0f) (10f * density).toInt() else 0


    data class ViewRow(
        val viewId: String,
        val kind: Int,
        val wDp: Float,
        val hDp: Float,

        val anchor: String,
        val marginLdp: Float = 0f,
        val marginRdp: Float = 0f,
        val marginTdp: Float = 0f,
        val marginBdp: Float = 0f,
        val drawable: String = "",
        val configName: String = "",
        val bind: String = "",
        val defaultVisible: Boolean = true,
        val macroIndex: Int = -1,
        val hotkey: String = "",
    )

     const val KIND_BUTTON: Int = 0
     const val KIND_DPAD: Int = 1
     const val KIND_AXIS: Int = 2

    fun viewRectPx(row: ViewRow, screenW: Int, screenH: Int, density: Float): IntArray {
        val w = (row.wDp * density).roundToInt()
        val h = (row.hDp * density).roundToInt()
        val x = when { row.anchor.contains("right") -> screenW - w - (row.marginRdp * density).roundToInt(); row.anchor.contains("center") -> (screenW - w) / 2; else -> (row.marginLdp * density).roundToInt() }
        val y = when { row.anchor.contains("bottom") -> screenH - h - (row.marginBdp * density).roundToInt(); row.anchor.contains("middle") -> (screenH - h) / 2; else -> (row.marginTdp * density).roundToInt() }
        return intArrayOf(x, y, w, h)
    }


    fun transformedRect(x: Int, y: Int, w: Int, h: Int, tx: Float, ty: Float, scale: Float): IntArray {
        require(scale.isFinite() && scale > 0 && tx.isFinite() && ty.isFinite())
        val cx = x + w / 2f
        val cy = y + h / 2f
        val left = (cx - w / 2f * scale + tx).roundToInt()
        val top = (cy - h / 2f * scale + ty).roundToInt()
        return intArrayOf(left, top, (cx + w / 2f * scale + tx).roundToInt() - left, (cy + h / 2f * scale + ty).roundToInt() - top)
    }


    fun scaleKey(view: String, name: String, orient: String): String = "TouchscreenController/$view/${name}${orient}Scale"
    fun xKey(view: String, name: String, orient: String): String = "TouchscreenController/$view/${name}${orient}XTranslation"
    fun yKey(view: String, name: String, orient: String): String = "TouchscreenController/$view/${name}${orient}YTranslation"
    fun visibleKey(view: String, name: String, orient: String): String = "TouchscreenController/$view/${name}${orient}Visible"
    fun toggleKey(view: String, name: String): String = "TouchscreenController/$view/${name}Toggle"

    data class NetherSettings(
        val view: String = "analog_stick",
        val opacity: Int = 75,
        val gliding: Boolean = false,
        val floats: Map<String, Float> = emptyMap(),
        val bools: Map<String, Boolean> = emptyMap(),
    ) {
        fun float(key: String, default: Float): Float = floats[key] ?: default
        fun bool(key: String, default: Boolean): Boolean = bools[key] ?: default
    }


    fun parseProfile(text: String): NetherSettings {
        val values = effectiveIni(text) { key, value -> when {
            key == "TouchscreenController/Opacity" -> value.toIntOrNull() != null
            key.endsWith("Scale") || key.endsWith("Translation") -> value.toFloatOrNull()?.isFinite() == true
            key.endsWith("Visible") || key.endsWith("Toggle") || key.endsWith("TouchGliding") -> DolphinOverlay.parseBool(value) != null
            else -> true
        } }
        return NetherSettings(values["TouchscreenController/View"] ?: "none",
            values["TouchscreenController/Opacity"]?.toIntOrNull() ?: 0,
            values["TouchscreenController/TouchGliding"]?.let(DolphinOverlay::parseBool) ?: false,
            values.mapNotNull { (k, v) -> v.toFloatOrNull()?.takeIf(Float::isFinite)?.let { k to it } }.toMap(),
            values.mapNotNull { (k, v) -> DolphinOverlay.parseBool(v)?.let { k to it } }.toMap())
    }


    fun dpadDirs(relX: Int, relY: Int, w: Int, h: Int): BooleanArray = if (w <= 0 || h <= 0) BooleanArray(4)
        else booleanArrayOf(relY < h / 3, relY >= 2 * (h / 3), relX < w / 3, relX >= 2 * (w / 3))


    data class AxisSample(val nx: Float, val ny: Float, val knobDx: Int, val knobDy: Int)

    fun axisSample(relX: Float, relY: Float, w: Int, h: Int, padPx: Int): AxisSample {
        val radius = w / 2 - padPx
        val x = relX - w / 2
        val y = relY - h / 2
        val distance = hypot(x.toDouble(), y.toDouble())
        if (radius <= 0 || distance == 0.0 || !distance.isFinite()) return AxisSample(0f, 0f, 0, 0)
        val magnitude = min(radius.toFloat(), max(abs(x), abs(y)))
        val dx = (magnitude * x / distance).toFloat()
        val dy = (magnitude * y / distance).toFloat()
        return AxisSample((dx * 1.1f / radius).coerceIn(-1f, 1f), (dy * 1.1f / radius).coerceIn(-1f, 1f), dx.toInt(), dy.toInt())
    }


    fun knobHalf(viewPx: Int): Int = (0.6f * viewPx).toInt()


    fun knobOrigin(centerPx: Float, knobPx: Float): Int = (centerPx - knobPx / 2f).toInt()


    fun outputFor(bind: String, macroIndex: Int = -1, hotkey: String = ""): OverlayOutput = if (macroIndex >= 0 || hotkey.isNotEmpty()) OverlayOutput.Ignored else ps2Output(bind)
}


object ArmsOverlay {
    data class ButtonCfg(
        val id: String,
        val xFrac: Float,
        val yFrac: Float,
        val sizeDp: Float,
        val enabled: Boolean = true,
        val tapToHold: Boolean = false,
        val turbo: Int = 0,
    )

     const val DEFAULT_OPACITY: Float = 0.55f
     const val DEFAULT_MULTI_RADIUS: Float = 0.62f
     const val DEFAULT_DPAD_GAP: Float = 0f

     const val MACRO_MIN_STATE_MS: Long = 24L

     const val PRESSED_SCALE_SPRITE: Float = 0.92f
     const val PRESSED_SCALE_PLAIN: Float = 0.85f

    fun hasPressedSprite(id: String): Boolean = id.uppercase() !in setOf("CIRCLE", "SQUARE")


    fun drawableFor(id: String, pressed: Boolean): String = "pad_" + id.lowercase() + if (pressed && hasPressedSprite(id)) "_pressed" else ""


    fun parseLayout(json: String): List<ButtonCfg> = runCatching {
        Json.parseToJsonElement(json).jsonObject["layout"]?.jsonObject?.get("buttons")?.jsonArray
            ?.mapNotNull { parseButton(it.toString()) }.orEmpty()
    }.getOrDefault(emptyList())

    private fun parseButton(obj: String): ButtonCfg? = runCatching {
        val value = Json.parseToJsonElement(obj).jsonObject
        val id = value["id"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: return null
        val x = value["x"]?.jsonPrimitive?.floatOrNull?.takeIf(Float::isFinite) ?: return null
        val y = value["y"]?.jsonPrimitive?.floatOrNull?.takeIf(Float::isFinite) ?: return null
        val side = value["size"]?.jsonPrimitive?.floatOrNull?.takeIf { it.isFinite() && it > 0f } ?: return null
        ButtonCfg(id, x, y, side, value["on"]?.jsonPrimitive?.booleanOrNull ?: true,
            value["hold"]?.jsonPrimitive?.booleanOrNull ?: false, value["turbo"]?.jsonPrimitive?.intOrNull ?: 0)
    }.getOrNull()


    fun widgetRect(cfg: ButtonCfg, screenW: Int, screenH: Int, density: Float): IntArray {
        val physicalSide = cfg.sizeDp * density
        val side = physicalSide.roundToInt()
        return intArrayOf((screenW * cfg.xFrac - physicalSide / 2f).roundToInt(), (screenH * cfg.yFrac - physicalSide / 2f).roundToInt(), side, side)
    }


    data class DpadArms(val up: IntArray, val down: IntArray, val left: IntArray, val right: IntArray)

    fun dpadArms(x: Int, y: Int, s: Int, density: Float, gap: Float): DpadArms {
        val fill = 0.5f - gap.coerceIn(0f, 0.35f)
        val vh = (s * fill).roundToInt()
        val vw = (vh * 45f / 52f).roundToInt()
        val hw = (s * fill).roundToInt()
        val hh = (hw * 44f / 53f).roundToInt()
        val centerX = ((s - vw) / 2f).roundToInt()
        val centerY = ((s - hh) / 2f).roundToInt()
        return DpadArms(intArrayOf(x + centerX, y - (4 * density).roundToInt(), vw, vh),
            intArrayOf(x + centerX, y + s - vh, vw, vh),
            intArrayOf(x, y + centerY, hw, hh), intArrayOf(x + s - hw, y + centerY, hw, hh))
    }


    fun dpadDirs(dx: Float, dy: Float, half: Float, gap: Float): BooleanArray {
        if (!dx.isFinite() || !dy.isFinite() || hypot(dx, dy) <= half * (0.08f + gap.coerceIn(0f, 0.35f))) return BooleanArray(4)
        val horizontal = abs(dx) >= 0.55f * abs(dy)
        val vertical = abs(dy) >= 0.55f * abs(dx)
        return booleanArrayOf(vertical && dy < 0, vertical && dy > 0, horizontal && dx < 0, horizontal && dx > 0)
    }


    data class StickSample(val nx: Float, val ny: Float, val thumbDx: Float, val thumbDy: Float)

    fun stickSample(dx: Float, dy: Float, radiusPx: Float): StickSample {
        val travel = radiusPx * 0.66f
        val length = hypot(dx, dy)
        if (travel <= 0 || !length.isFinite()) return StickSample(0f, 0f, 0f, 0f)
        val factor = if (length > travel) travel / length else 1f
        val x = dx * factor
        val y = dy * factor
        return StickSample(x / travel, y / travel, x, y)
    }

    fun thumbSizePx(widgetPx: Int): Int = (widgetPx * 0.62f).toInt()


    fun turboPeriodMs(frames: Int): Long = if (frames <= 0) 0 else max(24L, frames.toLong() * 1000 / 60)


     const val ANALOG_EXTRA_DEFAULT_KEYCODE: Int = 96


     const val PAUSE_DISC: Float = 0.66f
     const val PAUSE_BAR_W: Float = 0.095f
     const val PAUSE_BAR_H: Float = 0.34f


    fun outputFor(id: String): OverlayOutput = ps2Output(id)
}


object FlycastOverlay {

    val TEX_CELLS: List<IntArray> = listOf(intArrayOf(0, 0, 64, 64), intArrayOf(64, 0, 64, 64), intArrayOf(128, 0, 64, 64), intArrayOf(192, 0, 64, 64), intArrayOf(320, 0, 64, 64), intArrayOf(256, 0, 64, 64), intArrayOf(384, 0, 64, 64), intArrayOf(448, 0, 64, 64), intArrayOf(0, 64, 64, 64), intArrayOf(64, 64, 90, 64), intArrayOf(154, 64, 90, 64), intArrayOf(244, 64, 128, 128), intArrayOf(372, 64, 64, 64), intArrayOf(436, 64, 64, 64), intArrayOf(0, 128, 64, 64), intArrayOf(64, 128, 64, 64), intArrayOf(0, 192, 64, 64), intArrayOf(64, 192, 64, 64), intArrayOf(128, 128, 64, 64), intArrayOf(384, 128, 64, 64), intArrayOf(448, 128, 64, 64))
     const val ATLAS_CONSOLE_W: Int = 512
     const val ATLAS_CONSOLE_H: Int = 256
     const val ATLAS_ARCADE_W: Int = 512
     const val ATLAS_ARCADE_H: Int = 512


    val LAYOUT_DEFAULTS: List<Array<Any>> = listOf(arrayOf<Any>("dpad", 32.0f, -24.0f, 192.0f, 192.0f), arrayOf<Any>("buttons", -24.0f, -24.0f, 192.0f, 192.0f), arrayOf<Any>("start", 0.0f, -24.0f, 64.0f, 64.0f), arrayOf<Any>("LT", -134.0f, -240.0f, 90.0f, 64.0f), arrayOf<Any>("RT", -32.0f, -240.0f, 90.0f, 64.0f), arrayOf<Any>("analog", 40.0f, -320.0f, 128.0f, 128.0f), arrayOf<Any>("fforward", -24.0f, 24.0f, 64.0f, 64.0f), arrayOf<Any>("btn4", -24.0f, -216.0f, 64.0f, 64.0f), arrayOf<Any>("btn5", -152.0f, -216.0f, 64.0f, 64.0f), arrayOf<Any>("service", -24.0f, 96.0f, 64.0f, 64.0f), arrayOf<Any>("inscard", 40.0f, -250.0f, 64.0f, 64.0f))


    fun uiScale(screenH: Int, dpi: Int): Float = uiScale(screenH, dpi.toFloat())

    fun uiScale(screenH: Int, dpi: Float): Float = if (screenH > 0 && dpi.isFinite() && dpi > 0f) 1.1f * 750f / screenH * dpi / 320f else 0f

    fun dcWidth(screenW: Int, screenH: Int): Float = if (screenH > 0) 480f * screenW / screenH else 0f

    data class PlacedControl(
        val id: Int,
        val x: Float,
        val y: Float,
        val w: Float,
        val h: Float,
    )


    fun layoutControls(
        screenW: Int,
        screenH: Int,
        dpi: Int,
        cfg: Map<String, Triple<Float, Float, Float>> = emptyMap(),
    ): List<PlacedControl> = layoutControls(screenW, screenH, dpi.toFloat(), cfg)

    fun layoutControls(
        screenW: Int,
        screenH: Int,
        dpi: Float,
        cfg: Map<String, Triple<Float, Float, Float>> = emptyMap(),
     ): List<PlacedControl> {
        if (screenW <= 0 || screenH <= 0 || !dpi.isFinite() || dpi <= 0f) return emptyList()
        val u = uiScale(screenH, dpi)
        val logicalWidth = dcWidth(screenW, screenH)
        val extra = (640f - logicalWidth) / 2f
        val out = mutableListOf<PlacedControl>()
        fun fraction(offset: Float, size: Float, total: Float): Float = when {
            offset == 0f -> (total - size * u) / 2f / total
            offset > 0f -> offset * u / total
            else -> (total + offset * u - size * u) / total
        }
        LAYOUT_DEFAULTS.forEachIndexed { groupIndex, group ->
            val name = group[0] as String
            val width = group[3] as Float
            val height = group[4] as Float
            val configured = cfg[name]
            val xFraction = configured?.first?.takeIf(Float::isFinite) ?: fraction(group[1] as Float, width, logicalWidth)
            val yFraction = configured?.second?.takeIf(Float::isFinite) ?: fraction(group[2] as Float, height, 480f)
            val scale = u * (configured?.third?.takeIf { it.isFinite() && it > 0f } ?: 1f)
            val x = xFraction * logicalWidth + extra
            val y = yFraction * 480f
            fun place(id: Int, dx: Float = 0f, dy: Float = 0f, w: Float = width, h: Float = height) {
                out += PlacedControl(id, x + dx * scale, y + dy * scale, w * scale, h * scale)
            }
            when (groupIndex) {
                0 -> { place(0, 0f, 64f, 64f, 64f); place(1, 64f, 0f, 64f, 64f); place(2, 128f, 64f, 64f, 64f); place(3, 64f, 128f, 64f, 64f) }
                1 -> { place(4, 64f, 0f, 64f, 64f); place(5, 0f, 64f, 64f, 64f); place(6, 128f, 64f, 64f, 64f); place(7, 64f, 128f, 64f, 64f) }
                2 -> place(8)
                3 -> place(9)
                4 -> place(10)
                5 -> { place(11); place(12, 32f, 32f, 64f, 64f) }
                else -> place(groupIndex + 7)
            }
        }
        return out
    }


    fun dcToPx(dc: Float, screenH: Int, screenW: Int, isX: Boolean): Float = dc * screenH / 480f + if (isX) (screenW - 640f * screenH / 480f) / 2f else 0f


    fun pxToDcX(x: Float, screenW: Int, screenH: Int): Float = (x - (screenW - 640f * screenH / 480f) / 2f) * 480f / screenH

    fun pxToDcY(y: Float, screenH: Int): Float = y * 480f / screenH


    fun tintGray(pressed: Boolean): Float = if (pressed) 0.25f else 0.5f

    fun tintGray(id: Int, pressed: Boolean, triggerFraction: Float = if (pressed) 1f else 0f, visibility: Float = 1f): Float {
        val gray = when (id) {
            11 -> 0.5f
            12, 13 -> 0.25f
            9, 10 -> 0.5f - 0.25f * (triggerFraction.takeIf(Float::isFinite) ?: 0f).coerceIn(0f, 1f)
            else -> tintGray(pressed)
        }
        return gray * (visibility.takeIf(Float::isFinite) ?: 0f).coerceIn(0f, 1f)
    }


    fun overlayAlpha(transparency: Int): Float = (100 - transparency.coerceIn(0, 100)) / 100f

    fun overlayAlpha(transparency: Int, visibility: Float): Float = overlayAlpha(transparency) * (visibility.takeIf(Float::isFinite) ?: 0f).coerceIn(0f, 1f)

    fun stickAxis(dc: Float, bias: Float, stickW: Float): Float = if (stickW > 0 && dc.isFinite()) ((dc - bias) / stickW).coerceIn(-1f, 1f) else 0f


    data class FlycastSettings(
        val elements: Map<String, Triple<Float, Float, Float>> = emptyMap(),
        val transparency: Int = 37,
    )

    fun parseCfg(text: String): FlycastSettings {
        val values = effectiveIni(text)
        val groups = LAYOUT_DEFAULTS.mapNotNull { group ->
            val name = group[0] as String
            fun number(suffix: String) = values["vgamepad/${name}_$suffix"]?.toFloatOrNull()?.takeIf(Float::isFinite) ?: Float.NaN
            val triple = Triple(number("x"), number("y"), number("scale"))
            if (triple.first.isNaN() && triple.second.isNaN() && triple.third.isNaN()) null else name to triple
        }.toMap()
        val alpha = values.entries.lastOrNull { it.key.substringAfterLast('/') == "VirtualGamepadTransparency" }?.value?.toIntOrNull() ?: 37
        return FlycastSettings(groups, alpha)
    }


    fun outputFor(id: Int): OverlayOutput = when (id) {
        0 -> OverlayOutput.Hat(false, false, true, false); 1 -> OverlayOutput.Hat(true, false, false, false)
        2 -> OverlayOutput.Hat(false, false, false, true); 3 -> OverlayOutput.Hat(false, true, false, false)
        4 -> OverlayOutput.Buttons(PadState.Y); 5 -> OverlayOutput.Buttons(PadState.X)
        6 -> OverlayOutput.Buttons(PadState.B); 7 -> OverlayOutput.Buttons(PadState.A)
        8 -> OverlayOutput.Buttons(PadState.START); 9 -> OverlayOutput.Trigger(true); 10 -> OverlayOutput.Trigger(false)
        11 -> OverlayOutput.Stick(false); 14 -> OverlayOutput.Buttons(PadState.R1); 15 -> OverlayOutput.Buttons(PadState.L1); else -> OverlayOutput.Ignored
    }


    fun diagonalOutput(id: Int): OverlayOutput = when (id) {
        18 -> OverlayOutput.Hat(true, false, true, false); 19 -> OverlayOutput.Hat(true, false, false, true)
        20 -> OverlayOutput.Hat(false, true, true, false); 21 -> OverlayOutput.Hat(false, true, false, true)
        else -> OverlayOutput.Ignored
    }
}


object MelondsOverlay {
     const val DPAD_DRAWABLE: String = "keypad"
     const val BUTTONS_DRAWABLE: String = "buttons"
     const val L_DRAWABLE: String = "button_l"
     const val R_DRAWABLE: String = "button_r"
     const val START_DRAWABLE: String = "button_start"
     const val SELECT_DRAWABLE: String = "button_select"


     const val INPUT_ALPHA: Int = 128

     const val LARGE_DP: Float = 140f
     const val LR_DP: Float = 50f
     const val SMALL_DP: Float = 40f
     const val SPACING_DP: Float = 4f

     const val CONSOLE_ASPECT: Float = 256f / 192f

     const val GRID: Float = 512f


    fun dpToPx(dp: Float, dpi: Int): Int = (dp * dpi / 160f).toInt()


    val DPAD_ARMS: List<IntArray> = listOf(intArrayOf(172, 0, 164, 256), intArrayOf(172, 256, 164, 256), intArrayOf(0, 176, 256, 163), intArrayOf(256, 176, 256, 163))


    val BUTTON_QUADS: List<IntArray> = listOf(intArrayOf(128, 0, 256, 256), intArrayOf(0, 128, 256, 256), intArrayOf(256, 128, 256, 256), intArrayOf(128, 256, 256, 256))
    val BUTTON_IDS: List<String> = listOf("X", "Y", "A", "B")


    data class MelonRects(
        val dpad: IntArray,
        val buttons: IntArray,
        val l: IntArray,
        val r: IntArray,
        val select: IntArray,
        val start: IntArray,
    )


    fun defaultLayout(width: Int, height: Int, dpi: Int): MelonRects {
        val large = dpToPx(LARGE_DP, dpi)
        val shoulder = dpToPx(LR_DP, dpi)
        val small = dpToPx(SMALL_DP, dpi)
        val spacing = dpToPx(SPACING_DP, dpi)
        val portrait = height > width
        val screenHeight = min((width / CONSOLE_ASPECT).toInt(), height / 2)
        val sy = if (!portrait) 0 else if (screenHeight * 2 + shoulder + large > height) screenHeight else screenHeight * 2
        val selectX = if (portrait) width / 2 - small - spacing / 2 else (width - spacing) / 2 - small
        val startX = if (portrait) width / 2 + spacing / 2 else (width + spacing) / 2
        return MelonRects(intArrayOf(0, height - large, large, large), intArrayOf(width - large, height - large, large, large),
            intArrayOf(0, sy, shoulder, shoulder), intArrayOf(width - shoulder, sy, shoulder, shoulder),
            intArrayOf(selectX, height - small, small, small), intArrayOf(startX, height - small, small, small))
    }


    fun fracBox(box: IntArray, crop: IntArray): IntArray {
        val left = (crop[0] * box[2] / GRID).toInt()
        val top = (crop[1] * box[3] / GRID).toInt()
        return intArrayOf(box[0] + left, box[1] + top, ((crop[0] + crop[2]) * box[2] / GRID).toInt() - left, ((crop[1] + crop[3]) * box[3] / GRID).toInt() - top)
    }


    fun dpadDirs(px: Float, py: Float, w: Float, h: Float): BooleanArray {
        if (w <= 0 || h <= 0 || !px.isFinite() || !py.isFinite()) return BooleanArray(4)
        val centers = listOf(w / 2f to -36f / 512f * h, w / 2f to h + 36f / 512f * h,
            -36f / 512f * w to h / 2f, w + 36f / 512f * w to h / 2f)
        val radius = w / 2f
        return centers.map { (x, y) -> val dx = px - x.toInt(); val dy = py - y.toInt(); dx * dx + dy * dy <= radius * radius }.toBooleanArray()
    }


    fun outputFor(id: String): OverlayOutput = when (id.uppercase()) {
        "A" -> OverlayOutput.Buttons(PadState.A); "B" -> OverlayOutput.Buttons(PadState.B)
        "X" -> OverlayOutput.Buttons(PadState.X); "Y" -> OverlayOutput.Buttons(PadState.Y)
        "L" -> OverlayOutput.Buttons(PadState.L1); "R" -> OverlayOutput.Buttons(PadState.R1)
        "START" -> OverlayOutput.Buttons(PadState.START); "SELECT" -> OverlayOutput.Buttons(PadState.SELECT)
        else -> OverlayOutput.Ignored
    }
}


object PpssppOverlay {







    fun dpadDirs(px: Float, py: Float, w: Float, h: Float, disableDiagonals: Boolean = false): BooleanArray {
        val dx = (px - w / 2f) / w
        val dy = (py - h / 2f) / h
        val radius = hypot(dx, dy)
        if (!radius.isFinite() || radius < 0.05f) return BooleanArray(4)
        val step = if (disableDiagonals || radius < 0.2f) PI / 2 else PI / 4
        val angle = kotlin.math.round(atan2(dy.toDouble(), dx.toDouble()) / step) * step
        val x = cos(angle); val y = kotlin.math.sin(angle)
        return booleanArrayOf(y < -0.1, y > 0.1, x < -0.1, x > 0.1)
    }

    fun stickSample(dx: Float, dy: Float, travel: Float): ArmsOverlay.StickSample {
        if (travel <= 0f || !travel.isFinite()) return ArmsOverlay.StickSample(0f, 0f, 0f, 0f)
        val x = (dx / travel).coerceIn(-1f, 1f); val y = (dy / travel).coerceIn(-1f, 1f)
        return ArmsOverlay.StickSample(x, y, x * travel, y * travel)
    }

    fun outputFor(id: String): OverlayOutput = when (id.uppercase()) {
        "CIRCLE" -> OverlayOutput.Buttons(PadState.A); "CROSS" -> OverlayOutput.Buttons(PadState.B)
        "TRIANGLE" -> OverlayOutput.Buttons(PadState.X); "SQUARE" -> OverlayOutput.Buttons(PadState.Y)
        "L", "L1" -> OverlayOutput.Buttons(PadState.L1); "R", "R1" -> OverlayOutput.Buttons(PadState.R1)
        "START" -> OverlayOutput.Buttons(PadState.START); "SELECT" -> OverlayOutput.Buttons(PadState.SELECT)
        else -> OverlayOutput.Ignored
    }
}


internal fun effectiveIni(text: String, valid: (String, String) -> Boolean = { _, _ -> true }): Map<String, String> {
    val out = linkedMapOf<String, String>()
    var section = ""
    text.lineSequence().forEach { line ->
        val raw = line.trim()
        if (raw.startsWith('[') && raw.endsWith(']')) { section = raw.substring(1, raw.length - 1); return@forEach }
        if (raw.isEmpty() || raw.startsWith('#') || raw.startsWith(';') || '=' !in raw) return@forEach
        val key = "$section/${raw.substringBefore('=').trim()}"
        val value = raw.substringAfter('=').trim()
        if (value in setOf("NaN", "Infinity", "-Infinity") || !valid(key, value)) return@forEach
        val previous = out[key]
        if (previous?.toFloatOrNull() != null && value.toFloatOrNull()?.isFinite() != true) return@forEach
        if (previous?.lowercase() in setOf("true", "false", "yes", "no") && DolphinOverlay.parseBool(value) == null) return@forEach
        out[key] = value
    }
    return out
}

internal fun ps2Output(id: String): OverlayOutput = when (id.uppercase().removePrefix("PAD1/")) {
    "CROSS" -> OverlayOutput.Buttons(PadState.A); "CIRCLE" -> OverlayOutput.Buttons(PadState.B)
    "SQUARE" -> OverlayOutput.Buttons(PadState.X); "TRIANGLE" -> OverlayOutput.Buttons(PadState.Y)
    "L1" -> OverlayOutput.Buttons(PadState.L1); "R1" -> OverlayOutput.Buttons(PadState.R1)
    "L2" -> OverlayOutput.Trigger(true); "R2" -> OverlayOutput.Trigger(false)
    "L3" -> OverlayOutput.Buttons(PadState.L3); "R3" -> OverlayOutput.Buttons(PadState.R3)
    "START" -> OverlayOutput.Buttons(PadState.START); "SELECT" -> OverlayOutput.Buttons(PadState.SELECT)
    "L_STICK", "LEFTAXIS" -> OverlayOutput.Stick(false); "R_STICK", "RIGHTAXIS" -> OverlayOutput.Stick(true)
    "PAUSE", "OPENPAUSEMENU" -> OverlayOutput.MenuToggle
    else -> OverlayOutput.Ignored
}
