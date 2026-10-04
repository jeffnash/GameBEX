package com.gamebex.client.together

import com.gamebex.client.launch.CoreInstallProgress
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Link protocol version, sent in [LinkMsg.Hello]. Bump on any incompatible change. */
const val TOGETHER_PROTO: Int = 1

object TogetherPorts {
    /** GameBEX link (TCP). [LinkTransport.listen] falls back to an ephemeral port. */
    const val LINK: Int = 55440
    /** RetroArch netplay: game traffic (TCP) and LAN discovery (UDP). */
    const val RA_NETPLAY: Int = 55435
    /** PPSSPP built-in ad hoc server (TCP). */
    const val PPSSPP_ADHOC: Int = 27312
    /** Gambatte Game Link server (TCP). */
    const val GB_LINK: Int = 56400
}

@Serializable
enum class Role { HOST, GUEST }

@Serializable
enum class TogetherMode {
    RA_NETPLAY, PSP_ADHOC, GB_LINK,
    /** The host plays; the guest watches a video stream and is player 2 through a virtual pad. */
    STREAM,
}

/** The game the host offers: identity only, never bytes. */
@Serializable
data class OfferedGame(
    val title: String,
    /** PlatformRegistry folder, e.g. "snes". */
    val folder: String,
    /** Host launch file name: RomStore.resolveLaunchFile(ctx, game)!!.name. */
    val fileName: String,
    /** Host launch file length in bytes. */
    val fileSize: Long,
    /** RomHash fingerprint CRC (8 upper-case hex); null when the file is over RomHash.MAX_HASH_BYTES. */
    val crc: String? = null,
    /** RomHash fingerprint size (inner ROM for single-entry zips); null exactly when [crc] is null. */
    val romSize: Long? = null,
    /** The RetroArch core the host runs (TogetherSupport.coreFor); null for PSP_ADHOC. */
    val core: String? = null,
    val sourceId: String? = null,
    val sourceItemKey: String? = null,
    /** GameEntity.sourceRefJson, verbatim. */
    val sourceRefJson: String? = null,
    /** GameEntity.sourceVariantJson, verbatim. */
    val sourceVariantJson: String? = null,
)

/** One RetroArch core build: the .so SHA-256 (64 lower-case hex) and its size. */
@Serializable
data class CoreBuild(val core: String, val sha256: String, val sizeBytes: Long)

/** Guest progress, mirrored on the host screen. */
@Serializable
enum class GuestPhase {
    CHECKING, NEEDS_EMULATOR, NEEDS_SETUP, NEEDS_CORE, CORE_SYNC, INSTALLING_CORE,
    NEEDS_GAME, DOWNLOADING, PICKING_GAME, MISMATCH, READY, PLAYING,
}

@Serializable
enum class ByeReason { LEFT, REMOVED, FULL, VERSION, ERROR }

/** Every message on the link. JSON class discriminator is "t". */
@Serializable
sealed interface LinkMsg {
    /** First message both ways: the guest sends first, the host answers. */
    @Serializable @SerialName("hello")
    data class Hello(
        val proto: Int,
        val role: Role,
        val deviceId: String,
        val name: String,
        val appVersion: String,
        val raVersion: String? = null,
        val ppssppVersion: String? = null,
        /** StreamWire.VERSION this app speaks; null from apps without Stream. */
        val streamProto: Int? = null,
    ) : LinkMsg

    @Serializable @SerialName("offer")
    data class Offer(
        val sessionId: String,
        val mode: TogetherMode,
        val game: OfferedGame,
        /** Host's installed core build (ledger); RA_NETPLAY only; null = unknown. */
        val core: CoreBuild? = null,
        /** Host LAN IPv4 the guest's emulator must use. */
        val hostAddress: String,
    ) : LinkMsg

    @Serializable @SerialName("guest_state")
    data class GuestState(
        val sessionId: String,
        val phase: GuestPhase,
        /** Shown on the host under the phase line (NEEDS_SETUP only); at most 120 chars. */
        val detail: String? = null,
        /** 0..100 for DOWNLOADING and CORE_SYNC while copying, else null. */
        val progress: Int? = null,
    ) : LinkMsg

    @Serializable @SerialName("core_request")
    data class CoreRequest(val sessionId: String, val core: String) : LinkMsg

    @Serializable @SerialName("core_begin")
    data class CoreBegin(val sessionId: String, val blobId: Int, val build: CoreBuild) : LinkMsg

    @Serializable @SerialName("core_end")
    data class CoreEnd(val sessionId: String, val blobId: Int) : LinkMsg

    @Serializable @SerialName("core_unavailable")
    data class CoreUnavailable(val sessionId: String, val core: String) : LinkMsg

    /** Host → guest: start the game. [round] counts launches in this session from 1. */
    @Serializable @SerialName("launch")
    data class Launch(val sessionId: String, val round: Int) : LinkMsg

