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
            // GuidedInstall names the emulator too (Vita3K); SideloadApp and
            // Blocked have no host emulator, so Stream stays unoffered.
            is Route.GuidedInstall -> override ?: route.emu
            else -> null
        }
    }

    /** The RetroArch core for [game], or null when it doesn't run in RetroArch. */
    fun raCore(game: GameEntity): String? {
        if (emuFor(game) != Emu.RETROARCH) return null
        val platform = PlatformRegistry.byFolder[game.folder] ?: return null
        (platform.route as? Route.RetroCore)?.let { return game.coreOverride ?: it.core }
        return platform.altRetroCore?.let { game.coreOverride ?: it }
    }

    /**
     * The game-picture aspect RetroArch renders for [core] (core-provided
     * aspect), as the encoder-crop "N:D" string. Measured: mgba fills a
     * centered 3:2 box, flycast 4:3; gambatte is the Game Boy's 10:9 frame
     * and ppsspp the PSP's 16:9 one. Unknown cores get 4:3.
     */
    fun cropAspectForRaCore(core: String?): String = when (core) {
        "mgba" -> "3:2"
        "gambatte" -> "10:9"
        "ppsspp" -> "16:9"
        else -> "4:3"
    }
}
