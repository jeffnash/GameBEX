package com.gamebex.client.stream.shell

import com.gamebex.client.stream.StreamDebug

/**
 * Shell-side flag reads. The shell-uid process cannot read app prefs,
 * so host flags arrive in [com.gamebex.client.stream.StreamConfig]; the adb
 * prop wins on debug builds only (lab override).
 */
internal fun shellFlag(
    propName: String,
    configValue: Boolean,
    debug: Boolean = com.gamebex.client.BuildConfig.DEBUG,
    prop: (String) -> String? = StreamDebug::prop,
): Boolean {
    if (!debug) return configValue
    return when (prop(propName)) {
        "1" -> true
        "0" -> false
        else -> configValue
    }
}

internal fun shellInt(
    propName: String,
    configValue: Int,
    range: IntRange,
    debug: Boolean = com.gamebex.client.BuildConfig.DEBUG,
    prop: (String) -> String? = StreamDebug::prop,
): Int {
    if (!debug) return configValue
    return prop(propName)?.toIntOrNull()?.takeIf { it in range } ?: configValue
}

internal fun shellString(
    propName: String,
    configValue: String,
    valid: Set<String>,
    debug: Boolean = com.gamebex.client.BuildConfig.DEBUG,
    prop: (String) -> String? = StreamDebug::prop,
): String {
    if (!debug) return configValue
    return prop(propName)?.takeIf { it in valid } ?: configValue
}
