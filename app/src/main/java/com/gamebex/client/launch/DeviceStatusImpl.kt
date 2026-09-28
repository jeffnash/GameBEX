package com.gamebex.client.launch

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.StatFs
import com.gamebex.client.cocoon.CocoonDirs
import com.gamebex.client.data.local.LibraryDb
import com.gamebex.client.ui.home.model.DeviceStatus
import com.gamebex.client.ui.presentation.EmuSnap
import com.gamebex.client.ui.presentation.attentionCount
import com.gamebex.client.ui.presentation.prepSummaries
import com.gamebex.client.ui.presentation.systemReadinessItems
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * Online means the default network is `NET_CAPABILITY_VALIDATED`, so captive
 * portals don't count. Free space is read from the volume holding a folder's games.
 */
class DeviceStatusImpl(
    private val ctx: Context,
    private val db: LibraryDb,
) : DeviceStatus {

    private val cm =
        ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _online = MutableStateFlow(validatedNow())
    override val online: StateFlow<Boolean> = _online

    init {
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    _online.value = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                }

                override fun onLost(network: Network) {
                    _online.value = false
                }
            })
        }
    }

    override fun freeBytes(folder: String?): Long? =
        runCatching { StatFs(CocoonDirs.dirFor(ctx, folder).path).availableBytes }.getOrNull()

    override suspend fun systemWarnings(): Int = withContext(Dispatchers.IO) {
        val prepMap = prepSummaries(db.games().prepCounts())
        // A failed/unavailable core probe reads as "not probed yet", never
        // as "no cores installed": the count must match the System screen.
        val cores = runCatching { RetroCoreProbe.queryInstalledCores(ctx) }.getOrNull()
        val missingSys = CoreSystemFiles.missing(CoreSystemFiles.PACKS.keys)
        // Same derivation as the System screen, so the ⚠ count matches it.
        val items = systemReadinessItems(cores, prepMap, missingSys) { probe(it) }
        attentionCount(items, prepMap)
    }

    private fun validatedNow(): Boolean = runCatching {
        cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    }.getOrDefault(false)

    /** Installed-version probe for one emulator package (readiness needs no more). */
    private fun probe(pkg: String): EmuSnap = try {
        val info = ctx.packageManager.getPackageInfo(pkg, 0)
        val code = if (android.os.Build.VERSION.SDK_INT >= 28) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION") info.versionCode.toLong()
        }
        EmuSnap(true, info.versionName, code)
    } catch (_: Exception) {
        EmuSnap(false, null, 0)
    }
}
