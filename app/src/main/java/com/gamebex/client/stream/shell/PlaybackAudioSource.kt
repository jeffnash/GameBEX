package com.gamebex.client.stream.shell

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.os.Build
import android.util.Log
import com.gamebex.client.stream.StreamCaps

/**
 * Game audio from the host: an AudioPolicy loopback mix of every
 * player with usage MEDIA, GAME or UNKNOWN, routed LOOP_BACK_RENDER so the
 * host keeps hearing it. This is the shell-uid path scrcpy v4.1 uses for
 * `--audio-source=playback --audio-dup` (server/src/main/java/com/genymobile/scrcpy/audio/AudioPlaybackCapture.java),
 * reached by reflection because AudioMix, AudioMixingRule and AudioPolicy are
 * hidden. Android 13+ only. Apps that opted out of playback capture are not
 * captured; the stream then has no sound from them.
 */
class PlaybackAudioSource(private val context: Context) : AudioSource {
    private val lock = Any()
    private var record: AudioRecord? = null
    private var policy: Any? = null
    private var thread: Thread? = null
    @Volatile private var running = false

    override fun start(sink: AudioSink): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        synchronized(lock) {
            return try {
                val (p, r) = createPolicyAndRecord()
                policy = p
                record = r
                r.startRecording()
                if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw IllegalStateException("recording did not start")
                running = true
                thread = Thread({ readLoop(r, sink) }, "gbx-audio-read").apply {
                    isDaemon = true
                    priority = Thread.MAX_PRIORITY
                    start()
                }
                true
            } catch (t: Throwable) {
                Log.w(TAG, "playback capture unavailable", t)
                releaseLocked()
                false
            }
        }
    }

    override fun stop() {
        val t: Thread?
        synchronized(lock) {
            running = false
            t = thread
            thread = null
            releaseLocked()
        }
        t?.join(JOIN_MS)
    }

    private fun readLoop(r: AudioRecord, sink: AudioSink) {
        val bytes = StreamCaps.AUDIO_CHUNK_FRAMES * StreamCaps.AUDIO_CHANNELS * 2
        val chunkNs = StreamCaps.AUDIO_CHUNK_FRAMES * 1_000_000_000L / StreamCaps.AUDIO_RATE
        val ts = AudioTimestamp()
        var framesRead = 0L
        var seq = 0
        while (running) {
            val buffer = ByteArray(bytes)
            var off = 0
            while (off < bytes && running) {
                val n = r.read(buffer, off, bytes - off, AudioRecord.READ_BLOCKING)
                if (n < 0) {
                    Log.w(TAG, "audio read error $n")
                    return
                }
                off += n
            }
            if (!running) return
            val now = System.nanoTime()
            val capture = if (r.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                ts.nanoTime + (framesRead - ts.framePosition) * 1_000_000_000L / StreamCaps.AUDIO_RATE
            } else {
                now - chunkNs
            }
            framesRead += StreamCaps.AUDIO_CHUNK_FRAMES
            seq += 1
            sink.onPcm(PcmChunk(seq, capture.coerceAtMost(now), StreamCaps.AUDIO_CHUNK_FRAMES, buffer))
        }
    }

    private fun releaseLocked() {
        runCatching { record?.stop() }
        runCatching { record?.release() }
        record = null
        policy?.let { unregister(it) }
        policy = null
    }

    private fun unregister(p: Any) {
        val policyClass = Class.forName("android.media.audiopolicy.AudioPolicy")
        runCatching {
            AudioManager::class.java.getDeclaredMethod("unregisterAudioPolicyAsyncStatic", policyClass)
                .apply { isAccessible = true }
                .invoke(null, p)
        }.recoverCatching {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            AudioManager::class.java.getMethod("unregisterAudioPolicy", policyClass).invoke(am, p)
        }.onFailure { Log.w(TAG, "could not unregister the audio policy; it goes when the process exits", it) }
    }

    /** The reflection scrcpy's AudioPlaybackCapture.createAudioRecord() does, with three usages and loop-back-render. */
    private fun createPolicyAndRecord(): Pair<Any, AudioRecord> {
        val ruleClass = Class.forName("android.media.audiopolicy.AudioMixingRule")
        val ruleBuilderClass = Class.forName("android.media.audiopolicy.AudioMixingRule\$Builder")
        val ruleBuilder = ruleBuilderClass.getConstructor().newInstance()
        ruleBuilderClass.getMethod("setTargetMixRole", Int::class.javaPrimitiveType)
            .invoke(ruleBuilder, ruleClass.getField("MIX_ROLE_PLAYERS").getInt(null))
        val matchUsage = ruleClass.getField("RULE_MATCH_ATTRIBUTE_USAGE").getInt(null)
        val addRule = ruleBuilderClass.getMethod("addMixRule", Int::class.javaPrimitiveType, Any::class.java)
        for (usage in USAGES) addRule.invoke(ruleBuilder, matchUsage, AudioAttributes.Builder().setUsage(usage).build())
        val rule = ruleBuilderClass.getMethod("build").invoke(ruleBuilder)

        val mixClass = Class.forName("android.media.audiopolicy.AudioMix")
        val mixBuilderClass = Class.forName("android.media.audiopolicy.AudioMix\$Builder")
        val mixBuilder = mixBuilderClass.getConstructor(ruleClass).newInstance(rule)
        mixBuilderClass.getMethod("setFormat", AudioFormat::class.java).invoke(mixBuilder, format())
        mixBuilderClass.getMethod("setRouteFlags", Int::class.javaPrimitiveType)
            .invoke(mixBuilder, mixClass.getField("ROUTE_FLAG_LOOP_BACK_RENDER").getInt(null))
        val mix = mixBuilderClass.getMethod("build").invoke(mixBuilder)

        val policyClass = Class.forName("android.media.audiopolicy.AudioPolicy")
        val policyBuilderClass = Class.forName("android.media.audiopolicy.AudioPolicy\$Builder")
        val policyBuilder = policyBuilderClass.getConstructor(Context::class.java).newInstance(context)
        policyBuilderClass.getMethod("addMix", mixClass).invoke(policyBuilder, mix)
        val policy = policyBuilderClass.getMethod("build").invoke(policyBuilder)

        val register = AudioManager::class.java.getDeclaredMethod("registerAudioPolicyStatic", policyClass).apply { isAccessible = true }
        val result = register.invoke(null, policy) as Int
        if (result != 0) throw IllegalStateException("registerAudioPolicyStatic returned $result")
        val record = try {
            policyClass.getMethod("createAudioRecordSink", mixClass).invoke(policy, mix) as AudioRecord
        } catch (t: Throwable) {
            unregister(policy)
            throw t
        }
        return policy to record
    }

    private fun format(): AudioFormat = AudioFormat.Builder()
        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
        .setSampleRate(StreamCaps.AUDIO_RATE)
        .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
        .build()

    companion object {
        private const val TAG = "GameBEXStream"
        private const val JOIN_MS = 1_000L
        private val USAGES = intArrayOf(AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_GAME, AudioAttributes.USAGE_UNKNOWN)

        /** True when this build has every hidden piece the capture needs. */
        fun available(): Boolean = Build.VERSION.SDK_INT >= 33 && runCatching {
            Class.forName("android.media.audiopolicy.AudioMixingRule\$Builder").getMethod("setTargetMixRole", Int::class.javaPrimitiveType)
            Class.forName("android.media.audiopolicy.AudioMix").getField("ROUTE_FLAG_LOOP_BACK_RENDER")
            AudioManager::class.java.getDeclaredMethod("registerAudioPolicyStatic", Class.forName("android.media.audiopolicy.AudioPolicy"))
        }.isSuccess
    }
}
