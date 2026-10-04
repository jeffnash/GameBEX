package com.gamebex.client.together.session

import android.util.Log
import com.gamebex.client.data.local.GameEntity
import com.gamebex.client.launch.CoreInstallProgress
import com.gamebex.client.launch.EmuRouter
import com.gamebex.client.together.ByeReason
import com.gamebex.client.together.CoreBuild
import com.gamebex.client.together.CoreResult
import com.gamebex.client.together.GuestPhase
import com.gamebex.client.together.GuestStep
import com.gamebex.client.together.GuestView
import com.gamebex.client.together.GameChoice
import com.gamebex.client.together.Link
import com.gamebex.client.together.LinkEvent
import com.gamebex.client.together.LinkMsg
import com.gamebex.client.together.LinkTransport
import com.gamebex.client.together.LiveProbes
import com.gamebex.client.together.MatchResult
import com.gamebex.client.together.NearbyHost
import com.gamebex.client.together.Problem
import com.gamebex.client.together.Readiness
import com.gamebex.client.together.Role
import com.gamebex.client.together.TogetherAction
import com.gamebex.client.together.TogetherAlert
import com.gamebex.client.together.TogetherCaps
import com.gamebex.client.together.TogetherCopy
import com.gamebex.client.together.TogetherEmulators
import com.gamebex.client.together.TogetherLaunch
import com.gamebex.client.together.TogetherLibrary
import com.gamebex.client.together.TogetherMode
import com.gamebex.client.together.TogetherPlatform
import com.gamebex.client.together.TogetherSupport
import com.gamebex.client.together.TogetherTiming
import com.gamebex.client.together.TOGETHER_PROTO
import com.gamebex.client.together.DownloadResult
import com.gamebex.client.together.DownloadState
import com.gamebex.client.together.net.JoinLink
import com.gamebex.client.stream.StreamEndpoint
import com.gamebex.client.stream.StreamToken
import com.gamebex.client.stream.proto.StreamWire
import com.gamebex.client.together.proto.BlobSink
import java.io.File
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.yield
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

private const val CONSUMER_FAILURE = "Something went wrong."
private const val CORE_INSTALL_STALL_MS = 8_000L
private const val CORE_INSTALL_CANCELLED = "Core install cancelled."

