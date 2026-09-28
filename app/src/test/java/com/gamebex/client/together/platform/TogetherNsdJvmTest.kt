package com.gamebex.client.together.platform

import com.gamebex.client.together.TOGETHER_PROTO
import com.gamebex.client.together.TogetherMode
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TogetherNsdJvmTest {
    @Test
    fun lateResolveWhileStillFound_isAccepted() {
        val fake = FakeNsdClient()
        val poster = ManualPoster()
        val nsd = browser(fake, poster, sdkInt = 26)
        nsd.startBrowsing()
        fake.discoveryListeners.last().onServiceFound(hostInfo())
        assertEquals(1, poster.size)
        poster.fireNext()
        fake.resolveListener!!.onServiceResolved(hostInfo())
        val host = nsd.found.value.single()
        assertEquals("Sam", host.name)
        assertEquals("10.42.1.20", host.address)
        assertEquals(55440, host.port)
    }

    @Test
    fun lateResolveAfterStop_isDropped() {
        val fake = FakeNsdClient()
        val poster = ManualPoster()
        val nsd = browser(fake, poster, sdkInt = 26)
        nsd.startBrowsing()
        fake.discoveryListeners.last().onServiceFound(hostInfo())
        poster.fireNext()
        nsd.stopBrowsing()
        fake.resolveListener!!.onServiceResolved(hostInfo())
        assertTrue(nsd.found.value.isEmpty())
    }

    @Test
    fun lateResolveAfterServiceLost_isDropped() {
        val fake = FakeNsdClient()
        val poster = ManualPoster()
        val nsd = browser(fake, poster, sdkInt = 26)
        nsd.startBrowsing()
        val listener = fake.discoveryListeners.last()
        listener.onServiceFound(hostInfo())
        poster.fireNext()
        listener.onServiceLost(hostInfo())
        fake.resolveListener!!.onServiceResolved(hostInfo())
        assertTrue(nsd.found.value.isEmpty())
    }

    @Test
    fun staleDiscoveryListenerAfterRestart_isIgnored() {
        val fake = FakeNsdClient()
        val nsd = browser(fake, ManualPoster(), sdkInt = 26)
        nsd.startBrowsing()
        val first = fake.discoveryListeners.single()
        nsd.stopBrowsing()
        nsd.startBrowsing()
        val second = fake.discoveryListeners.last()
        assertTrue(first !== second)
        first.onServiceFound(hostInfo())
        assertEquals(0, fake.resolveCount)
        assertTrue(nsd.found.value.isEmpty())
        second.onServiceFound(hostInfo())
        assertEquals(1, fake.resolveCount)
    }

    @Test
    fun staleInfoCallbackRemove_keepsNewerRegistration() {
        val fake = FakeNsdClient()
        val nsd = browser(fake, ManualPoster(), sdkInt = 34)
        nsd.startBrowsing()
        val listener = fake.discoveryListeners.single()
        listener.onServiceFound(hostInfo())
        val first = fake.infoCallbacks.single()
        first.onServiceInfoCallbackRegistrationFailed(0)
        listener.onServiceFound(hostInfo())
        val second = fake.infoCallbacks.last()
        assertTrue(first !== second)
        first.onServiceInfoCallbackUnregistered()
        first.onServiceUpdated(hostInfo())
        assertTrue(nsd.found.value.isEmpty())
        second.onServiceUpdated(hostInfo())
        assertEquals("Sam", nsd.found.value.single().name)
    }

    @Test
    fun onStartDiscoveryFailed_resetsStopping() {
        val fake = FakeNsdClient()
        fake.completeStop = false
        val nsd = browser(fake, ManualPoster(), sdkInt = 26)
        nsd.startBrowsing()
        val listener = fake.discoveryListeners.single()
        nsd.stopBrowsing()
        assertEquals(1, fake.discoveryListeners.size)
        listener.onStartDiscoveryFailed("_gamebex._tcp", 0)
        nsd.startBrowsing()
        assertEquals(2, fake.discoveryListeners.size)
    }
    @Test
    fun advertise_putsTheNameInTheTxtRecordAndServiceName() {
        val fake = FakeNsdClient()
        val nsd = browser(fake, ManualPoster(), sdkInt = 34)
        nsd.advertise(
            com.gamebex.client.together.HostAd("self-device", "abcdef01", "Sam's Deck", "Tetris", "gb", TogetherMode.GB_LINK, 55440),
        )
        val record = fake.registered.single()
        assertEquals("GameBEX Sam's Deck", record.serviceName)
        assertEquals("Sam's Deck", record.attributes.getValue("n").toString(Charsets.UTF_8))
    }

    @Test
    fun publicIpv4Service_isIgnored() {
        for (sdkInt in listOf(26, 34)) {
            val fake = FakeNsdClient()
            val poster = ManualPoster()
            val nsd = browser(fake, poster, sdkInt)
            nsd.startBrowsing()
            val listener = fake.discoveryListeners.single()
            val public = hostInfo("8.8.8.8")
            listener.onServiceFound(public)
            if (sdkInt >= 34) {
                fake.infoCallbacks.single().onServiceUpdated(public)
            } else {
                poster.fireNext()
                fake.resolveListener!!.onServiceResolved(public)
            }
            assertTrue("sdk $sdkInt listed ${nsd.found.value}", nsd.found.value.isEmpty())
        }
    }

    @Test
    fun privateAddressIsPickedOverAPublicOne() {
        val fake = FakeNsdClient()
        val nsd = browser(fake, ManualPoster(), sdkInt = 34)
        nsd.startBrowsing()
        fake.discoveryListeners.single().onServiceFound(hostInfo())
        val mixed = hostInfo().copy(
            hostAddresses = listOf(InetAddress.getByName("8.8.8.8"), InetAddress.getByName("10.42.1.20")),
        )
        fake.infoCallbacks.single().onServiceUpdated(mixed)
        assertEquals("10.42.1.20", nsd.found.value.single().address)
    }

    private fun browser(fake: FakeNsdClient, poster: ManualPoster, sdkInt: Int): TogetherNsdLogic =
        TogetherNsdLogic(
            nsd = fake,
            selfDeviceId = "self-device",
            filterSelf = false,
            postDelayed = poster::post,
            sdkInt = sdkInt,
        )

    private fun hostInfo(ip: String = "10.42.1.20"): NsdRecord {
        val address = InetAddress.getByName(ip) as Inet4Address
        fun text(value: String) = value.toByteArray(Charsets.UTF_8)
        return NsdRecord(
            serviceName = "GameBEX Sam",
            serviceType = "_gamebex._tcp",
            port = 55440,
            host = address,
            hostAddresses = listOf(address),
            attributes = mapOf(
                "v" to text(TOGETHER_PROTO.toString()),
                "id" to text("peer-device"),
                "sid" to text("abcdef01"),
                "n" to text("Sam"),
                "g" to text("Tetris"),
                "f" to text("gb"),
                "m" to text(TogetherMode.GB_LINK.name),
            ),
        )
    }
}

