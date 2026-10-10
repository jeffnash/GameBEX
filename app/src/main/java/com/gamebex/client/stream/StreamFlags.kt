package com.gamebex.client.stream

import com.gamebex.client.stream.p2.P2SlotMode

/**
 * The single source of truth for Stream flags (Settings → Advanced →
 * Stream flags). Keys are the adb prop names (`debug.gamebex.<key>`), so one
 * vocabulary covers the UI, prefs and the lab scripts.
 *
 * Precedence (see [resolve]): adb prop on debug builds > in-app flag >
 * built-in default. Every consumer samples once per session; changes apply to
 * the next stream.
 *
 * Switches that stay adb-only (lab/diagnostic, no UI row): decoder, tdls,
 * ra_log, av_evidence, link_cut/link_fault hooks, ra_audio, input_reliable,
 * input_ack_drop_every, video_write_stall_ms, codec_diag, audio_jit,
 * audio_plc, audio_output_frames, rsound_quickack, control_resume, capture_hz,
 * encoder_intra_refresh, frame_rate_vote, stream_crop_aspect, measure_*,
 * run_label.
 */
enum class StreamFlagSide(val label: String) {
    HOST("Host phone"),
    WATCHING("Watching phone"),
    BOTH("Both phones"),
}

enum class StreamFlagGroup(val title: String) {
    DEFAULT_ON("On by default"),
    TRY("Try these"),
    WIFI("Bad Wi-Fi helpers"),
    NO_GAIN("No measured gain"),
}

sealed interface StreamFlagType {
    data object Toggle : StreamFlagType
    /** Choice among raw string values; [labels] parallels [values]. */
    data class Choice(val values: List<String>, val labels: List<String>) : StreamFlagType {
        init {
            require(values.isNotEmpty() && values.size == labels.size)
        }
    }
}

data class StreamFlagDef(
    val key: String,
    val label: String,
    val description: String,
    val side: StreamFlagSide,
    val type: StreamFlagType,
    /** Raw value meaning "built-in default". Toggles use "0"/"1". */
    val default: String,
    val group: StreamFlagGroup,
    /** "Everything promising ON" value; null means the first non-default choice value. */
    val promising: String? = null,
)

object StreamFlags {
    private fun toggle(
        key: String,
        label: String,
        description: String,
        side: StreamFlagSide,
        group: StreamFlagGroup,
        defaultOn: Boolean = false,
    ) = StreamFlagDef(key, label, description, side, StreamFlagType.Toggle, if (defaultOn) "1" else "0", group)

    private fun choice(
        key: String,
        label: String,
        description: String,
        side: StreamFlagSide,
        group: StreamFlagGroup,
        default: String,
        vararg options: Pair<String, String>,
        promising: String? = null,
    ) = StreamFlagDef(
        key, label, description, side,
        StreamFlagType.Choice(options.map { it.first }, options.map { it.second }),
        default, group, promising,
    )

