package com.gamebex.client.stream.guest

import android.content.Context

enum class TouchStickMode { FIXED, FLOATING }
enum class TouchHaptics { OFF, LIGHT }
enum class TouchContentAspect { FOUR_THREE, SIXTEEN_NINE }

/** Guest-side touch preferences, one set per touch layout, applied after neutralizing touches. */
data class TouchPrefs(
    val sizePercent: Int = 100,
    val opacityPercent: Int = 55,
    val stickMode: TouchStickMode = TouchStickMode.FIXED,
    val deadZonePercent: Int = 10,
    val invertRightStickY: Boolean = false,
    val haptics: TouchHaptics = TouchHaptics.OFF,
    val swapSides: Boolean = false,
    val n64CStick: Boolean = false,
    val analogDragTriggers: Boolean = false,
    val contentAspect: TouchContentAspect = TouchContentAspect.FOUR_THREE,
) {
    fun validated(): TouchPrefs = copy(
        sizePercent = sizePercent.coerceIn(SIZE_MIN, SIZE_MAX),
        opacityPercent = opacityPercent.coerceIn(OPACITY_MIN, OPACITY_MAX),
        deadZonePercent = deadZonePercent.coerceIn(DEAD_ZONE_MIN, DEAD_ZONE_MAX),
    )

    companion object {
        const val SIZE_MIN: Int = 85
        const val SIZE_MAX: Int = 125
        const val SIZE_STEP: Int = 5
        const val OPACITY_MIN: Int = 20
        const val OPACITY_MAX: Int = 80
        const val OPACITY_STEP: Int = 5
        const val DEAD_ZONE_MIN: Int = 0
        const val DEAD_ZONE_MAX: Int = 20
    }
}

/** Load before gestures start; writes use apply() so they stay off the input path. */
class StreamTouchPrefs(context: Context) {
    private val store = context.getSharedPreferences(NAMESPACE, Context.MODE_PRIVATE)

    fun load(layoutId: TouchLayoutId): TouchPrefs {
        val p = layoutId.name + "_"
        return TouchPrefs(
            sizePercent = store.getInt(p + "size", 100),
            opacityPercent = store.getInt(p + "opacity", 55),
            stickMode = TouchStickMode.valueOf(store.getString(p + "stick", TouchStickMode.FIXED.name)!!),
            deadZonePercent = store.getInt(p + "dead_zone", 10),
            invertRightStickY = store.getBoolean(p + "invert_ry", false),
            haptics = TouchHaptics.valueOf(store.getString(p + "haptics", TouchHaptics.OFF.name)!!),
            swapSides = store.getBoolean(p + "swap", false),
            n64CStick = store.getBoolean(p + "cstick", false),
            analogDragTriggers = store.getBoolean(p + "analog_trig", false),
            contentAspect = TouchContentAspect.valueOf(store.getString(p + "aspect", TouchContentAspect.FOUR_THREE.name)!!),
        ).validated()
    }

    fun save(layoutId: TouchLayoutId, prefs: TouchPrefs) {
        val v = prefs.validated()
        val p = layoutId.name + "_"
        store.edit()
            .putInt(p + "size", v.sizePercent)
            .putInt(p + "opacity", v.opacityPercent)
            .putString(p + "stick", v.stickMode.name)
            .putInt(p + "dead_zone", v.deadZonePercent)
            .putBoolean(p + "invert_ry", v.invertRightStickY)
            .putString(p + "haptics", v.haptics.name)
            .putBoolean(p + "swap", v.swapSides)
            .putBoolean(p + "cstick", v.n64CStick)
            .putBoolean(p + "analog_trig", v.analogDragTriggers)
            .putString(p + "aspect", v.contentAspect.name)
            .apply()
    }

    fun reset(layoutId: TouchLayoutId) {
        val p = layoutId.name + "_"
        store.edit()
            .remove(p + "size").remove(p + "opacity").remove(p + "stick").remove(p + "dead_zone")
            .remove(p + "invert_ry").remove(p + "haptics").remove(p + "swap").remove(p + "cstick")
            .remove(p + "analog_trig").remove(p + "aspect")
            .apply()
    }

    companion object {
        const val NAMESPACE: String = "stream_touch"
    }
}
