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
import com.gamebex.client.together.MatchResult
import com.gamebex.client.together.OfferedGame
import com.gamebex.client.together.RaRoom
import com.gamebex.client.together.Readiness
import com.gamebex.client.together.Role
import com.gamebex.client.together.TogetherAlert
import com.gamebex.client.together.TogetherEmulators
import com.gamebex.client.together.TogetherLaunch
import com.gamebex.client.together.TogetherLibrary
import com.gamebex.client.together.TogetherMode
import com.gamebex.client.together.TogetherPlatform
import com.gamebex.client.together.TogetherTiming
import kotlinx.coroutines.CompletableDeferred
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

class FakeLinkTransport : LinkTransport {
    lateinit var listener: FakeLinkListener
    var nextPort: Int = 55440

    override suspend fun listen(preferredPort: Int): LinkListener {
        listener = FakeLinkListener(nextPort)
        return listener
    }

    override suspend fun connect(address: String, port: Int, timeoutMs: Long): Link {
        error("FakeLinkTransport.connect is guest-owned")
    }

    suspend fun pair(remoteAddress: String = "10.42.1.21"): LinkPair {
        val pair = LinkPair(remoteAddress)
        listener.offer(pair.host)
        return pair
    }
}

class FakeLinkListener(override val port: Int) : LinkListener {
    private val accepted = Channel<Link>(Channel.UNLIMITED)
    var closeCount = 0
        private set

    suspend fun offer(link: Link) {
        accepted.send(link)
    }

    override suspend fun accept(): Link = accepted.receive()

    override fun close() {
        closeCount += 1
        accepted.close()
    }
}

class LinkPair(remoteAddress: String) {
    private val hostEvents = Channel<LinkEvent>(Channel.UNLIMITED)
    private val guestEvents = Channel<LinkEvent>(Channel.UNLIMITED)
    val host = FakeLink(remoteAddress, hostEvents, guestEvents)
    val guest = FakeLink("10.42.1.20", guestEvents, hostEvents)
}

class FakeLink(
    override val remoteAddress: String,
    private val incoming: Channel<LinkEvent>,
    private val outgoing: Channel<LinkEvent>,
) : Link {
    override val events = incoming
    val sent = mutableListOf<LinkMsg>()
    val sentBlobs = mutableListOf<Int>()
    private val closed = AtomicBoolean(false)

    override suspend fun send(msg: LinkMsg) {
        sent += msg
        if (!closed.get()) outgoing.send(LinkEvent.Message(msg))
    }

    override suspend fun sendBlob(blobId: Int, file: File, onProgress: (Long) -> Unit) {
        sentBlobs += blobId
        val bytes = file.readBytes()
        onProgress(bytes.size.toLong())
        if (!closed.get()) outgoing.send(LinkEvent.Blob(blobId, bytes))
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            incoming.trySend(LinkEvent.Closed(null))
            outgoing.trySend(LinkEvent.Closed(null))
        }
    }
}

class FakeDiscovery : Discovery {
    override val found = MutableStateFlow(emptyList<com.gamebex.client.together.NearbyHost>())
    val advertisements = mutableListOf<HostAd>()
    var stopCount = 0
        private set
    var failAdvertise = false

    override fun startBrowsing() = Unit
    override fun stopBrowsing() = Unit

    override fun advertise(ad: HostAd): Advertisement {
        advertisements += ad
        if (failAdvertise) {
            failAdvertise = false
            error("advertise failed")
        }
        return Advertisement { stopCount += 1 }
    }
}

