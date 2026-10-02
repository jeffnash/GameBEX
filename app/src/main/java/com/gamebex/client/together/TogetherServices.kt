package com.gamebex.client.together

import com.gamebex.client.data.Emu
import com.gamebex.client.data.local.GameEntity
import com.gamebex.client.launch.EmuRouter
import com.gamebex.client.stream.StreamEndpoint
import java.io.File
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Every engine and link interval in one place; tests pass shorter values. */
data class TogetherTiming(
    val pingMs: Long = 5_000L,
    val silenceMs: Long = 20_000L,
    val helloMs: Long = 5_000L,
    val offerMs: Long = 10_000L,
    val coreRequestMs: Long = 10_000L,
    val coreInactivityMs: Long = 30_000L,
    val countdownS: Int = 5,
    val launchWaitMs: Long = 20_000L,
    val watchIntervalMs: Long = 1_000L,
    val probeTimeoutMs: Long = 800L,
    val addressRefreshMs: Long = 5_000L,
    val byeMs: Long = 1_000L,
    val returnDelayMs: Long = 1_000L,
    val progressThrottleMs: Long = 1_000L,
    val downloadTickMs: Long = 1_000L,
    val downloadGiveUpMs: Long = 30L * 60_000L,
 /** STREAM: how long the host keeps streaming after the guest's link drops, in case the guest comes back. */
    val streamLinkGraceMs: Long = 15_000L,
)

interface LinkTransport {
 /** Binds 0.0.0.0:[preferredPort], or an ephemeral port when that one is taken. */
    suspend fun listen(preferredPort: Int = TogetherPorts.LINK): LinkListener
 /** Connects within [timeoutMs]; throws java.io.IOException on failure. */
    suspend fun connect(address: String, port: Int, timeoutMs: Long = 5_000L): Link
}

interface LinkListener {
    val port: Int
 /** Suspends until a peer connects. Throws java.io.IOException once closed. */
    suspend fun accept(): Link
    fun close()
}

interface Link {
 /** Peer IPv4 literal. */
    val remoteAddress: String
 /** Messages and blob chunks in arrival order; the last event is always [LinkEvent.Closed]. */
    val events: ReceiveChannel<LinkEvent>
 /** Throws java.io.IOException when the link is closed. */
    suspend fun send(msg: LinkMsg)
 /** Streams [file] as [blobId] in chunks of at most 256 KiB; [onProgress] gets total bytes sent. */
    suspend fun sendBlob(blobId: Int, file: File, onProgress: (sent: Long) -> Unit = {})
 /** Idempotent. Emits [LinkEvent.Closed] (cause null) if not already closed. */
    fun close()
}

sealed interface LinkEvent {
    data class Message(val msg: LinkMsg) : LinkEvent
    class Blob(val blobId: Int, val bytes: ByteArray) : LinkEvent
 /** [cause] null = closed by us or cleanly by the peer; LinkTimeoutException = silence. */
    data class Closed(val cause: Throwable?) : LinkEvent
}

class LinkTimeoutException : java.io.IOException("link silent too long")

/** RetroArch's LAN discovery answer. */
data class RaRoom(
    val nick: String,
    val core: String,
    val coreVersion: String,
    val content: String,
    val contentCrc: Long,
    val port: Int,
    val raVersion: String,
)

interface LiveProbes {
 /** RANQ to [address]:55435; the parsed RANS answer, or null within [timeoutMs]. */
    suspend fun raRoom(address: String, timeoutMs: Long = 800L): RaRoom?
 /** True when a TCP connect to [address]:[port] succeeds within [timeoutMs] (closed at once). */
    suspend fun tcpOpen(address: String, port: Int, timeoutMs: Long = 800L): Boolean
}

