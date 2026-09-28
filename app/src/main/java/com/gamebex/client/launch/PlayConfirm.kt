package com.gamebex.client.launch

/**
 * An emulator handoff only proves that startActivity succeeded and that the
 * user later came back: a live process may be a menu or hung screen, and a
 * crash looks like a short session. So a "play" is a direct ROM handoff whose
 * session closed at the next resume. Launcher-only handoffs (MAIN intent, no
 * ROM) never count, since the user may play anything or nothing in the emulator.
 */
object PlayConfirm {
    /** Pending stamps older than this are dropped, never counted. */
    const val MAX_PENDING_AGE_MS = 24 * 3600_000L

    /**
     * Emulators launched via MAIN-only intents (no ROM passed). Mirrors
     * EmuRouter.fireStandalone's launcher-only branch; keep the two in sync.
     */
    val LAUNCHER_ONLY_PKGS = setOf("org.scummvm.scummvm", "com.seleuco.mame4d2024")

    fun isDirectBoot(emuPkg: String): Boolean = emuPkg !in LAUNCHER_ONLY_PKGS

    data class Pending(
        val gameId: Long,
        val emuPkg: String,
        val launchedAt: Long,
        val direct: Boolean,
    )

    fun encode(p: Pending): String = "${p.gameId}|${p.emuPkg}|${p.launchedAt}|${if (p.direct) 1 else 0}"

    fun decode(raw: String?): Pending? {
        if (raw.isNullOrBlank()) return null
        val parts = raw.split('|')
        if (parts.size != 4) return null
        val id = parts[0].toLongOrNull() ?: return null
        val pkg = parts[1].ifBlank { return null }
        val at = parts[2].toLongOrNull() ?: return null
        return Pending(id, pkg, at, parts[3] == "1")
    }

    /** Direct handoffs count at any non-stale duration; future stamps never count. */
    fun shouldAttributePlay(p: Pending, now: Long): Boolean {
        val elapsed = now - p.launchedAt
        if (elapsed < 0 || elapsed > MAX_PENDING_AGE_MS) return false
        return p.direct
    }
}
