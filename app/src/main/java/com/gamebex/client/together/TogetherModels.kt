package com.gamebex.client.together

import com.gamebex.client.launch.CoreInstallProgress
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Link protocol version, sent in [LinkMsg.Hello]. Bump on any incompatible change.
 * 3 adds [LinkMsg.StreamOverlay] (the guest renders the host's RetroArch
 * overlay). It grew additively after that (settings, chunked parts,
 * [LinkMsg.StreamOverlayRequest]) without a bump: every new field carries a
 * default and older apps drop the unknown "t" discriminator in
 * LinkJson.decode instead of failing the link.
 * 4 raises the link JSON frame to 256 KB (Frames.MAX_JSON) so whole overlay
 * images fit one part; older apps reject bigger frames, hence the bump.
 */
const val TOGETHER_PROTO: Int = 4

/**
 * The host emulator's overlay viewport: origin + size in host-display
 * pixels. Doubles as a cutout rect for [defaultCutoutViewport].
 */
data class HostViewport(val x: Int, val y: Int, val w: Int, val h: Int) {
    val isEmpty: Boolean get() = w <= 0 || h <= 0
}

/**
 * The window a default-cutout-mode app (RetroArch, Dolphin, NetherSX2,
 * ...) gets on a [displayW]x[displayH] display with [cutouts] bounding
 * rects (display coords): the system letterboxes it off any display edge
 * a cutout touches. Floating hole-punches letterbox nothing. Pure.
 */
fun defaultCutoutViewport(
    displayW: Int,
    displayH: Int,
    cutouts: List<HostViewport>,
): HostViewport {
    var x = 0
    var y = 0
    var w = displayW
    var h = displayH
    cutouts.forEach { r ->
        if (r.x <= 0 && r.w > 0) {
            val nx = (r.x + r.w).coerceIn(0, displayW)
            if (nx > x) {
                w -= nx - x
                x = nx
            }
        } else if (r.x + r.w >= displayW && r.w > 0) {
            w = minOf(w, r.x - x)
        }
        if (r.y <= 0 && r.h > 0) {
            val ny = (r.y + r.h).coerceIn(0, displayH)
            if (ny > y) {
                h -= ny - y
                y = ny
            }
        } else if (r.y + r.h >= displayH && r.h > 0) {
            h = minOf(h, r.y - y)
        }
    }
    if (w <= 0 || h <= 0) return HostViewport(0, 0, displayW, displayH)
    return HostViewport(x, y, w, h)
}

/**
 * The host display plus the overlay viewport inside it (the default
 * cutout-mode window a RetroArch-class emulator gets). Empty viewport =
 * unknown; the guest maps its full canvas then.
 */
data class HostViewportInfo(
    val viewport: HostViewport,
    val displayW: Int,
    val displayH: Int,
) {
    companion object {
        val UNKNOWN: HostViewportInfo = HostViewportInfo(HostViewport(0, 0, 0, 0), 0, 0)
    }
}

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

