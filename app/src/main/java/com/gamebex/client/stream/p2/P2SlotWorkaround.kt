package com.gamebex.client.stream.p2

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.gamebex.client.data.Emu
import com.gamebex.client.data.EmuCatalog
import java.security.MessageDigest

/**
 * Player 2's buttons with official RetroArch. Official
 * `android_joypad_state()` reads buttons with the user number but stores
 * key state per device slot, so user 2's buttons only work when its pad
 * sits in slot 1, and slots are assigned lazily in first-input order.
 * The workaround makes slot == user: a silent placeholder pad takes slot 0
 * on touch-only hosts ([P2SlotMode.PLACEHOLDER]), or player 2's first report
 * waits for the host pad's first input ([P2SlotMode.GATE_REAL_PAD]).
 * GameBEX-patched builds read buttons from the slot and skip it.
 */
object P2SlotWorkaround {
    /** Official RetroArch signer, pinned in the catalog. */
    val OFFICIAL_SIGNER_SHA256: String =
        EmuCatalog.entries.first { it.emu == Emu.RETROARCH }.pinnedSignerSha256.orEmpty()

    /** GameBEX-patched RetroArch signer (`keytool -printcert -jarfile retroarch-gbx-v3.apk`). */
    const val GBX_SIGNER_SHA256: String =
        "ff4a81422b70fb859888e0b9659b8ee95280bb845074486ef4bddb829324a9d7"

    /**
     * First official versionCode containing the upstream button fix. No
     * official build has it yet, so no version skips the workaround.
     */
    const val OFFICIAL_FIXED_VERSION_CODE: Long = Long.MAX_VALUE

    /** Signer #1 cert SHA-256 (lowercase hex, no colons), or null when unreadable. */
    fun signerSha256(ctx: Context, pkg: String): String? = runCatching {
        val pm = ctx.packageManager
        val certs = if (Build.VERSION.SDK_INT >= 28) {
            @Suppress("DEPRECATION")
            val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
            val signing = info.signingInfo ?: return null
            if (signing.hasMultipleSigners()) signing.apkContentsSigners else signing.signingCertificateHistory
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures
        } ?: return null
        val cert = certs.firstOrNull() ?: return null
        MessageDigest.getInstance("SHA-256").digest(cert.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }.getOrNull()

    /**
     * Whether this RetroArch needs the workaround: the official signer
     * (or an unknown/unreadable one — fail closed, a wrong prompt beats
     * dead buttons), below the first fixed official version. The
     * GameBEX-patched signer never needs it.
     */
    fun needsFix(signerSha256: String?, raVersionCode: Long): Boolean {
        if (signerSha256 != null && signerSha256.equals(GBX_SIGNER_SHA256, ignoreCase = true)) return false
        if (signerSha256 != null && !signerSha256.equals(OFFICIAL_SIGNER_SHA256, ignoreCase = true)) return true
        return raVersionCode < OFFICIAL_FIXED_VERSION_CODE
    }

    /**
     * The workaround mode for a Stream session. [flag] is the raw
     * `p2_slot_workaround` value ("auto"/"on"/"off"). "off" always
     * disables; "on" forces it for RetroArch hosts whatever the build;
     * "auto" applies it only when [needsFix]. Non-RetroArch hosts never
     * apply it (no RA slots involved).
     */
    fun decide(
        emu: Emu?,
        signerSha256: String?,
        raVersionCode: Long,
        hasRealHostPad: Boolean,
        flag: String?,
    ): P2SlotMode {
        if (flag == P2SlotMode.FLAG_OFF) return P2SlotMode.OFF
        if (emu != Emu.RETROARCH) return P2SlotMode.OFF
        if (flag != P2SlotMode.FLAG_ON && !needsFix(signerSha256, raVersionCode)) return P2SlotMode.OFF
        return if (hasRealHostPad) P2SlotMode.GATE_REAL_PAD else P2SlotMode.PLACEHOLDER
    }
}

/** P2 slot workaround mode; [wire] rides in [StreamConfig.p2SlotMode]. */
enum class P2SlotMode(val wire: String) {
    OFF("off"),
    PLACEHOLDER("placeholder"),
    GATE_REAL_PAD("gate-realpad"),
    ;

    companion object {
        const val FLAG_AUTO: String = "auto"
        const val FLAG_ON: String = "on"
        const val FLAG_OFF: String = "off"

        /** Unknown values stay off: a misspelled prop must not change input routing. */
        fun fromWire(wire: String?): P2SlotMode = entries.firstOrNull { it.wire == wire } ?: OFF
    }
}
