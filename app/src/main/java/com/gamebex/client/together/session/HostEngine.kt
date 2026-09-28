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
) {
    private sealed interface Input {
        data class HostGame(
            val gameId: Long,
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
        ) : Input
        data class HeadStart(val epoch: Int, val round: Int) : Input
    }

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
        refreshPlayerName()
        replaceView(token, HostView(HostStep.CHECKING, input.gameId, "", "", null, myName))
        beginReadiness(input.gameId, epoch, token)
    }

    private fun beginReadiness(id: Long, startedEpoch: Int, token: Long) {
        launchStep {
            try {
                val found = withContext(ioDispatcher) { library.game(id) }
                if (found == null) {
                    post(Input.ReadinessDone(startedEpoch, null, null, null, null, token))
                    return@launchStep
                }
                val foundMode = TogetherSupport.modeFor(found)
                if (foundMode == null) {
                    post(Input.ReadinessDone(startedEpoch, found, null, null, null, token))
                    return@launchStep
                }
                val foundCore = TogetherSupport.coreFor(found, foundMode)
                val readiness = withContext(ioDispatcher) {
                    emulators.readiness(foundMode, Role.HOST, found, foundCore)
                }
                post(Input.ReadinessDone(startedEpoch, found, foundMode, foundCore, readiness, token))
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
                )
            }
            else -> {
                _view.value = current.copy(
                    gameTitle = input.game.title,
                    folder = input.game.folder,
                    mode = input.mode,
                    problem = null,
                    message = null,
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
                val setupOffer = withContext(ioDispatcher) { library.offer(setupGame.id) }
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
                        val outcome = try {
                            withContext(ioDispatcher) {
                                emulators.launch(
                                    TogetherLaunch(
                                        sessionId = currentSessionId,
                                        role = Role.HOST,
                                        mode = currentMode,
                                        game = currentGame,
                                        core = currentOffer.core,
                                        hostAddress = currentAddress,
                                        playerName = myName,
                                    ),
                                )
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (t: Throwable) {
                            log(t)
                            EmuRouter.Outcome.Failed(TogetherCopy.noWifi)
                        }
                        post(Input.HostLaunched(launchEpoch, launchRound, outcome))
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

    private suspend fun handleHostLaunched(input: Input.HostLaunched) {
        if (!isCurrent(input.epoch) || input.round != round || _view.value?.step != HostStep.LAUNCHING) return
        val outcome = input.outcome
        if (outcome is EmuRouter.Outcome.Launched) {
            val currentGuest = guest
            val currentSession = sessionId
            if (currentGuest != null && currentSession != null) {
                launchSession {
                    try {
                        withContext(ioDispatcher) {
                            currentGuest.send(LinkMsg.Launch(currentSession, input.round))
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (t: Throwable) {
                        log(t)
                    }
                }
            }
            _view.value = _view.value?.copy(
                step = HostStep.IN_GAME,
                message = null,
                problem = null,
            )
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