@Serializable
data class StreamResume(
    val sessionId: String,
    val round: Int,
    val token: String,
    val attempt: String,
)

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
        val streamResume: StreamResume? = null,
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
        /** PlatformRegistry folder of the hosted game, e.g. "n64"; "" = unknown (generic pad). */
        val system: String = "",
        /** Actual host emulator backend (touchBackendForEmuPkg); "" = unknown (generic pad). */
        val backend: String = "",
    ) : LinkMsg

    /** One overlay image: path as written in the cfg → base64-encoded bytes. */
    @Serializable
    data class OverlayImage(val name: String, val base64: String = "")

    /**
     * The effective RetroArch overlay settings the host renders with
     * (`input_overlay_*` from the files it loads), plus the host screen.
     * Native setting fields keep their Android defaults; optional rendering
     * metadata keeps absent-field behavior and is ignored by older guests.
     */
    @Serializable
    data class OverlaySettingsMsg(
        val opacity: Float = 0.7f,
        val autoScale: Boolean = true,
        /** `input_overlay_show_inputs`: 0 none, 1 touched, 2 physical. */
        val showInputs: Int = 1,
        val scaleLandscape: Float = 1f,
        val aspectAdjustLandscape: Float = 0f,
        val xSeparationLandscape: Float = 0f,
        val ySeparationLandscape: Float = 0f,
        val xOffsetLandscape: Float = 0f,
        val yOffsetLandscape: Float = 0f,
        val scalePortrait: Float = 1f,
        val aspectAdjustPortrait: Float = 0f,
        val xSeparationPortrait: Float = 0f,
        val ySeparationPortrait: Float = 0f,
        val xOffsetPortrait: Float = 0f,
        val yOffsetPortrait: Float = 0f,
        /**
         * The host emulator's overlay viewport in pixels: origin + size on
         * the host display. RetroArch-class emulators use the default
         * cutout mode, so in landscape the system letterboxes them off the
         * cutout side (70px on the lab phones) — overlay coords are
         * fractions of the VIEWPORT, not the display. Old hosts send 0,0
         * + display size (viewport == display).
         */
        val screenX: Int = 0,
        val screenY: Int = 0,
        val screenW: Int = 0,
        val screenH: Int = 0,
        /** Host display in pixels (the viewport sits inside it). */
        val displayW: Int = 0,
        val displayH: Int = 0,
        /**
         * The rect the host's encoder crops to (host-display px), or all 0
         * when the shell streams full-frame. The guest offsets its video
         * surface by this instead of centering, so the cropped game picture
         * lands where the emulator drew it (the viewport-centered crop sits
         * off-center on cutout phones). Additive: old hosts send 0.
         */
        val cropX: Int = 0,
        val cropY: Int = 0,
        val cropW: Int = 0,
        val cropH: Int = 0,
        /** Absent metadata preserves the existing packed-color alpha behavior. */
        val floatingAlpha: Boolean = false,
        val dpadDiagonalSensitivity: Float = 80f,
        val abxyDiagonalSensitivity: Float = 50f,
    )

    /**
     * Host → guest, STREAM only, right after [StreamReady] when the
     * guest-facing flag `overlay_26b` is on: the host's active RetroArch
     * overlay cfg, its effective settings, and its images at FULL size, so
     * the guest renders the host's own pad pixel-perfect.
     *
     * Images ship in [partCount] parts ([partIndex] 0-based); part 0 carries
     * the cfg + settings + first images, follow-ups the rest. Every part
     * fits one link JSON frame. [truncated] means unreadable images were
     * skipped at the source (never size-capped): the guest re-requests them
     * ([StreamOverlayRequest]) and meanwhile marks the session
     * OVERLAY_DEGRADED. A guest that can't use it (no play page at all)
     * falls back to its own RetroArch stock overlay.
     */
    @Serializable @SerialName("stream_overlay")
    data class StreamOverlay(
        val sessionId: String,
        val round: Int,
        /** Overlay page name (e.g. "landscape"); "" = unnamed. */
        val name: String = "",
        /** The overlay .cfg text (UTF-8), all pages (part 0 only). */
        val cfg: String = "",
        /** Images referenced by the cfg, relative path as written → base64 bytes. */
        val images: List<OverlayImage> = emptyList(),
        /** True when source images were unreadable/skipped (re-requested). */
        val truncated: Boolean = false,
        /** Effective host overlay settings + screen (part 0 only). */
        val settings: OverlaySettingsMsg = OverlaySettingsMsg(),
        val partIndex: Int = 0,
        val partCount: Int = 1,
    ) : LinkMsg

    /**
     * Guest → host, STREAM only: re-send [missing] image refs (paths as
     * written in the cfg). The host answers with a top-up [StreamOverlay]
     * ([partIndex] -1, merged by ref) or nothing when they're unreadable.
     */
    @Serializable @SerialName("stream_overlay_request")
    data class StreamOverlayRequest(
        val sessionId: String,
        val round: Int,
        val missing: List<String> = emptyList(),
        /** True when [missing] names [StreamEmuOverlay] control ids, not cfg refs. */
        val emu: Boolean = false,
    ) : LinkMsg

    /** One visual cell of a dpad (Nether/ARMSX2 arms): art + own rect, all host px. */
    @Serializable
    data class EmuCell(
        val art: String = "",
        val pressedArt: String = "",
        val x: Int = 0,
        val y: Int = 0,
        val w: Int = 0,
        val h: Int = 0,
    )

    /** One control of a non-RetroArch emulator's own touch overlay. */
    @Serializable
    data class EmuControl(
        /** The emulator's control id (e.g. Dolphin "A", ARMSX2 "CROSS"). */
        val id: String,
        /** 0 button, 1 dpad, 2 stick. */
        val kind: Int = 0,
        /** Unpressed art, base64 PNG at exact host pixels ([w]x[h]). */
        val art: String = "",
        /** Pressed art (buttons/sticks); "" shows [art] when pressed. */
        val pressedArt: String = "",
        /** Dpad two-directions art; "" shows [pressedArt]. */
        val pressedArt2: String = "",
        /** Stick movable knob art at rest size; "" = no knob. */
        val knobArt: String = "",
        /** Stick pressed knob art; "" shows [knobArt]. */
        val knobPressedArt: String = "",
        /** Knob size in host px (square, centered at rest). */
        val knobSize: Int = 0,
        /** Draw rect in host-screen pixels (same-model phones: 1:1 on guest). */
        val x: Int = 0,
        val y: Int = 0,
        val w: Int = 0,
        val h: Int = 0,
        /** The emulator's alpha for this control, 0-255 like Drawable alpha. */
        val alpha: Int = 255,
        /** True when a tap latches until the next tap (Dolphin latching). */
        val latching: Boolean = false,
        /** Stick origin capture (Dolphin JoystickRelCenter, ARMSX2 floating). */
        val recenter: Boolean = false,
        /** ARMSX2 turbo: toggle period in 60Hz frames, 0 = momentary. */
        val turboFrames: Int = 0,
        /** Nether: pressed art is [art] grown by this many host px each side. */
        val pressedGrowPx: Int = 0,
        /** ARMSX2 dpad key spacing 0..0.35 (dead-center + arm gap). */
        val dpadGap: Float = 0f,
        /** Dpad arm visuals (Nether 4 dirs, ARMSX2 4 arms); empty = single art. */
        val cells: List<EmuCell> = emptyList(),
        /** Nether axis: art is inset by this many host px per side (view padding). */
        val padPx: Int = 0,
        /** Nether: sliding onto this button pins it (TouchGliding). */
        val glide: Boolean = false,
        /** True for hit-only controls with no art by design (Flycast diagonals). */
        val noArt: Boolean = false,
        /** What a press drives on the wire. */
        val output: com.gamebex.client.stream.guest.OverlayOutput =
            com.gamebex.client.stream.guest.OverlayOutput.Ignored,
        val stickTravelPx: Float = 0f,
        val stickyDpad: Boolean = false,
        val dpadDiagonals: Boolean = true,
        /** Optional fractional art rectangles; older payloads use the integer diagnostics. */
        val renderRect: List<Float> = emptyList(),
        val knobRect: List<Float> = emptyList(),
        val renderGray: Float = 1f,
        val pressedRenderGray: Float = 1f,
        val knobRenderGray: Float = 1f,
        val textureInset: Int = 0,
    )

    /**
     * Host → guest, STREAM only, right after [StreamReady] when the
     * guest-facing flag `overlay_26b` is on and the host runs a non-RA
     * emulator (Dolphin app, NetherSX2, ARMSX2, Flycast): that emulator's
     * own touch controls — art rasterized at exact host pixels plus exact
     * rects — so the guest renders them 1:1. Chunked like
     * [StreamOverlay]; part 0 alone locates every control (rects first,
     * art follows). Missing art degrades like [StreamOverlay].
     */
    @Serializable @SerialName("stream_emu_overlay")
    data class StreamEmuOverlay(
        val sessionId: String,
        val round: Int,
        /** touchBackendForEmuPkg name: "dolphin", "nethersx2", "armsx2", "flycast", "melonds", "ppsspp". */
        val backend: String = "",
        val controls: List<EmuControl> = emptyList(),
        val partIndex: Int = 0,
        val partCount: Int = 1,
        val truncated: Boolean = false,
        /**
         * The host emulator's overlay viewport in pixels: origin + size on
         * the host display (default cutout mode letterboxes landscape apps
         * off the cutout side; control rects are viewport-px). Old hosts
         * send 0,0 + display size.
         */
        val screenX: Int = 0,
        val screenY: Int = 0,
        val screenW: Int = 0,
        val screenH: Int = 0,
        /** Host display in pixels (the viewport sits inside it). */
        val displayW: Int = 0,
        val displayH: Int = 0,
        /**
         * The rect the host's encoder crops to (host-display px), or all 0
         * when the shell streams full-frame. Mirrors
         * [OverlaySettingsMsg.cropX]: the guest offsets its video surface
         * by this instead of centering.
         */
        val cropX: Int = 0,
        val cropY: Int = 0,
        val cropW: Int = 0,
        val cropH: Int = 0,
    ) : LinkMsg

    @Serializable @SerialName("stream_resumed")
    data class StreamResumed(
        val sessionId: String,
        val round: Int,
        val attempt: String,
        val audioTimestampOrigin: String,
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

/**
 * The guest touch backend for a launched emulator package.
 * Unknown packages map to "" (generic pad); never guessed.
 */
fun touchBackendForEmuPkg(emuPkg: String?): String = when (emuPkg) {
    com.gamebex.client.data.Emu.RETROARCH.pkg -> "retroarch"
    com.gamebex.client.data.Emu.DOLPHIN.pkg -> "dolphin"
    com.gamebex.client.data.Emu.NETHER.pkg -> "nethersx2"
    com.gamebex.client.data.Emu.ARMSX2.pkg -> "armsx2"
    com.gamebex.client.data.Emu.FLYCAST.pkg -> "flycast"
    com.gamebex.client.data.Emu.MELONDS.pkg -> "melonds"
    com.gamebex.client.data.Emu.PPSSPP.pkg -> "ppsspp"
    else -> ""
}
