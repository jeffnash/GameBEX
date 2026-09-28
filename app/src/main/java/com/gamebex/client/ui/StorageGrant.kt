package com.gamebex.client.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/** True when the SD-backed library is readable (all-files grant on R+). */
fun Context.hasStorageGrant(): Boolean =
    com.gamebex.client.data.local.LibraryScanner.hasAllFilesAccess(this)

/** Opens the system screen that grants all-files access (best-effort). */
fun Context.openAllFilesSettings() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
        runCatching {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        }
        return
    }
    runCatching {
        startActivity(
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }.recoverCatching {
        startActivity(
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
