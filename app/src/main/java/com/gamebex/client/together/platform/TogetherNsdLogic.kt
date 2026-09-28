package com.gamebex.client.together.platform

import android.annotation.SuppressLint
import android.net.nsd.NsdServiceInfo
import com.gamebex.client.together.Advertisement
import com.gamebex.client.together.Discovery
import com.gamebex.client.together.HostAd
import com.gamebex.client.together.NearbyHost
import com.gamebex.client.together.TOGETHER_PROTO
import com.gamebex.client.together.TogetherMode
import com.gamebex.client.together.net.JoinLink
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.Executor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Fields TogetherNsd reads off an NSD service. Unit tests build this directly. */
internal data class NsdRecord(
    val serviceName: String,
    val serviceType: String,
    val port: Int = 0,
    val host: InetAddress? = null,
    val hostAddresses: List<InetAddress> = emptyList(),
    val attributes: Map<String, ByteArray> = emptyMap(),
    val platformInfo: NsdServiceInfo? = null,
)

/** NSD operations TogetherNsd needs. The platform class is final, so tests supply their own. */
internal interface NsdClient {
    fun registerService(info: NsdRecord, listener: RegistrationListener)
    fun unregisterService(listener: RegistrationListener)
    fun discoverServices(serviceType: String, listener: BrowseListener)
    fun stopServiceDiscovery(listener: BrowseListener)
    fun resolveService(info: NsdRecord, listener: ResolveListener)
    fun registerServiceInfoCallback(info: NsdRecord, executor: Executor, listener: InfoListener)
    fun unregisterServiceInfoCallback(listener: InfoListener)

    interface BrowseListener {
        fun onStartDiscoveryFailed(serviceType: String, errorCode: Int)
        fun onStopDiscoveryFailed(serviceType: String, errorCode: Int)
        fun onDiscoveryStarted(serviceType: String)
        fun onDiscoveryStopped(serviceType: String)
        fun onServiceFound(info: NsdRecord)
        fun onServiceLost(info: NsdRecord)
    }

    interface ResolveListener {
        fun onResolveFailed(info: NsdRecord, errorCode: Int)
        fun onServiceResolved(info: NsdRecord)
    }

    interface InfoListener {
        fun onServiceInfoCallbackRegistrationFailed(errorCode: Int)
        fun onServiceUpdated(info: NsdRecord)
        fun onServiceLost()
        fun onServiceInfoCallbackUnregistered()
    }

    interface RegistrationListener {
        fun onServiceRegistered()
        fun onRegistrationFailed(errorCode: Int)
        fun onServiceUnregistered()
        fun onUnregistrationFailed(errorCode: Int)
    }
}

