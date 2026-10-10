package com.gamebex.client.stream.guest

import android.content.pm.PackageManager
import com.gamebex.client.data.PlatformRegistry
import com.gamebex.client.data.Route
import com.gamebex.client.stream.p2.OverlaySeed

/**
 * When the host shares no overlay, the guest renders RetroArch's stock
 * overlay for the hosted system from its own RetroArch install. Uses the
 * folder's default core; host core overrides only matter for host-shared overlays.
 */
object GuestStockOverlay {
    data class Stock(val layout: OverlayLayout, val images: Map<String, ByteArray>)

    /** The default RetroArch core for a PlatformRegistry [folder], or null when it isn't RA-hosted. */
    fun coreForFolder(folder: String?): String? {
        if (folder.isNullOrEmpty()) return null
        val platform = PlatformRegistry.byFolder[folder] ?: return null
        (platform.route as? Route.RetroCore)?.let { return it.core }
        // A Standalone platform with an RA alternate (GC/Wii/PSP) keeps the
        // stock RA overlay as the guest fallback for RA-alt hosts.
        return platform.altRetroCore
    }

    /** Loads the stock overlay for [folder] out of this phone's RetroArch APK. Null when none fits. */
    fun load(pm: PackageManager, folder: String?): Stock? {
        val core = coreForFolder(folder) ?: return null
        // Wii shares the dolphin core with GameCube, but the gamecube
        // overlay is the wrong pad for it; RA's default retropad stands in.
        val asset = if (folder == "wii") OverlaySeed.RETROPAD_FALLBACK
        else OverlaySeed.assetForCore(core) ?: return null
        val apk = OverlaySeed.findRetroArchApk(pm) ?: return null
        val (cfgText, images) = OverlaySeed.read(apk, asset) ?: return null
        val layout = OverlayCfg.parse(cfgText, asset.cfg)
        if (layout.playPage()?.descs.isNullOrEmpty()) return null
        return Stock(layout, images)
    }
}
