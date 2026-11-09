package com.gamebex.client.together.session

import android.content.Context
import android.os.Environment
import android.util.Log
import com.gamebex.client.data.Emu
import com.gamebex.client.data.local.GameEntity
import com.gamebex.client.launch.ControllerRegistry
import com.gamebex.client.launch.CoreInstallProgress
import com.gamebex.client.launch.DirectFiles
import com.gamebex.client.launch.EmuControllerConfig
import com.gamebex.client.launch.EmuFiles
import com.gamebex.client.launch.EmuRouter
import com.gamebex.client.launch.RetroCoreFetcher
import com.gamebex.client.launch.RetroCoreProbe
import com.gamebex.client.together.CoreFile
import com.gamebex.client.together.CoreResult
import com.gamebex.client.together.Discovery
import com.gamebex.client.together.HostAd
import com.gamebex.client.together.HostStep
import com.gamebex.client.together.HostViewport
import com.gamebex.client.together.HostViewportInfo
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
import com.gamebex.client.together.touchBackendForEmuPkg
import com.gamebex.client.together.TogetherLibrary
import com.gamebex.client.together.TogetherMode
import com.gamebex.client.together.TogetherPlatform
import com.gamebex.client.together.TogetherSupport
import com.gamebex.client.together.TogetherTiming
import com.gamebex.client.together.TogetherCaps
import com.gamebex.client.together.TOGETHER_PROTO
import com.gamebex.client.stream.shell.CropRect
import com.gamebex.client.stream.shell.StreamCropOptions
import com.gamebex.client.stream.shell.shellFlag
import com.gamebex.client.stream.shell.streamCropRect
import com.gamebex.client.together.net.JoinLink
import com.gamebex.client.stream.NoStreamHost
import com.gamebex.client.stream.NoStreamPlayer2
import com.gamebex.client.stream.P2Prep
import com.gamebex.client.stream.p2.P2SlotMode
import com.gamebex.client.stream.p2.P2SlotWorkaround
import com.gamebex.client.stream.StreamCaps
import com.gamebex.client.stream.StreamConfig
import com.gamebex.client.stream.StreamDebug
import com.gamebex.client.stream.StreamEmus
import com.gamebex.client.stream.StreamFlags
import com.gamebex.client.stream.StreamHost
import com.gamebex.client.stream.StreamPlayer2
import com.gamebex.client.stream.StreamStarted
import com.gamebex.client.stream.StreamStatus
import com.gamebex.client.stream.StreamToken
import com.gamebex.client.stream.guest.HostOverlayBundle
import com.gamebex.client.stream.guest.RaOverlaySettings
import com.gamebex.client.stream.proto.StreamWire
import java.io.File
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch

private const val CONSUMER_FAILURE = "Something went wrong."
private const val CORE_INSTALL_STALL_MS = 8_000L
private const val CORE_INSTALL_CANCELLED = "Core installation cancelled."

/** STREAM: status polls (2 s apart) with no new guest input before the host is warned the guest went quiet. */
private const val SILENT_POLLS = 2

/**
 * STREAM: attach waits until this long after the emulator fired, so its task
 * stack is settled before the consent proxy starts. A proxy started into a
 * mid-boot task shuffle (Dolphin's MainActivity→EmulationActivity hop at
 * ~546ms) dies on a stale START when the grant returns. The fire
 * itself, the Launch/StreamReady sends and the guest viewer never wait —
 * only the share prompt shifts a couple of seconds, onto the booted game.
 */
internal const val ATTACH_STABLE_MS = 3_000L

/** RetroArch's shared config dir; null when storage is unreachable. */
private fun defaultOverlayConfigDir(): File? = runCatching {
    @Suppress("DEPRECATION")
    File(Environment.getExternalStorageDirectory(), "RetroArch").takeIf { it.isDirectory }
}.getOrNull()

/** Shared-storage Android/data root. */
private fun defaultEmuAndroidData(): File? = runCatching {
    @Suppress("DEPRECATION")
    File(Environment.getExternalStorageDirectory(), "Android/data").takeIf { it.isDirectory }
}.getOrNull()

