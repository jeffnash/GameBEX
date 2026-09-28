package com.gamebex.client.together.platform

import android.content.Context
import android.os.Build
import android.provider.Settings
import java.security.SecureRandom

/** Stable identity and display name used by Play together discovery. */
class TogetherPrefs(ctx: Context) {
    private val prefs = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    val deviceId: String
        get() {
            val existing = prefs.getString(KEY_DEVICE_ID, null)
            if (!existing.isNullOrEmpty()) return existing
            val bytes = ByteArray(8)
            SecureRandom().nextBytes(bytes)
            val generated = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
            prefs.edit().putString(KEY_DEVICE_ID, generated).apply()
            return generated
        }

    /** The console name the user chose, or null to use the device name. */
    val customName: String?
        get() = sanitizeCustomName(prefs.getString(KEY_CUSTOM_NAME, null))

    /** Saves a custom name, sanitized; blank clears it (back to the device name). */
    fun setCustomName(raw: String?) {
        val name = sanitizeCustomName(raw)
        prefs.edit().apply { if (name == null) remove(KEY_CUSTOM_NAME) else putString(KEY_CUSTOM_NAME, name) }.apply()
    }

    fun playerName(lanAddress: String?): String {
        val custom = customName
        if (custom != null) return custom
        val raw = Settings.Global.getString(
            ctxResolver,
            Settings.Global.DEVICE_NAME,
        ).takeUnless { it.isNullOrBlank() } ?: Build.MODEL
        return resolvePlayerName(null, raw, lanAddress)
    }

    private val ctxResolver get() = resolver
    private val resolver = ctx.applicationContext.contentResolver

    companion object {
        private const val FILE = "together"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_CUSTOM_NAME = "custom_name"

        /** Longest custom name, in characters (code points). */
        const val MAX_CUSTOM_NAME = 24

        /** Null when nothing is left after cleanup, which means "use the device name". */
        fun sanitizeCustomName(raw: String?): String? {
            if (raw == null) return null
            val stripped = raw.filterNot { it.isISOControl() || it == '\u2028' || it == '\u2029' }
            val collapsed = stripped.replace(Regex("[\\s\\p{Z}]+"), " ").trim()
            val end = if (collapsed.codePointCount(0, collapsed.length) > MAX_CUSTOM_NAME) {
                collapsed.offsetByCodePoints(0, MAX_CUSTOM_NAME)
            } else {
                collapsed.length
            }
            return collapsed.substring(0, end).trim().ifEmpty { null }
        }

        /** The name other phones see. */
        fun resolvePlayerName(customName: String?, deviceName: String, lanAddress: String?): String {
            if (customName != null) return customName
            val suffix = lanAddress?.substringAfterLast('.')?.let { " ·$it" }.orEmpty()
            return sanitize(deviceName) + suffix
        }

        fun sanitize(raw: String): String {
            val cleaned = raw.filter { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == ' ' || it == '.' || it == '_' || it == '-' }
                .replace(Regex(" +"), " ")
                .trim()
                .take(24)
            return cleaned.ifEmpty { "Player" }
        }
    }
}
