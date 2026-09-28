package com.gamebex.client.together.session.fakes

import com.gamebex.client.data.local.GameEntity
import com.gamebex.client.launch.EmuRouter
import com.gamebex.client.together.CoreBuild
import com.gamebex.client.together.CoreFile
import com.gamebex.client.together.CoreResult
import com.gamebex.client.together.DownloadResult
import com.gamebex.client.together.DownloadState
import com.gamebex.client.together.GameChoice
import com.gamebex.client.together.Link
import com.gamebex.client.together.LinkEvent
import com.gamebex.client.together.LinkMsg
import com.gamebex.client.together.LinkTransport
import com.gamebex.client.together.LiveProbes
import com.gamebex.client.together.MatchResult
import com.gamebex.client.together.NearbyHost
import com.gamebex.client.together.RaRoom
import com.gamebex.client.together.Readiness
import com.gamebex.client.together.Role
import com.gamebex.client.together.TogetherAlert
import com.gamebex.client.together.TogetherEmulators
import com.gamebex.client.together.TogetherLaunch
import com.gamebex.client.together.TogetherLibrary
import com.gamebex.client.together.TogetherMode
import com.gamebex.client.together.TogetherPlatform
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow

class GuestTestTransport : LinkTransport {
    lateinit var pair: GuestLinkPair
        private set
    val connected = CompletableDeferred<GuestLinkPair>()
    var failConnect: java.io.IOException? = null
    var remoteAddress: String = "10.42.1.20"

    override suspend fun listen(preferredPort: Int): com.gamebex.client.together.LinkListener = error("guest does not listen")

    override suspend fun connect(address: String, port: Int, timeoutMs: Long): Link {
        failConnect?.let { throw it }
        val next = GuestLinkPair(remoteAddress)
        pair = next
        if (!connected.isCompleted) connected.complete(next)
        return next.guest
    }
}

class GuestLinkPair(remoteAddress: String = "10.42.1.20") {
    private val guestEvents = Channel<LinkEvent>(Channel.UNLIMITED)
    private val hostEvents = Channel<LinkEvent>(Channel.UNLIMITED)
    private val closed = AtomicBoolean(false)
    val guest = GuestTestLink(remoteAddress, guestEvents, hostEvents, closed)
    val host = GuestTestLink("10.42.1.21", hostEvents, guestEvents, closed)
}

class GuestTestLink(
    override val remoteAddress: String,
    private val incoming: Channel<LinkEvent>,
    private val outgoing: Channel<LinkEvent>,
    private val closed: AtomicBoolean,
) : Link {
    override val events: ReceiveChannel<LinkEvent> = incoming
    val sent = mutableListOf<LinkMsg>()
    val sentBlobs = mutableListOf<Int>()
    var failNextSend = false

    override suspend fun send(msg: LinkMsg) {
        if (closed.get()) throw IOException("link closed")
        if (failNextSend) {
            failNextSend = false
            throw IOException("send failed")
        }
        sent += msg
        outgoing.send(LinkEvent.Message(msg))
    }

    override suspend fun sendBlob(blobId: Int, file: File, onProgress: (sent: Long) -> Unit) {
        if (closed.get()) throw IOException("link closed")
        sentBlobs += blobId
        val bytes = file.readBytes()
        onProgress(bytes.size.toLong())
        outgoing.send(LinkEvent.Blob(blobId, bytes))
    }

    fun emit(event: LinkEvent) {
        incoming.trySend(event)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            incoming.trySend(LinkEvent.Closed(null))
            outgoing.trySend(LinkEvent.Closed(null))
        }
    }
}

class GuestTestProbes : LiveProbes {
    var room: RaRoom? = null
    var tcp = false
    var raCalls = 0
    var tcpCalls = 0
    val tcpPorts = mutableListOf<Int>()
    override suspend fun raRoom(address: String, timeoutMs: Long): RaRoom? {
        raCalls += 1
        return room
    }
    override suspend fun tcpOpen(address: String, port: Int, timeoutMs: Long): Boolean {
        tcpCalls += 1
        tcpPorts += port
        return tcp
    }
}

class GuestTestEmulators : TogetherEmulators {
    var readinessValue: Readiness = Readiness.Ready
    var installed: CoreFile? = null
    var received = false
    var installResult: CoreResult = CoreResult.Started
    var fetchResult: CoreResult = CoreResult.Started
    var fetchGate: CompletableDeferred<Unit>? = null
    var coreDestination: File = File.createTempFile("guest-core", ".so").also { it.delete() }
    val launches = mutableListOf<TogetherLaunch>()
    val launchStarted = Channel<TogetherLaunch>(Channel.UNLIMITED)
    val installCalls = mutableListOf<Triple<File, CoreBuild, GameEntity>>()
    val fetchCalls = mutableListOf<Pair<String, GameEntity>>()
    val receivedCoreNames = mutableListOf<String>()
    val trace = mutableListOf<String>()
    var throwOnEnd = false
    val launchGates = ArrayDeque<CompletableDeferred<Unit>>()
    val launchOutcomes = ArrayDeque<EmuRouter.Outcome>()
    var endSessionCount = 0

