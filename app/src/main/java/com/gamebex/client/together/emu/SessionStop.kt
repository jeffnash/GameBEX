package com.gamebex.client.together.emu

import android.content.Context
import com.gamebex.client.launch.ShizukuFiles
import kotlinx.coroutines.delay

/** Stops another emulator when Shizuku can force-stop it; never throws. */
object SessionStop {
    suspend fun stop(ctx: Context, pkg: String): Boolean {
        return runCatching {
            val forced = ShizukuFiles.forceStopper(ctx)?.invoke(pkg) == true
            if (forced) delay(300L)
            forced
        }.getOrDefault(false)
    }
}