    val ALL: List<StreamFlagDef> = listOf(
        choice("audio_lead_frames", "Audio lead", "Guest playout lead; 512 ms-frames is the default.",
            StreamFlagSide.WATCHING, StreamFlagGroup.DEFAULT_ON, "512",
            "256" to "256 (lowest latency)", "384" to "384", "512" to "512 (default)", "768" to "768 (legacy)"),
        // On by default: avoids the periodic 10 s keyframe storms.
        toggle("keyframes_on_request", "Keyframes on request", "No periodic 10 s keyframe; startup and recovery only.",
            StreamFlagSide.HOST, StreamFlagGroup.DEFAULT_ON, defaultOn = true),
        // On by default so the guest pad always matches the host's RetroArch overlay.
        toggle("overlay_26b", "Use host RetroArch overlay", "Set on both phones: the host shares its RetroArch overlay and the guest renders it.",
            StreamFlagSide.BOTH, StreamFlagGroup.DEFAULT_ON, defaultOn = true),
        // Keeps slot == user for player 2 on RetroArch builds without the button fix.
        choice("p2_slot_workaround", "P2 slot workaround (official RetroArch)",
            "Reserves player 1's device slot so player 2's buttons work on official RetroArch.",
            StreamFlagSide.HOST, StreamFlagGroup.DEFAULT_ON, P2SlotMode.FLAG_AUTO,
            P2SlotMode.FLAG_AUTO to "Auto (official RetroArch only)",
            P2SlotMode.FLAG_ON to "Always on",
            P2SlotMode.FLAG_OFF to "Off"),
        choice("start_level", "Start picture size", "Encoder size at stream start; 960 saves ~6 ms of video latency.",
            StreamFlagSide.HOST, StreamFlagGroup.TRY, "-1",
            "-1" to "Default (1280×576)", "0" to "1600×720", "1" to "1280×576", "2" to "960×432", "3" to "1280×576 @30fps",
            promising = "2"),
        toggle("audio_regulator", "Audio gap regulator", "Trades a small delay tail for far fewer audio gaps.",
            StreamFlagSide.WATCHING, StreamFlagGroup.TRY),
        choice("ra_audio_latency", "RetroArch audio latency", "RetroArch audio buffer in ms; lower is tighter.",
            StreamFlagSide.HOST, StreamFlagGroup.TRY, "32",
            "32" to "32 ms (default)", "16" to "16 ms", "8" to "8 ms"),
        choice("ra_swapchain", "RetroArch frame queue", "Queued frames ahead; 2 can save host latency.",
            StreamFlagSide.HOST, StreamFlagGroup.TRY, "default",
            "default" to "Default", "2" to "2 frames"),
        toggle("ra_no_smooth", "RetroArch smoothing off", "Disables bilinear filtering; sharper, less GPU work.",
            StreamFlagSide.HOST, StreamFlagGroup.TRY),
        toggle("minimal_postprocess", "Minimal post-processing", "Asks the display pipeline to skip extra processing.",
            StreamFlagSide.WATCHING, StreamFlagGroup.TRY),
        choice("ra_poll", "RetroArch input poll", "When input is sampled; late can react faster.",
            StreamFlagSide.HOST, StreamFlagGroup.TRY, "0",
            "0" to "Early (default)", "1" to "Normal", "2" to "Late",
            promising = "2"),
        toggle("unbuffered_input", "Unbuffered stick", "Skips guest input batching for physical sticks.",
            StreamFlagSide.WATCHING, StreamFlagGroup.TRY),
        choice("encoder_fps", "Encoder frame rate", "50 fps matches 50 Hz (PAL) games.",
            StreamFlagSide.HOST, StreamFlagGroup.TRY, "default",
            "default" to "Default (60)", "50" to "50"),
        toggle("overlay_autohide_v3", "Host overlay auto-hide", "RetroArch v3 hides its overlay for player 1's pad.",
            StreamFlagSide.HOST, StreamFlagGroup.TRY),
        choice("queue_deadline_ms", "Send deadline", "Drops stale frames under congestion instead of queueing.",
            StreamFlagSide.HOST, StreamFlagGroup.WIFI, "0",
            "0" to "Off", "40" to "40 ms"),
        toggle("latency_cc", "Congestion control", "Lowers bitrate proactively when the link struggles.",
            StreamFlagSide.HOST, StreamFlagGroup.WIFI),
        toggle("latest_output", "Latest decoded output", "Shows the newest decoded frame during decoder bursts.",
            StreamFlagSide.WATCHING, StreamFlagGroup.WIFI),
        toggle("input_cs6", "Priority input packets", "Marks input packets so Wi-Fi queues them first.",
            StreamFlagSide.BOTH, StreamFlagGroup.WIFI),
        toggle("frame_rate_fixed", "Fixed-source frame rate", "Tells the display the rate never changes.",
            StreamFlagSide.WATCHING, StreamFlagGroup.WIFI),
        toggle("frame_rate_always", "Frame rate always", "Re-applies the frame-rate vote on every frame.",
            StreamFlagSide.WATCHING, StreamFlagGroup.WIFI),
        toggle("hud_off", "HUD off", "Hides the in-stream overlay.", StreamFlagSide.WATCHING, StreamFlagGroup.NO_GAIN),
        toggle("touch_pad_hidden", "Touch pad hidden", "Hides the touch controls.",
            StreamFlagSide.WATCHING, StreamFlagGroup.NO_GAIN),
        toggle("fixed_surface", "Fixed surface size", "Keeps the decoder surface at one size.",
            StreamFlagSide.WATCHING, StreamFlagGroup.NO_GAIN),
        toggle("surface_opaque", "Opaque surface", "Marks the video surface opaque.",
            StreamFlagSide.WATCHING, StreamFlagGroup.NO_GAIN),
        choice("display_mode", "Display mode", "Prefer the native 60 Hz mode.",
            StreamFlagSide.WATCHING, StreamFlagGroup.NO_GAIN, "default",
            "default" to "Default (fastest)", "60hz" to "60 Hz if available"),
        toggle("stream_crop", "Crop to game picture", "Encodes only the game area (per-core aspect); slower than 960 today.",
            StreamFlagSide.HOST, StreamFlagGroup.NO_GAIN),
    )

    val BY_KEY: Map<String, StreamFlagDef> = ALL.associateBy { it.key }

    /** Group 2's "on" value per flag: toggles on, choices to the first non-default value. */
    fun promisingOn(def: StreamFlagDef): String = def.promising ?: when (val t = def.type) {
        is StreamFlagType.Toggle -> "1"
        is StreamFlagType.Choice -> t.values.firstOrNull { it != def.default } ?: def.default
    }

    /**
     * Effective raw value: adb prop on debug builds, else the in-app flag,
     * else the built-in default. [pref] returns the stored raw value or null
     * when the user never set the flag.
     */
    fun resolve(
        key: String,
        debug: Boolean,
        prop: (String) -> String?,
        pref: (String) -> String?,
    ): String {
        val def = BY_KEY[key] ?: return pref(key) ?: prop(key).orEmpty()
        if (debug) {
            val p = prop(key)
            if (!p.isNullOrEmpty()) return p
        }
        val v = pref(key)
        if (!v.isNullOrEmpty()) return v
        return def.default
    }

    /**
     * Effective toggle for engine gates: [lookup] is the already-merged
     * adb-prop/pref value (null/empty when never set), falling back to
     * the built-in default. A bare `lookup(key) == "1"` would miss
     * default-on flags on fresh installs.
     */
    fun toggleOn(key: String, lookup: (String) -> String?): Boolean {
        val v = lookup(key)
        if (!v.isNullOrEmpty()) return v == "1"
        return BY_KEY[key]?.default == "1"
    }

    /** True when the effective value differs from the built-in default. */
    fun isNonDefault(
        key: String,
        debug: Boolean,
        prop: (String) -> String?,
        pref: (String) -> String?,
    ): Boolean {
        val def = BY_KEY[key] ?: return false
        return resolve(key, debug, prop, pref) != def.default
    }

    /** One-line stats summary of this phone's non-default flags, or null when none. */
    fun activeSummary(
        debug: Boolean,
        prop: (String) -> String?,
        pref: (String) -> String?,
        sides: Set<StreamFlagSide> = StreamFlagSide.entries.toSet(),
    ): String? {
        val active = ALL.filter { it.side in sides && isNonDefault(it.key, debug, prop, pref) }
        if (active.isEmpty()) return null
        return "Flags: " + active.joinToString(", ") { "${it.key}=${resolve(it.key, debug, prop, pref)}" }
    }
}