class FakePlatform(
    var name: String = "Host",
    private val address: String? = "10.42.1.20",
) : TogetherPlatform {
    override val deviceId = "host-device"
    val foregroundState = MutableStateFlow(true)
    override val foreground: StateFlow<Boolean> = foregroundState
    var serviceEntered: CompletableDeferred<Unit>? = null
    var serviceRelease: CompletableDeferred<Unit>? = null
    var playingEntered: CompletableDeferred<Unit>? = null
    var lastUpdate: String? = null
    var startedText: String? = null
    var startServiceCount = 0
        private set
    var stopServiceCount = 0
        private set
    var cancelAlertsCount = 0
        private set
    val alerts = mutableListOf<TogetherAlert>()

    override fun playerName(): String = name
    override fun lanAddress(): String? = address
    override fun appVersion(): String = "1.0"
    override fun packageVersion(pkg: String): String? = "1.0"
    override fun startService(ongoingText: String) {
        startServiceCount += 1
        startedText = ongoingText
        serviceEntered?.complete(Unit)
        serviceRelease?.let { runBlocking { it.await() } }
    }
    override fun updateService(ongoingText: String) {
        lastUpdate = ongoingText
        if (ongoingText.startsWith("With ")) playingEntered?.complete(Unit)
    }
    override fun stopService() { stopServiceCount += 1 }
    override fun alert(alert: TogetherAlert) { alerts += alert }
    override fun cancelAlerts() { cancelAlertsCount += 1 }
}

class FakeLibrary(
    var gameValue: GameEntity? = sampleGame(),
    var offerValue: OfferedGame? = sampleOffer(),
) : TogetherLibrary {
    override suspend fun game(gameId: Long): GameEntity? = gameValue?.takeIf { it.id == gameId }
    override suspend fun offer(gameId: Long): OfferedGame? = offerValue
    override suspend fun match(offer: OfferedGame, mode: TogetherMode): MatchResult = MatchResult.Found(offerGameId)
    override suspend fun gbChoices(): List<GameChoice> = emptyList()
    override suspend fun sourceName(offer: OfferedGame): String? = null
    override suspend fun download(offer: OfferedGame): DownloadResult = DownloadResult.Failed("unused")
    override fun downloadState(jobId: Long, offer: OfferedGame, knownIds: Set<Long>) = flowOf<DownloadState>()

    private val offerGameId = 1L
}

class FakeEmulators : TogetherEmulators {
    var readinessValue: Readiness = Readiness.Ready
    var launchOutcomes: ArrayDeque<EmuRouter.Outcome> = ArrayDeque(listOf(EmuRouter.Outcome.Launched("emu", "pkg")))
    var endSessionCount = 0
        private set
    val launches = mutableListOf<TogetherLaunch>()
    var launchGate: CompletableDeferred<Unit>? = null
    var launchCompleted: CompletableDeferred<Unit>? = null
    var coreFile: CoreFile? = null
    var fetchResult: CoreResult = CoreResult.Started
    var fetchGate: CompletableDeferred<Unit>? = null

    override suspend fun readiness(mode: TogetherMode, role: Role, game: GameEntity, core: String?): Readiness = readinessValue
    override suspend fun installedCore(core: String): CoreFile? = coreFile
    override suspend fun fetchAndInstallCore(core: String, game: GameEntity, onProgress: (Long, Long) -> Unit): CoreResult {
        onProgress(1, 1)
        fetchGate?.await()
        return fetchResult
    }
    override fun receivedCoreFile(build: CoreBuild): File = error("unused")
    override suspend fun hasReceivedCore(build: CoreBuild): Boolean = false
    override suspend fun installReceivedCore(file: File, build: CoreBuild, game: GameEntity): CoreResult = CoreResult.Started
    override suspend fun launch(req: TogetherLaunch): EmuRouter.Outcome {
        launches += req
        launchGate?.await()
        val outcome = launchOutcomes.removeFirstOrNull() ?: EmuRouter.Outcome.Launched("emu", "pkg")
        launchCompleted?.complete(Unit)
        return outcome
    }
    override suspend fun endSession() { endSessionCount += 1 }
}

fun sampleGame() = GameEntity(
    id = 1,
    title = "Sample game",
    folder = "psp",
    volumeId = "volume",
    relPath = "sample.iso",
    fileName = "sample.iso",
    sizeBytes = 1,
    mtimeMs = 1,
    ext = "iso",
)

fun sampleOffer() = OfferedGame(
    title = "Sample game",
    folder = "psp",
    fileName = "sample.iso",
    fileSize = 1,
)
