package com.gamebex.client.together.session

import android.util.Log
import com.gamebex.client.data.local.GameEntity
import com.gamebex.client.launch.EmuRouter
import com.gamebex.client.launch.RetroCoreFetcher
import com.gamebex.client.together.CoreFile
import com.gamebex.client.together.CoreResult
import com.gamebex.client.together.Discovery
import com.gamebex.client.together.HostAd
import com.gamebex.client.together.HostStep
import com.gamebex.client.together.HostView
import com.gamebex.client.together.Link
import com.gamebex.client.together.LinkEvent
import com.gamebex.client.together.LinkMsg
import com.gamebex.client.together.LinkTransport
import com.gamebex.client.together.OfferedGame
import com.gamebex.client.together.Problem
import com.gamebex.client.together.Readiness
import com.gamebex.client.together.Role
import com.gamebex.client.together.TogetherAction
import com.gamebex.client.together.TogetherAlert
import com.gamebex.client.together.TogetherCopy
import com.gamebex.client.together.TogetherEmulators
import com.gamebex.client.together.TogetherLaunch
import com.gamebex.client.together.TogetherLibrary
import com.gamebex.client.together.TogetherMode
import com.gamebex.client.together.TogetherPlatform
import com.gamebex.client.together.TogetherSupport
import com.gamebex.client.together.TogetherTiming
import com.gamebex.client.together.TogetherCaps
import com.gamebex.client.together.TOGETHER_PROTO
import com.gamebex.client.together.net.JoinLink
import com.gamebex.client.stream.NoStreamHost
import com.gamebex.client.stream.NoStreamPlayer2
import com.gamebex.client.stream.P2Prep
import com.gamebex.client.stream.StreamCaps
import com.gamebex.client.stream.StreamConfig
import com.gamebex.client.stream.StreamEmus
import com.gamebex.client.stream.StreamHost
import com.gamebex.client.stream.StreamPlayer2
import com.gamebex.client.stream.StreamStarted
import com.gamebex.client.stream.StreamStatus
import com.gamebex.client.stream.StreamToken
import com.gamebex.client.stream.proto.StreamWire
import java.util.Collections
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch

private const val CONSUMER_FAILURE = "Something went wrong."

/** STREAM: status polls (2 s apart) with no new guest input before the host is warned the guest went quiet. */
private const val SILENT_POLLS = 2

