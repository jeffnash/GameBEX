package com.gamebex.client.stream

import com.gamebex.client.data.Emu
import com.gamebex.client.data.PlatformRegistry
import com.gamebex.client.data.Route
import com.gamebex.client.data.local.GameEntity

/**
 * Which emulator a Stream host runs a game in, resolved the way Play does
 * (EmuRouter.playInner).
 */
object StreamEmus {
    fun emuFor(game: GameEntity): Emu? {
        val platform = PlatformRegistry.byFolder[game.folder] ?: return null
        val override = game.emulatorOverride?.let { pkg -> Emu.entries.firstOrNull { it.pkg == pkg } }
        return when (val route = platform.route) {
            is Route.RetroCore -> override ?: Emu.RETROARCH
            is Route.Standalone -> override ?: route.emu
            else -> null
        }
    }

    /** The RetroArch core for [game], or null when it doesn't run in RetroArch. */
    fun raCore(game: GameEntity): String? {
        if (emuFor(game) != Emu.RETROARCH) return null
        val route = PlatformRegistry.byFolder[game.folder]?.route as? Route.RetroCore ?: return null
        return game.coreOverride ?: route.core
    }
}
