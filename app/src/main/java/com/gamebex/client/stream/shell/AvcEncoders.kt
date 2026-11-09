package com.gamebex.client.stream.shell

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import com.gamebex.client.stream.StreamCaps
import com.gamebex.client.stream.VideoLevel

/**
 * Which H.264 encoder to use and how to configure it. The
 * ranking is pure ([rank]); [list] reads MediaCodecList on the device.
 */
object AvcEncoders {
    const val MIME: String = MediaFormat.MIMETYPE_VIDEO_AVC

    /** AVCProfileConstrainedBaseline (API 27 constant; the value is fixed by OMX). */
    const val PROFILE_CONSTRAINED_BASELINE: Int = 0x10000
    const val PROFILE_BASELINE: Int = 0x01

    /** MediaFormat.KEY_MAX_FPS_TO_ENCODER's string ("max-fps-to-encoder", API 29), as scrcpy uses it. */
    const val KEY_MAX_FPS_TO_ENCODER: String = "max-fps-to-encoder"

    /** What GameBEX needs to know about one encoder. */
    data class EncoderInfo(
        val name: String,
        val hardware: Boolean,
        val profiles: Set<Int>,
        val cbr: Boolean,
        val maxWidth: Int,
        val maxHeight: Int,
        val supportedLevelIds: Set<Int>? = null,
        val supportedPal50LevelIds: Set<Int>? = null,
        val intraRefresh: Boolean = false,
    )

    /** MediaTek encoders size their clock to KEY_FRAME_RATE, so a frame takes about 1/rate to come out. */
    const val MTK_DECLARED_FPS: Int = 240

    /** [Options.rateHint]: -1 uses measured hardware, 0 declares the real rate, positive values override it. */
    const val RATE_HINT_AUTO: Int = -1

    data class Options(
        val fps: Int = 0,
        val intraRefresh: Boolean = false,
        val keyframesOnRequest: Boolean = false,
        val rateHint: Int = RATE_HINT_AUTO,
        val socModel: String = "",
    ) {
        fun fpsFor(level: VideoLevel): Int = if (fps == 50 && level.fps == 60) 50 else level.fps

        /**
         * KEY_FRAME_RATE for [info] at the real cadence [fps]. On the MT6835 declaring 240 cut the
         * capture-to-output time from about 23 to 13 ms at the same bitrate; KEY_MAX_FPS_TO_ENCODER
         * keeps the input at [fps]. Never below [fps].
         */
        fun declaredFps(info: EncoderInfo, fps: Int): Int = when {
            rateHint == 0 -> fps
            rateHint > 0 -> maxOf(fps, rateHint)
            isMediaTek(info.name) && socModel.uppercase().startsWith("MT6835") -> maxOf(fps, MTK_DECLARED_FPS)
            else -> fps
        }
    }

    fun isMediaTek(name: String): Boolean = name.startsWith("c2.mtk.", ignoreCase = true) || name.startsWith("OMX.MTK.", ignoreCase = true)

    data class Configuration(
        val strict: Boolean,
        val fps: Int,
        val intraRefreshPeriod: Int = 0,
        val intraRefreshFallback: Boolean = false,
        val keyframesOnRequest: Boolean = false,
        /** KEY_FRAME_RATE: [fps] unless the vendor runs faster when told a higher rate. */
        val declaredFps: Int = fps,
    ) {
        /** KEY_I_FRAME_INTERVAL in declared-rate seconds, so the IDR spacing in real frames is unchanged. */
        val declaredIFrameInterval: Float get() =
            if (iFrameIntervalS <= 0 || declaredFps == fps) iFrameIntervalS.toFloat() else iFrameIntervalS * fps.toFloat() / declaredFps

        val iFrameIntervalS: Int get() = when {
            intraRefreshPeriod > 0 -> -1
            keyframesOnRequest -> StreamCaps.KEYFRAMES_ON_REQUEST_INTERVAL_S
            else -> StreamCaps.I_FRAME_INTERVAL_S
        }
    }

    /** Periodic IDR interval for non-intra-refresh attempts under [options]. */
    fun periodicIdrS(options: Options): Int =
        if (options.keyframesOnRequest) StreamCaps.KEYFRAMES_ON_REQUEST_INTERVAL_S else StreamCaps.I_FRAME_INTERVAL_S

    fun configurationAttempts(level: VideoLevel, info: EncoderInfo, options: Options): List<Configuration> {
        val fps = options.fpsFor(level)
        val declared = options.declaredFps(info, fps)
        val periodic = listOf(true, false).map { Configuration(it, fps,
            intraRefreshFallback = options.intraRefresh, keyframesOnRequest = options.keyframesOnRequest, declaredFps = declared) }
        val attempts = if (options.intraRefresh && info.intraRefresh) {
            listOf(true, false).map { Configuration(it, fps, fps * StreamCaps.I_FRAME_INTERVAL_S, declaredFps = declared) } + periodic
        } else periodic
        return if (declared == fps) attempts else attempts + attempts.map { it.copy(declaredFps = fps) }
    }

    /** Parses the debug `venc_keys` setting ("vendor.key=1;frame-rate=120") into integer format keys; malformed entries are skipped. */
    internal fun vendorKeys(value: String?): Map<String, Int> =
        value.orEmpty().split(';', ',').mapNotNull { entry ->
            val parts = entry.split('=', limit = 2)
            if (parts.size != 2) return@mapNotNull null
            val name = parts[0].trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            parts[1].trim().toIntOrNull()?.let { name to it }
        }.toMap()