/** The host half of the Play together session actor. */
internal class HostEngine(
    private val transport: LinkTransport,
    private val emulators: TogetherEmulators,
    private val library: TogetherLibrary,
    private val discovery: Discovery,
    private val platform: TogetherPlatform,
    private val timing: TogetherTiming,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
 /** STREAM: the shell-uid stream process. */
    private val streamHost: StreamHost = NoStreamHost,
 /** STREAM: player 2's binding in the host's emulator. */
    private val player2: StreamPlayer2 = NoStreamPlayer2,
 /** a game's modes, best first. Tests pass their own. */
    private val modesFor: (GameEntity) -> List<TogetherMode> = TogetherSupport::modesFor,
) {
    private sealed interface Input {
        data class HostGame(
            val gameId: Long,
 /** A mode the host switched to; null picks the best one. */
            val forcedMode: TogetherMode? = null,
            val beforeStart: suspend () -> Unit,
        ) : Input
        data object Start : Input
        data object Remove : Input
        data object PlayAgain : Input
        data class Act(val action: TogetherAction) : Input
        data class End(val token: Long, val done: CompletableDeferred<Unit>?) : Input
        data object Foreground : Input
        data class ReadinessDone(
            val epoch: Int,
            val game: GameEntity?,
            val mode: TogetherMode?,
            val core: String?,
            val readiness: Readiness?,
            val token: Long,
 /** The game's other modes, best first. */
            val altModes: List<TogetherMode> = emptyList(),
        ) : Input
        data class SetupDone(
            val epoch: Int,
            val offer: OfferedGame,
            val hostCore: CoreFile?,
            val address: String,
            val listener: com.gamebex.client.together.LinkListener,
            val ad: com.gamebex.client.together.Advertisement,
            val token: Long,
        ) : Input
        data class SetupFailed(val epoch: Int, val problem: Problem, val token: Long) : Input
        data class Incoming(val link: Link, val hello: LinkMsg.Hello) : Input
        data class LinkEvt(val link: Link, val event: LinkEvent) : Input
        data class CoreFetch(
            val epoch: Int,
            val progress: Int? = null,
            val result: CoreResult? = null,
        ) : Input
        data class HostLaunched(
            val epoch: Int,
            val round: Int,
            val outcome: EmuRouter.Outcome,
 /** STREAM: the stream started for this round. */
            val stream: StreamRound? = null,
 /** STREAM: player-2 steps to show once the game runs. */
            val guide: String? = null,
        ) : Input
        data class HeadStart(val epoch: Int, val round: Int) : Input
        data class SwitchMode(val mode: TogetherMode) : Input
        data class StreamPolled(val epoch: Int, val round: Int, val status: StreamStatus?) : Input
        data class StreamGraceOver(val epoch: Int, val round: Int) : Input
    }

 /** A stream the shell runs for one round, with the token the guest needs. */
    private class StreamRound(val token: String, val started: StreamStarted)

 /** What STREAM prepares before the host's emulator starts. */
    private class StreamPrep(
        val round: StreamRound? = null,
        val p2Config: java.io.File? = null,
        val guide: String? = null,
        val failure: EmuRouter.Outcome? = null,
    )

    private val inputs = Channel<Input>(Channel.UNLIMITED)
    private val _view = MutableStateFlow<HostView?>(null)
    val view: StateFlow<HostView?> = _view.asStateFlow()

    @Volatile
    private var myName: String = ""

    private val sessionJobs = Collections.synchronizedSet(mutableSetOf<Job>())
    private val stepJobs = Collections.synchronizedSet(mutableSetOf<Job>())

    private var epoch = 0
    @Volatile
    private var sessionEnded = true
    private val sessionToken = AtomicLong()
    private val viewLock = Any()
    @Volatile
    private var publishedToken = 0L
    private val criticalLaunches = CriticalLaunches()
    private var sessionId: String? = null
    private var gameId: Long? = null
    private var game: GameEntity? = null
    private var mode: TogetherMode? = null
    private var core: String? = null
    private var offer: OfferedGame? = null
    private var hostCore: CoreFile? = null
    private var address: String? = null
    private var listener: com.gamebex.client.together.LinkListener? = null
    private var ad: com.gamebex.client.together.Advertisement? = null
    private var guest: Link? = null
    private var guestHello: LinkMsg.Hello? = null
    private var round = 0
    private var blobSeq = 0
    private var coreFetchRunning = false
    private var resources: SessionResources? = null
    private var headStartJob: Job? = null
    private var forcedMode: TogetherMode? = null
 /** STREAM: the stream of the current round, or null. */
    private var stream: StreamRound? = null
    private var streamPoll: Job? = null
 /** STREAM: the guest's link dropped and the stream is being kept for [TogetherTiming.streamLinkGraceMs]. */
    private var streamGrace: Job? = null
    private var streamGuestName: String? = null
    private var streamGuestConnected = false
    private var lastInputPackets = -1L
    private var silentPolls = 0
    private var peerSilent = false

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
    internal fun refreshPlayerName() {
        scope.launch {
            try {
                myName = withContext(ioDispatcher) { platform.playerName() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                log(t)
            }
        }
    }

    fun hostGame(gameId: Long) {
        prepareHostGame(gameId)
        commitHostGame(gameId)
    }

 /** Sets CHECKING on the caller thread without entering the actor. */
    internal fun prepareHostGame(gameId: Long) {
        val token = sessionToken.incrementAndGet()
        replaceView(token, HostView(HostStep.CHECKING, gameId, "", "", null, myName))
    }

 /** Starts the session after the other engine has finished ending. */
    internal fun commitHostGame(gameId: Long) {
        inputs.trySend(Input.HostGame(gameId) {})
    }

    fun start() {
        inputs.trySend(Input.Start)
    }

    fun removeGuest() {
        inputs.trySend(Input.Remove)
    }

    fun playAgain() {
        inputs.trySend(Input.PlayAgain)
    }

 /** Host the same game in [mode] instead; only before a guest joins. */
    fun switchMode(mode: TogetherMode) {
        inputs.trySend(Input.SwitchMode(mode))
    }

    fun act(action: TogetherAction) {
        inputs.trySend(Input.Act(action))
    }

    fun end() {
        inputs.trySend(Input.End(publishedToken(), null))
    }

 /** Completes only after this engine has run its ordered end cleanup. */
    internal suspend fun endAndAwait() {
        val done = CompletableDeferred<Unit>()
        inputs.send(Input.End(publishedToken(), done))
        done.await()
    }

    fun onForeground() {
        refreshPlayerName()
        inputs.trySend(Input.Foreground)
    }

    private suspend fun handle(input: Input) {
        when (input) {
            is Input.HostGame -> handleHostGame(input)
            Input.Start -> handleStart()
            Input.Remove -> handleRemove()
            Input.PlayAgain -> handlePlayAgain()
            is Input.Act -> handleAct(input.action)
            is Input.End -> {
                try {
                    endInternal(updateView = true, token = input.token)
                } finally {
                    input.done?.complete(Unit)
                }
            }
            Input.Foreground -> handleForeground()
            is Input.ReadinessDone -> handleReadinessDone(input)
            is Input.SetupDone -> handleSetupDone(input)
            is Input.SetupFailed -> handleSetupFailed(input)
            is Input.Incoming -> handleIncoming(input)
            is Input.LinkEvt -> handleLinkEvent(input)
            is Input.CoreFetch -> handleCoreFetch(input)
            is Input.HostLaunched -> handleHostLaunched(input)
            is Input.HeadStart -> handleHeadStart(input)
            is Input.SwitchMode -> handleSwitchMode(input.mode)
            is Input.StreamPolled -> handleStreamPolled(input)
            is Input.StreamGraceOver -> handleStreamGraceOver(input)
        }
    }

    private suspend fun handleHostGame(input: Input.HostGame) {
        val token = publishedToken()
        input.beforeStart()
        if (!sessionEnded) endInternal(updateView = false, token = token)
        if (token != publishedToken()) return

        epoch += 1
        sessionEnded = false
        sessionId = ByteArray(4).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        gameId = input.gameId
        game = null
        mode = null
        core = null
        offer = null
        hostCore = null
        address = null
        listener = null
        ad = null
        guest = null
        guestHello = null
        round = 0
        blobSeq = 0
        coreFetchRunning = false
        resources = SessionResources()
        headStartJob?.cancel()
        headStartJob = null
        forcedMode = input.forcedMode
        stream = null
        streamPoll?.cancel()
        streamPoll = null
        streamGrace?.cancel()
        streamGrace = null
        refreshPlayerName()
        replaceView(token, HostView(HostStep.CHECKING, input.gameId, "", "", null, myName))
        beginReadiness(input.gameId, epoch, token)
    }

    private fun beginReadiness(id: Long, startedEpoch: Int, token: Long) {
        val forced = forcedMode
        launchStep {
            try {
                val found = withContext(ioDispatcher) { library.game(id) }
                if (found == null) {
                    post(Input.ReadinessDone(startedEpoch, null, null, null, null, token))
                    return@launchStep
                }
 // The best mode first; a switch picks another listed one.
                val modes = modesFor(found)
                val foundMode = forced?.takeIf { it in modes } ?: modes.firstOrNull()
                if (foundMode == null) {
                    post(Input.ReadinessDone(startedEpoch, found, null, null, null, token))
                    return@launchStep
                }
                val foundCore = TogetherSupport.coreFor(found, foundMode)
                val readiness = withContext(ioDispatcher) {
                    emulators.readiness(foundMode, Role.HOST, found, foundCore)
                }
                post(Input.ReadinessDone(startedEpoch, found, foundMode, foundCore, readiness, token, modes - foundMode))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                log(t)
                post(
                    Input.ReadinessDone(
                        startedEpoch,
                        null,
                        null,
                        null,
                        Readiness.Blocked(TogetherCopy.noWifi),
                        token,
                    ),
                )
            }
        }
    }

    private suspend fun handleReadinessDone(input: Input.ReadinessDone) {
        if (!isCurrent(input.epoch) || input.token != publishedToken() || _view.value?.step != HostStep.CHECKING) return

        game = input.game
        mode = input.mode
        core = input.core
        offer = null
        hostCore = null
        val current = _view.value ?: return
        when {
            input.game == null -> {
                _view.value = current.copy(
                    step = HostStep.NOT_READY,
                    problem = Problem(TogetherCopy.gameFileMissing, TogetherAction.RECHECK),
                )
            }
            input.mode == null -> {
                _view.value = current.copy(
                    step = HostStep.NOT_READY,
                    gameTitle = input.game.title,
                    folder = input.game.folder,
                    message = TogetherCopy.notSupported,
                    problem = null,
                )
            }
            input.readiness != Readiness.Ready -> {
                val readiness = input.readiness ?: Readiness.Blocked(TogetherCopy.noWifi)
                _view.value = current.copy(
                    step = HostStep.NOT_READY,
                    gameTitle = input.game.title,
                    folder = input.game.folder,
                    mode = input.mode,
                    problem = TogetherCopy.problemFor(readiness),
                    altModes = input.altModes,
                )
            }
            else -> {
                _view.value = current.copy(
                    gameTitle = input.game.title,
                    folder = input.game.folder,
                    mode = input.mode,
                    problem = null,
                    message = null,
                    altModes = input.altModes,
                )
                beginSetup(input.epoch, input.game, input.mode, input.core, sessionId ?: return, input.token)
            }
        }
    }

    private fun beginSetup(
        startedEpoch: Int,
        setupGame: GameEntity,
        setupMode: TogetherMode,
        setupCore: String?,
        setupSessionId: String,
        token: Long,
    ) {
        val sessionResources = resources ?: return
        launchStep {
            try {
 // The core follows the chosen mode: a STREAM offer carries none.
                val setupOffer = withContext(ioDispatcher) { library.offer(setupGame.id) }
                    ?.copy(core = TogetherSupport.coreFor(setupGame, setupMode))
                if (setupOffer == null) {
                    post(
                        Input.SetupFailed(
                            startedEpoch,
                            Problem(TogetherCopy.gameFileMissing, TogetherAction.RECHECK),
                            token,
                        ),
                    )
                    return@launchStep
                }
                val setupAddress = withContext(ioDispatcher) { platform.lanAddress() }
                if (setupAddress == null) {
                    post(Input.SetupFailed(startedEpoch, Problem(TogetherCopy.noWifi, TogetherAction.RECHECK), token))
                    return@launchStep
                }

                val setupHostCore = if (setupMode == TogetherMode.RA_NETPLAY && setupCore != null) {
                    withContext(ioDispatcher) { emulators.installedCore(setupCore) }
                } else {
                    null
                }

                val refreshedName = withContext(ioDispatcher) {
                    runCatching { platform.playerName() }.getOrNull()
                }
                if (refreshedName != null) myName = refreshedName

                val setupListener = openOnIo<com.gamebex.client.together.LinkListener>({ mark ->
                    val created = transport.listen()
                    mark(created)
                    created
                }) { it.close() }
                if (!sessionResources.register { setupListener.close() }) {
                    setupListener.close()
                    return@launchStep
                }

                val setupAd = discovery.advertise(
                    HostAd(
                        deviceId = platform.deviceId,
                        sessionId = setupSessionId,
                        name = myName,
                        gameTitle = setupGame.title,
                        folder = setupGame.folder,
                        mode = setupMode,
                        port = setupListener.port,
                    ),
                )
                if (!sessionResources.register { setupAd.stop() }) {
                    setupAd.stop()
                    return@launchStep
                }

                val serviceRegistered = withContext(NonCancellable) {
                    withContext(ioDispatcher) { platform.startService(TogetherCopy.ongoingWaiting) }
                    val registered = sessionResources.register { platform.stopService() }
                    if (!registered) platform.stopService()
                    registered
                }
                if (!serviceRegistered) return@launchStep

                post(
                    Input.SetupDone(
                        epoch = startedEpoch,
                        offer = setupOffer,
                        hostCore = setupHostCore,
                        address = setupAddress,
                        listener = setupListener,
                        ad = setupAd,
                        token = token,
                    ),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                log(t)
                post(Input.SetupFailed(startedEpoch, Problem(TogetherCopy.noWifi, TogetherAction.RECHECK), token))
            }
        }
    }

    private fun handleSetupDone(input: Input.SetupDone) {
        if (!isCurrent(input.epoch) || input.token != publishedToken() || _view.value?.step != HostStep.CHECKING) return
        offer = input.offer
        hostCore = input.hostCore
        address = input.address
        listener = input.listener
        ad = input.ad
        val current = _view.value ?: return
        _view.value = current.copy(
            step = HostStep.WAITING,
            myAddress = input.address,
            port = input.listener.port,
            qrText = JoinLink.buildLink(input.address, input.listener.port, myName),
            problem = null,
            message = null,
        )
        startAcceptLoop(input.listener)
    }

    private fun handleSetupFailed(input: Input.SetupFailed) {
        if (!isCurrent(input.epoch) || input.token != publishedToken()) return
        resources?.closeAll()
        resources = SessionResources()
        listener = null
        ad = null
        address = null
        offer = null
        hostCore = null
        _view.value = _view.value?.copy(step = HostStep.NOT_READY, problem = input.problem, message = null)
    }

    private fun startAcceptLoop(setupListener: com.gamebex.client.together.LinkListener) {
        launchSession {
            while (!sessionEnded) {
                val next = try {
                    openOnIo<Link>({ mark ->
                        val created = setupListener.accept()
                        mark(created)
                        created
                    }) { it.close() }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (t: Throwable) {
                    log(t)
                    if (sessionEnded || t is java.io.IOException) break else continue
                }
                val sessionResources = resources
                if (sessionResources == null || !sessionResources.register { next.close() }) {
                    next.close()
                    continue
                }
                launchSession { inspectIncoming(next) }
            }
        }
    }

    private suspend fun inspectIncoming(link: Link) {
        val first = try {
            withTimeoutOrNull(timing.helloMs) {
                for (event in link.events) {
                    if (event is LinkEvent.Message) return@withTimeoutOrNull event.msg
                }
                null
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            log(t)
            null
        }
        if (first is LinkMsg.Hello && first.role == Role.GUEST && !sessionEnded) {
            post(Input.Incoming(link, first))
        } else {
            link.close()
        }
    }

    private suspend fun handleIncoming(input: Input.Incoming) {
        if (!isCurrent() || _view.value?.step != HostStep.WAITING || guest != null) {
            sendByeAndClose(input.link, com.gamebex.client.together.ByeReason.FULL)
            return
        }
        if (input.hello.proto != TOGETHER_PROTO) {
            sendByeAndClose(input.link, com.gamebex.client.together.ByeReason.VERSION)
            return
        }
 // A STREAM guest must speak this host's stream protocol.
        if (mode == TogetherMode.STREAM && input.hello.streamProto != StreamWire.VERSION) {
            sendByeAndClose(input.link, com.gamebex.client.together.ByeReason.VERSION)
            return
        }

        val currentSession = sessionId ?: return
        val currentOffer = offer ?: return
        val currentMode = mode ?: return
        val currentAddress = address ?: return
        guest = input.link
        guestHello = input.hello
        val guestName = input.hello.name
        _view.value = _view.value?.copy(
            step = HostStep.JOINED,
            guestName = guestName,
            guestPhase = com.gamebex.client.together.GuestPhase.CHECKING,
            guestDetail = null,
            guestProgress = null,
            instructions = TogetherCopy.hostInstructions(currentMode),
            message = null,
            linkLost = false,
        )
        platform.updateService(TogetherCopy.ongoingJoined(guestName))
        startGuestForwarder(input.link)
        launchSession {
            try {
                val refreshedName = withContext(ioDispatcher) {
                    runCatching { platform.playerName() }.getOrNull()
                }
                if (refreshedName != null) myName = refreshedName
                val hello = LinkMsg.Hello(
                    proto = TOGETHER_PROTO,
                    role = Role.HOST,
                    deviceId = platform.deviceId,
                    name = myName,
                    appVersion = withContext(ioDispatcher) { platform.appVersion() },
                    raVersion = withContext(ioDispatcher) {
                        platform.packageVersion("com.retroarch.aarch64")
                    },
                    ppssppVersion = withContext(ioDispatcher) {
                        platform.packageVersion("org.ppsspp.ppsspp")
                    },
                    streamProto = StreamWire.VERSION,
                )
                withContext(ioDispatcher) {
                    input.link.send(hello)
                    input.link.send(
                        LinkMsg.Offer(
                            sessionId = currentSession,
                            mode = currentMode,
                            game = currentOffer,
                            core = hostCore?.build,
                            hostAddress = currentAddress,
                        ),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                log(t)
            }
        }
    }

    private fun startGuestForwarder(link: Link) {
        launchSession {
            try {
                for (event in link.events) {
                    post(Input.LinkEvt(link, event))
                    if (event is LinkEvent.Closed) break
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                log(t)
                post(Input.LinkEvt(link, LinkEvent.Closed(t)))
            }
        }
    }

    private suspend fun handleLinkEvent(input: Input.LinkEvt) {
        if (input.link !== guest || !isCurrent()) return
        val event = input.event
        when (event) {
            is LinkEvent.Message -> when (val message = event.msg) {
                is LinkMsg.GuestState -> {
                    if (message.sessionId != sessionId) return
                    val step = _view.value?.step
                    if (step != HostStep.JOINED && step != HostStep.LAUNCHING && step != HostStep.IN_GAME) return
                    _view.value = _view.value?.copy(
                        guestPhase = message.phase,
                        guestDetail = message.detail,
                        guestProgress = message.progress,
                    )
                }
                is LinkMsg.CoreRequest -> {
                    if (message.sessionId != sessionId) return
                    val step = _view.value?.step
                    if (step != HostStep.JOINED && step != HostStep.LAUNCHING && step != HostStep.IN_GAME) return
                    sendCore(input.link, message.core)
                }
                is LinkMsg.Bye -> guestLeft(message.reason, input.link)
                else -> Unit
            }
            is LinkEvent.Blob -> Unit
            is LinkEvent.Closed -> guestClosed(input.link)
        }
    }

    private fun guestLeft(reason: com.gamebex.client.together.ByeReason, link: Link) {
        val name = _view.value?.guestName ?: guestHello?.name ?: TogetherCopy.otherPhone
        val currentStep = _view.value?.step
        stopStream()
        guest = null
        guestHello = null
        clearGuestFields()
        when (currentStep) {
            HostStep.IN_GAME -> {
                _view.value = _view.value?.copy(
                    step = HostStep.IN_GAME,
                    linkLost = true,
                    message = TogetherCopy.peerLeft(name),
                )
                if (!platform.foreground.value) platform.alert(TogetherAlert.PeerLeft(name))
            }
            HostStep.WAITING, HostStep.JOINED, HostStep.LAUNCHING -> {
                _view.value = _view.value?.copy(
                    step = HostStep.WAITING,
                    message = TogetherCopy.peerLeft(name),
                    linkLost = false,
                )
                advertiseAgain()
            }
            else -> Unit
        }
    }

    private fun guestClosed(link: Link) {
        val name = _view.value?.guestName ?: guestHello?.name ?: TogetherCopy.otherPhone
        val currentStep = _view.value?.step
        if (currentStep == HostStep.IN_GAME && stream != null) {
            keepStreamAfterLinkLoss(name)
            return
        }
        stopStream()
        guest = null
        guestHello = null
        clearGuestFields()
        when (currentStep) {
            HostStep.IN_GAME -> {
                _view.value = _view.value?.copy(
                    step = HostStep.IN_GAME,
                    linkLost = true,
                    message = TogetherCopy.linkLost(name),
                )
                if (!platform.foreground.value) platform.alert(TogetherAlert.LinkLost(name))
            }
            HostStep.WAITING, HostStep.JOINED, HostStep.LAUNCHING -> {
                _view.value = _view.value?.copy(
                    step = HostStep.WAITING,
                    message = TogetherCopy.linkLost(name),
                    linkLost = false,
                )
                advertiseAgain()
            }
            else -> Unit
        }
    }

 /**
 * STREAM, link dropped without a Bye (a Wi-Fi blip, say): warn at once, but keep the stream and
 * player 2's pad for [TogetherTiming.streamLinkGraceMs] so the viewer's own reconnect can heal it.
 * An explicit Bye (guestLeft) still stops everything immediately.
 */
    private fun keepStreamAfterLinkLoss(name: String) {
        guest = null
        guestHello = null
        clearGuestFields()
        streamGuestName = name
        val seconds = (timing.streamLinkGraceMs / 1_000L).toInt().coerceAtLeast(1)
        _view.value = _view.value?.copy(step = HostStep.IN_GAME, linkLost = true, message = TogetherCopy.streamLinkLostHost(name, seconds))
        if (!platform.foreground.value) platform.alert(TogetherAlert.LinkLost(name, streamGraceS = seconds))
        val graceEpoch = epoch
        val graceRound = round
        streamGrace?.cancel()
        streamGrace = launchSession {
            kotlinx.coroutines.delay(timing.streamLinkGraceMs)
            post(Input.StreamGraceOver(graceEpoch, graceRound))
        }
    }

    private fun handleStreamGraceOver(input: Input.StreamGraceOver) {
        if (!isCurrent(input.epoch) || input.round != round || stream == null) return
        streamGrace = null
        val name = streamGuestName ?: TogetherCopy.otherPhone
        if (streamGuestConnected) {
 // The guest is still watching, so the stream (and its pad) stay.
            _view.value = _view.value?.copy(message = TogetherCopy.streamLinkLostStill(name))
        } else {
            stopStream()
            _view.value = _view.value?.copy(message = TogetherCopy.linkLost(name))
        }
    }

    private fun clearGuestFields() {
        _view.value = _view.value?.copy(
            guestName = null,
            guestPhase = null,
            guestDetail = null,
            guestProgress = null,
            instructions = emptyList(),
        )
    }

    private fun advertiseAgain() {
        if (ad != null) return
        val sessionResources = resources ?: return
        val setupAddress = address ?: return
        val setupListener = listener ?: return
        val setupMode = mode ?: return
        val setupGame = game ?: return
        val next = runCatching {
            discovery.advertise(
                HostAd(
                    deviceId = platform.deviceId,
                    sessionId = sessionId ?: return,
                    name = myName,
                    gameTitle = setupGame.title,
                    folder = setupGame.folder,
                    mode = setupMode,
                    port = setupListener.port,
                ),
            )
        }.getOrNull() ?: return
        if (!sessionResources.register { next.stop() }) {
            next.stop()
            return
        }
        ad = next
    }

    private suspend fun handleRemove() {
        if (_view.value?.step != HostStep.JOINED || guest == null) return
        epoch += 1
        cancelStepJobs()
        headStartJob?.cancel()
        headStartJob = null
        val oldGuest = guest
        sendByeAndClose(oldGuest, com.gamebex.client.together.ByeReason.REMOVED)
        guest = null
        guestHello = null
        clearGuestFields()
        _view.value = _view.value?.copy(step = HostStep.WAITING, message = null, linkLost = false)
        advertiseAgain()
    }

    private suspend fun handleStart() {
        if (_view.value?.canStart != true) return
        beginLaunch()
    }

    private suspend fun handlePlayAgain() {
        if (_view.value?.step != HostStep.IN_GAME || guest == null || _view.value?.linkLost == true) return
        beginLaunch()
    }

    private suspend fun beginLaunch() {
        val currentGame = game ?: return
        val currentOffer = offer ?: return
        val currentMode = mode ?: return
        val currentAddress = address ?: return
        val currentSessionId = sessionId ?: return
        val guestAddress = guest?.remoteAddress
        ad?.stop()
        ad = null
        headStartJob?.cancel()
        headStartJob = null
        round += 1
        val launchRound = round
        val launchEpoch = epoch
        _view.value = _view.value?.copy(step = HostStep.LAUNCHING, message = null, linkLost = false)
        launchStep {
            try {
                val marker = criticalLaunches.enter(currentCoroutineContext().job)
                try {
                    withContext(NonCancellable) {
                        var prep = StreamPrep()
                        val outcome = try {
                            withContext(ioDispatcher) {
 // Player 2's pad exists before the emulator starts, so it sees it at boot.
                                if (currentMode == TogetherMode.STREAM) prep = prepareStream(currentGame, guestAddress)
                                prep.failure ?: emulators.launch(
                                    TogetherLaunch(
                                        sessionId = currentSessionId,
                                        role = Role.HOST,
                                        mode = currentMode,
                                        game = currentGame,
                                        core = currentOffer.core,
                                        hostAddress = currentAddress,
                                        playerName = myName,
                                        p2Config = prep.p2Config,
                                    ),
                                )
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (t: Throwable) {
                            log(t)
                            EmuRouter.Outcome.Failed(TogetherCopy.noWifi)
                        }
                        post(Input.HostLaunched(launchEpoch, launchRound, outcome, prep.round, prep.guide))
                    }
                } finally {
                    criticalLaunches.exit(marker)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                log(t)
                post(
                    Input.HostLaunched(
                        launchEpoch,
                        launchRound,
                        EmuRouter.Outcome.Failed(TogetherCopy.noWifi),
                    ),
                )
            }
        }
    }

 /**
 * STREAM, before the host's emulator starts: bind player 2, then
 * start capture, encoding and the pad for the joined guest only.
 */
    private suspend fun prepareStream(currentGame: GameEntity, guestAddress: String?): StreamPrep {
        if (guestAddress == null) return StreamPrep(failure = EmuRouter.Outcome.Failed(TogetherCopy.streamNetwork))
        val emu = StreamEmus.emuFor(currentGame)
            ?: return StreamPrep(failure = EmuRouter.Outcome.Blocked(TogetherCopy.notSupported))
        val prepared = player2.prepare(currentGame, emu)
        if (prepared is P2Prep.Failed) return StreamPrep(failure = EmuRouter.Outcome.Failed(prepared.text))
        val token = StreamToken.generate()
        val started = streamHost.start(StreamConfig(token = token, guestAddress = guestAddress))
        if (!started.ok) {
            return StreamPrep(failure = EmuRouter.Outcome.Failed(TogetherCopy.streamStartFailed(started.error)))
        }
        return StreamPrep(
            round = StreamRound(token, started),
            p2Config = (prepared as? P2Prep.RetroArchConfig)?.cfg,
            guide = (prepared as? P2Prep.Guide)?.lines?.joinToString(" "),
        )
    }

    private suspend fun handleHostLaunched(input: Input.HostLaunched) {
        if (!isCurrent(input.epoch) || input.round != round || _view.value?.step != HostStep.LAUNCHING) {
 // A stream started for a launch nobody waits for any more must not keep the pad.
            if (input.stream != null) launchSession { runCatching { withContext(ioDispatcher) { streamHost.stop() } }.onFailure(::log) }
            return
        }
        val outcome = input.outcome
        if (outcome is EmuRouter.Outcome.Launched) {
            val currentGuest = guest
            val currentSession = sessionId
            val started = input.stream
            if (currentGuest != null && currentSession != null) {
                launchSession {
                    try {
                        withContext(ioDispatcher) {
                            currentGuest.send(LinkMsg.Launch(currentSession, input.round))
 // The guest opens its viewer on this, after Launch.
                            if (started != null) {
                                currentGuest.send(
                                    LinkMsg.StreamReady(
                                        sessionId = currentSession,
                                        round = input.round,
                                        proto = StreamWire.VERSION,
                                        token = started.token,
                                        mediaPort = started.started.mediaPort,
                                        inputPort = started.started.inputPort,
                                        width = started.started.width,
                                        height = started.started.height,
                                        audio = started.started.audio,
                                    ),
                                )
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (t: Throwable) {
                        log(t)
                    }
                }
            }
            val streamMessage = when {
                input.guide != null -> input.guide
                started != null && !started.started.audio -> TogetherCopy.streamNoSound
                else -> null
            }
            _view.value = _view.value?.copy(
                step = HostStep.IN_GAME,
                message = streamMessage,
                problem = null,
                streamLine = if (started != null) TogetherCopy.hostStreamWaiting else null,
            )
            if (started != null) {
                stream = started
                startStreamPoll(input.round)
 // The game is in front now: capture only its app (the host picks it in Android's share prompt).
                launchSession { runCatching { withContext(ioDispatcher) { streamHost.attachCapture() } }.onFailure(::log) }
            }
            val name = _view.value?.guestName ?: TogetherCopy.otherPhone
            platform.updateService(TogetherCopy.ongoingPlaying(name, game?.title ?: ""))
            if (mode == TogetherMode.GB_LINK) {
                val launchEpoch = epoch
                val launchRound = round
                headStartJob = launchStep {
                    try {
                        kotlinx.coroutines.delay(TogetherCaps.GB_HOST_HEADSTART_S * 1_000L)
                        post(Input.HeadStart(launchEpoch, launchRound))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    }
                }
            }
        } else {
 // The game didn't start: the stream it was meant for must not outlive it.
            if (input.stream != null) launchSession { runCatching { withContext(ioDispatcher) { streamHost.stop() } }.onFailure(::log) }
            val message = TogetherCopy.launchFailed(TogetherCopy.outcomeText(outcome))
            _view.value = _view.value?.copy(
                step = if (input.round == 1) HostStep.JOINED else HostStep.IN_GAME,
                message = message,
            )
        }
    }

    private fun handleHeadStart(input: Input.HeadStart) {
        if (!isCurrent(input.epoch) || input.round != round || _view.value?.step != HostStep.IN_GAME) return
        val currentGuest = guest ?: return
        val currentSession = sessionId ?: return
        if (mode != TogetherMode.GB_LINK) return
        launchSession {
            try {
                withContext(ioDispatcher) {
                    currentGuest.send(LinkMsg.HostLive(currentSession, input.round))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                log(t)
            }
        }
    }

 /** Only before a guest joins, and only to a mode this game lists. */
    private suspend fun handleSwitchMode(next: TogetherMode) {
        val current = _view.value ?: return
        if (guest != null || next == current.mode || next !in current.altModes) return
        if (current.step != HostStep.WAITING && current.step != HostStep.NOT_READY) return
        val id = gameId ?: return
        handleHostGame(Input.HostGame(id, next) {})
    }

 /** The host's stream line, refreshed every [StreamCaps.STATUS_POLL_MS]; also feeds the shell's app watchdog. */
    private fun startStreamPoll(pollRound: Int) {
        streamPoll?.cancel()
        lastInputPackets = -1L
        silentPolls = 0
        peerSilent = false
        val pollEpoch = epoch
        streamPoll = launchSession {
            while (true) {
                kotlinx.coroutines.delay(StreamCaps.STATUS_POLL_MS)
                val status = try {
                    withContext(ioDispatcher) { streamHost.status() }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (t: Throwable) {
                    log(t)
                    null
                }
                post(Input.StreamPolled(pollEpoch, pollRound, status))
                if (status == null || !status.running) break
            }
        }
    }

    private fun handleStreamPolled(input: Input.StreamPolled) {
        if (!isCurrent(input.epoch) || input.round != round || stream == null) return
        val status = input.status
        if (status == null || !status.running) {
            stream = null
            streamPoll = null
            streamGrace?.cancel()
            streamGrace = null
            val text = status?.error?.let { TogetherCopy.streamStartFailed(it) } ?: TogetherCopy.streamStopped
            _view.value = _view.value?.copy(streamLine = null, message = text)
            return
        }
        val name = _view.value?.guestName ?: streamGuestName ?: TogetherCopy.otherPhone
        streamGuestConnected = status.guestConnected
        watchForSilence(status, name)
        val line = if (status.guestConnected) TogetherCopy.hostStreamLine(name, status.fps, status.bitrate) else TogetherCopy.hostStreamWaiting
        _view.value = _view.value?.copy(streamLine = line)
    }

 /**
 * The guest's input sender sends at least every 50 ms, so a connected guest whose packet count stands
 * still across two polls (2+ s) has dropped off without the link noticing yet: warn the host at once
 * (a heads-up while the game is on screen) and say when it is back.
 */
    private fun watchForSilence(status: StreamStatus, name: String) {
        val packets = status.inputPackets
        val moving = packets != lastInputPackets
        lastInputPackets = packets
        silentPolls = if (status.guestConnected && !moving) silentPolls + 1 else 0
        if (!peerSilent && silentPolls >= SILENT_POLLS && _view.value?.linkLost != true) {
            peerSilent = true
            _view.value = _view.value?.copy(message = TogetherCopy.streamPeerSilent(name))
            if (!platform.foreground.value) platform.alert(TogetherAlert.PeerSilent(name))
        } else if (peerSilent && silentPolls == 0) {
            peerSilent = false
            if (_view.value?.message == TogetherCopy.streamPeerSilent(name)) _view.value = _view.value?.copy(message = null)
        }
    }

 /** Stops the stream and removes player 2's pad; the host keeps playing. */
    private fun stopStream() {
        streamPoll?.cancel()
        streamPoll = null
        streamGrace?.cancel()
        streamGrace = null
        streamGuestConnected = false
        lastInputPackets = -1L
        silentPolls = 0
        peerSilent = false
        if (stream == null) return
        stream = null
        _view.value = _view.value?.copy(streamLine = null)
        launchSession { runCatching { withContext(ioDispatcher) { streamHost.stop() } }.onFailure(::log) }
    }

    private suspend fun handleAct(action: TogetherAction) {
        when (action) {
            TogetherAction.RECHECK -> {
                if (_view.value?.step == HostStep.NOT_READY && _view.value?.problem != null && !coreFetchRunning) {
                    epoch += 1
                    cancelStepJobs()
                    _view.value = _view.value?.copy(step = HostStep.CHECKING, message = null)
                    val id = gameId ?: return
                    beginReadiness(id, epoch, publishedToken())
                }
            }
            TogetherAction.INSTALL_CORE -> {
                if (_view.value?.step != HostStep.NOT_READY || coreFetchRunning) return
                val currentGame = game ?: return
                val currentCore = core ?: return
                val launchEpoch = epoch
                coreFetchRunning = true
                launchStep {
                    try {
                        val result = withContext(ioDispatcher) {
                            emulators.fetchAndInstallCore(currentCore, currentGame) { read, total ->
                                val pct = if (total <= 0L) 0 else ((read * 100L / total).toInt().coerceIn(0, 100))
                                post(Input.CoreFetch(launchEpoch, progress = pct))
                            }
                        }
                        post(Input.CoreFetch(launchEpoch, result = result))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (t: Throwable) {
                        log(t)
                        post(
                            Input.CoreFetch(
                                launchEpoch,
                                result = CoreResult.Failed(TogetherCopy.noWifi),
                            ),
                        )
                    }
                }
            }
            TogetherAction.OPEN_SYSTEM, TogetherAction.OPEN_PPSSPP, TogetherAction.OPEN_RETROARCH -> Unit
            else -> Unit
        }
    }

    private fun handleCoreFetch(input: Input.CoreFetch) {
        if (!isCurrent(input.epoch) || _view.value?.step != HostStep.NOT_READY) return
        if (input.progress != null) {
            _view.value = _view.value?.copy(message = TogetherCopy.fetchingCore(input.progress))
        }
        val result = input.result ?: return
        coreFetchRunning = false
        when (result) {
            CoreResult.Started -> {
                _view.value = _view.value?.copy(
                    message = null,
                    problem = Problem(TogetherCopy.installingCore, TogetherAction.RECHECK),
                )
            }
            is CoreResult.Failed -> {
                _view.value = _view.value?.copy(problem = Problem(result.text, TogetherAction.INSTALL_CORE))
            }
        }
    }

    private suspend fun handleForeground() {
        if (_view.value?.step != HostStep.NOT_READY || _view.value?.problem == null || coreFetchRunning) return
        epoch += 1
        cancelStepJobs()
        _view.value = _view.value?.copy(step = HostStep.CHECKING, message = null)
        val id = gameId ?: return
        beginReadiness(id, epoch, publishedToken())
    }

    private fun sendCore(link: Link, requestedCore: String) {
        val currentSession = sessionId ?: return
        val available = hostCore
        val matches = available != null && available.build.core == requestedCore
        if (!matches) {
            launchSession {
                try {
                    withContext(ioDispatcher) {
                        link.send(LinkMsg.CoreUnavailable(currentSession, requestedCore))
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (t: Throwable) {
                    log(t)
                }
            }
            return
        }
        val file = available!!.file
        val build = available.build
        val blobId = ++blobSeq
        launchSession {
            try {
                val validSize = withContext(ioDispatcher) {
                    file.length() <= RetroCoreFetcher.MAX_CORE_ZIP_BYTES
                }
                if (!validSize) {
                    withContext(ioDispatcher) {
                        link.send(LinkMsg.CoreUnavailable(currentSession, requestedCore))
                    }
                    return@launchSession
                }
                withContext(ioDispatcher) {
                    link.send(LinkMsg.CoreBegin(currentSession, blobId, build))
                    link.sendBlob(blobId, file)
                    link.send(LinkMsg.CoreEnd(currentSession, blobId))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                log(t)
            }
        }
    }

    private suspend fun sendByeAndClose(link: Link?, reason: com.gamebex.client.together.ByeReason) {
        if (link == null) return
        try {
            withTimeoutOrNull(timing.byeMs) {
                withContext(ioDispatcher) { link.send(LinkMsg.Bye(reason)) }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            log(t)
        } finally {
            runCatching { link.close() }.onFailure(::log)
        }
    }

    private suspend fun endInternal(updateView: Boolean, token: Long) {
        if (sessionEnded) {
            if (updateView) writeEnded(token)
            return
        }

        runCatching {
            cancelStepJobs()
            cancelSessionJobs()
        }.onFailure(::log)
        runCatching { criticalLaunches.join(2_000L) }.onFailure(::log)
        runCatching { sendByeAndClose(guest, com.gamebex.client.together.ByeReason.LEFT) }.onFailure(::log)
 // The pad goes first, then player 2's emulator settings come back.
        runCatching {
            streamPoll = null
            if (stream != null || mode == TogetherMode.STREAM) withContext(ioDispatcher) { streamHost.stop() }
            stream = null
        }.onFailure(::log)
        runCatching { if (mode == TogetherMode.STREAM) withContext(ioDispatcher) { player2.restore() } }.onFailure(::log)
        runCatching { resources?.closeAll() }.onFailure(::log)
        runCatching { withContext(ioDispatcher) { emulators.endSession() } }.onFailure(::log)
        runCatching { platform.cancelAlerts() }.onFailure(::log)
        runCatching {
            headStartJob = null
            guest = null
            guestHello = null
            listener = null
            ad = null
            sessionEnded = true
            epoch += 1
            if (updateView) writeEnded(token)
            sessionId = null
        }.onFailure(::log)
    }

    private fun isCurrent(expectedEpoch: Int = epoch): Boolean =
        !sessionEnded && expectedEpoch == epoch

    private fun publishedToken(): Long = publishedToken

    private fun replaceView(token: Long, view: HostView) {
        synchronized(viewLock) {
            publishedToken = token
            _view.value = view
        }
    }

    private fun writeEnded(token: Long) {
        synchronized(viewLock) {
            if (publishedToken != token) return
            val current = _view.value ?: return
            if (current.step == HostStep.ENDED) return
            _view.value = current.copy(step = HostStep.ENDED)
        }
    }

    private fun post(input: Input) {
        inputs.trySend(input)
    }

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
        criticalLaunches.cancel(synchronized(stepJobs) { stepJobs.toList() })
    }
    private fun cancelSessionJobs() {
        synchronized(sessionJobs) { sessionJobs.toList() }.forEach { it.cancel() }
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

    private fun log(t: Throwable) {
        runCatching { Log.w("GameBEXTogether", t.message, t) }
    }
}