/** The stream session config GameBEX wrote for RetroArch. */
private fun defaultStreamCfgText(): String? = runCatching {
    @Suppress("DEPRECATION")
    com.gamebex.client.stream.p2.StreamRaConfig
        .fileUnder(Environment.getExternalStorageDirectory())
        .takeIf { it.isFile }?.readText()
}.getOrNull()

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
    /** A game's modes, best first. Tests pass their own. */
    private val modesFor: (GameEntity) -> List<TogetherMode> = TogetherSupport::modesFor,
    private val streamAudioOverride: () -> String? = { StreamDebug.prop("ra_audio") },
    /** STREAM: post-fire settle before the consent proxy starts ([ATTACH_STABLE_MS]). Tests pass their own. */
    private val attachStableMs: () -> Long = { ATTACH_STABLE_MS },
    /** Flag `overlay_26b` (default off): share the host's RetroArch overlay with the guest. */
    private val overlay26bEnabled: () -> Boolean = { false },
    /** RetroArch's shared config dir (`.../RetroArch`); null skips the overlay. Tests pass temp dirs. */
    private val overlayConfigDir: () -> File? = { defaultOverlayConfigDir() },
    /** Seeds the core's overlay from the RA APK when RA configures none. Tests pass their own. */
    private val seedOverlayForCore: (String?) -> File? = { null },
    /** The stream session config text RetroArch loads (CONFIGFILE). */
    private val streamCfgText: () -> String? = { defaultStreamCfgText() },
    /** The host display + overlay viewport (overlay coords are fractions of the viewport). */
    private val hostViewportInfo: () -> HostViewportInfo = { HostViewportInfo.UNKNOWN },
    /** App context for reading installed emulator art (PM resources/APK). Null skips emu overlays. */
    private val appContext: () -> Context? = { null },
    /** File backend for emulator configs (Shizuku shell-uid in prod). Tests pass their own. */
    private val emuOverlayFiles: () -> EmuFiles = { DirectFiles },
    /** Host density dpi (Flycast layouts are dpi-aware). */
    private val hostDensityDpi: () -> Int = { 0 },
    /** Shared-storage Android/data root (emulator configs live under it). */
    private val emuAndroidData: () -> File? = { defaultEmuAndroidData() },
    /** STREAM host flags: raw in-app value or null when unset. Tests pass their own. */
    private val hostFlag: (String) -> String? = { null },
    /** A real host pad means gate-on-pad, else the placeholder. Tests pass their own. */
    private val hasRealHostPad: () -> Boolean = {
        runCatching { ControllerRegistry.snapshot().isNotEmpty() }.getOrDefault(false)
    },
    private val controlOptions: () -> TogetherControlOptions = TogetherControlOptions::read,
    private val controlLog: (String) -> Unit = { Log.i("GameBEXControl", it) },
    private val monotonicNs: () -> Long = System::nanoTime,
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
        data class Incoming(val token: Long, val resources: SessionResources, val link: Link, val hello: LinkMsg.Hello) : Input
        data class LinkEvt(val link: Link, val event: LinkEvent) : Input
        data class CoreFetch(
            val install: CoreInstall,
            val read: Long? = null,
            val total: Long = 0L,
            val sequence: Long = 0L,
            val result: CoreResult? = null,
        ) : Input
        data class CoreFetchFinished(val install: CoreInstall) : Input
        data class CoreFetchStalled(val install: CoreInstall, val sequence: Long) : Input
        data class HostLaunched(
            val epoch: Int,
            val round: Int,
            val outcome: EmuRouter.Outcome,
            /** STREAM: the stream started for this round. */
            val stream: StreamRound? = null,
            /** STREAM: player-2 steps to show once the game runs. */
            val guide: String? = null,
            /** STREAM: hosted game folder for the guest touch layout; "" = unknown. */
            val system: String = "",
            /** STREAM: actual host emulator backend for the guest touch layout; "" = unknown. */
            val backend: String = "",
            /** STREAM: nanoTime right before the emulator fired (0 = unknown); attach waits for settle. */
            val firedAtNs: Long = 0L,
            /** STREAM: hosted game file ext; bare dol/elf boots GC mode in Dolphin. */
            val gameExt: String = "",
        ) : Input
        data class HeadStart(val epoch: Int, val round: Int) : Input
        data class SwitchMode(val mode: TogetherMode) : Input
        data class StreamPolled(val epoch: Int, val round: Int, val status: StreamStatus?) : Input
        data class StreamGraceOver(val epoch: Int, val round: Int, val serial: Long) : Input
        data class DebugCutLink(val epoch: Int, val round: Int, val link: Link) : Input
    }

    /** Actor-owned identity: old callbacks/completions must never release a replacement install. */
    private class CoreInstall(val epoch: Int, val token: Long, core: String) {
        val callbackSequence = AtomicLong()
        var progressSequence = 0L
        var progress = CoreInstallProgress(core)
        var job: Job? = null
        var stallJob: Job? = null
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
    private var coreInstall: CoreInstall? = null
    private var resources: SessionResources? = null
    private var headStartJob: Job? = null
    private var forcedMode: TogetherMode? = null
    /** STREAM: the stream of the current round, or null. */
    private var stream: StreamRound? = null
    private var streamPoll: Job? = null
    /** STREAM: the guest's link dropped and the stream is being kept for [TogetherTiming.streamLinkGraceMs]. */
    private var streamGrace: Job? = null
    private var streamGraceSerial = 0L
    private var streamResumeUntilNs: Long? = null
    private var streamResumeOwner: StreamResumeGuard.Bound? = null
    private var controlSnapshot = TogetherControlOptions()
    private var debugCutJob: Job? = null
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
            is Input.CoreFetchFinished -> handleCoreFetchFinished(input.install)
            is Input.CoreFetchStalled -> handleCoreFetchStalled(input)
            is Input.HostLaunched -> handleHostLaunched(input)
            is Input.HeadStart -> handleHeadStart(input)
            is Input.SwitchMode -> handleSwitchMode(input.mode)
            is Input.StreamPolled -> handleStreamPolled(input)
            is Input.StreamGraceOver -> handleStreamGraceOver(input)
            is Input.DebugCutLink -> if (isCurrent(input.epoch) && input.round == round &&
                input.link === guest && _view.value?.step == HostStep.IN_GAME
            ) input.link.close()
        }
    }

    private suspend fun handleHostGame(input: Input.HostGame) {
        val token = publishedToken()
        input.beforeStart()
        if (!sessionEnded) endInternal(updateView = false, token = token)
        if (token != publishedToken()) return

        epoch += 1
        sessionEnded = false
        controlSnapshot = withContext(ioDispatcher) { controlOptions() }
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
        coreInstall?.let { releaseCoreInstall(it, cancel = true) }
        resources = SessionResources()
        headStartJob?.cancel()
        headStartJob = null
        forcedMode = input.forcedMode
        stream = null
        streamPoll?.cancel()
        streamPoll = null
        streamGrace?.cancel()
        streamGrace = null
        streamResumeUntilNs = null
        streamGraceSerial += 1
        streamResumeOwner = null
        debugCutJob?.cancel()
        debugCutJob = null
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
        val acceptToken = publishedToken()
        val sessionResources = resources ?: return
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
                if (!sessionResources.register { next.close() }) {
                    next.close()
                    continue
                }
                launchSession { inspectIncoming(next, acceptToken, sessionResources) }
            }
        }
    }

    private suspend fun inspectIncoming(link: Link, acceptToken: Long, sessionResources: SessionResources) {
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
        if (first is LinkMsg.Hello && (first.role == Role.GUEST || first.streamResume != null) && !sessionEnded) {
            post(Input.Incoming(acceptToken, sessionResources, link, first))
        } else {
            link.close()
        }
    }

    private suspend fun handleIncoming(input: Input.Incoming) {
        if (input.hello.streamResume != null) {
            handleStreamResume(input)
            return
        }
        if (!isCurrent() || input.token != publishedToken() || input.resources !== resources || _view.value?.step != HostStep.WAITING || guest != null) {
            sendByeAndClose(input.link, com.gamebex.client.together.ByeReason.FULL)
            return
        }
        if (input.hello.proto != TOGETHER_PROTO) {
            sendByeAndClose(input.link, com.gamebex.client.together.ByeReason.VERSION)
            return
        }
        // A STREAM guest must speak this host's stream protocol.
        if (mode == TogetherMode.STREAM && input.hello.streamProto != StreamWire.VERSION) {
            _view.value = _view.value?.copy(message = TogetherCopy.byeText(com.gamebex.client.together.ByeReason.VERSION, input.hello.name))
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
            instructions = TogetherCopy.hostInstructions(
                currentMode,
                p2SlotPrompt = currentMode == TogetherMode.STREAM &&
                    game?.let(::resolveP2SlotMode) == P2SlotMode.GATE_REAL_PAD,
            ),
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

    private suspend fun handleStreamResume(input: Input.Incoming) {
        val request = input.hello.streamResume
        val bound = streamResumeOwner
        val validIdentity = controlSnapshot.controlResume && isCurrent() && input.token == publishedToken() && input.resources === resources &&
            mode == TogetherMode.STREAM && _view.value?.step == HostStep.IN_GAME && stream != null &&
            bound != null && request != null &&
            input.hello.proto == TOGETHER_PROTO && input.hello.streamProto == StreamWire.VERSION &&
            input.hello.role == Role.GUEST && StreamResumeGuard.validAttempt(request.attempt) &&
            StreamResumeGuard.matches(bound, request.sessionId, request.round, request.token,
                input.hello.deviceId, input.link.remoteAddress)
        if (guest != null && validIdentity) {
            sendByeAndClose(input.link, com.gamebex.client.together.ByeReason.FULL)
            return
        }
        if (!validIdentity || streamGrace == null || streamResumeUntilNs?.let { monotonicNs() < it } != true) {
            sendByeAndClose(input.link, com.gamebex.client.together.ByeReason.ERROR)
            return
        }
        val accepted = requireNotNull(request)
        val owner = requireNotNull(bound)
        guest = input.link
        guestHello = input.hello.copy(streamResume = null)
        streamGrace?.cancel()
        streamGrace = null
        streamResumeUntilNs = null
        streamGraceSerial += 1
        _view.value = _view.value?.copy(guestName = input.hello.name, linkLost = false, message = null)
        startGuestForwarder(input.link)
        startDebugCuts(input.link)
        recordControl("control_resumed")
        launchSession {
            try {
                withContext(ioDispatcher) {
                    input.link.send(LinkMsg.StreamResumed(accepted.sessionId, accepted.round, accepted.attempt, owner.audioTimestampOrigin))
                }
            } catch (cancelled: CancellationException) {
                input.link.close()
                throw cancelled
            } catch (_: Throwable) {
                input.link.close()
                post(Input.LinkEvt(input.link, LinkEvent.Closed(null)))
            }
        }
    }

    private fun startDebugCuts(link: Link) {
        debugCutJob?.cancel()
        debugCutJob = null
        val interval = controlSnapshot.linkCutEveryS.coerceIn(0, 120) * 1_000L
        if (interval == 0L) return
        val cutEpoch = epoch
        val cutRound = round
        debugCutJob = launchSession {
            delay(interval)
            post(Input.DebugCutLink(cutEpoch, cutRound, link))
        }
    }

    private fun recordControl(event: String) {
        runCatching { controlLog("event=$event monoNs=${System.nanoTime()} round=$round") }
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
                is LinkMsg.StreamOverlayRequest -> handleOverlayRequest(input.link, message)
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
        recordControl("control_lost")
        debugCutJob?.cancel()
        debugCutJob = null
        guest = null
        guestHello = null
        clearGuestFields()
        streamGuestName = name
        val seconds = (timing.streamLinkGraceMs / 1_000L).toInt().coerceAtLeast(1)
        _view.value = _view.value?.copy(step = HostStep.IN_GAME, linkLost = true, message = TogetherCopy.streamLinkLostHost(name, seconds))
        if (!platform.foreground.value) platform.alert(TogetherAlert.LinkLost(name, streamGraceS = seconds))
        val graceEpoch = epoch
        val graceRound = round
        val graceSerial = ++streamGraceSerial
        streamResumeUntilNs = monotonicNs() + timing.streamLinkGraceMs * 1_000_000L
        streamGrace?.cancel()
        streamGrace = launchSession {
            kotlinx.coroutines.delay(timing.streamLinkGraceMs)
            post(Input.StreamGraceOver(graceEpoch, graceRound, graceSerial))
        }
    }

    private fun handleStreamGraceOver(input: Input.StreamGraceOver) {
        if (!isCurrent(input.epoch) || input.round != round || input.serial != streamGraceSerial ||
            guest != null || stream == null
        ) return
        streamGrace = null
        streamResumeUntilNs = null
        streamResumeOwner = null
        recordControl("control_resume_failed")
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
        streamResumeOwner = null
        streamResumeUntilNs = null
        streamGraceSerial += 1
        debugCutJob?.cancel()
        debugCutJob = null
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
                        var firedAt = 0L
                        val outcome = try {
                            withContext(ioDispatcher) {
                                // Player 2's pad exists before the emulator starts, so it sees it at boot.
                                if (currentMode == TogetherMode.STREAM) prep = prepareStream(currentGame, guestAddress)
                                prep.failure ?: run {
                                    firedAt = monotonicNs()
                                    emulators.launch(
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
                                ).also { launched ->
                                    if (launched is EmuRouter.Outcome.Launched && prep.round != null) {
                                        runCatching { streamHost.gameLaunched() }.onFailure(::log)
                                    }
                                }
                                }
                            }
                        } catch (cancelled: CancellationException) {
                            if (prep.round != null) {
                                withContext(NonCancellable + ioDispatcher) {
                                    runCatching { streamHost.stop() }.onFailure(::log)
                                }
                            }
                            throw cancelled
                        } catch (t: Throwable) {
                            log(t)
                            EmuRouter.Outcome.Failed(TogetherCopy.noWifi)
                        }
                        post(Input.HostLaunched(launchEpoch, launchRound, outcome, prep.round, prep.guide,
                            system = currentGame.folder,
                            backend = touchBackendForEmuPkg((outcome as? EmuRouter.Outcome.Launched)?.emuPkg),
                            firedAtNs = firedAt,
                            gameExt = currentGame.ext))
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
     * The P2 slot workaround mode for this host (signer + version +
     * pads + flag). Sampled per session; the shell applies it.
     */
    private fun resolveP2SlotMode(currentGame: GameEntity): P2SlotMode {
        val emu = StreamEmus.emuFor(currentGame)
        val ctx = appContext()
        val signer = ctx?.let { P2SlotWorkaround.signerSha256(it, Emu.RETROARCH.pkg) }
        val version = ctx?.let { RetroCoreProbe.versionCode(it) } ?: 0L
        return P2SlotWorkaround.decide(emu, signer, version, hasRealHostPad(), hostFlag("p2_slot_workaround"))
    }

    /**
     * STREAM, before the host's emulator starts: bind player 2, then
     * start capture, encoding and the pad for the joined guest only.
     */
    private suspend fun prepareStream(currentGame: GameEntity, guestAddress: String?): StreamPrep {
        if (guestAddress == null) return StreamPrep(failure = EmuRouter.Outcome.Failed(TogetherCopy.streamNetwork))
        val emu = StreamEmus.emuFor(currentGame)
            ?: return StreamPrep(failure = EmuRouter.Outcome.Blocked(TogetherCopy.notSupported))
        var prepared = player2.prepare(currentGame, emu, rsoundReady = false, p2SlotMode = resolveP2SlotMode(currentGame))
        if (prepared is P2Prep.Failed) return StreamPrep(failure = EmuRouter.Outcome.Failed(prepared.text))
        if (emu == Emu.DOLPHIN) ensureDolphinStreamOverlay()
        val rsoundRequested = emu == Emu.RETROARCH && streamAudioOverride() != "opensl"
        val token = StreamToken.generate()
        val vp = hostViewportInfo()
        val cropVp = if (vp.viewport.isEmpty || vp.displayW <= 0 || vp.displayH <= 0) ""
        else "${vp.viewport.x},${vp.viewport.y},${vp.viewport.w},${vp.viewport.h},${vp.displayW},${vp.displayH}"
        val startLevelProp = if (com.gamebex.client.BuildConfig.DEBUG) {
            runCatching { com.gamebex.client.stream.StreamDebug.prop("start_level")?.toIntOrNull() }.getOrNull()
        } else null
        val streamConfig = streamConfigForFlags(
            token, guestAddress, rsoundRequested, hostFlag, cropVp, startLevelProp,
            p2SlotMode = resolveP2SlotMode(currentGame).wire,
            cropAspect = StreamEmus.cropAspectForRaCore(StreamEmus.raCore(currentGame)),
        )
        val started = streamHost.start(streamConfig)
        if (!started.ok) {
            return StreamPrep(failure = EmuRouter.Outcome.Failed(TogetherCopy.streamStartFailed(started.error)))
        }
        var handedOff = false
        try {
            if (rsoundRequested && started.rsound) {
                prepared = player2.prepare(currentGame, emu, rsoundReady = true, p2SlotMode = resolveP2SlotMode(currentGame))
                if (prepared is P2Prep.Failed) return StreamPrep(failure = EmuRouter.Outcome.Failed(prepared.text))
            }
            return StreamPrep(
                round = StreamRound(token, started),
                p2Config = (prepared as? P2Prep.RetroArchConfig)?.cfg,
                guide = (prepared as? P2Prep.Guide)?.lines?.joinToString(" "),
            ).also { handedOff = true }
        } finally {
            if (!handedOff) {
                withContext(NonCancellable) { runCatching { streamHost.stop() }.onFailure(::log) }
            }
        }
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
                                        audioTimestampOrigin = if (started.started.rsound) "bridge-arrival" else "capture",
                                        system = input.system,
                                        backend = input.backend,
                                    ),
                                )
                                // The guest renders the host's own overlay when it can use it.
                                hostOverlayMessage(game, input.backend, currentSession, input.round)?.forEach {
                                    currentGuest.send(it)
                                }
                                // Standalone emulators send their own touch controls.
                                // Per-part: one oversized part must not starve the rest (part 1
                                // threw and part 2 was never attempted). The guest draws what
                                // arrives and re-requests the rest.
                                val emuPkg = (input.outcome as? EmuRouter.Outcome.Launched)?.emuPkg
                                hostEmuOverlayMessages(emuPkg, input.system, input.backend, currentSession, input.round, input.gameExt)
                                    ?.forEach { runCatching { currentGuest.send(it) }.onFailure(::log) }
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
                streamResumeOwner = if (currentSession != null && currentGuest != null && guestHello != null) {
                    StreamResumeGuard.Bound(currentSession, input.round, started.token,
                        requireNotNull(guestHello).deviceId, currentGuest.remoteAddress,
                        if (started.started.rsound) "bridge-arrival" else "capture")
                } else null
                startStreamPoll(input.round)
                currentGuest?.let(::startDebugCuts)
                // The game is in front now: capture only its app (the host picks it in Android's share prompt).
                // The proxy waits for the task stack to settle first: started into a mid-boot shuffle it
                // dies on a stale START when the grant returns. BAL (no visible
                // activity this late) is defeated with a 1px overlay window; see StreamAppCapture.
                launchSession {
                    runCatching {
                        val elapsedMs = (monotonicNs() - input.firedAtNs) / 1_000_000
                        val waitMs = (attachStableMs() - elapsedMs).coerceAtLeast(0)
                        if (waitMs > 0) delay(waitMs)
                        withContext(ioDispatcher) { streamHost.attachCapture() }
                    }.onFailure(::log)
                }
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

    /**
     * The host's active RetroArch overlay for the guest — cfg, effective
     * settings and full-size images in frame-fitting parts — or null when
     * the flag is off, the host runs another emulator, or nothing is
     * readable (then the guest falls back to its own RA stock overlay).
     * Runs on IO: reads shared storage. Remembers the cfg file to answer
     * the guest's re-requests.
     */
    /**
     * The rect the stream's encoder crops to, in host-display px — the
     * shell's exact computation ([streamCropRect] with the shell's
     * effective flag/aspect/viewport inputs; the shell-uid encoder and
     * this app-uid host read the same adb props on debug builds) — or
     * null when the shell streams full-frame (flag off, unknown display,
     * the full-frame bail-out [streamCropEncodeLevel] applies, or an
     * EGL-less fallback the guest can't see; the guest's aspect guard
     * covers the last case). The capture runs display-native so the
     * stamp is the content rect 1:1. Stamped on the overlay sends so the
     * guest offsets its video surface instead of centering.
     */
    private fun hostCropRect(info: HostViewportInfo, cropAspect: String): CropRect? {
        if (!shellFlag("stream_crop", hostFlag("stream_crop") == "1")) return null
        if (info.displayW <= 0 || info.displayH <= 0) return null
        val propAspect = if (com.gamebex.client.BuildConfig.DEBUG) {
            runCatching { com.gamebex.client.stream.StreamDebug.prop("stream_crop_aspect") }.getOrNull()
        } else null
        val (num, den) = (StreamCropOptions.aspectOrNull(propAspect)
            ?: StreamCropOptions.aspectOrNull(cropAspect) ?: intArrayOf(4, 3)).let { it[0] to it[1] }
        val vp = info.viewport
        val vpStr = if (vp.isEmpty) "" else "${vp.x},${vp.y},${vp.w},${vp.h},${info.displayW},${info.displayH}"
        val rect = runCatching { streamCropRect(info.displayW, info.displayH, num, den, vpStr) }.getOrNull()
            ?: return null
        if (rect.width >= info.displayW && rect.height >= info.displayH) return null
        return rect
    }

    private fun hostOverlayMessage(
        currentGame: GameEntity?,
        backend: String,
        sessionId: String,
        round: Int,
    ): List<LinkMsg.StreamOverlay>? {
        if (!overlay26bEnabled()) {
            runCatching { android.util.Log.i("GameBEXTogether", "26b skip: flag off") }
            return null
        }
        if (currentGame == null || backend != "retroarch") {
            runCatching { android.util.Log.i("GameBEXTogether", "26b skip: game=$currentGame backend=$backend") }
            return null
        }
        val mainFile = runCatching { emuAndroidData()?.let { File(it, "${Emu.RETROARCH.pkg}/files/retroarch.cfg") } }.getOrNull()
        val mainText = runCatching { mainFile?.let { emuOverlayFiles().readText(it.path) } }.getOrNull()
        val configDir = overlayConfigDir() ?: mainFile?.parentFile?.takeIf { mainText != null } ?: run {
            runCatching { android.util.Log.i("GameBEXTogether", "26b skip: no RA config dir") }
            return null
        }
        val core = TogetherSupport.coreFor(currentGame, TogetherMode.RA_NETPLAY)
        val streamText = runCatching { streamCfgText() }.getOrNull()
        // RA resolves its overlay against its own window (the viewport),
        // which letterboxes off the cutout side — not the display.
        val info = runCatching { hostViewportInfo() }.getOrNull() ?: HostViewportInfo.UNKNOWN
        val vp = info.viewport
        val (screenW, screenH) = if (vp.isEmpty) 0 to 0 else vp.w to vp.h
        // A stock RetroArch configures no overlay: fall back to the
        // seeded per-core cfg (idempotent — usually already on disk
        // from session-config time) before giving up.
        // The stamp uses the session core's aspect, like the shell's
        // crop (StreamConfig.cropAspect from prepareStream).
        val cropAspect = StreamEmus.cropAspectForRaCore(StreamEmus.raCore(currentGame))
        val crop = runCatching { hostCropRect(info, cropAspect) }.getOrNull()
        val packed: Triple<File, RaOverlaySettings, List<LinkMsg.StreamOverlay>>? = runCatching {
            val cfgFile = HostOverlayBundle.activeOverlayFile(configDir, core, streamText)
            if (cfgFile != null) {
                val settings = HostOverlayBundle.effectiveSettings(configDir, core, streamText, mainText)
                HostOverlayBundle.packFile(
                    cfgFile, sessionId, round, settings, screenW, screenH,
                    screenX = vp.x, screenY = vp.y, displayW = info.displayW, displayH = info.displayH,
                    cropX = crop?.left ?: 0, cropY = crop?.top ?: 0,
                    cropW = crop?.width ?: 0, cropH = crop?.height ?: 0,
                )?.let { Triple(cfgFile, settings, it) }
            } else {
                runCatching { seedOverlayForCore(core) }.getOrNull()?.let { seeded ->
                    val settings = HostOverlayBundle.effectiveSettings(configDir, core, streamText, mainText)
                    HostOverlayBundle.packFile(
                        seeded, sessionId, round, settings, screenW, screenH,
                        screenX = vp.x, screenY = vp.y, displayW = info.displayW, displayH = info.displayH,
                        cropX = crop?.left ?: 0, cropY = crop?.top ?: 0,
                        cropW = crop?.width ?: 0, cropH = crop?.height ?: 0,
                    )?.let { Triple(seeded, settings, it) }
                }
            }
        }.getOrNull()
        lastOverlayCfg = packed?.let { Triple(it.first, sessionId, round) }
        runCatching {
            android.util.Log.i(
                "GameBEXTogether",
                if (packed == null) "26b skip: nothing packed core=$core"
                else {
                    val s = packed.second
                    "26b send: core=$core cfg=${packed.first.absolutePath} screen=${screenW}x$screenH " +
                        "viewport=${vp.x},${vp.y},${vp.w}x${vp.h} display=${info.displayW}x${info.displayH} " +
                        "crop=${crop?.let { "${it.left},${it.top},${it.width}x${it.height}" } ?: "none"} " +
                        "parts=${packed.third.size} images=${packed.third.sumOf { it.images.size }} " +
                        "opacity=${s.opacity} floatingAlpha=${s.floatingAlpha} autoScale=${s.autoScale} showInputs=${s.showInputs} " +
                        "scale=${s.scaleLandscape},${s.scalePortrait} aspect=${s.aspectAdjustLandscape},${s.aspectAdjustPortrait} " +
                        "sep=${s.xSeparationLandscape},${s.ySeparationLandscape},${s.xSeparationPortrait},${s.ySeparationPortrait} " +
                        "off=${s.xOffsetLandscape},${s.yOffsetLandscape},${s.xOffsetPortrait},${s.yOffsetPortrait}"
                },
            )
        }
        return packed?.third
    }

    /** The cfg file behind the last overlay send, for re-requests: file, session, round. */
    private var lastOverlayCfg: Triple<File, String, Int>? = null

    /**
     * The standalone emulator's own touch controls for this round —
     * art rasterized at exact host pixels plus exact rects — or null when
     * the flag is off, the host runs RetroArch, or the emulator's overlay
     * is unreadable (the guest falls back to its stock overlay). Runs on IO.
     */
    private fun hostEmuOverlayMessages(
        emuPkg: String?,
        system: String,
        backend: String,
        sessionId: String,
        round: Int,
        gameExt: String = "",
    ): List<LinkMsg.StreamEmuOverlay>? {
        if (!overlay26bEnabled()) return null
        if (emuPkg.isNullOrEmpty() || backend == "retroarch") return null
        val ctx = appContext() ?: run {
            runCatching { android.util.Log.i("GameBEXTogether", "emu skip: no app ctx") }
            return null
        }
        // Standalone emulators lay out against their own window too
        // (default cutout mode letterboxes landscape apps off the cutout
        // side); unknown viewport falls back to the display.
        val info = runCatching { hostViewportInfo() }.getOrNull() ?: HostViewportInfo.UNKNOWN
        val vp = info.viewport.takeUnless { it.isEmpty }
            ?: HostViewport(0, 0, info.displayW, info.displayH)
        val (screenW, screenH) = vp.w to vp.h
        if (screenW <= 0 || screenH <= 0) {
            runCatching { android.util.Log.i("GameBEXTogether", "emu skip: no host screen") }
            return null
        }
        val dpi = runCatching { hostDensityDpi() }.getOrDefault(0)
        val androidData = emuAndroidData() ?: run {
            runCatching { android.util.Log.i("GameBEXTogether", "emu skip: no Android/data") }
            return null
        }
        // Standalone emulators keep the historical 4:3 crop.
        val crop = runCatching { hostCropRect(info, "4:3") }.getOrNull()
        val bundle = runCatching {
            EmuOverlayReader.read(
                ctx, emuPkg, system.ifEmpty { null }, serial = null, gameExt = gameExt,
                screenW = screenW, screenH = screenH,
                screenX = vp.x, screenY = vp.y, displayW = info.displayW, displayH = info.displayH,
                cropX = crop?.left ?: 0, cropY = crop?.top ?: 0,
                cropW = crop?.width ?: 0, cropH = crop?.height ?: 0,
                density = if (dpi > 0) dpi / 160f else 0f, dpi = dpi,
                files = emuOverlayFiles(), androidData = androidData,
            )
        }.getOrNull()
        if (bundle == null) {
            lastEmuBundle = null
            runCatching { android.util.Log.i("GameBEXTogether", "emu skip: unreadable emuPkg=$emuPkg") }
            return null
        }
        val parts = EmuOverlayReader.packParts(sessionId, round, bundle)
        lastEmuBundle = Triple(bundle, sessionId, round)
        runCatching {
            android.util.Log.i(
                "GameBEXTogether",
                "emu send: backend=${bundle.backend} emuPkg=$emuPkg ext=$gameExt screen=${screenW}x$screenH " +
                    "viewport=${vp.x},${vp.y},${vp.w}x${vp.h} display=${info.displayW}x${info.displayH} " +
                    "crop=${crop?.let { "${it.left},${it.top},${it.width}x${it.height}" } ?: "none"} " +
                    "dpi=$dpi controls=${bundle.controls.size} parts=${parts.size}",
            )
        }
        return parts
    }

    /**
     * Stream-host Dolphin prep: local-play setup turns its touch overlay off
     * (physical pad), but a stream guest needs Dolphin's own controls on both
     * sides. Runs before the emulator boots, so Dolphin reads it. Best
     * effort: when the write fails the reader sees overlay-off and shares
     * nothing, and the guest keeps its stock overlay.
     */
    private fun ensureDolphinStreamOverlay() {
        runCatching {
            val androidData = emuAndroidData() ?: return@runCatching
            val files = emuOverlayFiles()
            val ini = File(androidData, "${Emu.DOLPHIN.pkg}/files/Config/Dolphin.ini").path
            val before = files.readText(ini) ?: return@runCatching
            val after = EmuControllerConfig.dolphinStreamIni(before)
            if (after !== before && after != before) {
                files.writeTextAtomic(ini, after)
                runCatching { Log.i("GameBEXTogether", "emu prep: Dolphin overlay on for stream") }
            }
        }.onFailure(::log)
    }

    /** The bundle behind the last emu overlay send, for re-requests: bundle, session, round. */
    private var lastEmuBundle: Triple<EmuOverlayReader.Bundle, String, Int>? = null

    /** Answers a guest [LinkMsg.StreamOverlayRequest] with a top-up part. Runs on IO. */
    private suspend fun handleOverlayRequest(link: Link, message: LinkMsg.StreamOverlayRequest) {
        if (message.emu) {
            handleEmuOverlayRequest(link, message)
            return
        }
        val (cfgFile, session, currentRound) = lastOverlayCfg ?: return
        if (message.sessionId != session || message.round != currentRound) return
        if (message.sessionId != sessionId) return
        val step = _view.value?.step
        if (step != HostStep.JOINED && step != HostStep.LAUNCHING && step != HostStep.IN_GAME) return
        val topUp = runCatching {
            withContext(ioDispatcher) {
                HostOverlayBundle.packRefs(cfgFile, message.sessionId, message.round, message.missing)
            }
        }.getOrNull() ?: return
        runCatching { link.send(topUp) }
        runCatching {
            android.util.Log.i("GameBEXTogether", "26b top-up: images=${topUp.images.size}")
        }
    }

    /** Answers an emu re-request with one top-up part carrying the missing controls' art. */
    private suspend fun handleEmuOverlayRequest(link: Link, message: LinkMsg.StreamOverlayRequest) {
        val (bundle, session, currentRound) = lastEmuBundle ?: return
        if (message.sessionId != session || message.round != currentRound) return
        if (message.sessionId != sessionId) return
        val step = _view.value?.step
        if (step != HostStep.JOINED && step != HostStep.LAUNCHING && step != HostStep.IN_GAME) return
        val wanted = message.missing.toSet()
        val controls = bundle.controls.filter { it.id in wanted }
        if (controls.isEmpty()) return
        val topUp = LinkMsg.StreamEmuOverlay(
            sessionId = message.sessionId,
            round = message.round,
            backend = bundle.backend,
            controls = controls,
            partIndex = -1,
            partCount = -1,
            screenX = bundle.screenX,
            screenY = bundle.screenY,
            screenW = bundle.screenW,
            screenH = bundle.screenH,
            displayW = bundle.displayW,
            displayH = bundle.displayH,
            cropX = bundle.cropX,
            cropY = bundle.cropY,
            cropW = bundle.cropW,
            cropH = bundle.cropH,
        )
        runCatching { link.send(topUp) }
        runCatching {
            android.util.Log.i("GameBEXTogether", "emu top-up: controls=${controls.size}")
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
            streamResumeOwner = null
            streamResumeUntilNs = null
            streamGraceSerial += 1
            debugCutJob?.cancel()
            debugCutJob = null
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
        streamResumeOwner = null
        streamResumeUntilNs = null
        streamGraceSerial += 1
        debugCutJob?.cancel()
        debugCutJob = null
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
                if (_view.value?.step == HostStep.NOT_READY && _view.value?.problem != null && coreInstall == null) {
                    recheckReadiness()
                }
            }
            TogetherAction.INSTALL_CORE -> beginCoreInstall()
            TogetherAction.CANCEL_CORE_INSTALL -> cancelCoreInstall()
            TogetherAction.RETRY_CORE_INSTALL -> {
                cancelCoreInstall()
                beginCoreInstall()
            }
            TogetherAction.OPEN_RETROARCH -> {
                val token = publishedToken()
                val opened = try {
                    platform.openRetroArch()
                } catch (t: Exception) {
                    if (t is CancellationException) throw t
                    log(t)
                    false
                }
                if (!opened) {
                    synchronized(viewLock) {
                        if (token == publishedToken()) {
                            _view.value = _view.value?.copy(
                                message = "Couldn't open RetroArch. Open it from your launcher, then check again.",
                            )
                        }
                    }
                }
            }
            TogetherAction.OPEN_SYSTEM, TogetherAction.OPEN_PPSSPP -> Unit
            else -> Unit
        }
    }

    private fun beginCoreInstall() {
        if (!isCurrent() || _view.value?.step != HostStep.NOT_READY) return
        coreInstall?.let { install ->
            if (isCurrentCoreInstall(install)) {
                showCoreInstall(install)
                return
            }
        }
        val currentGame = game ?: return
        // STREAM carries no offer core, but a RetroArch host still
        // needs its core installed; resolve it like readiness does
        // (TogetherEmulatorsImpl.readinessStreamHost).
        val currentCore = core ?: StreamEmus.raCore(currentGame) ?: return
        val install = CoreInstall(epoch, publishedToken(), currentCore)
        coreInstall = install
        // Publish before the backend starts, even if it never sends its first callback.
        showCoreInstall(install)
        scheduleCoreInstallStall(install)
        install.job = launchStep {
            try {
                val result = withContext(ioDispatcher) {
                    emulators.fetchAndInstallCore(currentCore, currentGame) { read, total ->
                        post(Input.CoreFetch(install, read, total, install.callbackSequence.incrementAndGet()))
                    }
                }
                post(Input.CoreFetch(install, result = result))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                log(t)
                post(Input.CoreFetch(install, result = CoreResult.Failed(TogetherCopy.noWifi)))
            } finally {
                // Only the actor releases ownership; cancellation cannot strand the running marker.
                post(Input.CoreFetchFinished(install))
            }
        }
        // Covers a job cancelled before its body (and therefore its finally) ever starts.
        install.job?.invokeOnCompletion { post(Input.CoreFetchFinished(install)) }
    }

    private fun isCurrentCoreInstall(install: CoreInstall): Boolean {
        if (coreInstall !== install) return false
        if (isCurrent(install.epoch) && install.token == publishedToken() && _view.value?.step == HostStep.NOT_READY) {
            return true
        }
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

    private fun showCoreInstall(install: CoreInstall) {
        val progress = install.progress
        val message = when {
            progress.stalled -> "Still working…"
            progress.installing -> TogetherCopy.installingCoreHeadline
            else -> TogetherCopy.fetchingCore(
                if (progress.total <= 0L) 0 else
                    (progress.read.toDouble() / progress.total * 100).toInt().coerceIn(0, 100),
            )
        }
        synchronized(viewLock) {
            if (install.token != publishedToken()) return
            _view.value = _view.value?.copy(coreInstall = progress, message = message, problem = null)
        }
    }

    private fun scheduleCoreInstallStall(install: CoreInstall) {
        install.stallJob?.cancel()
        val sequence = install.progressSequence
        install.stallJob = launchStep {
            delay(CORE_INSTALL_STALL_MS)
            post(Input.CoreFetchStalled(install, sequence))
        }
    }

    private fun handleCoreFetchStalled(input: Input.CoreFetchStalled) {
        val install = input.install
        if (!isCurrentCoreInstall(install) || input.sequence != install.progressSequence ||
            input.sequence != install.callbackSequence.get()) return
        install.progress = install.progress.copy(stalled = true)
        showCoreInstall(install)
    }

    private fun handleCoreFetch(input: Input.CoreFetch) {
        val install = input.install
        if (!isCurrentCoreInstall(install)) return
        input.read?.let { read ->
            if (input.sequence <= install.progressSequence) return
            install.progressSequence = input.sequence
            install.progress = if (read == -1L && input.total == -1L) {
                install.progress.copy(installing = true, stalled = false)
            } else {
                install.progress.copy(
                    read = read.coerceAtLeast(0L),
                    total = input.total.coerceAtLeast(0L),
                    installing = false,
                    stalled = false,
                )
            }
            showCoreInstall(install)
            scheduleCoreInstallStall(install)
        }
        val result = input.result ?: return
        releaseCoreInstall(install, cancel = false)
        when (result) {
            CoreResult.Started -> recheckReadiness(install.token)
            is CoreResult.Failed -> {
                synchronized(viewLock) {
                    if (install.token != publishedToken()) return
                    _view.value = _view.value?.copy(
                        coreInstall = null,
                        message = null,
                        problem = Problem(result.text, result.action),
                    )
                }
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
        synchronized(viewLock) {
            if (token != publishedToken()) return
            _view.value = _view.value?.copy(
                coreInstall = null,
                message = null,
                problem = Problem(CORE_INSTALL_CANCELLED, TogetherAction.RETRY_CORE_INSTALL),
            )
        }
    }

    private fun recheckReadiness(token: Long = publishedToken()) {
        synchronized(viewLock) {
            if (token != publishedToken()) return
            val id = gameId ?: return
            epoch += 1
            cancelStepJobs()
            _view.value = _view.value?.copy(step = HostStep.CHECKING, message = null, problem = null, coreInstall = null)
            beginReadiness(id, epoch, token)
        }
    }

    private suspend fun handleForeground() {
        if (_view.value?.step != HostStep.NOT_READY || _view.value?.problem == null || coreInstall != null) return
        recheckReadiness()
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

        streamResumeOwner = null
        streamResumeUntilNs = null
        streamGraceSerial += 1
        streamGrace?.cancel()
        streamGrace = null
        debugCutJob?.cancel()
        debugCutJob = null
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
        coreInstall?.let { install ->
            releaseCoreInstall(install, cancel = true)
            synchronized(viewLock) {
                if (install.token == publishedToken()) {
                    _view.value = _view.value?.copy(coreInstall = null, message = null)
                }
            }
        }
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

/**
 * Host Stream flags → [StreamConfig]. The shell-uid process cannot
 * read app prefs, so every host-side flag travels in the config; the shell
 * re-applies the adb prop on debug builds only. Pure for tests.
 */
internal fun streamConfigForFlags(
    token: String,
    guestAddress: String,
    rsound: Boolean,
    hostFlag: (String) -> String?,
    cropViewport: String = "",
    /**
     * Debug adb-prop override for the start level (0..3, else ignored):
     * the host honors `debug.gamebex.start_level` on debug builds the
     * way the shell honors its props, so lab captures can pin full-res
     * video (overlays need 1:1 video, not upscaled). Pure: the
     * caller samples the prop.
     */
    startLevelOverride: Int? = null,
    /** [P2SlotMode.wire], resolved by the caller. */
    p2SlotMode: String = P2SlotMode.OFF.wire,
    /** Encoder-crop aspect ("N:D"); the session core's game picture, default 4:3. */
    cropAspect: String = "4:3",
): StreamConfig = StreamConfig(
    token = token,
    guestAddress = guestAddress,
    rsound = rsound,
    p2SlotMode = p2SlotMode,
    level = startLevelOverride?.takeIf { it in 0..3 }
        ?: hostFlag("start_level")?.toIntOrNull()?.takeIf { it in 0..3 } ?: 1,
    queueDeadlineMs = hostFlag("queue_deadline_ms")?.toIntOrNull()?.takeIf { it in 0..40 } ?: 0,
    latencyCc = hostFlag("latency_cc") == "1",
    inputCs6 = hostFlag("input_cs6") == "1",
    encoderFps = hostFlag("encoder_fps")?.toIntOrNull()?.takeIf { it == 50 } ?: 0,
    encoderRateHint = when (hostFlag("encoder_rate_hint")) {
        "off", "0" -> 0
        "240" -> 240
        else -> -1
    },
    keyframesOnRequest = StreamFlags.toggleOn("keyframes_on_request", hostFlag),
    crop = hostFlag("stream_crop") == "1",
    cropAspect = cropAspect,
    cropViewport = cropViewport,
)