/** The guest half of the Play together session actor. */
internal class GuestEngine(
    private val transport: LinkTransport,
    private val probes: LiveProbes,
    private val emulators: TogetherEmulators,
    private val library: TogetherLibrary,
    private val platform: TogetherPlatform,
    private val timing: TogetherTiming,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val logSink: (String) -> Unit = { message -> Log.w("GameBEXTogether", message) },
) {
    private sealed interface Input {
        data class Join(val target: NearbyHost, val beforeStart: suspend () -> Unit) : Input
        data class Act(val action: TogetherAction) : Input
        data class Pick(val gameId: Long) : Input
        data class End(val token: Long, val done: CompletableDeferred<Unit>?) : Input
        data object Foreground : Input
        data class Connected(val epoch: Int, val link: Link, val token: Long) : Input
        data class ConnectFailed(val epoch: Int, val token: Long) : Input
        data class OfferTimeout(val epoch: Int, val token: Long) : Input
        data class LinkEvt(val link: Link, val event: LinkEvent, val token: Long) : Input
        data class ChainDone(val epoch: Int, val result: ChainResult) : Input
        data class DownloadStarted(val epoch: Int, val result: DownloadResult) : Input
        data class DownloadProgress(val epoch: Int, val percent: Int) : Input
        data class DownloadDone(val epoch: Int, val gameId: Long) : Input
        data class DownloadFailed(val epoch: Int, val text: String) : Input
        data class CoreFetch(
            val install: CoreInstall,
            val read: Long? = null,
            val total: Long = 0,
            val sequence: Long = 0,
            val result: CoreResult? = null,
        ) : Input
        data class CoreFetchFinished(val install: CoreInstall) : Input
        data class CoreFetchStalled(val install: CoreInstall, val generation: Long) : Input
        data class CorePrepared(
            val epoch: Int,
            val hasReceived: Boolean,
            val file: File?,
            val failed: Boolean,
        ) : Input
        data class CoreWriteDone(val epoch: Int, val blobId: Int, val failed: Boolean) : Input
        data class CoreFinished(val epoch: Int, val ok: Boolean) : Input
        data class CoreInstalled(val epoch: Int, val result: CoreResult) : Input
        data class ResumePrep(val epoch: Int, val token: Long) : Input
        data class CoreRequestTimeout(val epoch: Int, val generation: Long) : Input
        data class CoreInactivity(val epoch: Int, val generation: Long) : Input
        data class Countdown(val epoch: Int, val n: Int) : Input
        data class GuestLaunched(val epoch: Int, val round: Int, val outcome: EmuRouter.Outcome) : Input
        data class RoomSeen(val epoch: Int, val round: Int) : Input
        data class PspServerUp(val epoch: Int, val round: Int) : Input
    }

    private sealed interface ChainResult {
        val local: GameEntity?
        val coreMissing: Boolean
        val requestedCore: String?
        val clearPickedGameId: Boolean

        data class Ready(
            override val local: GameEntity,
            override val coreMissing: Boolean,
            override val clearPickedGameId: Boolean = false,
        ) : ChainResult {
            override val requestedCore: String? = null
        }

        data class Stop(
            val step: GuestStep,
            val phase: GuestPhase,
            val problem: Problem?,
            val choices: List<GameChoice> = emptyList(),
            val progress: Int? = null,
            override val local: GameEntity? = null,
            override val coreMissing: Boolean = false,
            override val requestedCore: String? = null,
            override val clearPickedGameId: Boolean = false,
        ) : ChainResult
    }

    private val inputs = Channel<Input>(Channel.UNLIMITED)
    private val _view = MutableStateFlow<GuestView?>(null)
    val view: StateFlow<GuestView?> = _view.asStateFlow()

    @Volatile
    private var myName: String = ""

    private val sessionJobs = Collections.synchronizedSet(mutableSetOf<Job>())
    private val stepJobs = Collections.synchronizedSet(mutableSetOf<Job>())
    private val sendMutex = Mutex()

    /** Guest states are sent from separate coroutines; only the newest may reach the host. */
    private val stateSeq = AtomicLong()
    private var sentStateSeq = 0L // guarded by sendMutex
    private val coreInstallMutex = Mutex()
    private var coreInstall: CoreInstall? = null

    private class CoreInstall(val epoch: Int, val token: Long, core: String) {
        var progress = CoreInstallProgress(core)
        val callbackSequence = AtomicLong()
        var progressSequence = 0L
        var stallGeneration = 0L
        var job: Job? = null
        var stallJob: Job? = null
    }

    private var epoch = 0
    @Volatile
    private var sessionEnded = true
    private val sessionToken = AtomicLong()
    private val viewLock = Any()
    @Volatile
    private var publishedToken = 0L
    private val criticalLaunches = CriticalLaunches()
    private var target: NearbyHost? = null
    private var link: Link? = null
    private var hostHello: LinkMsg.Hello? = null
    private var offer: LinkMsg.Offer? = null
    private var sessionCore: String? = null
    private var local: GameEntity? = null
    private var pickedGameId: Long? = null
    private var requestedCore: String? = null
    private var useAnyway = false
    private var coreMissing = false
    private var lastLaunchFailed = false
    private var sink: BlobSink? = null
    private var blobId: Int? = null
    private var coreWriteJob: Job? = null
    private var coreInactivityGeneration = 0L
    private var coreBlobPermits = Semaphore(1)
    private var coreBlobPermitHeld = false
    private var coreEndPending = false
    private var coreWriteActive = false
    private val coreWrites = ArrayDeque<ByteArray>()
    private var coreRequestTimer: Job? = null
    private var coreRequestGeneration = 0L
    private var coreInactivityTimer: Job? = null
    private var coreFinishJob: Job? = null
    private var readyOnce = false
    private var round = 0
    private var liveSignal = CompletableDeferred<Unit>()
    private var roomAlerted = 0
    private var linkOpen = false
    private var byeReceived = false
    private var lastProgress: Int? = null
    private var lastProgressAtNs = 0L
    private var lastCoreProgress: Int? = null
    private var lastCoreProgressAtNs = 0L
    private var resources: SessionResources? = null
 /** Validated dialed address, actor-owned; emulator config uses this, not Offer.hostAddress. */
    private var playAddress: String? = null
 /** STREAM: the host's last StreamReady; the viewer reopens with it. */
    private var streamReady: LinkMsg.StreamReady? = null
 /** STREAM: completed by the StreamReady of the round being launched. */
    private var streamSignal = CompletableDeferred<LinkMsg.StreamReady>()
    private val coreName = Regex("^[a-z0-9_]+$")

    init {
        refreshPlayerName()
        scope.launch {
            for (input in inputs) {
                try {
                    handle(input)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (t: Throwable) {
                    log(t)
                    _view.value = _view.value?.copy(message = CONSUMER_FAILURE)
                }
            }
        }
    }

 /** Refreshes the cached player name without doing Settings/network work on the caller. */
    internal fun refreshPlayerName(): Job = scope.launch {
            try {
                myName = withContext(ioDispatcher) { platform.playerName() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                log(t)
            }
        }

    fun join(target: NearbyHost) {
        prepareJoin(target)
        commitJoin(target)
    }

 /** Sets CONNECTING on the caller thread without entering the actor. */
    internal fun prepareJoin(target: NearbyHost) {
        val token = sessionToken.incrementAndGet()
        replaceView(token, GuestView(GuestStep.CONNECTING, target.name))
    }

 /** Starts the join after the other engine has finished ending. */
    internal fun commitJoin(target: NearbyHost) {
        post(Input.Join(target) {})
    }

    fun act(action: TogetherAction) {
        post(Input.Act(action))
    }

    fun pick(gameId: Long) {
        post(Input.Pick(gameId))
    }

    fun end() {
        post(Input.End(publishedToken(), null))
    }

 /** Completes only after this engine has run its ordered end cleanup. */
    internal suspend fun endAndAwait() {
        val done = CompletableDeferred<Unit>()
        inputs.send(Input.End(publishedToken(), done))
        done.await()
    }

    fun onForeground() {
        refreshPlayerName()
        post(Input.Foreground)
    }

    private suspend fun handle(input: Input) {
        when (input) {
            is Input.Join -> handleJoin(input)
            is Input.Act -> handleAct(input.action)
            is Input.Pick -> handlePick(input.gameId)
            is Input.End -> {
                try {
                    endInternal(null, sendBye = true, updateView = true, token = input.token)
                } finally {
                    input.done?.complete(Unit)
                }
            }
            Input.Foreground -> handleForeground()
            is Input.Connected -> handleConnected(input)
            is Input.ConnectFailed -> if (isCurrent(input.epoch) && owns(input.token)) {
                endInternal(TogetherCopy.cantReach(target?.name ?: TogetherCopy.otherPhone), sendBye = false, updateView = true, token = input.token)
            }
            is Input.OfferTimeout -> if (isCurrent(input.epoch) && owns(input.token) && _view.value?.step == GuestStep.WAITING_OFFER) {
                endInternal(TogetherCopy.cantReach(hostName()), sendBye = false, updateView = true, token = input.token)
            }
            is Input.LinkEvt -> handleLinkEvent(input)
            is Input.ChainDone -> handleChainDone(input)
            is Input.DownloadStarted -> handleDownloadStarted(input)
            is Input.DownloadProgress -> handleDownloadProgress(input)
            is Input.DownloadDone -> handleDownloadDone(input)
            is Input.DownloadFailed -> handleDownloadFailed(input)
            is Input.CoreFetch -> handleCoreFetch(input)
            is Input.CoreFetchFinished -> handleCoreFetchFinished(input.install)
            is Input.CoreFetchStalled -> handleCoreFetchStalled(input)
            is Input.CorePrepared -> handleCorePrepared(input)
            is Input.CoreWriteDone -> handleCoreWriteDone(input)
            is Input.CoreFinished -> handleCoreFinished(input)
            is Input.CoreInstalled -> handleCoreInstalled(input)
            is Input.ResumePrep -> if (isCurrent(input.epoch) && owns(input.token)) startPrep()
            is Input.CoreRequestTimeout -> if (isCurrent(input.epoch) && input.generation == coreRequestGeneration) handleCoreUnavailable()
            is Input.CoreInactivity -> if (isCurrent(input.epoch) && input.generation == coreInactivityGeneration) handleCoreFailure()
            is Input.Countdown -> if (isCurrent(input.epoch) && _view.value?.step == GuestStep.LAUNCHING) {
                _view.value = _view.value?.copy(countdown = input.n)
            }
            is Input.GuestLaunched -> handleGuestLaunched(input)
            is Input.RoomSeen -> handleRoomSeen(input)
            is Input.PspServerUp -> if (isCurrent(input.epoch) && input.round == round) {
                liveSignal.complete(Unit)
            }
        }
    }

    private suspend fun handleJoin(input: Input.Join) {
        val token = publishedToken()
        if (!sessionEnded) endInternal(null, sendBye = true, updateView = false, token = token)
        if (token != publishedToken()) {
            input.beforeStart()
            return
        }
        input.beforeStart()
        epoch += 1
        sessionEnded = false
        target = input.target
        link = null
        hostHello = null
        offer = null
        sessionCore = null
        local = null
        pickedGameId = null
        requestedCore = null
        useAnyway = false
        coreMissing = false
        lastLaunchFailed = false
        sink = null
        blobId = null
        coreBlobPermits = Semaphore(1)
        readyOnce = false
        round = 0
        roomAlerted = 0
        linkOpen = false
        byeReceived = false
        playAddress = null
        streamReady = null
        streamSignal = CompletableDeferred()
        resources = SessionResources()
        replaceView(token, GuestView(GuestStep.CONNECTING, input.target.name))
        val startedEpoch = epoch
        val sessionResources = resources ?: return
        launchStep {
            try {
                val connected = openOnIo<Link>({ mark ->
                    val created = transport.connect(input.target.address, input.target.port)
                    mark(created)
                    yield()
                    created
                }) { it.close() }
                val registered = sessionResources.register { connected.close() }
                if (!registered) {
                    connected.close()
                    return@launchStep
                }
                myName = withContext(ioDispatcher) { platform.playerName() }
                post(Input.Connected(startedEpoch, connected, token))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                log(t)
                post(Input.ConnectFailed(startedEpoch, token))
            }
        }
    }

    private suspend fun handleConnected(input: Input.Connected) {
        if (!isCurrent(input.epoch) || !owns(input.token) || _view.value?.step != GuestStep.CONNECTING) {
            runCatching { input.link.close() }
            return
        }
        link = input.link
        linkOpen = true
        startForwarder(input.link, input.token)
        writeIfToken(input.token) { it.copy(step = GuestStep.WAITING_OFFER, message = null) }
        val timerEpoch = epoch
        val playerName = myName
        val token = input.token
        launchStep {
            delay(timing.offerMs)
            post(Input.OfferTimeout(timerEpoch, token))
        }
        launchSession {
            try {
                val hello = withContext(ioDispatcher) {
                    LinkMsg.Hello(
                        proto = TOGETHER_PROTO,
                        role = Role.GUEST,
                        deviceId = platform.deviceId,
                        name = playerName,
                        appVersion = platform.appVersion(),
                        raVersion = platform.packageVersion("com.retroarch.aarch64"),
                        ppssppVersion = platform.packageVersion("org.ppsspp.ppsspp"),
                        streamProto = StreamWire.VERSION,
                    )
                }
                withContext(ioDispatcher) { input.link.send(hello) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                log(t)
            }
        }
    }

    private fun startForwarder(currentLink: Link, token: Long) {
        launchSession {
            try {
                for (event in currentLink.events) {
                    if (event is LinkEvent.Blob) coreBlobPermits.acquire()
                    val accepted = post(Input.LinkEvt(currentLink, event, token))
                    if (!accepted && event is LinkEvent.Blob) coreBlobPermits.release()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                log(t)
            }
        }
    }

    private suspend fun handleLinkEvent(input: Input.LinkEvt) {
        if (!owns(input.token) || input.link !== link || sessionEnded) {
            if (input.event is LinkEvent.Blob) {
                coreBlobPermitHeld = true
                releaseBlobPermit()
            }
            return
        }
        when (val event = input.event) {
            is LinkEvent.Message -> handleMessage(input.link, event.msg, input.token)
            is LinkEvent.Blob -> {
                coreBlobPermitHeld = true
                handleBlob(event.blobId, event.bytes)
            }
            is LinkEvent.Closed -> {
                linkOpen = false
                if (_view.value?.step == GuestStep.IN_GAME) {
                    writeIfToken(input.token) { it.copy(linkLost = true, message = TogetherCopy.linkLost(hostName())) }
                    if (!platform.foreground.value) platform.alert(TogetherAlert.LinkLost(hostName()))
                } else {
                    endInternal(TogetherCopy.linkLost(hostName()), sendBye = false, updateView = true, token = input.token)
                }
            }
        }
    }

    private suspend fun handleMessage(currentLink: Link, message: LinkMsg, token: Long) {
        when (message) {
            is LinkMsg.Hello -> {
                if (_view.value?.step == GuestStep.WAITING_OFFER && message.role == Role.HOST) {
                    if (message.proto != TOGETHER_PROTO ||
                        (target?.mode == TogetherMode.STREAM && message.streamProto != StreamWire.VERSION)
                    ) {
                        endInternal(TogetherCopy.byeText(ByeReason.VERSION, message.name), sendBye = true, updateView = true, token = token)
                        return
                    }
                    hostHello = message
                    _view.value = _view.value?.copy(hostName = message.name)
                }
            }
            is LinkMsg.Offer -> {
                if (_view.value?.step != GuestStep.WAITING_OFFER || hostHello == null) return
                if (message.mode == TogetherMode.STREAM && hostHello?.streamProto != StreamWire.VERSION) {
                    endInternal(TogetherCopy.byeText(ByeReason.VERSION, hostName()), sendBye = true, updateView = true, token = token)
                    return
                }
                if (!acceptedHostAddress(message.hostAddress, currentLink)) {
                    log("rejected offer hostAddress=${message.hostAddress}")
                    return
                }
                val offeredCore = message.game.core
                if (message.mode != TogetherMode.PSP_ADHOC && offeredCore != null && !acceptableCore(message.mode, offeredCore)) {
                    log("rejected offer core=$offeredCore")
                    return
                }
                offer = message
                playAddress = currentLink.remoteAddress
                sessionCore = when (message.mode) {
                    TogetherMode.RA_NETPLAY -> message.game.core
                    TogetherMode.GB_LINK -> "gambatte"
                    TogetherMode.PSP_ADHOC, TogetherMode.STREAM -> null
                }
                _view.value = _view.value?.copy(
                    hostName = hostName(),
                    gameTitle = message.game.title,
                    folder = message.game.folder,
                    mode = message.mode,
                    message = null,
                )
                platform.startService(TogetherCopy.ongoingConnected(hostName()))
                val registered = resources?.register { platform.stopService() } == true
                if (!registered) platform.stopService()
                startPrep()
            }
            is LinkMsg.Bye -> {
                byeReceived = true
                val name = hostName()
                val background = !platform.foreground.value
                endInternal(TogetherCopy.byeText(message.reason, name), sendBye = false, updateView = true, token = token)
                if (background) platform.alert(TogetherAlert.PeerLeft(name))
            }
            is LinkMsg.Launch -> handleLaunch(message)
            is LinkMsg.HostLive -> if (message.sessionId == offer?.sessionId && message.round == round) {
                liveSignal.complete(Unit)
            }
            is LinkMsg.CoreBegin -> handleCoreBegin(message)
            is LinkMsg.CoreEnd -> handleCoreEnd(message)
            is LinkMsg.CoreUnavailable -> if (message.sessionId == offer?.sessionId) handleCoreUnavailable()
            is LinkMsg.StreamReady -> handleStreamReady(message)
            is LinkMsg.Ping, is LinkMsg.Pong, is LinkMsg.GuestState, is LinkMsg.CoreRequest -> Unit
        }
    }

    private suspend fun handleLaunch(message: LinkMsg.Launch) {
        if (message.sessionId != offer?.sessionId) return
        val step = _view.value?.step ?: return
        val accepted = step == GuestStep.READY || step == GuestStep.LAUNCHING || step == GuestStep.IN_GAME ||
            (step == GuestStep.ACTION_NEEDED && lastLaunchFailed)
        if (!accepted) {
            log(IllegalStateException("Launch ignored in $step"))
            return
        }
        val selectedOffer = offer ?: return
        val selectedLocal = local ?: return
        val selectedAddress = playAddress ?: return
        epoch += 1
        cancelStepJobs()
        clearCoreTransfer()
        round = message.round
        lastLaunchFailed = false
        liveSignal = CompletableDeferred()
        streamSignal = CompletableDeferred()
        val signal = streamSignal
        val launchEpoch = epoch
        val launchHostName = hostName()
        val selectedPlayerName = myName
        val selectedCore = sessionCore
        val mode = selectedOffer.mode
 // A STREAM guest has nothing to count down to; it waits for the host's stream.
        val countdownS = if (mode == TogetherMode.STREAM) 0 else timing.countdownS
        _view.value = _view.value?.copy(
            step = GuestStep.LAUNCHING,
            phase = _view.value?.phase,
            problem = null,
            instructions = TogetherCopy.guestInstructions(mode, launchHostName, platform.raMenuButton()),
            countdown = if (countdownS > 0) countdownS else null,
            linkLost = false,
            message = null,
        )
        if (mode == TogetherMode.PSP_ADHOC) startPspWatcher(launchEpoch, message.round, selectedAddress)
        launchStep {
            var elapsedMs = 0L
            try {
                repeat(countdownS.coerceAtLeast(0)) { i ->
                    post(Input.Countdown(launchEpoch, countdownS - i))
                    delay(1_000L)
                    elapsedMs += 1_000L
                }
                var ready: LinkMsg.StreamReady? = null
                if (mode == TogetherMode.STREAM) {
                    ready = withTimeoutOrNull(timing.launchWaitMs) { signal.await() }
                    if (ready == null) {
                        post(Input.GuestLaunched(launchEpoch, message.round, EmuRouter.Outcome.Failed(TogetherCopy.streamNotReady)))
                        return@launchStep
                    }
                } else if (mode != TogetherMode.RA_NETPLAY) {
                    val remaining = (timing.launchWaitMs - elapsedMs).coerceAtLeast(0L)
                    withTimeoutOrNull(remaining) { liveSignal.await() }
                }
                if (!platform.foreground.value) {
                    platform.alert(TogetherAlert.TapToStart(launchHostName))
                    platform.foreground.first { it }
                }
                val marker = criticalLaunches.enter(currentCoroutineContext().job)
                try {
                    withContext(NonCancellable) {
                        val outcome = try {
                            withContext(ioDispatcher) {
                                emulators.launch(
                                    TogetherLaunch(
                                        sessionId = selectedOffer.sessionId,
                                        role = Role.GUEST,
                                        mode = mode,
                                        game = selectedLocal,
                                        core = selectedCore,
                                        hostAddress = selectedAddress,
                                        playerName = selectedPlayerName,
                                        stream = ready?.let { endpointFor(it, selectedAddress, launchHostName) },
                                    ),
                                )
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (t: Throwable) {
                            log(t)
                            EmuRouter.Outcome.Failed(t.message ?: TogetherCopy.noWifi)
                        }
                        post(Input.GuestLaunched(launchEpoch, message.round, outcome))
                    }
                } finally {
                    criticalLaunches.exit(marker)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                log(t)
                post(Input.GuestLaunched(launchEpoch, message.round, EmuRouter.Outcome.Failed(t.message ?: TogetherCopy.noWifi)))
            }
        }
    }

    private fun startPspWatcher(startedEpoch: Int, watcherRound: Int, address: String) {
        launchStep {
            val interval = timing.watchIntervalMs.coerceAtLeast(1L)
            var elapsed = 0L
            while (elapsed < timing.launchWaitMs) {
                val wait = minOf(interval, timing.launchWaitMs - elapsed)
                delay(wait)
                elapsed += wait
                val open = runCatching { withContext(ioDispatcher) { probes.tcpOpen(address, 27312, timing.probeTimeoutMs) } }
                    .onFailure(::log)
                    .getOrDefault(false)
                if (open) {
                    post(Input.PspServerUp(startedEpoch, watcherRound))
                    return@launchStep
                }
            }
        }
    }

    private fun startRaWatcher(startedEpoch: Int, watcherRound: Int, address: String) {
        launchStep {
            val interval = timing.watchIntervalMs.coerceAtLeast(1L)
            val deadline = TogetherCaps.LIVE_WATCH_S * 1_000L
            var elapsed = 0L
            while (elapsed < deadline) {
                val wait = minOf(interval, deadline - elapsed)
                delay(wait)
                elapsed += wait
                val room = runCatching { withContext(ioDispatcher) { probes.raRoom(address, timing.probeTimeoutMs) } }
                    .onFailure(::log)
                    .getOrNull()
                if (room != null) {
                    post(Input.RoomSeen(startedEpoch, watcherRound))
                    return@launchStep
                }
            }
        }
    }

    private suspend fun handleGuestLaunched(input: Input.GuestLaunched) {
        if (!isCurrent(input.epoch) || input.round != round || _view.value?.step != GuestStep.LAUNCHING) return
        when (input.outcome) {
            is EmuRouter.Outcome.Launched -> {
                _view.value = _view.value?.copy(
                    step = GuestStep.IN_GAME,
                    phase = GuestPhase.PLAYING,
                    problem = null,
                    progress = null,
                    countdown = null,
                    message = null,
                )
                sendState(GuestPhase.PLAYING)
                platform.updateService(TogetherCopy.ongoingPlaying(hostName(), offer?.game?.title ?: ""))
                if (offer?.mode == TogetherMode.RA_NETPLAY) {
                    startRaWatcher(input.epoch, input.round, playAddress ?: return)
                }
            }
            else -> {
                lastLaunchFailed = true
                val text = TogetherCopy.outcomeText(input.outcome)
                val problem = Problem(TogetherCopy.launchFailed(text), TogetherAction.RECHECK)
                _view.value = _view.value?.copy(
                    step = GuestStep.ACTION_NEEDED,
                    phase = GuestPhase.NEEDS_SETUP,
                    problem = problem,
                    progress = null,
                    countdown = null,
                    message = null,
                )
                sendState(GuestPhase.NEEDS_SETUP, text.take(120))
            }
        }
    }

    private suspend fun handleRoomSeen(input: Input.RoomSeen) {
        if (!isCurrent(input.epoch) || input.round != round || _view.value?.step != GuestStep.IN_GAME || roomAlerted == input.round) return
        roomAlerted = input.round
        platform.alert(TogetherAlert.RoomOpen(hostName(), offer?.mode ?: return))
        _view.value = _view.value?.copy(message = TogetherCopy.roomOpen(hostName()))
    }

    private fun handleStreamReady(message: LinkMsg.StreamReady) {
        if (message.sessionId != offer?.sessionId || message.round != round) return
        if (message.proto != StreamWire.VERSION || !StreamToken.isValid(message.token)) {
            log("rejected stream_ready proto=${message.proto}")
            return
        }
        streamReady = message
        streamSignal.complete(message)
    }

 /** The guest closed its viewer while the host still streams; open it again. */
    private fun reopenStream() {
        val ready = streamReady ?: return
        val selectedOffer = offer ?: return
        val selectedLocal = local ?: return
        val address = playAddress ?: return
        val name = hostName()
        val playerName = myName
        launchSession {
            val outcome = try {
                withContext(ioDispatcher) {
                    emulators.launch(
                        TogetherLaunch(
                            sessionId = selectedOffer.sessionId,
                            role = Role.GUEST,
                            mode = TogetherMode.STREAM,
                            game = selectedLocal,
                            core = null,
                            hostAddress = address,
                            playerName = playerName,
                            stream = endpointFor(ready, address, name),
                        ),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                log(t)
                EmuRouter.Outcome.Failed(t.message ?: TogetherCopy.noWifi)
            }
            if (outcome !is EmuRouter.Outcome.Launched) log("stream reopen failed: $outcome")
        }
    }

 /** The viewer connects to the dialed host address, never to one the host names. */
    private fun endpointFor(ready: LinkMsg.StreamReady, address: String, name: String) = StreamEndpoint(
        host = address,
        mediaPort = ready.mediaPort,
        inputPort = ready.inputPort,
        token = ready.token,
        width = ready.width,
        height = ready.height,
        audio = ready.audio,
        hostName = name,
        audioTimestampOrigin = ready.audioTimestampOrigin,
    )

 /** STREAM guests never run the game: a row-less stand-in carries its title and folder. */
    private fun streamStandIn(o: LinkMsg.Offer): GameEntity = GameEntity(
        id = 0,
        title = o.game.title,
        folder = o.game.folder,
        volumeId = "",
        relPath = "",
        fileName = o.game.fileName,
        sizeBytes = o.game.fileSize,
        mtimeMs = 0,
        ext = o.game.fileName.substringAfterLast('.', ""),
    )

    private suspend fun handleAct(action: TogetherAction) {
        val current = _view.value ?: return
        when (action) {
            TogetherAction.RECHECK -> {
                if (coreInstall == null && (current.step == GuestStep.ACTION_NEEDED || current.step == GuestStep.INSTALLING_CORE)) {
                    startPrep()
                }
            }
            TogetherAction.OPEN_SYSTEM, TogetherAction.OPEN_PPSSPP -> Unit
            TogetherAction.OPEN_RETROARCH -> if (current.step == GuestStep.ACTION_NEEDED && coreInstall == null) {
                val token = publishedToken()
                val opened = try {
                    platform.openRetroArch()
                } catch (t: Exception) {
                    if (t is CancellationException) throw t
                    log(t)
                    false
                }
                if (!opened) {
                    writeIfToken(token) { it.copy(message = "Couldn't open RetroArch. Open it from your launcher, then check again.") }
                }
            }
            TogetherAction.USE_ANYWAY -> if (current.step == GuestStep.ACTION_NEEDED && current.phase == GuestPhase.MISMATCH && offer?.mode == TogetherMode.PSP_ADHOC) {
                useAnyway = true
                startPrep()
            }
            TogetherAction.DOWNLOAD -> if (current.step == GuestStep.ACTION_NEEDED) startDownload()
            TogetherAction.INSTALL_CORE -> startCoreInstall()
            TogetherAction.COPY_CORE -> if (current.step == GuestStep.ACTION_NEEDED) startCoreCopy()
            TogetherAction.OPEN_STREAM -> if (current.step == GuestStep.IN_GAME && offer?.mode == TogetherMode.STREAM) reopenStream()
            TogetherAction.CANCEL_CORE_INSTALL -> cancelCoreInstall()
            TogetherAction.RETRY_CORE_INSTALL -> {
                if (coreInstall?.progress?.stalled == true ||
                    (current.step == GuestStep.ACTION_NEEDED && current.problem?.action == TogetherAction.RETRY_CORE_INSTALL)) {
                    cancelCoreInstall()
                    startCoreInstall()
                } else {
                    coreInstall?.let { if (isCurrentCoreInstall(it)) showCoreInstall(it) }
                }
            }
        }
    }

    private suspend fun handlePick(gameId: Long) {
        if (_view.value?.step != GuestStep.PICKING_GAME) return
        pickedGameId = gameId
        startPrep()
    }

    private suspend fun handleForeground() {
        val current = _view.value ?: return
        if (coreInstall != null) return
        when (current.step) {
            GuestStep.ACTION_NEEDED -> if (current.problem?.action != TogetherAction.RETRY_CORE_INSTALL) startPrep()
            GuestStep.INSTALLING_CORE -> scheduleResume()
            else -> Unit
        }
    }

    private fun scheduleResume(token: Long = publishedToken()) {
        if (coreInstall != null || !owns(token)) return
        epoch += 1
        cancelStepJobs()
        val scheduledEpoch = epoch
        launchStep {
            delay(timing.returnDelayMs)
            post(Input.ResumePrep(scheduledEpoch, token))
        }
    }

    private data class PrepSnapshot(
        val offer: LinkMsg.Offer,
        val mode: TogetherMode,
        val sessionCore: String?,
        val pickedGameId: Long?,
        val useAnyway: Boolean,
        val hostName: String,
    )

    private fun startPrep() {
        val selectedOffer = offer ?: return
        if (sessionEnded || link == null || coreInstall != null) return
        val snapshot = PrepSnapshot(selectedOffer, selectedOffer.mode, sessionCore, pickedGameId, useAnyway, hostName())
        epoch += 1
        cancelStepJobs()
        coreMissing = false
        requestedCore = null
        lastLaunchFailed = false
        val startedEpoch = epoch
        _view.value = _view.value?.copy(
            step = GuestStep.CHECKING,
            phase = GuestPhase.CHECKING,
            problem = null,
            progress = null,
            choices = emptyList(),
            message = null,
            coreInstall = null,
        )
        sendState(GuestPhase.CHECKING)
        launchStep {
            val result = try {
                withContext(ioDispatcher) { runPrepChain(snapshot) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                log(t)
                ChainResult.Stop(
                    step = GuestStep.ACTION_NEEDED,
                    phase = GuestPhase.NEEDS_SETUP,
                    problem = Problem(t.message ?: TogetherCopy.noWifi, TogetherAction.RECHECK),
                )
            }
            post(Input.ChainDone(startedEpoch, result))
        }
    }

    private fun sideloadAllowed(mode: TogetherMode, core: String): Boolean = when (mode) {
        TogetherMode.RA_NETPLAY -> core in TogetherCaps.RA_NETPLAY_CORES
        TogetherMode.GB_LINK -> core == "gambatte"
        TogetherMode.PSP_ADHOC, TogetherMode.STREAM -> false
    }

    private suspend fun runPrepChain(snapshot: PrepSnapshot): ChainResult {
        val selectedOffer = snapshot.offer
        val mode = snapshot.mode
 // A STREAM guest needs no game, emulator or core, only a phone that can watch.
        if (mode == TogetherMode.STREAM) {
            val stand = streamStandIn(selectedOffer)
            return when (val readiness = emulators.readiness(mode, Role.GUEST, stand, null)) {
                Readiness.Ready -> ChainResult.Ready(stand, coreMissing = false)
                else -> ChainResult.Stop(GuestStep.ACTION_NEEDED, GuestPhase.NEEDS_SETUP, TogetherCopy.problemFor(readiness), local = stand)
            }
        }
        val clearPick = booleanArrayOf(false)
        val foundLocal = findLocalGame(snapshot, clearPick)
        fun done(result: ChainResult): ChainResult {
            if (!clearPick[0] || result.clearPickedGameId) return result
            return when (result) {
                is ChainResult.Ready -> result.copy(clearPickedGameId = true)
                is ChainResult.Stop -> result.copy(clearPickedGameId = true)
            }
        }
        if (foundLocal is LocalResult.Stop) return done(foundLocal.result)
        val selectedLocal = (foundLocal as LocalResult.Found).game
        val readiness = emulators.readiness(mode, Role.GUEST, selectedLocal, snapshot.sessionCore)
        var missingCore = false
        var requiredCore: String? = null
        when (readiness) {
            Readiness.Ready -> Unit
            is Readiness.NeedsCore -> {
                if (mode == TogetherMode.RA_NETPLAY && selectedOffer.core?.core == readiness.core) {
                    missingCore = true
                    requiredCore = readiness.core
                } else {
                    return done(ChainResult.Stop(
                        GuestStep.ACTION_NEEDED,
                        GuestPhase.NEEDS_CORE,
                        Problem(TogetherCopy.needsCore(com.gamebex.client.data.CoreNames.display(readiness.core)), TogetherAction.INSTALL_CORE),
                        local = selectedLocal,
                        requestedCore = readiness.core,
                    ))
                }
            }
            is Readiness.NeedsEmulator -> return done(ChainResult.Stop(GuestStep.ACTION_NEEDED, GuestPhase.NEEDS_EMULATOR, TogetherCopy.problemFor(readiness), local = selectedLocal))
            else -> return done(ChainResult.Stop(GuestStep.ACTION_NEEDED, GuestPhase.NEEDS_SETUP, TogetherCopy.problemFor(readiness), local = selectedLocal))
        }
        if (mode == TogetherMode.RA_NETPLAY) {
            val hostBuild = selectedOffer.core
            if (hostBuild != null) {
                val expectedCore = snapshot.sessionCore ?: hostBuild.core
                val buildSafe = TogetherSupport.isSafeCoreBuild(hostBuild, expectedCore)
                if (!buildSafe) log(IllegalArgumentException("unsafe core build $expectedCore"))
                val safe = buildSafe && sideloadAllowed(mode, hostBuild.core)
                if (!safe) {
                    if (missingCore) {
                        return done(ChainResult.Stop(
                            GuestStep.ACTION_NEEDED,
                            GuestPhase.NEEDS_CORE,
                            Problem(TogetherCopy.needsCore(com.gamebex.client.data.CoreNames.display(expectedCore)), TogetherAction.INSTALL_CORE),
                            local = selectedLocal,
                            coreMissing = true,
                            requestedCore = requiredCore,
                        ))
                    }
                } else {
                    val installed = if (!missingCore) emulators.installedCore(hostBuild.core) else null
                    if (missingCore || installed?.build?.sha256 != hostBuild.sha256) {
                        val text = if (missingCore) {
                            TogetherCopy.needsCoreFromHost(snapshot.hostName, com.gamebex.client.data.CoreNames.display(hostBuild.core))
                        } else {
                            TogetherCopy.coreDiffers(snapshot.hostName, com.gamebex.client.data.CoreNames.display(hostBuild.core))
                        }
                        return done(ChainResult.Stop(
                            GuestStep.ACTION_NEEDED,
                            GuestPhase.CORE_SYNC,
                            Problem(text, TogetherAction.COPY_CORE),
                            local = selectedLocal,
                            coreMissing = missingCore,
                            requestedCore = requiredCore,
                        ))
                    }
                }
            }
        }
        return done(ChainResult.Ready(selectedLocal, missingCore))
    }

    private sealed interface LocalResult {
        data class Found(val game: GameEntity) : LocalResult
        data class Stop(val result: ChainResult.Stop) : LocalResult
    }

    private suspend fun findLocalGame(snapshot: PrepSnapshot, clearPick: BooleanArray): LocalResult {
        val selectedOffer = snapshot.offer
        val mode = snapshot.mode
        if (mode == TogetherMode.GB_LINK) {
            val selectedId = snapshot.pickedGameId
            if (selectedId != null) {
                val picked = library.game(selectedId)
                if (picked != null) return LocalResult.Found(picked)
                clearPick[0] = true
            }
            when (val match = library.match(selectedOffer.game, mode)) {
                is MatchResult.Found -> library.game(match.gameId)?.let { return LocalResult.Found(it) }
                else -> Unit
            }
            val choices = library.gbChoices()
            if (choices.isNotEmpty()) return LocalResult.Stop(ChainResult.Stop(GuestStep.PICKING_GAME, GuestPhase.PICKING_GAME, null, choices = choices))
            val source = if (selectedOffer.game.sourceRefJson != null) library.sourceName(selectedOffer.game) else null
            return if (source != null) {
                LocalResult.Stop(
                    ChainResult.Stop(
                        GuestStep.ACTION_NEEDED,
                        GuestPhase.NEEDS_GAME,
                        Problem(TogetherCopy.missingDownloadable(selectedOffer.game.title, source), TogetherAction.DOWNLOAD),
                    ),
                )
            } else {
                LocalResult.Stop(
                    ChainResult.Stop(
                        GuestStep.ACTION_NEEDED,
                        GuestPhase.NEEDS_GAME,
                        Problem(TogetherCopy.noGbGames, null),
                    ),
                )
            }
        }
        val match = snapshot.pickedGameId?.let { MatchResult.Found(it) } ?: library.match(selectedOffer.game, mode)
        val downloadable = selectedOffer.game.sourceRefJson != null && library.sourceName(selectedOffer.game) != null
        return when (match) {
            is MatchResult.Found -> {
                val game = library.game(match.gameId)
                if (game != null) LocalResult.Found(game) else LocalResult.Stop(
                    ChainResult.Stop(
                        GuestStep.ACTION_NEEDED,
                        GuestPhase.NEEDS_GAME,
                        if (downloadable) Problem(TogetherCopy.missingDownloadable(selectedOffer.game.title, library.sourceName(selectedOffer.game)!!), TogetherAction.DOWNLOAD)
                        else Problem(TogetherCopy.missingNotDownloadable(selectedOffer.game.title), null),
                    ),
                )
            }
            is MatchResult.Mismatch -> if (mode == TogetherMode.PSP_ADHOC && snapshot.useAnyway) {
                val game = library.game(match.gameId)
                if (game != null) LocalResult.Found(game) else LocalResult.Stop(ChainResult.Stop(GuestStep.ACTION_NEEDED, GuestPhase.NEEDS_GAME, Problem(TogetherCopy.missingNotDownloadable(selectedOffer.game.title), null)))
            } else if (mode == TogetherMode.PSP_ADHOC) {
                LocalResult.Stop(ChainResult.Stop(GuestStep.ACTION_NEEDED, GuestPhase.MISMATCH, Problem(TogetherCopy.mismatchPsp(selectedOffer.game.title, snapshot.hostName), TogetherAction.USE_ANYWAY)))
            } else {
                val source = if (downloadable) library.sourceName(selectedOffer.game) else null
                LocalResult.Stop(ChainResult.Stop(GuestStep.ACTION_NEEDED, GuestPhase.MISMATCH, Problem(TogetherCopy.mismatchRa(selectedOffer.game.title, snapshot.hostName), if (downloadable && source != null) TogetherAction.DOWNLOAD else null)))
            }
            is MatchResult.Missing -> if (match.downloadable) {
                val source = library.sourceName(selectedOffer.game)
                if (source != null) LocalResult.Stop(ChainResult.Stop(GuestStep.ACTION_NEEDED, GuestPhase.NEEDS_GAME, Problem(TogetherCopy.missingDownloadable(selectedOffer.game.title, source), TogetherAction.DOWNLOAD)))
                else LocalResult.Stop(ChainResult.Stop(GuestStep.ACTION_NEEDED, GuestPhase.NEEDS_GAME, Problem(TogetherCopy.missingNotDownloadable(selectedOffer.game.title), null)))
            } else LocalResult.Stop(ChainResult.Stop(GuestStep.ACTION_NEEDED, GuestPhase.NEEDS_GAME, Problem(TogetherCopy.missingNotDownloadable(selectedOffer.game.title), null)))
        }
    }

    private suspend fun handleChainDone(input: Input.ChainDone) {
        if (!isCurrent(input.epoch) || _view.value?.step != GuestStep.CHECKING) return
        val result = input.result
        local = result.local
        coreMissing = result.coreMissing
        requestedCore = result.requestedCore
        if (result.clearPickedGameId) pickedGameId = null
        when (result) {
            is ChainResult.Ready -> {
                readyOnce = true
                _view.value = _view.value?.copy(
                    step = GuestStep.READY,
                    phase = GuestPhase.READY,
                    problem = null,
                    progress = null,
                    choices = emptyList(),
                    message = null,
                )
                sendState(GuestPhase.READY)
            }
            is ChainResult.Stop -> {
                _view.value = _view.value?.copy(
                    step = result.step,
                    phase = result.phase,
                    problem = result.problem,
                    progress = result.progress,
                    choices = result.choices,
                    message = null,
                )
                sendState(result.phase, result.problem?.text?.takeIf { result.phase == GuestPhase.NEEDS_SETUP })
            }
        }
    }

    private suspend fun startDownload() {
        val selectedOffer = offer ?: return
        epoch += 1
        cancelStepJobs()
        val startedEpoch = epoch
        lastProgress = null
        lastProgressAtNs = 0L
        _view.value = _view.value?.copy(step = GuestStep.DOWNLOADING, phase = GuestPhase.DOWNLOADING, problem = null, progress = 0, message = null)
        sendState(GuestPhase.DOWNLOADING, progress = 0)
        launchStep {
            try {
                val result = withContext(ioDispatcher) { library.download(selectedOffer.game) }
                post(Input.DownloadStarted(startedEpoch, result))
                if (result is DownloadResult.Started) {
                    withContext(ioDispatcher) {
                        library.downloadState(result.jobId, selectedOffer.game, result.knownIds).collect { state ->
                            when (state) {
                                is DownloadState.Progress -> post(Input.DownloadProgress(startedEpoch, state.percent))
                                is DownloadState.Done -> post(Input.DownloadDone(startedEpoch, state.gameId))
                                is DownloadState.Failed -> post(Input.DownloadFailed(startedEpoch, state.text))
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                log(t)
                post(Input.DownloadFailed(startedEpoch, t.message ?: TogetherCopy.downloadFailed))
            }
        }
    }

    private suspend fun handleDownloadStarted(input: Input.DownloadStarted) {
        if (!isCurrent(input.epoch) || _view.value?.step != GuestStep.DOWNLOADING) return
        when (val result = input.result) {
            is DownloadResult.Started -> Unit
            is DownloadResult.Failed -> setActionNeeded(GuestPhase.NEEDS_GAME, Problem(result.text, TogetherAction.DOWNLOAD))
        }
    }

    private suspend fun handleDownloadProgress(input: Input.DownloadProgress) {
        if (!isCurrent(input.epoch) || _view.value?.step != GuestStep.DOWNLOADING) return
        _view.value = _view.value?.copy(progress = input.percent)
        val now = System.nanoTime()
        if (input.percent != lastProgress && (lastProgressAtNs == 0L || now - lastProgressAtNs >= timing.progressThrottleMs * 1_000_000L || input.percent >= 100)) {
            lastProgress = input.percent
            lastProgressAtNs = now
            sendState(GuestPhase.DOWNLOADING, progress = input.percent)
        }
    }

    private suspend fun handleDownloadDone(input: Input.DownloadDone) {
        if (!isCurrent(input.epoch)) return
        pickedGameId = input.gameId
        startPrep()
    }

    private suspend fun handleDownloadFailed(input: Input.DownloadFailed) {
        if (!isCurrent(input.epoch)) return
        setActionNeeded(GuestPhase.NEEDS_GAME, Problem(input.text, TogetherAction.DOWNLOAD))
    }

    private fun startCoreInstall() {
        if (!isCurrent()) return
        coreInstall?.let { install ->
            if (isCurrentCoreInstall(install)) {
                showCoreInstall(install)
                return
            }
        }
        val token = publishedToken()
        val current = _view.value ?: return
        if (current.step != GuestStep.ACTION_NEEDED ||
            current.problem?.action !in setOf(TogetherAction.INSTALL_CORE, TogetherAction.RETRY_CORE_INSTALL)) return
        val selectedLocal = local ?: return
        val core = requestedCore ?: sessionCore ?: return
        val mode = offer?.mode ?: return
        if (!coreName.matches(core) || !sideloadAllowed(mode, core)) {
            log("rejected core install $core")
            return
        }
        epoch += 1
        cancelStepJobs()
        if (!owns(token)) return
        val install = CoreInstall(epoch, token, core)
        coreInstall = install
        showCoreInstall(install)
        sendState(GuestPhase.INSTALLING_CORE, progress = 0)
        scheduleCoreInstallStall(install)
        install.job = launchStep {
            try {
                val result = withContext(ioDispatcher) {
                    coreInstallMutex.withLock {
                        currentCoroutineContext().ensureActive()
                        emulators.fetchAndInstallCore(core, selectedLocal) { read, total ->
                            post(Input.CoreFetch(install, read, total, install.callbackSequence.incrementAndGet()))
                        }
                    }
                }
                currentCoroutineContext().ensureActive()
                post(Input.CoreFetch(install, result = result))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                log(t)
                post(Input.CoreFetch(install, result = CoreResult.Failed(t.message ?: TogetherCopy.coreInstallBlocked)))
            } finally {
                post(Input.CoreFetchFinished(install))
            }
        }
        install.job?.invokeOnCompletion { post(Input.CoreFetchFinished(install)) }
    }

    private fun isCurrentCoreInstall(install: CoreInstall): Boolean {
        if (coreInstall !== install) return false
        if (isCurrent(install.epoch) && owns(install.token) && _view.value?.step == GuestStep.INSTALLING_CORE) return true
        releaseCoreInstall(install, cancel = true)
        return false
    }

    private fun releaseCoreInstall(install: CoreInstall, cancel: Boolean) {
        if (coreInstall !== install) return
        coreInstall = null
        install.stallJob?.cancel()
        install.stallJob = null
        if (cancel) install.job?.cancel()
    }

    private fun coreInstallPercent(progress: CoreInstallProgress): Int? = when {
        progress.installing -> null
        progress.total <= 0L -> 0
        else -> (progress.read.toDouble() / progress.total * 100).toInt().coerceIn(0, 100)
    }

    private fun showCoreInstall(install: CoreInstall) {
        val progress = install.progress
        val percent = coreInstallPercent(progress)
        writeIfToken(install.token) {
            it.copy(
                step = GuestStep.INSTALLING_CORE,
                phase = GuestPhase.INSTALLING_CORE,
                problem = null,
                choices = emptyList(),
                progress = percent,
                coreInstall = progress,
                message = when {
                    progress.stalled -> "Still working…"
                    progress.installing -> null
                    else -> TogetherCopy.fetchingCore(percent ?: 0)
                },
            )
        }
    }

    private fun scheduleCoreInstallStall(install: CoreInstall) {
        install.stallJob?.cancel()
        val generation = ++install.stallGeneration
        install.stallJob = launchStep {
            delay(CORE_INSTALL_STALL_MS)
            post(Input.CoreFetchStalled(install, generation))
        }
    }

    private fun handleCoreFetchStalled(input: Input.CoreFetchStalled) {
        val install = input.install
        if (!isCurrentCoreInstall(install) || input.generation != install.stallGeneration) return
        install.progress = install.progress.copy(stalled = true)
        showCoreInstall(install)
    }

    private suspend fun handleCoreFetch(input: Input.CoreFetch) {
        val install = input.install
        if (!isCurrentCoreInstall(install)) return
        input.read?.let { read ->
            if (input.sequence <= install.progressSequence) return
            install.progressSequence = input.sequence
            val progress = if (read == -1L && input.total == -1L) {
                install.progress.copy(installing = true, stalled = false)
            } else {
                val total = input.total.coerceAtLeast(0L)
                install.progress.copy(
                    read = read.coerceAtLeast(0L).let { if (total > 0L) it.coerceAtMost(total) else it },
                    total = total,
                    installing = false,
                    stalled = false,
                )
            }
            if (progress != install.progress.copy(stalled = false)) {
                install.progress = progress
                showCoreInstall(install)
                sendState(GuestPhase.INSTALLING_CORE, progress = coreInstallPercent(progress))
                scheduleCoreInstallStall(install)
            }
        }
        val result = input.result ?: return
        releaseCoreInstall(install, cancel = false)
        when (result) {
            CoreResult.Started -> {
                writeIfToken(install.token) { it.copy(coreInstall = null, progress = null, message = null) }
                sendState(GuestPhase.INSTALLING_CORE)
                if (platform.foreground.value) scheduleResume(install.token)
            }
            is CoreResult.Failed -> {
                writeIfToken(install.token) {
                    it.copy(step = GuestStep.ACTION_NEEDED, phase = GuestPhase.NEEDS_CORE,
                        problem = Problem(result.text, result.action), coreInstall = null, progress = null, message = null)
                }
                if (owns(install.token)) sendState(GuestPhase.NEEDS_CORE)
            }
        }
    }

    private fun handleCoreFetchFinished(install: CoreInstall) {
        if (!isCurrentCoreInstall(install)) return
        releaseCoreInstall(install, cancel = false)
        showCoreInstallCancelled(install.token)
    }

    private fun cancelCoreInstall() {
        val install = coreInstall ?: return
        if (!isCurrentCoreInstall(install)) return
        epoch += 1
        cancelStepJobs()
        showCoreInstallCancelled(install.token)
    }

    private fun showCoreInstallCancelled(token: Long) {
        writeIfToken(token) {
            it.copy(step = GuestStep.ACTION_NEEDED, phase = GuestPhase.NEEDS_CORE,
                problem = Problem(CORE_INSTALL_CANCELLED, TogetherAction.RETRY_CORE_INSTALL),
                coreInstall = null, progress = null, message = null)
        }
        if (owns(token)) sendState(GuestPhase.NEEDS_CORE)
    }

    private suspend fun startCoreCopy() {
        val selectedOffer = offer ?: return
        val hostBuild = selectedOffer.core ?: return
        epoch += 1
        cancelStepJobs()
        clearCoreTransfer()
        val startedEpoch = epoch
        _view.value = _view.value?.copy(step = GuestStep.CORE_SYNC, phase = GuestPhase.CORE_SYNC, problem = null, progress = 0, message = null)
        sendState(GuestPhase.CORE_SYNC, progress = 0)
        launchStep {
            try {
                val has = withContext(ioDispatcher) { emulators.hasReceivedCore(hostBuild) }
                val file = if (has) withContext(ioDispatcher) { emulators.receivedCoreFile(hostBuild) } else null
                post(Input.CorePrepared(startedEpoch, has, file, failed = false))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                log(t)
                post(Input.CorePrepared(startedEpoch, false, null, failed = true))
            }
        }
    }

    private suspend fun handleCorePrepared(input: Input.CorePrepared) {
        if (!isCurrent(input.epoch)) return
        val selectedOffer = offer ?: return
        val hostBuild = selectedOffer.core ?: return
        if (input.failed) {
            handleCoreFailure()
            return
        }
        if (input.hasReceived && input.file != null) {
            installReceivedCore(input.epoch, input.file, hostBuild)
            return
        }
        _view.value = _view.value?.copy(step = GuestStep.CORE_SYNC, phase = GuestPhase.CORE_SYNC, problem = null, progress = 0)
        sendState(GuestPhase.CORE_SYNC, progress = 0)
        val currentSession = selectedOffer.sessionId
        val currentLink = link ?: return
        coreRequestTimer?.cancel()
        val requestGeneration = ++coreRequestGeneration
        coreRequestTimer = launchStep {
            delay(timing.coreRequestMs)
            post(Input.CoreRequestTimeout(input.epoch, requestGeneration))
        }
        launchSession {
            try {
                withContext(ioDispatcher) { currentLink.send(LinkMsg.CoreRequest(currentSession, hostBuild.core)) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                log(t)
            }
        }
    }

    private suspend fun handleCoreBegin(message: LinkMsg.CoreBegin) {
        val selectedOffer = offer ?: return
        val hostBuild = selectedOffer.core ?: return
        if (_view.value?.step != GuestStep.CORE_SYNC || message.sessionId != selectedOffer.sessionId) return
        coreRequestTimer?.cancel()
        coreRequestTimer = null
        coreRequestGeneration += 1
        val expected = sessionCore ?: return
 // isSafeCoreBuild pins build.core to expected. The allowlist check is defense in
 // depth: Offer receipt already rejected an off-allowlist session core.
        if (message.build != hostBuild || !TogetherSupport.isSafeCoreBuild(message.build, expected) || !sideloadAllowed(selectedOffer.mode, expected)) {
            handleCoreFailure()
            return
        }
        clearCoreWriteQueueOnly()
        blobId = message.blobId
        sink = BlobSink(emulators.receivedCoreFile(hostBuild), hostBuild)
        coreEndPending = false
        restartCoreInactivityTimer()
    }

    private suspend fun handleBlob(receivedBlobId: Int, bytes: ByteArray) {
        if (_view.value?.step != GuestStep.CORE_SYNC || receivedBlobId != blobId || sink == null) {
            releaseBlobPermit()
            return
        }
        coreBlobPermitHeld = true
        restartCoreInactivityTimer()
        coreWrites.addLast(bytes)
        startNextCoreWrite()
    }

    private fun startNextCoreWrite() {
        if (coreWriteActive || coreWrites.isEmpty()) return
        val expectedEpoch = epoch
        val currentSink = sink ?: return
        val currentBlobId = blobId ?: return
        val bytes = coreWrites.removeFirst()
        coreWriteActive = true
        coreWriteJob = launchStep {
            val failed = try {
                withContext(ioDispatcher) { currentSink.write(bytes) }
                false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                log(t)
                true
            }
            post(Input.CoreWriteDone(expectedEpoch, currentBlobId, failed))
        }
    }

    private suspend fun handleCoreWriteDone(input: Input.CoreWriteDone) {
        if (!isCurrent(input.epoch) || input.blobId != blobId) return
        coreWriteActive = false
        coreWriteJob = null
        releaseBlobPermit()
        if (input.failed) {
            handleCoreFailure()
            return
        }
        val currentSink = sink ?: return
        val expected = offer?.core ?: return
        val progress = (currentSink.written * 100L / expected.sizeBytes.coerceAtLeast(1L)).toInt().coerceIn(0, 100)
        _view.value = _view.value?.copy(progress = progress)
        val now = System.nanoTime()
        if (progress != lastCoreProgress && (lastCoreProgressAtNs == 0L || now - lastCoreProgressAtNs >= timing.progressThrottleMs * 1_000_000L || progress >= 100)) {
            lastCoreProgress = progress
            lastCoreProgressAtNs = now
            sendState(GuestPhase.CORE_SYNC, progress = progress)
        }
        startNextCoreWrite()
        if (!coreWriteActive && coreWrites.isEmpty() && coreEndPending) finishCore(input.epoch)
    }

    private suspend fun handleCoreEnd(message: LinkMsg.CoreEnd) {
        val selectedOffer = offer ?: return
        if (_view.value?.step != GuestStep.CORE_SYNC || message.sessionId != selectedOffer.sessionId || message.blobId != blobId || sink == null) return
        coreInactivityTimer?.cancel()
        coreInactivityTimer = null
        coreInactivityGeneration += 1
        coreEndPending = true
        val expectedEpoch = epoch
        if (!coreWriteActive && coreWrites.isEmpty()) finishCore(expectedEpoch)
    }

    private fun finishCore(expectedEpoch: Int) {
        if (coreFinishJob?.isActive == true) return
        val currentSink = sink ?: return
        coreFinishJob = launchStep {
            val ok = try {
                withContext(ioDispatcher) { currentSink.finish() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                log(t)
                false
            }
            post(Input.CoreFinished(expectedEpoch, ok))
        }
    }

    private suspend fun handleCoreFinished(input: Input.CoreFinished) {
        if (!isCurrent(input.epoch)) return
        coreFinishJob = null
        if (!input.ok) {
            handleCoreFailure()
            return
        }
        val selectedOffer = offer ?: return
        val hostBuild = selectedOffer.core ?: return
        val file = emulators.receivedCoreFile(hostBuild)
        sink = null
        blobId = null
        installReceivedCore(input.epoch, file, hostBuild)
    }


    private fun installReceivedCore(startedEpoch: Int, file: File, hostBuild: CoreBuild) {
        val selectedLocal = local ?: return
        val installHostName = hostName()
        launchStep {
            try {
                if (!platform.foreground.value) {
                    platform.alert(TogetherAlert.TapToStart(installHostName))
                    platform.foreground.first { it }
                }
                val result = withContext(ioDispatcher) {
                    coreInstallMutex.withLock {
                        currentCoroutineContext().ensureActive()
                        emulators.installReceivedCore(file, hostBuild, selectedLocal)
                    }
                }
                post(Input.CoreInstalled(startedEpoch, result))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                log(t)
                post(Input.CoreInstalled(startedEpoch, CoreResult.Failed(t.message ?: TogetherCopy.coreCopyFailed)))
            }
        }
    }

    private suspend fun handleCoreInstalled(input: Input.CoreInstalled) {
        if (!isCurrent(input.epoch)) return
        when (val result = input.result) {
            CoreResult.Started -> {
                _view.value = _view.value?.copy(step = GuestStep.INSTALLING_CORE, phase = GuestPhase.INSTALLING_CORE, problem = null, progress = null, message = null)
                sendState(GuestPhase.INSTALLING_CORE)
                if (platform.foreground.value) scheduleResume()
            }
            is CoreResult.Failed -> setActionNeeded(GuestPhase.CORE_SYNC, Problem(result.text,
                if (result.action == TogetherAction.INSTALL_CORE) TogetherAction.COPY_CORE else result.action))
        }
    }

    private suspend fun handleCoreUnavailable() {
        if (_view.value?.step != GuestStep.CORE_SYNC) return
        clearCoreTransfer()
        if (coreMissing) {
            val core = sessionCore ?: return
            setActionNeeded(GuestPhase.NEEDS_CORE, Problem(TogetherCopy.needsCore(com.gamebex.client.data.CoreNames.display(core)), TogetherAction.INSTALL_CORE))
        } else {
            readyOnce = true
            _view.value = _view.value?.copy(step = GuestStep.READY, phase = GuestPhase.READY, problem = null, progress = null, message = TogetherCopy.coreSyncUnavailable(hostName()))
            sendState(GuestPhase.READY)
        }
    }

    private suspend fun handleCoreFailure() {
        clearCoreTransfer()
        setActionNeeded(GuestPhase.CORE_SYNC, Problem(TogetherCopy.coreCopyFailed, TogetherAction.COPY_CORE))
    }

    private suspend fun setActionNeeded(phase: GuestPhase, problem: Problem) {
        _view.value = _view.value?.copy(step = GuestStep.ACTION_NEEDED, phase = phase, problem = problem, progress = null, choices = emptyList(), message = null, coreInstall = null)
        sendState(phase, problem.text.takeIf { phase == GuestPhase.NEEDS_SETUP })
    }

    private fun sendState(phase: GuestPhase, detail: String? = null, progress: Int? = null) {
        val currentLink = link ?: return
        val sessionId = offer?.sessionId ?: return
        val seq = stateSeq.incrementAndGet()
        launchSession {
            try {
                sendMutex.withLock {
                    // A state that lost the race to a newer one would overwrite it on the host.
                    if (seq < sentStateSeq) return@withLock
                    sentStateSeq = seq
                    withContext(ioDispatcher) {
                        currentLink.send(LinkMsg.GuestState(sessionId, phase, detail?.take(120), progress))
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                log(t)
            }
        }
    }

    private fun releaseBlobPermit() {
        if (coreBlobPermitHeld) {
            coreBlobPermitHeld = false
            coreBlobPermits.release()
        }
    }

    private fun restartCoreInactivityTimer() {
        coreInactivityTimer?.cancel()
        val timerEpoch = epoch
        val generation = ++coreInactivityGeneration
        coreInactivityTimer = launchStep {
            delay(timing.coreInactivityMs)
            post(Input.CoreInactivity(timerEpoch, generation))
        }
    }

    private fun clearCoreWriteQueueOnly() {
        coreWrites.clear()
        coreWriteActive = false
        coreEndPending = false
        coreFinishJob?.cancel()
        coreFinishJob = null
        lastCoreProgress = null
        lastCoreProgressAtNs = 0L
    }

    private suspend fun clearCoreTransfer() {
        coreRequestTimer?.cancel()
        coreRequestTimer = null
        coreRequestGeneration += 1
        coreInactivityTimer?.cancel()
        coreInactivityTimer = null
        coreInactivityGeneration += 1
        coreWriteJob?.let { cancelAndJoinSafely(it) }
        coreWriteJob = null
        coreFinishJob?.let { cancelAndJoinSafely(it) }
        coreFinishJob = null
        releaseBlobPermit()
        val currentSink = sink
        withContext(ioDispatcher) { runCatching { currentSink?.abort() } }
        sink = null
        blobId = null
    }

    private fun hostName(): String = _view.value?.hostName ?: hostHello?.name ?: target?.name ?: TogetherCopy.otherPhone

 /** The dialed address becomes the emulator target, so it must be private too; loopback pairs only with loopback. */
    private fun acceptedHostAddress(hostAddress: String, currentLink: Link): Boolean {
        val remote = currentLink.remoteAddress
        if (isLoopbackHost(remote) || isLoopbackHost(hostAddress)) return isLoopbackHost(remote) && isLoopbackHost(hostAddress)
        return JoinLink.isPrivateIpv4(remote) && JoinLink.isPrivateIpv4(hostAddress)
    }

    private fun isLoopbackHost(address: String): Boolean {
        val parts = address.split('.')
        return parts.size == 4 && parts[0] == "127" && parts.all { it.toIntOrNull() in 0..255 }
    }

    private suspend fun endInternal(reason: String?, sendBye: Boolean, updateView: Boolean, token: Long) {
        if (sessionEnded) {
            if (updateView) writeEnded(token, reason)
            return
        }
        runCatching {
            cancelStepJobs()
            cancelSessionJobs()
            val sinkJob = coreWriteJob
            coreWriteJob = null
            if (sinkJob != null) cancelAndJoinSafely(sinkJob)
        }.onFailure(::log)
        runCatching { criticalLaunches.join(2_000L) }.onFailure(::log)
        runCatching {
            val currentSink = sink
            withContext(ioDispatcher) { currentSink?.abort() }
        }.onFailure(::log)
        runCatching {
            val currentLink = link
            if (sendBye && linkOpen && !byeReceived && currentLink != null) sendBye(currentLink)
        }.onFailure(::log)
        runCatching { resources?.closeAll() }.onFailure(::log)
        runCatching { withContext(ioDispatcher) { emulators.endSession() } }.onFailure(::log)
        runCatching { platform.cancelAlerts() }.onFailure(::log)
        runCatching {
            releaseCoreTransferState()
            target = null
            link = null
            hostHello = null
            offer = null
            local = null
            sessionCore = null
            playAddress = null
            linkOpen = false
            sessionEnded = true
            epoch += 1
            if (updateView) writeEnded(token, reason)
        }.onFailure(::log)
    }

    private suspend fun sendBye(currentLink: Link) {
        try {
            withTimeoutOrNull(timing.byeMs) {
                withContext(ioDispatcher) { currentLink.send(LinkMsg.Bye(ByeReason.LEFT)) }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            log(t)
        }
    }

    private fun isCurrent(expectedEpoch: Int = epoch): Boolean = !sessionEnded && expectedEpoch == epoch

    private fun publishedToken(): Long = publishedToken

    private fun owns(token: Long): Boolean = token == publishedToken

    private fun replaceView(token: Long, view: GuestView) {
        synchronized(viewLock) {
            publishedToken = token
            _view.value = view
        }
    }

    private fun writeIfToken(token: Long, transform: (GuestView) -> GuestView) {
        synchronized(viewLock) {
            if (publishedToken != token) return
            val current = _view.value ?: return
            _view.value = transform(current)
        }
    }

    private fun writeEnded(token: Long, reason: String?) {
        synchronized(viewLock) {
            if (publishedToken != token) return
            val current = _view.value ?: return
            if (current.step == GuestStep.ENDED) return
            _view.value = current.copy(step = GuestStep.ENDED, message = reason, coreInstall = null)
        }
    }

    private fun acceptableCore(mode: TogetherMode, core: String?): Boolean {
        if (core == null || !coreName.matches(core)) return false
        return sideloadAllowed(mode, core)
    }

    private fun post(input: Input): Boolean = inputs.trySend(input).isSuccess

    private fun launchStep(block: suspend () -> Unit): Job {
        lateinit var job: Job
        job = scope.launch {
            try {
                block()
            } finally {
                stepJobs.remove(coroutineContext[Job])
            }
        }
        stepJobs += job
        return job
    }

    private fun launchSession(block: suspend () -> Unit): Job {
        lateinit var job: Job
        job = scope.launch {
            try {
                block()
            } finally {
                sessionJobs.remove(coroutineContext[Job])
            }
        }
        sessionJobs += job
        return job
    }

    private fun cancelStepJobs() {
        coreInstall?.let { releaseCoreInstall(it, cancel = true) }
        criticalLaunches.cancel(synchronized(stepJobs) { stepJobs.toList() })
    }

    private fun cancelSessionJobs() {
        synchronized(sessionJobs) { sessionJobs.toList() }.forEach { it.cancel() }
    }


    private fun releaseCoreTransferState() {
        coreRequestTimer?.cancel()
        coreRequestTimer = null
        coreRequestGeneration += 1
        coreInactivityTimer?.cancel()
        coreInactivityTimer = null
        coreInactivityGeneration += 1
        coreFinishJob?.cancel()
        coreFinishJob = null
        coreWriteJob = null
        coreWrites.clear()
        coreWriteActive = false
        coreEndPending = false
        lastCoreProgress = null
        lastCoreProgressAtNs = 0L
        releaseBlobPermit()
        sink = null
        blobId = null
    }

    internal suspend fun <T> openOnIo(open: suspend (mark: (T) -> Unit) -> T, close: (T) -> Unit): T {
        val held = AtomicReference<T?>(null)
        try {
            val resource = withContext(ioDispatcher) {
                val created = open { held.set(it) }
                if (held.get() == null) held.set(created)
                ensureActive()
                created
            }
            currentCoroutineContext().ensureActive()
            held.set(null)
            return resource
        } catch (cancelled: CancellationException) {
            held.getAndSet(null)?.let { runCatching { close(it) } }
            throw cancelled
        }
    }

    private suspend fun cancelAndJoinSafely(job: Job) {
        try {
            job.cancelAndJoin()
        } catch (t: Throwable) {
            log(t)
        }
    }

    private fun log(t: Throwable) {
        log(t.message ?: t.javaClass.simpleName)
    }

    private fun log(message: String) {
        runCatching { logSink(message) }
    }
}
