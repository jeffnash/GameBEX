package com.gamebex.client.install

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller

/** Manifest-declared status target for PackageInstaller commits. */
class InstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_STATUS) return
        val pkg = intent.getStringExtra(EXTRA_PKG) ?: return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val action = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        // App-scoped singleton: the UI observes this same instance's states,
        // so terminal results survive even if the activity is dead.
        val app = context.applicationContext as com.gamebex.client.GameBexApp
        app.installer.onSessionStatus(pkg, status, message, action)
    }

    companion object {
        const val ACTION_STATUS = "com.gamebex.client.install.STATUS"
        const val EXTRA_PKG = "pkg"
    }
}
