package com.gamebex.client.stream.guest

import kotlin.math.pow

internal data class PpssppControlRect(
    val id: String, val resource: String, val x: Float, val y: Float, val w: Float, val h: Float,
    val kind: Int = 0, val travel: Float = 0f, val knobSize: Float = 0f,
)

internal fun ppssppLayout(
    settings: Map<String, String>, metadata: Map<String, PpssppAtlasImage>,
    width: Int, height: Int, dpi: Int, uiScale: Float,
): List<PpssppControlRect>? {
    if (width <= 0 || height <= 0 || dpi <= 0 || !uiScale.isFinite()) return null
    fun number(key: String) = settings[key]?.toFloatOrNull()?.takeIf { it.isFinite() }
    fun visible(key: String) = settings[key]?.let(DolphinOverlay::parseBool) == true
    if (!visible("ShowTouchControls")) return if (settings["ShowTouchControls"] == null) null else emptyList()
    val factor = dpi / 240f * 2f.pow(uiScale / 8f)
    val lined = (settings["TouchButtonStyle"]?.toIntOrNull() ?: return null) != 0
    fun resource(name: String) = "I_$name" + if (lined) "_LINE" else ""
    val out = mutableListOf<PpssppControlRect>()
    fun add(id: String, image: String, cx: Float, cy: Float, scale: Float, kind: Int = 0, side: Float? = null, head: Float = 1f): Boolean {
        val intrinsic = metadata[image] ?: return false
        if (scale <= 0f || head <= 0f) return false
        val w = side ?: intrinsic.width * scale * factor
        val h = side ?: intrinsic.height * scale * factor
        val knob = if (kind == 2) (metadata[resource("STICK")] ?: return false).width * scale * head * factor else 0f
        out += PpssppControlRect(id, image, cx - w / 2f, cy - h / 2f, w, h, kind,
            if (kind == 2) 50f * scale * factor else 0f, knob)
        return true
    }
    val actions = listOf("triangle" to (0f to -1f), "circle" to (1f to 0f), "cross" to (0f to 1f), "square" to (-1f to 0f))
    if (actions.any { visible("ShowTouch" + it.first.replaceFirstChar(Char::uppercase)) }) {
        val cx = (number("ActionButtonCenterX") ?: return null) * width
        val cy = (number("ActionButtonCenterY") ?: return null) * height
        val scale = number("ActionButtonScale") ?: return null
        val spacing = (number("ActionButtonSpacing2") ?: return null) * 60f * factor
        for ((id, offset) in actions) if (visible("ShowTouch" + id.replaceFirstChar(Char::uppercase)) &&
            !add(id, resource("ROUND"), cx + offset.first * spacing, cy + offset.second * spacing, scale)) return null
    }
    for ((id, prefix, toggle) in listOf(Triple("L", "LKey", "ShowTouchLTrigger"), Triple("R", "RKey", "ShowTouchRTrigger"),
        Triple("start", "StartKey", "ShowTouchStart"), Triple("select", "SelectKey", "ShowTouchSelect"), Triple("fast", "UnthrottleKey", "ShowTouchUnthrottle"),
        Triple("analog", "AnalogStick", "ShowAnalogStick"), Triple("rightAnalog", "RightAnalogStick", "ShowRightAnalogStick"), Triple("dpad", "DPad", "ShowTouchDpad"))) {
        if (!visible(toggle)) continue
        val cx = (number(prefix + "X") ?: return null) * width
        val cy = (number(prefix + "Y") ?: return null) * height
        val scale = number(prefix + "Scale") ?: return null
        val kind = if (id == "dpad") 1 else if (id in setOf("analog", "rightAnalog")) 2 else 0
        val image = resource(when (kind) { 1 -> "DIR"; 2 -> "STICK_BG"; else -> if (id in setOf("L", "R")) "SHOULDER" else "RECT" })
        val side = if (kind == 1) (100f * (number("DPadSpacing") ?: return null) + (metadata[image] ?: return null).width * scale) * factor else null
        val head = if (kind == 2) number(if (id == "analog") "LeftStickHeadScale" else "RightStickHeadScale") ?: return null else 1f
        if (!add(id, image, cx, cy, scale, kind, side, head)) return null
    }
    if (visible("ShowTouchPause") && !add("pause", resource("ROUND"), width / 2f, 20f * factor, 1f)) return null
    return out
}