    override suspend fun readiness(mode: TogetherMode, role: Role, game: GameEntity, core: String?): Readiness = readinessValue
    override suspend fun installedCore(core: String): CoreFile? = installed
    override suspend fun fetchAndInstallCore(core: String, game: GameEntity, onProgress: (read: Long, total: Long) -> Unit): CoreResult {
        fetchCalls += core to game
        onProgress(1, 1)
        fetchGate?.await()
        return fetchResult
    }
    override fun receivedCoreFile(build: CoreBuild): File {
        receivedCoreNames += build.core
        return coreDestination
    }
    override suspend fun hasReceivedCore(build: CoreBuild): Boolean = received
    override suspend fun installReceivedCore(file: File, build: CoreBuild, game: GameEntity): CoreResult {
        installCalls += Triple(file, build, game)
        return installResult
    }
    override suspend fun launch(req: TogetherLaunch): EmuRouter.Outcome {
        launches += req
        launchStarted.trySend(req)
        val outcome = launchOutcomes.removeFirstOrNull() ?: EmuRouter.Outcome.Launched("emu", "pkg")
        trace += "write-start"
        val gate = launchGates.removeFirstOrNull()
        if (gate != null) withContext(NonCancellable) { gate.await() }
        trace += "write-done"
        return outcome
    }
    override suspend fun endSession() {
        trace += "endSession"
        endSessionCount += 1
        if (throwOnEnd) throw IllegalStateException("end failed")
    }
}

class GuestTestLibrary(
    var gameValue: GameEntity? = guestGame(),
    var offeredValue: com.gamebex.client.together.OfferedGame? = guestOffer(),
) : TogetherLibrary {
    var matchValue: MatchResult = MatchResult.Found(1)
    var choicesValue: List<GameChoice> = emptyList()
    var sourceValue: String? = null
    var downloadValue: DownloadResult = DownloadResult.Failed("unused")
    val downloadStates = Channel<DownloadState>(Channel.UNLIMITED)
    var downloadCall: com.gamebex.client.together.OfferedGame? = null
    var downloadStateJob: Long? = null
    var downloadStateKnownIds: Set<Long>? = null

    override suspend fun game(gameId: Long): GameEntity? = gameValue?.takeIf { it.id == gameId }
    override suspend fun offer(gameId: Long): com.gamebex.client.together.OfferedGame? = offeredValue
    override suspend fun match(offer: com.gamebex.client.together.OfferedGame, mode: TogetherMode): MatchResult = matchValue
    override suspend fun gbChoices(): List<GameChoice> = choicesValue
    override suspend fun sourceName(offer: com.gamebex.client.together.OfferedGame): String? = sourceValue
    override suspend fun download(offer: com.gamebex.client.together.OfferedGame): DownloadResult {
        downloadCall = offer
        return downloadValue
    }
    override fun downloadState(jobId: Long, offer: com.gamebex.client.together.OfferedGame, knownIds: Set<Long>): Flow<DownloadState> = flow {
        downloadStateJob = jobId
        downloadStateKnownIds = knownIds
        for (state in downloadStates) emit(state)
    }
}

class GuestTestPlatform : TogetherPlatform {
    override val deviceId: String = "guest-device"
    private val foregroundState = MutableStateFlow(true)

    /** Runs once, on the engine's next foreground read, then clears itself. */
    val onForegroundRead = java.util.concurrent.atomic.AtomicReference<(() -> Unit)?>(null)
    override val foreground: MutableStateFlow<Boolean>
        get() {
            onForegroundRead.getAndSet(null)?.invoke()
            return foregroundState
        }
    val alerts = mutableListOf<TogetherAlert>()
    var startServiceCount = 0
    var stopServiceCount = 0
    var cancelAlertsCount = 0
    var failStartService = false
    var name = "Guest"
    override fun playerName(): String = name
    override fun lanAddress(): String? = "10.42.1.22"
    override fun appVersion(): String = "1.0"
    override fun packageVersion(pkg: String): String? = "1.0"
    override fun startService(ongoingText: String) {
        startServiceCount += 1
        if (failStartService) {
            failStartService = false
            throw IllegalStateException("boom")
        }
    }
    override fun updateService(ongoingText: String) = Unit
    override fun stopService() { stopServiceCount += 1 }
    override fun alert(alert: TogetherAlert) { alerts += alert }
    override fun cancelAlerts() { cancelAlertsCount += 1 }
}

fun guestGame(
    id: Long = 1,
    title: String = "Sample game",
    folder: String = "psp",
    coreOverride: String? = null,
    sourceRefJson: String? = null,
) = GameEntity(
    id = id,
    title = title,
    folder = folder,
    volumeId = "volume",
    relPath = "sample.iso",
    fileName = "sample.iso",
    sizeBytes = 1,
    mtimeMs = 1,
    ext = "iso",
    coreOverride = coreOverride,
    sourceRefJson = sourceRefJson,
)

fun guestOffer(
    title: String = "Sample game",
    folder: String = "psp",
    core: String? = null,
    sourceRefJson: String? = null,
) = com.gamebex.client.together.OfferedGame(
    title = title,
    folder = folder,
    fileName = "sample.iso",
    fileSize = 1,
    core = core,
    sourceRefJson = sourceRefJson,
)