data class TogetherLaunch(
    val sessionId: String,
    val role: Role,
    val mode: TogetherMode,
    val game: GameEntity,
 /** The RetroArch core both phones run (the host's OfferedGame.core); null for PSP_ADHOC. */
    val core: String?,
 /** The host's LAN IPv4 (the host passes its own). */
    val hostAddress: String,
    val playerName: String,
 /** STREAM guest only: where the viewer connects. */
    val stream: StreamEndpoint? = null,
 /** STREAM host on RetroArch only: the session config reserving player 2 (StreamPlayer2.prepare). */
    val p2Config: File? = null,
)

sealed interface Readiness {
    data object Ready : Readiness
    data class NeedsEmulator(val emu: Emu) : Readiness
 /** Installed, but too old for this mode; the pinned build is in System. */
    data class NeedsUpdate(val emu: Emu) : Readiness
    data class NeedsCore(val core: String) : Readiness
 /** [running] false: start Shizuku. true: allow GameBEX in Shizuku. */
    data class NeedsShizuku(val running: Boolean) : Readiness
 /** The emulator never created its config: open it once. */
    data class NeedsOpenOnce(val emu: Emu) : Readiness
 /** A launch gate (BIOS, prep, missing file) stops it; [text] is the router's copy. */
    data class Blocked(val text: String) : Readiness
}

data class CoreFile(val build: CoreBuild, val file: File)

sealed interface CoreResult {
 /** CoreSideloadActivity was started; the user must come back to GameBEX. */
    data object Started : CoreResult
    data class Failed(val text: String) : CoreResult
}

interface TogetherEmulators {
 /** [core]: the RetroArch core the session runs (null for PSP_ADHOC). */
    suspend fun readiness(mode: TogetherMode, role: Role, game: GameEntity, core: String?): Readiness
 /** The core build GameBEX last installed for [core], when its file still matches. */
    suspend fun installedCore(core: String): CoreFile?
 /** Buildbot fetch into the stage dir (no launch), then install. [game] is the ROM source when TogetherCaps.SIDELOAD_WITHOUT_ROM is false. */
    suspend fun fetchAndInstallCore(core: String, game: GameEntity, onProgress: (read: Long, total: Long) -> Unit): CoreResult
 /** Where the guest's BlobSink writes a received core. */
    fun receivedCoreFile(build: CoreBuild): File
 /** True when [receivedCoreFile] already holds exactly [build] (size and SHA-256). */
    suspend fun hasReceivedCore(build: CoreBuild): Boolean
 /** Validates [file] against [build], installs it, and records it in the ledger. */
    suspend fun installReceivedCore(file: File, build: CoreBuild, game: GameEntity): CoreResult
 /** Writes this role's session config and launches through EmuRouter. */
    suspend fun launch(req: TogetherLaunch): EmuRouter.Outcome
 /** Restores everything [launch] changed. Safe to call any number of times. */
    suspend fun endSession()
}

sealed interface MatchResult {
    data class Found(val gameId: Long) : MatchResult
 /** Candidates exist but none is the same file; [gameId] is the first reachable one. */
    data class Mismatch(val gameId: Long) : MatchResult
 /** [downloadable]: the offer's source is installed and enabled on this phone. */
    data class Missing(val downloadable: Boolean) : MatchResult
}

sealed interface DownloadResult {
 /** [knownIds]: playable row ids for the offer's source item before enqueueing. */
    data class Started(val jobId: Long, val knownIds: Set<Long>) : DownloadResult
    data class Failed(val text: String) : DownloadResult
}

sealed interface DownloadState {
    data class Progress(val percent: Int) : DownloadState
    data class Done(val gameId: Long) : DownloadState
    data class Failed(val text: String) : DownloadState
}

interface TogetherLibrary {
    suspend fun game(gameId: Long): GameEntity?
    suspend fun offer(gameId: Long): OfferedGame?
    suspend fun match(offer: OfferedGame, mode: TogetherMode): MatchResult
 /** Every playable Game Boy / Game Boy Color game, in title order. */
    suspend fun gbChoices(): List<GameChoice>
 /** Display name of the offer's source on this phone, or null when not installed and enabled. */
    suspend fun sourceName(offer: OfferedGame): String?
    suspend fun download(offer: OfferedGame): DownloadResult
 /** Emits Progress until Done (the ingested row) or Failed; completes after either. */
    fun downloadState(jobId: Long, offer: OfferedGame, knownIds: Set<Long>): Flow<DownloadState>
}

