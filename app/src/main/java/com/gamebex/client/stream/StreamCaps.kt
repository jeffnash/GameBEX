package com.gamebex.client.stream

object StreamCaps {
    /** Hosting needs the playback-capture loopback route (Android 13). */
    const val MIN_HOST_SDK: Int = 33

    /** Watching needs KEY_LOW_LATENCY-era decoders (Android 10). */
    const val MIN_GUEST_SDK: Int = 29

    const val PAD_NAME: String = "GameBEX Player 2"
    const val PAD_UNIQ: String = "gamebex-player-2"
    const val PAD_PHYS: String = "gamebex:stream"

    /**
     * Silent placeholder pad that takes official RetroArch's slot 0 on touch-only
     * hosts so player 2 lands in slot 1. Same vendor/product 0 and descriptor as [PAD_NAME].
     */
    const val PLACEHOLDER_PAD_NAME: String = "GameBEX Player 1 slot"
    const val PLACEHOLDER_PAD_UNIQ: String = "gamebex-player-1-slot"

    /** Vendor/product 0: no Android .kl, no RetroArch profile and no kernel driver match it. */
    const val PAD_VENDOR: Int = 0x0000
    const val PAD_PRODUCT: Int = 0x0000
    const val PAD_VERSION: Int = 0x0001

    /** Linux BUS_VIRTUAL, as scrcpy's UHID gamepads use. */
    const val PAD_BUS: Int = 0x06

    const val MEDIA_PORT: Int = 55441
    const val INPUT_PORT: Int = 55442

    /** Quality levels, best first. */
    val LEVELS: List<VideoLevel> = listOf(
        VideoLevel(0, 1600, 720, 60, 8_000_000),
        VideoLevel(1, 1280, 576, 60, 4_000_000),
        VideoLevel(2, 960, 432, 60, 3_000_000),
        VideoLevel(3, 1280, 576, 30, 3_000_000),
    )
    const val MIN_BITRATE: Int = 1_500_000
    const val I_FRAME_INTERVAL_S: Int = 10
    /**
     * KEY_I_FRAME_INTERVAL for keyframes-on-request. 0 would make every frame an
     * I-frame; no session lasts 24 h, so IDRs come only at startup and on request.
     */
    const val KEYFRAMES_ON_REQUEST_INTERVAL_S: Int = 86_400
    const val REPEAT_FRAME_US: Long = 100_000L

    /** Encoded frames the host may queue before it drops to the next keyframe. */
    const val MAX_QUEUED_FRAMES: Int = 4
    const val MAX_QUEUE_AGE_MS: Long = 120L
    const val STEP_UP_AFTER_MS: Long = 5_000L
    const val LEVEL_DOWN_AFTER_MS: Long = 5_000L

    const val AUDIO_RATE: Int = 48_000
    const val AUDIO_CHANNELS: Int = 2
    /** 5 ms: one chunk (960 bytes of PCM) fits one UDP datagram under the Wi-Fi MTU. */
    const val AUDIO_CHUNK_FRAMES: Int = 240
    const val AUDIO_TARGET_MS: Int = 30
    const val AUDIO_MIN_MS: Int = 20
    const val AUDIO_MAX_MS: Int = 120

    /** Input keep-alive while something is held, and while idle. */
    const val INPUT_ACTIVE_MS: Long = 8L
    const val INPUT_IDLE_MS: Long = 50L
    /** Host releases player 2 after this much input silence. */
    const val INPUT_TIMEOUT_MS: Long = 250L

    /** Guest holds SELECT+START this long to leave the viewer. */
    const val LEAVE_HOLD_MS: Long = 2_000L
    const val RECONNECT_WINDOW_MS: Long = 10_000L
    const val PING_INTERVAL_MS: Long = 1_000L
    const val PING_BURST: Int = 8

    /** Shell watchdogs: no guest this long, or no app status poll this long, ends the stream. */
    const val GUEST_ABSENT_STOP_MS: Long = 60_000L
    const val APP_SILENT_STOP_MS: Long = 15_000L
    const val STATUS_POLL_MS: Long = 2_000L

    /** Rewrite the SPS so decoders output each frame at once. */
    const val SPS_FIXUP: Boolean = true

    /** Bump when IStreamShell or the shell code changes so Shizuku restarts the service. */
    const val SHELL_SERVICE_VERSION: Int = 34

    /** Dolphin's index for the only device named [PAD_NAME]. */
    const val DOLPHIN_DEVICE_INDEX: Int = 0

    /** NetherSX2 keeps a port-2 mapping across sessions. */
    const val NETHER_MAPPING_PERSISTS: Boolean = true
}

/** One encoder setting the congestion controller can pick. */
data class VideoLevel(val index: Int, val width: Int, val height: Int, val fps: Int, val maxBitrate: Int)
