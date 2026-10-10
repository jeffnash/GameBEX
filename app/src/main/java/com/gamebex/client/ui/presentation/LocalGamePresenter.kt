package com.gamebex.client.ui.presentation

import android.content.Context
import android.content.pm.PackageManager
import com.gamebex.client.data.Emu
import com.gamebex.client.data.PlatformRegistry
import com.gamebex.client.data.PlayStates
import com.gamebex.client.data.PlayableGame
import com.gamebex.client.data.RomEntry
import com.gamebex.client.data.Route
import com.gamebex.client.data.toStableId
import com.gamebex.client.data.local.GameEntity
import com.gamebex.client.data.local.RomStore

/**
 * Shared local-game presenter: Home shelves and Game Detail build the
 * SAME PlayableGame inputs so shelf CTAs and detail CTAs can never
 * disagree. Thin UI projection only — the state machine stays source of
 * truth.
 */
data class LocalPresentation(
    val presentation: GamePresentation,
    val playable: PlayableGame,
)

object LocalGamePresenter {
    fun present(
        ctx: Context,
        g: GameEntity,
        continued: Boolean,
        metaLine: String?,
        biosMissing: List<String> = emptyList(),
        /** A prep (extract, copy, fix) is running for this game right now. */
        preparing: Boolean = false,
    ): LocalPresentation {
        // coreReady stays true: a missing core auto-fetches inside
        // EmuRouter.play, so the CTA is always Play — never a setup gate.
        val pg = PlayableGame.fromLocal(
            game = g, dl = null,
            biosReady = g.prepState != "NEEDS_BIOS",
            biosMissing = biosMissing,
            runtimeInstalled = runtimeReady(ctx, g.folder, g.emulatorOverride),
            coreReady = true,
            spaceOk = hasLaunchSpace(ctx, g),
            preparing = preparing,
        )
        return LocalPresentation(
            pg.toPresentation(
                lastPlayedMs = g.lastPlayedMs.takeIf { it > 0 },
                sizeBytes = g.sizeBytes,
                metaLine = metaLine,
                continued = continued,
                coverFallback = g.coverPath,
            ),
            pg,
        )
    }

    /**
     * Catalog face of the unified detail screen: same PlayableGame status
     * machine, rowExists=false until ingest lands the library row — then
     * the caller switches to [present] and Download becomes Play in place.
     */
    fun presentCatalog(
        entry: RomEntry,
        folder: String?,
        dl: PlayStates.Dl?,
    ): LocalPresentation {
        val route = folder?.let { PlatformRegistry.byFolder[it]?.route }
            ?: Route.Blocked("No system mapped yet")
        val pg = PlayableGame(
            key = entry.key.toStableId(), title = entry.title,
            systemLabel = entry.consoleName ?: folder ?: "?",
            folder = folder,
            artBox = entry.coverUrl, artHero = null, artLogo = null, artShot = null,
            cap = PlayStates.Capabilities(
                route = route, rowExists = false, local = false,
                prep = PlayStates.Prep.NONE, biosReady = true,
                runtimeInstalled = false, coreReady = false,
                spaceOk = true, dl = dl,
            ),
        )
        return LocalPresentation(pg.toPresentation(), pg)
    }

    /** Launch-time space probe: the ROM volume needs headroom for saves/states. */
// The launch guard measures available bytes without reclaiming unrelated application caches.
@android.annotation.SuppressLint("UsableSpace")
    private fun hasLaunchSpace(ctx: Context, g: GameEntity): Boolean {
        val dir = RomStore.resolveFile(ctx, g.volumeId, g.relPath)?.parentFile ?: return true
        return runCatching { dir.usableSpace }.getOrDefault(Long.MAX_VALUE) > 64L * 1024 * 1024
    }

    fun runtimeReady(ctx: Context, folder: String, emuOverride: String? = null): Boolean {
        // A per-game override reroutes the launch (EmuRouter), so readiness
        // must follow the override package — not the folder default.
        val emu = emuOverride?.let { pkg -> Emu.entries.firstOrNull { it.pkg == pkg } }
            ?: when (val r = PlatformRegistry.byFolder[folder]?.route) {
                is Route.Standalone -> r.emu
                is Route.GuidedInstall -> r.emu
                is Route.RetroCore -> Emu.RETROARCH
                else -> null
            }
            ?: return true
        return try {
            ctx.packageManager.getPackageInfo(emu.pkg, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }
}