private class ManualPoster {
    private val tasks = ArrayDeque<() -> Unit>()
    val size: Int get() = tasks.size

    fun post(delayMs: Long, block: () -> Unit) {
        tasks.addLast(block)
    }

    fun fireNext() {
        tasks.removeFirst().invoke()
    }
}

private class FakeNsdClient : NsdClient {
    val discoveryListeners = mutableListOf<NsdClient.BrowseListener>()
    val infoCallbacks = mutableListOf<NsdClient.InfoListener>()
    var resolveListener: NsdClient.ResolveListener? = null
    var resolveCount: Int = 0
    var completeStop: Boolean = true
    private var serviceType: String = "_gamebex._tcp"

    override fun discoverServices(serviceType: String, listener: NsdClient.BrowseListener) {
        this.serviceType = serviceType
        discoveryListeners.add(listener)
        listener.onDiscoveryStarted(serviceType)
    }

    override fun stopServiceDiscovery(listener: NsdClient.BrowseListener) {
        if (completeStop) listener.onDiscoveryStopped(serviceType)
    }

    override fun resolveService(info: NsdRecord, listener: NsdClient.ResolveListener) {
        resolveCount++
        resolveListener = listener
    }

    override fun registerServiceInfoCallback(
        info: NsdRecord,
        executor: Executor,
        listener: NsdClient.InfoListener,
    ) {
        infoCallbacks.add(listener)
    }

    override fun unregisterServiceInfoCallback(listener: NsdClient.InfoListener) = Unit

    val registered = mutableListOf<NsdRecord>()

    override fun registerService(info: NsdRecord, listener: NsdClient.RegistrationListener) {
        registered += info
    }

    override fun unregisterService(listener: NsdClient.RegistrationListener) = Unit
}
