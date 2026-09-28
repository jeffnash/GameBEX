package com.gamebex.client.together.session.fakes

import com.gamebex.client.data.local.GameEntity
import com.gamebex.client.launch.EmuRouter
import com.gamebex.client.together.Advertisement
import com.gamebex.client.together.CoreBuild
import com.gamebex.client.together.CoreFile
import com.gamebex.client.together.CoreResult
import com.gamebex.client.together.Discovery
import com.gamebex.client.together.DownloadResult
import com.gamebex.client.together.DownloadState
import com.gamebex.client.together.GameChoice
import com.gamebex.client.together.HostAd
import com.gamebex.client.together.Link
import com.gamebex.client.together.LinkEvent
import com.gamebex.client.together.LinkListener
import com.gamebex.client.together.LinkMsg
import com.gamebex.client.together.LinkTransport
import com.gamebex.client.together.LiveProbes
import com.gamebex.client.together.MatchResult
import com.gamebex.client.together.NearbyHost
import com.gamebex.client.together.RaRoom
import com.gamebex.client.together.Readiness
import com.gamebex.client.together.TogetherAlert
import com.gamebex.client.together.TogetherEmulators
import com.gamebex.client.together.TogetherLaunch
import com.gamebex.client.together.TogetherLibrary
import com.gamebex.client.together.TogetherMode
import com.gamebex.client.together.TogetherPlatform
import com.gamebex.client.together.OfferedGame
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

class EngineTestTransport : LinkTransport {
    lateinit var listener: EngineTestListener
        private set
    var peer: Link? = null
        private set

    override suspend fun listen(preferredPort: Int): LinkListener = EngineTestListener().also { listener = it }

    override suspend fun connect(address: String, port: Int, timeoutMs: Long): Link {
        val (guest, host) = engineTestLinkPair()
        peer = host
        return guest
    }
}

class EngineTestListener(override val port: Int = 55_440) : LinkListener {
    private val accepted = Channel<Link>(Channel.UNLIMITED)
    private val closed = AtomicBoolean(false)

    override suspend fun accept(): Link {
        if (closed.get()) throw IOException("listener closed")
        return accepted.receiveCatching().getOrNull() ?: throw IOException("listener closed")
    }

    fun offer(link: Link) {
        accepted.trySend(link)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) accepted.close()
    }
}

private class EngineTestLink(
    override val remoteAddress: String,
    private val incoming: Channel<LinkEvent>,
    private val outgoing: Channel<LinkEvent>,
    private val closed: AtomicBoolean,
) : Link {
    override val events: ReceiveChannel<LinkEvent> = incoming

    override suspend fun send(msg: LinkMsg) {
        if (closed.get()) throw IOException("link closed")
        outgoing.send(LinkEvent.Message(msg))
    }

    override suspend fun sendBlob(blobId: Int, file: File, onProgress: (sent: Long) -> Unit) {
        withContext(Dispatchers.IO) {
            file.inputStream().use { input ->
                val buffer = ByteArray(256 * 1024)
                var sent = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (closed.get()) throw IOException("link closed")
                    val chunk = buffer.copyOf(count)
                    outgoing.send(LinkEvent.Blob(blobId, chunk))
                    sent += count
                    onProgress(sent)
                }
            }
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            incoming.trySend(LinkEvent.Closed(null))
            outgoing.trySend(LinkEvent.Closed(null))
            incoming.close()
            outgoing.close()
        }
    }
}

fun engineTestLinkPair(): Pair<Link, Link> {
    val leftEvents = Channel<LinkEvent>(Channel.UNLIMITED)
    val rightEvents = Channel<LinkEvent>(Channel.UNLIMITED)
    val closed = AtomicBoolean(false)
    return EngineTestLink("10.42.1.2", leftEvents, rightEvents, closed) to
        EngineTestLink("10.42.1.3", rightEvents, leftEvents, closed)
}

