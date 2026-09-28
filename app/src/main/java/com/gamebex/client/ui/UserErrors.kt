package com.gamebex.client.ui

import com.gamebex.source.SourceException
import com.gamebex.source.SourceFailure
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * User-facing copy for catalog/search failures. Raw exception messages never
 * reach the UI (they can be empty, technical, or leak host internals).
 * Callers log the underlying exception and show only these mapped strings.
 * Pure.
 */
object UserErrors {
    fun noSource(): String = "Import a ROM source to discover games."

    fun sourceDisabled(name: String): String = "$name is disabled. Enable it in Sources."

    fun sourceMissing(name: String): String = "Install $name to continue this download."

    /** RetroArch has never been opened, so it can't read games yet. */
    fun retroArchFirstRun(): String = "Open RetroArch once and allow it to access files, then play again."

    fun sourceNeededForCatalog(name: String): String = "Install $name to see versions or download again."

    private fun mapped(e: Exception, sourceName: String?, fallback: String): String {
        val name = sourceName ?: "This source"
        if (e is SourceException) {
            return when (e.failure) {
                SourceFailure.NETWORK ->
                    "Couldn't reach $name — check your connection and retry."
                SourceFailure.HTTP -> when (e.statusCode) {
                    404 -> "That page is no longer available on $name."
                    429 -> "$name is rate-limiting requests — retry shortly."
                    in 500..599 -> "$name is having trouble — retry shortly."
                    else -> fallback
                }
                SourceFailure.INVALID_RESPONSE, SourceFailure.UNSUPPORTED ->
                    "$name changed its response. Update the source or retry later."
                SourceFailure.POLICY ->
                    "$name requested an unapproved address. Review its source definition."
                SourceFailure.INCOMPATIBLE, SourceFailure.INVALID_DEFINITION ->
                    "This source needs a newer GameBEX version."
                SourceFailure.UNAVAILABLE ->
                    if (sourceName != null) sourceDisabled(sourceName) else noSource()
            }
        }
        if (e is UnknownHostException || e is SocketTimeoutException || e is ConnectException) {
            return "Couldn't reach $name — check your connection and retry."
        }
        return fallback
    }

    fun forCatalog(e: Exception, sourceName: String?): String =
        mapped(e, sourceName, "Couldn't load this list — retry.")

    fun forSearch(e: Exception, sourceName: String?): String =
        mapped(e, sourceName, "Search failed — retry.")

    // Internal route notes ("post-v1", "verifies in P2") and exception
    // text stay in logs; the user gets what happened and what to do.

    fun emuMissing(emu: String): String = "$emu isn't installed."

    fun unsupportedSystem(system: String): String = "GameBEX can't play $system games yet."

    fun unknownSystem(): String = "GameBEX doesn't recognize this game's system."

    fun noDirectBoot(emu: String): String =
        "GameBEX can't start games in $emu yet. Open $emu and start it there."

    fun guidedImport(emu: String, title: String): String =
        "Play this one inside $emu: install it if needed, import \"$title\", then start it there."

    fun launchFailed(): String = "Couldn't start this game. Retry, or open details."

    fun freshUnavailable(): String =
        "Couldn't set up a fresh start (storage not writable). Play resumes as usual."

    fun prepFailed(): String = "Preparation didn't finish. Retry from Advanced."

    fun emuDownloadFailed(): String = "Download failed. Check your connection and retry."

    fun emuInstallFailed(): String = "Install didn't finish. Retry."
}

/**
 * Best-effort warning log for catalog ViewModels: android.jar stubs throw
 * on JVM unit tests, and logging must never break the code under test.
 */
internal fun logW(tag: String, msg: String, e: Throwable) {
    try {
        android.util.Log.w(tag, msg, e)
    } catch (_: RuntimeException) {
        // Unit-test stub: the mapped error state is the asserted behavior.
    }
}
