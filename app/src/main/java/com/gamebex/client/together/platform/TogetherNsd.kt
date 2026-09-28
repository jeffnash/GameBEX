package com.gamebex.client.together.platform

import android.annotation.SuppressLint
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.gamebex.client.together.Discovery
import java.util.IdentityHashMap
import java.util.concurrent.Executor

/** Android adapter for [TogetherNsdLogic], which makes every decision; this supplies only NsdManager and the main looper. */
class TogetherNsd private constructor(logic: TogetherNsdLogic) : Discovery by logic {
    constructor(ctx: Context, selfDeviceId: String, filterSelf: Boolean = true) : this(
        TogetherNsdLogic(
            nsd = ctx.applicationContext.getSystemService(NsdManager::class.java)?.let(::AndroidNsdClient),
            selfDeviceId = selfDeviceId,
            filterSelf = filterSelf,
            postDelayed = mainLooperDelay(),
            sdkInt = Build.VERSION.SDK_INT,
        ),
    )

    private companion object {
        fun mainLooperDelay(): (Long, () -> Unit) -> Unit {
            val handler = Handler(Looper.getMainLooper())
            return { delayMs, block -> handler.postDelayed(block, delayMs) }
        }
    }
}

/** Maps [NsdClient] onto NsdManager. Listener maps are touched from several threads, so they are guarded. */
private class AndroidNsdClient(private val manager: NsdManager) : NsdClient {
    private val discovery = IdentityHashMap<NsdClient.BrowseListener, NsdManager.DiscoveryListener>()
    private val registration = IdentityHashMap<NsdClient.RegistrationListener, NsdManager.RegistrationListener>()
    private val infoListeners = IdentityHashMap<NsdClient.InfoListener, NsdManager.ServiceInfoCallback>()

    override fun registerService(info: NsdRecord, listener: NsdClient.RegistrationListener) {
        val wrapped = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) = listener.onServiceRegistered()
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) =
                listener.onRegistrationFailed(errorCode)
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = listener.onServiceUnregistered()
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) =
                listener.onUnregistrationFailed(errorCode)
        }
        synchronized(this) { registration[listener] = wrapped }
        manager.registerService(info.toServiceInfo(), NsdManager.PROTOCOL_DNS_SD, wrapped)
    }

    override fun unregisterService(listener: NsdClient.RegistrationListener) {
        manager.unregisterService(synchronized(this) { registration.remove(listener) } ?: return)
    }

    override fun discoverServices(serviceType: String, listener: NsdClient.BrowseListener) {
        val wrapped = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) =
                listener.onStartDiscoveryFailed(serviceType, errorCode)
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) =
                listener.onStopDiscoveryFailed(serviceType, errorCode)
            override fun onDiscoveryStarted(serviceType: String) = listener.onDiscoveryStarted(serviceType)
            override fun onDiscoveryStopped(serviceType: String) = listener.onDiscoveryStopped(serviceType)
            override fun onServiceFound(serviceInfo: NsdServiceInfo) = listener.onServiceFound(serviceInfo.toRecord())
            override fun onServiceLost(serviceInfo: NsdServiceInfo) = listener.onServiceLost(serviceInfo.toRecord())
        }
        synchronized(this) { discovery[listener] = wrapped }
        manager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, wrapped)
    }

    override fun stopServiceDiscovery(listener: NsdClient.BrowseListener) {
        manager.stopServiceDiscovery(synchronized(this) { discovery.remove(listener) } ?: return)
    }

    override fun resolveService(info: NsdRecord, listener: NsdClient.ResolveListener) {
        manager.resolveService(info.toServiceInfo(), object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) =
                listener.onResolveFailed(serviceInfo.toRecord(), errorCode)
            override fun onServiceResolved(serviceInfo: NsdServiceInfo) =
                listener.onServiceResolved(serviceInfo.toRecord())
        })
    }

    @SuppressLint("NewApi")
    override fun registerServiceInfoCallback(
        info: NsdRecord,
        executor: Executor,
        listener: NsdClient.InfoListener,
    ) {
        val wrapped = object : NsdManager.ServiceInfoCallback {
            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) =
                listener.onServiceInfoCallbackRegistrationFailed(errorCode)
            override fun onServiceUpdated(serviceInfo: NsdServiceInfo) =
                listener.onServiceUpdated(serviceInfo.toRecord())
            override fun onServiceLost() = listener.onServiceLost()
            override fun onServiceInfoCallbackUnregistered() = listener.onServiceInfoCallbackUnregistered()
        }
        synchronized(this) { infoListeners[listener] = wrapped }
        manager.registerServiceInfoCallback(info.toServiceInfo(), executor, wrapped)
    }

    @SuppressLint("NewApi")
    override fun unregisterServiceInfoCallback(listener: NsdClient.InfoListener) {
        manager.unregisterServiceInfoCallback(synchronized(this) { infoListeners.remove(listener) } ?: return)
    }
}

private fun NsdServiceInfo.toRecord(): NsdRecord {
    val hosts = if (Build.VERSION.SDK_INT >= 34) {
        runCatching {
            @SuppressLint("NewApi")
            hostAddresses?.toList().orEmpty()
        }.getOrDefault(emptyList())
    } else {
        emptyList()
    }
    @Suppress("DEPRECATION")
    val resolvedHost = runCatching { host }.getOrNull()
    val attrs = runCatching { attributes?.mapValues { (_, value) -> value } ?: emptyMap() }.getOrDefault(emptyMap())
    return NsdRecord(
        serviceName = serviceName.orEmpty(),
        serviceType = serviceType.orEmpty(),
        port = port,
        host = resolvedHost,
        hostAddresses = hosts,
        attributes = attrs,
        platformInfo = this,
    )
}

private fun NsdRecord.toServiceInfo(): NsdServiceInfo = platformInfo ?: NsdServiceInfo().apply {
    serviceName = this@toServiceInfo.serviceName
    serviceType = this@toServiceInfo.serviceType
    port = this@toServiceInfo.port
    // Qualified: inside apply, a bare `attributes` is the new, empty NsdServiceInfo's map.
    this@toServiceInfo.attributes.forEach { (key, value) -> setAttribute(key, value.toString(Charsets.UTF_8)) }
}