class EngineTestProbes : LiveProbes {
    override suspend fun raRoom(address: String, timeoutMs: Long): RaRoom? = null
    override suspend fun tcpOpen(address: String, port: Int, timeoutMs: Long): Boolean = false
}

class EngineTestEmulators(val events: MutableList<String> = mutableListOf()) : TogetherEmulators {
    var endSessionCount = 0
    val readinessObserved = CompletableDeferred<Unit>()
    val endSessionObserved = CompletableDeferred<Unit>()
    override suspend fun readiness(mode: TogetherMode, role: com.gamebex.client.together.Role, game: GameEntity, core: String?): Readiness {
        events += "readiness"
        readinessObserved.complete(Unit)
        return Readiness.Ready
    }
    override suspend fun installedCore(core: String): CoreFile? = null
    override suspend fun fetchAndInstallCore(core: String, game: GameEntity, onProgress: (read: Long, total: Long) -> Unit): CoreResult = CoreResult.Started
    override fun receivedCoreFile(build: CoreBuild): File = File("/tmp/${build.core}.zip")
    override suspend fun hasReceivedCore(build: CoreBuild): Boolean = false
    override suspend fun installReceivedCore(file: File, build: CoreBuild, game: GameEntity): CoreResult = CoreResult.Started
    override suspend fun launch(req: TogetherLaunch): EmuRouter.Outcome = EmuRouter.Outcome.Launched("RetroArch", "com.retroarch.aarch64")
    override suspend fun endSession() {
        endSessionCount += 1
        events += "endSession"
        endSessionObserved.complete(Unit)
    }
}

class EngineTestLibrary(var localGame: GameEntity? = null, var offeredGame: OfferedGame? = null) : TogetherLibrary {
    override suspend fun game(gameId: Long): GameEntity? = localGame?.takeIf { it.id == gameId }
    override suspend fun offer(gameId: Long): OfferedGame? = offeredGame
    override suspend fun match(offer: OfferedGame, mode: TogetherMode): MatchResult = MatchResult.Missing(false)
    override suspend fun gbChoices(): List<GameChoice> = emptyList()
    override suspend fun sourceName(offer: OfferedGame): String? = null
    override suspend fun download(offer: OfferedGame): DownloadResult = DownloadResult.Failed("not used")
    override fun downloadState(jobId: Long, offer: OfferedGame, knownIds: Set<Long>): Flow<DownloadState> = kotlinx.coroutines.flow.emptyFlow()
}

class EngineTestDiscovery : Discovery {
    override val found = MutableStateFlow<List<NearbyHost>>(emptyList())
    val advertised = mutableListOf<HostAd>()
    var browseStarts = 0
    var browseStops = 0
    override fun startBrowsing() { browseStarts += 1 }
    override fun stopBrowsing() { browseStops += 1 }
    override fun advertise(ad: HostAd): Advertisement {
        advertised += ad
        return Advertisement { }
    }
}

class EngineTestPlatform : TogetherPlatform {
    override val deviceId: String = "local-device"
    override val foreground = MutableStateFlow(true)
    val alerts = mutableListOf<TogetherAlert>()
    var serviceStarts = 0
    var serviceStops = 0
    val cleanupSignals = Channel<Unit>(Channel.UNLIMITED)
    /** Saved custom name, sanitized as TogetherPlatformImpl's prefs do. */
    var custom: String? = null
    override fun playerName(): String = custom ?: "Local"
    override fun customName(): String? = custom
    override fun setCustomName(name: String?) { custom = com.gamebex.client.together.platform.TogetherPrefs.sanitizeCustomName(name) }
    var address: String? = "10.42.1.10"
    override fun lanAddress(): String? = address
    override fun appVersion(): String = "1.0"
    override fun packageVersion(pkg: String): String? = null
    override fun startService(ongoingText: String) { serviceStarts += 1 }
    override fun updateService(ongoingText: String) = Unit
    override fun stopService() { serviceStops += 1 }
    override fun alert(alert: TogetherAlert) { alerts += alert }
    override fun cancelAlerts() { cleanupSignals.trySend(Unit) }
}
