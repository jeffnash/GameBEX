package com.gamebex.client.stream

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/** Storage behind the Stream flags screen; [StreamFlagPrefs] is the real one. */
interface StreamFlagStore {
    /** Stored raw value, or null when the user never set [key]. */
    fun get(key: String): String?
    fun set(key: String, value: String)
    fun resetAll()
    fun setPromising()
}

/**
 * Per-phone Stream flag storage. Each phone's flags apply to its own role;
 * the UI shows each flag's side so the user knows which phone to set.
 */
class StreamFlagPrefs(context: Context) : StreamFlagStore {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun get(key: String): String? = prefs.getString(key, null)

    fun lookup(): (String) -> String? = ::get

    override fun set(key: String, value: String) {
        prefs.edit { putString(key, value) }
    }

    /** True when the user set [key] to anything but its built-in default. */
    fun isNonDefault(key: String): Boolean {
        val v = get(key) ?: return false
        return v != StreamFlags.BY_KEY[key]?.default
    }

    override fun resetAll() {
        prefs.edit { clear() }
    }

    /** Turns on the whole "Try these" group (see [StreamFlags.promisingOn]). */
    override fun setPromising() {
        prefs.edit {
            StreamFlags.ALL.filter { it.group == StreamFlagGroup.TRY }.forEach { def ->
                putString(def.key, StreamFlags.promisingOn(def))
            }
        }
    }

    companion object {
        internal const val PREFS_NAME = "stream_flags"
    }
}