    internal fun intraRefreshConfirmed(configuration: Configuration, reportedPeriod: Int?): Boolean =
        configuration.intraRefreshPeriod == 0 || (reportedPeriod != null && reportedPeriod > 0)

    /**
     * Usable encoders, best first: hardware before software (software can't keep
     * 60 fps at 1600x720 on a Dimensity 6300), then those offering Constrained
     * Baseline, then CBR, then by name so the order is stable.
     */
    fun rank(all: List<EncoderInfo>, level: VideoLevel, options: Options = Options()): List<EncoderInfo> =
        all.filter {
            val supported = if (options.fpsFor(level) != level.fps) it.supportedPal50LevelIds else it.supportedLevelIds
            it.maxWidth >= level.width && it.maxHeight >= level.height &&
                (supported == null || level.index in supported)
        }
            .sortedWith(
                compareByDescending<EncoderInfo> { it.hardware }
                    .thenByDescending { PROFILE_CONSTRAINED_BASELINE in it.profiles }
                    .thenByDescending { it.cbr }
                    .thenBy { it.name },
            )

    internal fun aligned(level: VideoLevel, widthAlignment: Int, heightAlignment: Int): Boolean =
        widthAlignment > 0 && heightAlignment > 0 && level.width % widthAlignment == 0 && level.height % heightAlignment == 0

    /** Profile to ask for: Constrained Baseline when offered, else Baseline, else the encoder's default. */
    fun profileFor(info: EncoderInfo): Int? = when {
        PROFILE_CONSTRAINED_BASELINE in info.profiles -> PROFILE_CONSTRAINED_BASELINE
        PROFILE_BASELINE in info.profiles -> PROFILE_BASELINE
        else -> null
    }

    /** Heuristic for pre-API-29 devices: Google's and generic software encoders by name. */
    fun looksSoftware(name: String): Boolean {
        val n = name.lowercase()
        return n.startsWith("omx.google.") || n.startsWith("c2.android.") || n.contains(".sw.") || n.startsWith("omx.ffmpeg.")
    }

    /** Every AVC encoder on this device that takes a Surface as input. */
    fun list(): List<EncoderInfo> = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        .filter { it.isEncoder && it.supportedTypes.any { t -> t.equals(MIME, ignoreCase = true) } }
        .mapNotNull { info ->
            runCatching {
                val caps = info.getCapabilitiesForType(MIME)
                if (MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface !in caps.colorFormats) return@runCatching null
                val video = caps.videoCapabilities
                val hardware = if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated else !looksSoftware(info.name)
                val cbr = caps.encoderCapabilities?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR) == true
                EncoderInfo(
                    name = info.name,
                    hardware = hardware,
                    profiles = caps.profileLevels.map { it.profile }.toSet(),
                    cbr = cbr,
                    maxWidth = video.supportedWidths.upper,
                    maxHeight = video.supportedHeights.upper,
                    supportedLevelIds = StreamCaps.LEVELS.filter { level ->
                        runCatching {
                            aligned(level, video.widthAlignment, video.heightAlignment) &&
                                video.areSizeAndRateSupported(level.width, level.height, level.fps.toDouble())
                        }.getOrDefault(false)
                    }.map { it.index }.toSet(),
                    supportedPal50LevelIds = StreamCaps.LEVELS.filter { level ->
                        level.fps == 60 && runCatching {
                            aligned(level, video.widthAlignment, video.heightAlignment) &&
                                video.areSizeAndRateSupported(level.width, level.height, 50.0)
                        }.getOrDefault(false)
                    }.map { it.index }.toSet(),
                    intraRefresh = runCatching {
                        caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_IntraRefresh)
                    }.getOrDefault(false),
                )
            }.getOrNull()
        }

    /**
     * The encoder format for [level] at [bitrate]. [strict] adds the optional
     * keys (profile, CBR); the first retry drops them, because some vendor
     * encoders refuse a key they don't know instead of ignoring it.
     */
    fun format(level: VideoLevel, bitrate: Int, info: EncoderInfo, strict: Boolean): MediaFormat =
        format(level, bitrate, info, Configuration(strict, level.fps))

    fun format(level: VideoLevel, bitrate: Int, info: EncoderInfo, configuration: Configuration): MediaFormat =
        MediaFormat.createVideoFormat(MIME, level.width, level.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, configuration.declaredFps)
            if (configuration.declaredFps == configuration.fps) {
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, configuration.iFrameIntervalS)
            } else {
                setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, configuration.declaredIFrameInterval)
            }
            if (configuration.intraRefreshPeriod > 0) {
                setInteger(MediaFormat.KEY_INTRA_REFRESH_PERIOD, configuration.intraRefreshPeriod)
            }
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, StreamCaps.REPEAT_FRAME_US)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            setInteger(MediaFormat.KEY_LATENCY, 1)
            if (Build.VERSION.SDK_INT >= 29) {
                setFloat(KEY_MAX_FPS_TO_ENCODER, configuration.fps.toFloat())
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            }
            // No KEY_OPERATING_RATE: on the MT6835 an operating rate of 120 made the encoder batch frames
            // (encode p50 20 -> 50 ms on-device).
            if (configuration.strict) {
                profileFor(info)?.let { setInteger(MediaFormat.KEY_PROFILE, it) }
                if (info.cbr) setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            }
        }
}
