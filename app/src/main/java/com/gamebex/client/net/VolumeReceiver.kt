package com.gamebex.client.net

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.gamebex.client.GameBexApp
import com.gamebex.client.data.local.LibraryDb
import com.gamebex.client.data.local.LibraryScanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Parks live downloads when their volume goes away and resumes them when it
 * comes back. Registered dynamically because context receivers get MEDIA_*
 * broadcasts on all API levels.
 */
class VolumeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? GameBexApp ?: return
        val uuid = intent.data?.path?.removePrefix("/storage/")?.substringBefore('/')
            ?.takeIf { it.isNotEmpty() && it != "emulated" && it != "self" } ?: return
        val volumeId = "sd:$uuid"
        when (intent.action) {
            Intent.ACTION_MEDIA_EJECT, Intent.ACTION_MEDIA_UNMOUNTED,
            Intent.ACTION_MEDIA_BAD_REMOVAL, Intent.ACTION_MEDIA_REMOVED -> {
                app.engine.parkVolume(volumeId)
                // Ejected games must not look playable; remount rescans.
                libScope.launch {
                    runCatching {
                        LibraryDb.get(app).games().markVolumeUnavailable(volumeId)
                    }
                }
            }
            Intent.ACTION_MEDIA_MOUNTED -> {
                app.engine.unparkVolume(volumeId)
                libScope.launch {
                    runCatching { LibraryScanner.refresh(app, LibraryDb.get(app)) }
                }
            }
        }
    }

    companion object {
        private val libScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun register(app: GameBexApp): BroadcastReceiver {
            val recv = VolumeReceiver()
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_MEDIA_EJECT)
                addAction(Intent.ACTION_MEDIA_UNMOUNTED)
                addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
                addAction(Intent.ACTION_MEDIA_REMOVED)
                addAction(Intent.ACTION_MEDIA_MOUNTED)
                addDataScheme("file")
            }
            app.registerReceiver(recv, filter)
            return recv
        }
    }
}