    /** Host → guest, GB_LINK only: the host's link server has had its head start for [round]. */
    @Serializable @SerialName("host_live")
    data class HostLive(val sessionId: String, val round: Int) : LinkMsg

    /** Host → guest, STREAM only: the host's stream for [round] is listening. */
    @Serializable @SerialName("stream_ready")
    data class StreamReady(
        val sessionId: String,
        val round: Int,
        /** StreamWire.VERSION. */
        val proto: Int,
        /** 32 lower-case hex characters; the guest proves it on the media and input channels. */
        val token: String,
        val mediaPort: Int,
        val inputPort: Int,
        val width: Int,
        val height: Int,
        val audio: Boolean,
        val audioTimestampOrigin: String = "capture",
    ) : LinkMsg

    @Serializable @SerialName("ping")
    data class Ping(val n: Long) : LinkMsg

    @Serializable @SerialName("pong")
    data class Pong(val n: Long) : LinkMsg

    @Serializable @SerialName("bye")
    data class Bye(val reason: ByeReason) : LinkMsg
}

/** A host seen through NSD, or built from a QR link / typed address. */
data class NearbyHost(
    /** "" for QR/typed joins. */
    val deviceId: String,
    /** "" for QR/typed joins. */
    val sessionId: String,
    val name: String,
    /** "" for QR/typed joins until the offer arrives. */
    val gameTitle: String,
    /** "" for QR/typed joins until the offer arrives. */
    val folder: String,
    /** null for QR/typed joins until the offer arrives. */
    val mode: TogetherMode?,
    /** IPv4 literal. */
    val address: String,
    val port: Int,
) {
    val key: String get() = "$deviceId/$sessionId/$address:$port"
}

/** The single action a problem offers (one button, A). */
enum class TogetherAction {
    RECHECK, OPEN_SYSTEM, OPEN_PPSSPP, OPEN_RETROARCH, INSTALL_CORE, COPY_CORE, DOWNLOAD, USE_ANYWAY,
    /** Guest, STREAM: reopen the stream viewer. */
    OPEN_STREAM,
    CANCEL_CORE_INSTALL, RETRY_CORE_INSTALL,
}

data class Problem(val text: String, val action: TogetherAction?)

enum class HostStep { CHECKING, NOT_READY, WAITING, JOINED, LAUNCHING, IN_GAME, ENDED }

data class HostView(
    val step: HostStep,
    val gameId: Long,
    /** "" until the game row loads. */
    val gameTitle: String,
    /** "" until the game row loads. */
    val folder: String,
    /** null until the game row loads, and when the game has no mode. */
    val mode: TogetherMode?,
    val myName: String,
    val myAddress: String? = null,
    val port: Int? = null,
    /** gamebex://join link; null until listening. */
    val qrText: String? = null,
    val problem: Problem? = null,
    val guestName: String? = null,
    val guestPhase: GuestPhase? = null,
    val guestDetail: String? = null,
    val guestProgress: Int? = null,
    /** Launch instructions for this mode, shown in JOINED, LAUNCHING and IN_GAME. */
    val instructions: List<String> = emptyList(),
    val linkLost: Boolean = false,
    /** One-line transient message (errors, "Sam left."). */
    val message: String? = null,
    /** Other modes this game supports, best first; the host may switch before a guest joins. */
    val altModes: List<TogetherMode> = emptyList(),
    /** STREAM, IN_GAME: "Streaming to Sam · 60 fps · 7.9 Mbps", refreshed every 2 s. */
    val streamLine: String? = null,
    val coreInstall: CoreInstallProgress? = null,
) {
    val canStart: Boolean get() = step == HostStep.JOINED && guestPhase == GuestPhase.READY
}

enum class GuestStep {
    CONNECTING, WAITING_OFFER, CHECKING, ACTION_NEEDED, DOWNLOADING, CORE_SYNC,
    INSTALLING_CORE, PICKING_GAME, READY, LAUNCHING, IN_GAME, ENDED,
}

data class GameChoice(val gameId: Long, val title: String, val folder: String)

data class GuestView(
    val step: GuestStep,
    val hostName: String,
    val gameTitle: String? = null,
    val folder: String? = null,
    val mode: TogetherMode? = null,
    /** The phase last reported to the host; drives the ACTION_NEEDED headline. */
    val phase: GuestPhase? = null,
    val problem: Problem? = null,
    /** 0..100 in DOWNLOADING and CORE_SYNC. */
    val progress: Int? = null,
    /** PICKING_GAME only. */
    val choices: List<GameChoice> = emptyList(),
    /** LAUNCHING and IN_GAME. */
    val instructions: List<String> = emptyList(),
    /** Seconds left before launch, LAUNCHING only. */
    val countdown: Int? = null,
    val linkLost: Boolean = false,
    /** ENDED reason, or a transient line (e.g. roomOpen). */
    val message: String? = null,
    val coreInstall: CoreInstallProgress? = null,
)
