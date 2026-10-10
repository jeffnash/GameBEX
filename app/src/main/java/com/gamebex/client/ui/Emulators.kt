package com.gamebex.client.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamebex.client.GameBexApp
import com.gamebex.client.data.EmuCatalog
import com.gamebex.client.install.EmuInstaller
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class InstalledSnapshot(
    val installed: Boolean,
    val versionName: String?,
    val versionCode: Long,
    val signerSha256: String?,
)

class EmulatorsViewModel(appContext: Context) : ViewModel() {
    // Only the process-wide application context is retained.
    @android.annotation.SuppressLint("StaticFieldLeak")
    private val app = appContext.applicationContext
    val installer: EmuInstaller = (app as GameBexApp).installer
    private val _snaps = MutableStateFlow<Map<String, InstalledSnapshot>>(emptyMap())
    val snaps: StateFlow<Map<String, InstalledSnapshot>> = _snaps

    init {
        refresh()
        installer.refreshUpstream()
    }

    fun refresh() {
        installer.onResumed()
        viewModelScope.launch(Dispatchers.IO) {
            val pm = app.packageManager
            val out = mutableMapOf<String, InstalledSnapshot>()
            for (e in EmuCatalog.entries) {
                out[e.emu.pkg] = try {
                    val flags = if (Build.VERSION.SDK_INT >= 28) {
                        PackageManager.GET_SIGNING_CERTIFICATES
                    } else {
                        @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
                    }
                    val info = pm.getPackageInfo(e.emu.pkg, flags)
                    val vc = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
                    val sig = if (Build.VERSION.SDK_INT >= 28) {
                        info.signingInfo?.apkContentsSigners?.firstOrNull()
                    } else {
                        @Suppress("DEPRECATION") info.signatures?.firstOrNull()
                    }
                    InstalledSnapshot(
                        true, info.versionName, vc,
                        sig?.let { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()) }
                            ?.joinToString("") { "%02x".format(it) },
                    )
                } catch (_: PackageManager.NameNotFoundException) {
                    InstalledSnapshot(false, null, 0, null)
                }
            }
            _snaps.value = out
        }
    }

    fun openApp(pkg: String) {
        val launch = app.packageManager.getLaunchIntentForPackage(pkg) ?: return
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        app.startActivity(launch)
    }

    fun openUrl(url: String) {
        runCatching {
            app.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun openUnknownSources() {
        runCatching { app.startActivity(installer.unknownSourcesIntent()) }
    }

    /**
     * Offers GameBEX as the home screen: requests the HOME role where the
     * platform supports it, else opens the default-home settings (or the
     * HOME picker as a last resort). Firing HOME alone never changes the
     * default — it just launches whoever already owns it.
     * Default home also owns boot landing (after the lock screen).
     */
    fun openHomeChooser() {
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                val rm = app.getSystemService(android.app.role.RoleManager::class.java)
                if (rm != null && rm.isRoleAvailable(android.app.role.RoleManager.ROLE_HOME) &&
                    !rm.isRoleHeld(android.app.role.RoleManager.ROLE_HOME)
                ) {
                    app.startActivity(
                        rm.createRequestRoleIntent(android.app.role.RoleManager.ROLE_HOME)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                    return
                }
            }
            // Already default, or no role API: let the user change it.
            try {
                app.startActivity(
                    Intent(android.provider.Settings.ACTION_HOME_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (_: Exception) {
                app.startActivity(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }

    /**
     * Escape hatch back to the previous launcher: starts the system
     * launcher (or the first non-self HOME handler as a fallback).
     * Explicit component, so it works even when we are the default
     * home. When no alternate exists, opens the system default-home
     * picker so the user can change the default (uninstalling us also
     * restores it).
     */
    fun openSystemLauncher() {
        runCatching {
            val pm = app.packageManager
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val self = app.packageName
            @Suppress("DEPRECATION")
            val other = pm.queryIntentActivities(home, 0)
                .mapNotNull { it.activityInfo }
                .filter { it.packageName != self }
                .sortedByDescending { isSystemApp(pm, it.packageName) }
                .firstOrNull()
            if (other != null) {
                app.startActivity(
                    Intent(Intent.ACTION_MAIN).apply {
                        component = android.content.ComponentName(other.packageName, other.name)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                )
            } else {
                app.startActivity(
                    Intent(android.provider.Settings.ACTION_HOME_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }

    private fun isSystemApp(pm: PackageManager, pkg: String): Boolean {
        return runCatching {
            @Suppress("DEPRECATION")
            val info = pm.getApplicationInfo(pkg, 0)
            (info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0 ||
                (info.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        }.getOrDefault(false)
    }

    fun requestUninstall(pkg: String) {
        // System-owned flow (user confirms in the OS dialog); never silent.
        runCatching {
            app.startActivity(
                Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    fun useExisting(pkg: String) {
        installer.dismiss(pkg)
        // Leave the row in its natural "another source" derived state.
        refresh()
    }

    /** Guided-origin links for [pkg], or null when directly installable. */
    fun guidedLinks(pkg: String): GuidedLinks? = guidedLinksFor(pkg)

    /** Opens the guided flow target: Play Store listing or official site. */
    fun openGuided(pkg: String, playStore: Boolean) {
        guidedLinks(pkg)?.let { openUrl(if (playStore) it.playUrl else it.siteUrl) }
    }
}

/** Guided install targets (Play listing + official site); pure lookup. */
data class GuidedLinks(val playUrl: String, val siteUrl: String)

/** Null unless [pkg] is a Guided origin (e.g. Dolphin). Pure for tests. */
fun guidedLinksFor(pkg: String): GuidedLinks? {
    val origin = EmuCatalog.byPkg[pkg]?.origin as? EmuCatalog.Origin.Guided
        ?: return null
    return GuidedLinks(origin.playUrl, origin.siteUrl)
}
