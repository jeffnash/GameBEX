package com.gamebex.client.together.session

import android.util.Log
import com.gamebex.client.together.Discovery
import com.gamebex.client.together.HostView
import com.gamebex.client.together.LiveProbes
import com.gamebex.client.together.LinkTransport
import com.gamebex.client.together.NearbyHost
import com.gamebex.client.together.TogetherAction
import com.gamebex.client.together.TogetherController
import com.gamebex.client.together.TogetherEmulators
import com.gamebex.client.together.TogetherLibrary
import com.gamebex.client.together.TogetherPlatform
import com.gamebex.client.together.TogetherTiming
import com.gamebex.client.together.GuestView
import com.gamebex.client.together.net.JoinLink
import com.gamebex.client.stream.NoStreamHost
import com.gamebex.client.stream.NoStreamPlayer2
import com.gamebex.client.stream.StreamFlags
import com.gamebex.client.stream.StreamHost
import com.gamebex.client.stream.StreamPlayer2
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Owns the host and guest actors and routes the single active session. */
class TogetherEngine(
    transport: LinkTransport,
    probes: LiveProbes,
    private val emulators: TogetherEmulators,
    library: TogetherLibrary,
    private val discovery: Discovery,
    private val platform: TogetherPlatform,
    private val timing: TogetherTiming = TogetherTiming(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
 /** STREAM host: the shell-uid stream process. */
    streamHost: StreamHost = NoStreamHost,
 /** STREAM host: player 2's binding. */
    player2: StreamPlayer2 = NoStreamPlayer2,
 /** STREAM host flags: raw in-app value or null when unset. */
    hostFlag: (String) -> String? = { null },
 /** seeds the core's overlay from the RA APK when RA configures none. */
    seedOverlayForCore: (String?) -> java.io.File? = { null },
) : TogetherController {
    private val hostEngine = HostEngine(transport, emulators, library, discovery, platform, timing, scope, ioDispatcher, streamHost, player2, hostFlag = hostFlag, overlay26bEnabled = { StreamFlags.toggleOn("overlay_26b", hostFlag) }, seedOverlayForCore = seedOverlayForCore)
    private val guestEngine = GuestEngine(transport, probes, emulators, library, platform, timing, scope, ioDispatcher)

    override val nearby: StateFlow<List<NearbyHost>> = discovery.found
    override val host: StateFlow<HostView?> = hostEngine.view
    override val guest: StateFlow<GuestView?> = guestEngine.view

    private val dismissed = MutableStateFlow<Set<String>>(emptySet())
    override val invite: StateFlow<NearbyHost?> = combine(nearby, host, guest, dismissed) { found, hostView, guestView, keys ->
        if (hostView.isActive() || guestView.isActive()) null
        else found.firstOrNull { it.key !in keys }
    }.stateIn(scope, SharingStarted.Eagerly, null)

    private val _myAddress = MutableStateFlow<String?>(null)
    override val myAddress: StateFlow<String?> = _myAddress.asStateFlow()
    private val _myName = MutableStateFlow("")
    override val myName: StateFlow<String> = _myName.asStateFlow()
    private val _customName = MutableStateFlow<String?>(null)
    override val customName: StateFlow<String?> = _customName.asStateFlow()

    private val browsingLock = Any()
    private var browsingCount = 0
    private var addressJob: Job? = null
    private val recoveryLock = Any()
    private var recoveryRestoreStarted = false
    private val switchMutex = Mutex()
    private val switchGeneration = AtomicInteger()

    override fun startBrowsing() {
        runCatching { discovery.startBrowsing() }
            .onFailure { log("startBrowsing failed", it) }
        hostEngine.refreshPlayerName()
        guestEngine.refreshPlayerName()
        val shouldStart = synchronized(browsingLock) {
            browsingCount += 1
            browsingCount == 1
        }
        refreshAddress()
        if (shouldStart) {
            addressJob = scope.launch {
                while (true) {
                    delay(timing.addressRefreshMs)
                    refreshAddressNow()
                    refreshNameNow()
                }
            }
        }
    }

    override fun stopBrowsing() {
        runCatching { discovery.stopBrowsing() }
            .onFailure { log("stopBrowsing failed", it) }
        val stopAddressRefresh = synchronized(browsingLock) {
            if (browsingCount == 0) false else {
                browsingCount -= 1
                browsingCount == 0
            }
        }
        if (stopAddressRefresh) {
            addressJob?.cancel()
            addressJob = null
        }
    }

    override fun dismissInvite() {
        val key = invite.value?.key ?: return
        dismissed.value = dismissed.value + key
    }

    override fun hostGame(gameId: Long) {
        val generation = switchGeneration.incrementAndGet()
        hostEngine.prepareHostGame(gameId)
        scope.launch {
            switchMutex.withLock {
                if (generation != switchGeneration.get()) return@withLock
                if (guest.value.isActive()) guestEngine.endAndAwait()
                if (generation != switchGeneration.get()) return@withLock
                hostEngine.commitHostGame(gameId)
            }
        }
    }

    override fun hostStart() = hostEngine.start()
    override fun hostRemoveGuest() = hostEngine.removeGuest()
    override fun hostPlayAgain() = hostEngine.playAgain()
    override fun hostSwitchMode(mode: com.gamebex.client.together.TogetherMode) = hostEngine.switchMode(mode)

    override fun join(target: NearbyHost) {
        dismissed.value = dismissed.value + target.key
        val generation = switchGeneration.incrementAndGet()
        guestEngine.prepareJoin(target)
        scope.launch {
            switchMutex.withLock {
                if (generation != switchGeneration.get()) return@withLock
                if (host.value.isActive()) hostEngine.endAndAwait()
                if (generation != switchGeneration.get()) return@withLock
                guestEngine.commitJoin(target)
            }
        }
    }

    override fun joinAddress(text: String): String? {
        val target = JoinLink.parseAddress(text) ?: return com.gamebex.client.together.TogetherCopy.addressInvalid
        join(target)
        return null
    }

    override fun joinLink(uri: String): String? {
        if (hasActiveSession()) return null
        val target = JoinLink.parseLink(uri) ?: return com.gamebex.client.together.TogetherCopy.linkInvalid
        join(target)
        return null
    }

    override fun act(action: TogetherAction) {
        when {
            guest.value.isActive() -> guestEngine.act(action)
            host.value.isActive() -> hostEngine.act(action)
        }
    }

    override fun pick(gameId: Long) {
        if (guest.value.isActive()) guestEngine.pick(gameId)
    }

    override fun end() {
        when {
            guest.value.isActive() -> guestEngine.end()
            host.value.isActive() -> hostEngine.end()
        }
    }

 /**
 * The first idle foreground restores a PPSSPP session that died with the
 * process. That restore force-stops a backgrounded PPSSPP; that's
 * acceptable because the session already ended abnormally.
 */
    override fun onForeground() {
        refreshAddress()
        hostEngine.onForeground()
        guestEngine.onForeground()

        val restore = synchronized(recoveryLock) {
            if (!recoveryRestoreStarted && !hasActiveSession()) {
                recoveryRestoreStarted = true
                true
            } else false
        }
        if (restore) {
            scope.launch {
                runCatching { withContext(Dispatchers.IO) { emulators.endSession() } }
                    .onFailure { log("crash recovery restore failed", it) }
            }
        }
    }

    private fun hasActiveSession(): Boolean = host.value.isActive() || guest.value.isActive()

    private fun refreshAddress() {
        scope.launch { refreshAddressNow() }
        scope.launch { refreshNameNow() }
    }

 /**
 * Saves the name and republishes [myName]. The engines read platform.playerName
 * at their own refresh points (browse, foreground, setup, connect), so a new
 * session uses the new name; one already running keeps the name it started with.
 */
    override fun rename(name: String?) {
        scope.launch {
            runCatching { withContext(ioDispatcher) { platform.setCustomName(name) } }
                .onFailure { log("rename failed", it) }
            refreshNameNow()
        }
    }

    private suspend fun refreshNameNow() {
        runCatching { withContext(ioDispatcher) { platform.playerName() to platform.customName() } }
            .onFailure { log("player name refresh failed", it) }
            .onSuccess { (name, custom) ->
                _myName.value = name
                _customName.value = custom
            }
    }

    private suspend fun refreshAddressNow() {
        val address = runCatching { withContext(Dispatchers.IO) { platform.lanAddress() } }
            .onFailure { log("LAN address refresh failed", it) }
            .getOrNull()
        _myAddress.value = address
    }

    private fun log(message: String, error: Throwable) {
        runCatching { Log.w("GameBEXTogether", message, error) }
    }

    private fun HostView?.isActive(): Boolean = this != null && step != com.gamebex.client.together.HostStep.ENDED
    private fun GuestView?.isActive(): Boolean = this != null && step != com.gamebex.client.together.GuestStep.ENDED
}