data class HostAd(
    val deviceId: String,
    val sessionId: String,
    val name: String,
    val gameTitle: String,
    val folder: String,
    val mode: TogetherMode,
    val port: Int,
)

fun interface Advertisement { fun stop() }

interface Discovery {
 /** Hosts seen while browsing, self filtered, deduped by deviceId+sessionId. */
    val found: StateFlow<List<NearbyHost>>
    fun startBrowsing()
    fun stopBrowsing()
    fun advertise(ad: HostAd): Advertisement
}

sealed interface TogetherAlert {
    data class RoomOpen(val hostName: String, val mode: TogetherMode) : TogetherAlert
    data class PeerLeft(val name: String) : TogetherAlert
 /** [streamGraceS] is set when a running stream stays up that long for the guest to come back. */
    data class LinkLost(val name: String, val streamGraceS: Int? = null) : TogetherAlert
 /** STREAM: the guest's phone stopped sending input while the stream is up (a Wi-Fi drop the link hasn't noticed yet). */
    data class PeerSilent(val name: String) : TogetherAlert
 /** A launch or core install is waiting for GameBEX to be on screen. */
    data class TapToStart(val hostName: String) : TogetherAlert
}

interface TogetherPlatform {
    val deviceId: String
 /** The name other phones see: the custom console name, or the sanitized device name. */
    fun playerName(): String
 /** The saved custom console name, or null when the device name is used. */
    fun customName(): String? = null
 /** Saves a custom console name, sanitized; null or blank goes back to the device name. */
    fun setCustomName(name: String?) {}
    fun lanAddress(): String?
    fun appVersion(): String
 /** versionName of an installed package, or null. */
    fun packageVersion(pkg: String): String?
 /** True while MainActivity is resumed (TogetherRuntime.foreground). */
    val foreground: StateFlow<Boolean>
    fun startService(ongoingText: String)
    fun updateService(ongoingText: String)
    fun stopService()
    fun alert(alert: TogetherAlert)
 /** printed label of the pad button that opens RetroArch's menu (keycode 100). */
    fun raMenuButton(): String = "Y"
    fun cancelAlerts()
}


interface TogetherController {
    val nearby: StateFlow<List<NearbyHost>>
 /** The host the invite dialog should show, or null. */
    val invite: StateFlow<NearbyHost?>
    val host: StateFlow<HostView?>
    val guest: StateFlow<GuestView?>
    val myAddress: StateFlow<String?>
 /** The name other phones will see for this one (refreshed with [myAddress]). */
    val myName: StateFlow<String>
 /** The saved custom console name, or null when the device name is used. */
    val customName: StateFlow<String?>
 /** Saves a custom console name for new sessions; null or blank goes back to the device name. */
    fun rename(name: String?)
    fun startBrowsing()
    fun stopBrowsing()
    fun dismissInvite()
 /** Sets host to HostView(CHECKING, gameId, "", "", null, myName) synchronously, then starts. */
    fun hostGame(gameId: Long)
    fun hostStart()
    fun hostRemoveGuest()
    fun hostPlayAgain()
 /** Re-checks the hosted game in [mode] (one of HostView.altModes); only before a guest joins. */
    fun hostSwitchMode(mode: TogetherMode) {}
 /** Sets guest to GuestView(CONNECTING, target.name, …) synchronously, then starts. */
    fun join(target: NearbyHost)
 /** Null when joining started; otherwise the error line to show. */
    fun joinAddress(text: String): String?
 /** Null when joining started; otherwise the error line to show. */
    fun joinLink(uri: String): String?
 /** The problem action of whichever side is active. */
    fun act(action: TogetherAction)
    fun pick(gameId: Long)
 /** Host END or guest LEAVE. */
    fun end()
 /** Called by MainActivity.onResume. */
    fun onForeground()
}