/** NSD advertisement and browsing for Play together hosts. [TogetherNsd] only wires in NsdManager and the main looper. */
class TogetherNsdLogic internal constructor(
    private val nsd: NsdClient?,
    private val selfDeviceId: String,
    private val filterSelf: Boolean,
    private val postDelayed: (delayMs: Long, block: () -> Unit) -> Unit,
    private val sdkInt: Int,
) : Discovery {

    private val executor: Executor = Executor { command -> postDelayed(0L) { command.run() } }
    private val lock = Any()
    private val foundFlow = MutableStateFlow<List<NearbyHost>>(emptyList())
    private val entries = mutableMapOf<String, NearbyHost>()
    private val entryOrder = mutableMapOf<String, Long>()
    private var orderCounter = 0L
    private val callbacks = mutableMapOf<String, NsdClient.InfoListener>()
    private val foundNames = mutableSetOf<String>()
    private val resolveQueue = ArrayDeque<NsdRecord>()
    private var resolveBusy = false
    private var resolveGeneration = 0
    private var browseCount = 0
    private var browsing = false
    private var stopping = false
    private var listener: NsdClient.BrowseListener? = null

    override val found: StateFlow<List<NearbyHost>> = foundFlow.asStateFlow()

    override fun startBrowsing() {
        synchronized(lock) {
            browseCount++
            if (browseCount == 1 && !browsing && !stopping) startBrowseLocked()
        }
    }

    override fun stopBrowsing() {
        synchronized(lock) {
            if (browseCount == 0) return
            browseCount--
            if (browseCount == 0) stopBrowseLocked()
        }
    }

    override fun advertise(ad: HostAd): Advertisement {
        val info = NsdRecord(
            serviceName = truncateUtf8("GameBEX ${ad.name}", 63),
            serviceType = SERVICE_TYPE,
            port = ad.port,
            attributes = mapOf(
                "v" to TOGETHER_PROTO.toString().toByteArray(Charsets.UTF_8),
                "id" to truncateUtf8(ad.deviceId, 16).toByteArray(Charsets.UTF_8),
                "sid" to truncateUtf8(ad.sessionId, 8).toByteArray(Charsets.UTF_8),
                "n" to truncateUtf8(ad.name, 48).toByteArray(Charsets.UTF_8),
                "g" to truncateUtf8(ad.gameTitle, 64).toByteArray(Charsets.UTF_8),
                "f" to truncateUtf8(ad.folder, 32).toByteArray(Charsets.UTF_8),
                "m" to truncateUtf8(ad.mode.name, 16).toByteArray(Charsets.UTF_8),
            ),
        )
        val registration = object : NsdClient.RegistrationListener {
            private var stopped = false

            override fun onServiceRegistered() = Unit
            override fun onRegistrationFailed(errorCode: Int) {
                runCatching { android.util.Log.w(TAG, "NSD registration failed: $errorCode") }
            }
            override fun onServiceUnregistered() = Unit
            override fun onUnregistrationFailed(errorCode: Int) {
                runCatching { android.util.Log.w(TAG, "NSD unregistration failed: $errorCode") }
            }

            fun stopOnce() {
                synchronized(this) {
                    if (stopped) return
                    stopped = true
                    runCatching { nsd?.unregisterService(this) }
                }
            }
        }
        runCatching { nsd?.registerService(info, registration) }
        return Advertisement { registration.stopOnce() }
    }

    private fun serviceEventsOpen(source: NsdClient.BrowseListener): Boolean =
        listener === source && browsing && !stopping && browseCount > 0

    private fun startBrowseLocked() {
        val manager = nsd ?: return
        val newListener = object : NsdClient.BrowseListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                synchronized(lock) {
                    if (listener !== this) return@synchronized
                    stopping = false
                    browsing = false
                    listener = null
                    entries.clear()
                    publishLocked()
                }
                runCatching { android.util.Log.w(TAG, "NSD discovery start failed: $errorCode") }
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                synchronized(lock) {
                    if (listener !== this) return@synchronized
                    stopping = false
                    browsing = false
                    listener = null
                    if (browseCount > 0) startBrowseLocked()
                }
                runCatching { android.util.Log.w(TAG, "NSD discovery stop failed: $errorCode") }
            }

            override fun onDiscoveryStarted(serviceType: String) {
                synchronized(lock) {
                    if (listener === this && !stopping) browsing = true
                }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                synchronized(lock) {
                    if (listener !== this) return@synchronized
                    stopping = false
                    browsing = false
                    listener = null
                    if (browseCount > 0) startBrowseLocked()
                }
            }

            override fun onServiceFound(info: NsdRecord) {
                if (info.serviceType.trimEnd('.') != SERVICE_TYPE) return
                synchronized(lock) {
                    if (!serviceEventsOpen(this)) return@synchronized
                    foundNames.add(info.serviceName)
                    if (sdkInt >= 34) {
                        registerInfoCallback(info)
                    } else if (resolveQueue.none { it.serviceName == info.serviceName }) {
                        resolveQueue.addLast(info)
                        resolveNextLocked()
                    }
                }
            }

            override fun onServiceLost(info: NsdRecord) {
                synchronized(lock) {
                    if (!serviceEventsOpen(this)) return@synchronized
                    removeService(info.serviceName)
                }
            }
        }
        listener = newListener
        browsing = true
        runCatching { manager.discoverServices(SERVICE_TYPE, newListener) }
            .onFailure {
                browsing = false
                listener = null
                runCatching { android.util.Log.w(TAG, "NSD discovery failed", it) }
            }
    }

    private fun stopBrowseLocked() {
        entries.clear()
        entryOrder.clear()
        foundNames.clear()
        publishLocked()
        resolveQueue.clear()
        resolveBusy = false
        resolveGeneration++
        callbacks.values.toList().forEach { callback -> runCatching { unregisterInfoCallback(callback) } }
        callbacks.clear()
        val old = listener
        if (old != null && (browsing || stopping)) {
            stopping = true
            browsing = false
            val manager = nsd
            if (manager == null) {
                stopping = false
                listener = null
                if (browseCount > 0) startBrowseLocked()
            } else {
                runCatching { manager.stopServiceDiscovery(old) }
                    .onFailure {
                        stopping = false
                        browsing = false
                        listener = null
                        if (browseCount > 0) startBrowseLocked()
                        runCatching { android.util.Log.w(TAG, "NSD discovery stop failed", it) }
                    }
            }
        } else if (!stopping) {
            browsing = false
            listener = null
        }
    }

    @SuppressLint("NewApi")
    private fun registerInfoCallback(info: NsdRecord) {
        synchronized(lock) {
            if (!browsing || stopping || browseCount == 0) return
            if (callbacks.containsKey(info.serviceName)) return
            val serviceName = info.serviceName
            val callback = object : NsdClient.InfoListener {
                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                    synchronized(lock) { callbacks.remove(serviceName, this) }
                    runCatching { android.util.Log.w(TAG, "NSD info registration failed: $errorCode") }
                }

                override fun onServiceUpdated(info: NsdRecord) {
                    synchronized(lock) {
                        if (callbacks[serviceName] !== this || !browsing || stopping || browseCount == 0) return@synchronized
                        updateService(info)
                    }
                }

                override fun onServiceLost() {
                    synchronized(lock) {
                        if (callbacks[serviceName] !== this || !browsing || stopping || browseCount == 0) return@synchronized
                        removeService(serviceName)
                    }
                }

                override fun onServiceInfoCallbackUnregistered() {
                    synchronized(lock) { callbacks.remove(serviceName, this) }
                }
            }
            callbacks[serviceName] = callback
            runCatching { nsd?.registerServiceInfoCallback(info, executor, callback) }
                .onFailure { callbacks.remove(serviceName, callback) }
        }
    }

    private fun resolveNextLocked() {
        if (resolveBusy || resolveQueue.isEmpty() || browseCount == 0) return
        val manager = nsd ?: return
        val info = resolveQueue.removeFirst()
        resolveBusy = true
        val generation = ++resolveGeneration
        postDelayed(5_000L) {
            synchronized(lock) {
                if (resolveBusy && generation == resolveGeneration) {
                    resolveGeneration++
                    resolveBusy = false
                    resolveNextLocked()
                }
            }
        }
        runCatching {
            manager.resolveService(info, object : NsdClient.ResolveListener {
                override fun onResolveFailed(info: NsdRecord, errorCode: Int) {
                    synchronized(lock) {
                        if (generation != resolveGeneration) return
                        resolveBusy = false
                        resolveNextLocked()
                    }
                }

                override fun onServiceResolved(info: NsdRecord) {
                    synchronized(lock) {
                        val current = generation == resolveGeneration
                        if (current) resolveBusy = false
                        if (browsing && !stopping && browseCount > 0 &&
                            foundNames.contains(info.serviceName)
                        ) {
                            updateService(info)
                        }
                        if (current) resolveNextLocked()
                    }
                }
            })
        }.onFailure {
            if (generation == resolveGeneration) {
                resolveBusy = false
                resolveNextLocked()
            }
        }
    }

    private fun updateService(info: NsdRecord) {
        val attributes = info.attributes.mapValues { (_, value) -> value.toString(Charsets.UTF_8) }
        val id = attributes["id"].orEmpty()
        val session = attributes["sid"].orEmpty()
        val address = firstPrivateIpv4(info)
        val mode = attributes["m"]?.let { runCatching { TogetherMode.valueOf(it) }.getOrNull() }
        val entry = if (attributes["v"] == TOGETHER_PROTO.toString() &&
            (!filterSelf || id != selfDeviceId) && mode != null && address != null
        ) {
            NearbyHost(
                deviceId = id,
                sessionId = session,
                name = attributes["n"].orEmpty(),
                gameTitle = attributes["g"].orEmpty(),
                folder = attributes["f"].orEmpty(),
                mode = mode,
                address = address,
                port = info.port,
            )
        } else null
        synchronized(lock) {
            if (entry == null) {
                entries.remove(info.serviceName)
                entryOrder.remove(info.serviceName)
            } else {
                entries[info.serviceName] = entry
                entryOrder[info.serviceName] = ++orderCounter
            }
            publishLocked()
        }
    }

    private fun removeService(name: String?) {
        if (name == null) return
        synchronized(lock) {
            foundNames.remove(name)
            entries.remove(name)
            entryOrder.remove(name)
            callbacks.remove(name)?.let { runCatching { unregisterInfoCallback(it) } }
            publishLocked()
        }
    }

    @SuppressLint("NewApi")
    private fun unregisterInfoCallback(callback: NsdClient.InfoListener) {
        if (sdkInt >= 34) nsd?.unregisterServiceInfoCallback(callback)
    }

    private fun publishLocked() {
        foundFlow.value = entries.entries
            .sortedByDescending { entryOrder[it.key] ?: 0L }
            .map { it.value }
            .distinctBy { "${it.deviceId}/${it.sessionId}" }
            .sortedWith(compareBy<NearbyHost> { it.name }.thenBy { it.gameTitle })
    }

    /** A host is only listed at a private LAN IPv4; the guest dials it and aims the emulator at it. */
    private fun firstPrivateIpv4(info: NsdRecord): String? {
        val candidates = if (sdkInt >= 34) info.hostAddresses else listOfNotNull(info.host)
        return candidates.filterIsInstance<Inet4Address>()
            .mapNotNull { it.hostAddress }
            .firstOrNull { JoinLink.isPrivateIpv4(it) }
    }

    companion object {
        private const val TAG = "GameBEXTogether"
        private const val SERVICE_TYPE = "_gamebex._tcp"

        internal fun truncateUtf8(value: String, maxBytes: Int): String {
            if (value.toByteArray(Charsets.UTF_8).size <= maxBytes) return value
            val result = StringBuilder()
            for (codePoint in value.codePoints().toArray()) {
                val candidate = result.toString() + String(Character.toChars(codePoint))
                if (candidate.toByteArray(Charsets.UTF_8).size > maxBytes) break
                result.appendCodePoint(codePoint)
            }
            return result.toString()
        }
    }
}
