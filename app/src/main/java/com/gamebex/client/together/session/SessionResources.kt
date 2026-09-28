package com.gamebex.client.together.session

class SessionResources {
    private val closeActions = mutableListOf<() -> Unit>()
    private var closed = false

    /** Adds an action, or returns false after closure so the caller can close a late resource itself. */
    @Synchronized
    fun register(close: () -> Unit): Boolean {
        if (closed) return false
        closeActions += close
        return true
    }

    /** Runs every action once, in reverse registration order. */
    @Synchronized
    fun closeAll() {
        if (closed) return
        closed = true
        closeActions.asReversed().forEach { close -> runCatching(close) }
        closeActions.clear()
    }
}
