package com.gamebex.client.ui.presentation

import android.content.Context
import android.util.Log
import com.gamebex.client.GameBexApp
import com.gamebex.client.data.Emu
import com.gamebex.client.data.local.LibraryDb
import com.gamebex.client.launch.EmuRouter
import com.gamebex.client.ui.UserErrors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Shared Play for library games (Home, Library): routes through EmuRouter
 * and reports one structured status at a time; null clears it.
 */
object LocalLauncher {
    suspend fun play(
        ctx: Context,
        id: Long,
        fresh: Boolean = false,
        onStatus: (LaunchStatus?) -> Unit,
    ) {
        onStatus(LaunchStatus.progress(id, "Launching…"))
        val status = withContext(Dispatchers.IO) {
            val g = LibraryDb.get(ctx).games().findById(id) ?: return@withContext null
            var announced = false
            val out = try {
                EmuRouter.play(ctx, g, fresh) { _, _ ->
                    if (!announced) {
                        announced = true
                        onStatus(LaunchStatus.progress(id, "Fetching missing core…"))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("GameBEXPlay", "quick-play failed", e)
                EmuRouter.Outcome.Failed(UserErrors.launchFailed())
            }
            if (out is EmuRouter.Outcome.Launched) {
                // Counted on confirmed boot at next resume, not here.
                (ctx.applicationContext as GameBexApp).noteLaunch(g.id, out.emuPkg)
            }
            launchStatusFor(id, out, fresh)
        }
        onStatus(status)
    }

    /**
     * The one install path every surface shares (Detail's dialog, Home and
     * Library prompts): start the reviewed-build pipeline, then the caller
     * opens System, where progress, Android's confirmation and the
     * unknown-sources fix render. Guided origins (no direct channel) are a
     * no-op in the installer; System shows their store links instead.
     */
    fun beginInstall(ctx: Context, emu: Emu) {
        (ctx.applicationContext as GameBexApp).installer.start(emu.pkg)
    }
}
