package com.offline.phonelink.bt

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import java.util.concurrent.Executors

/**
 * Experimental: carries the call's sound by software, for players whose audio service cannot play a
 * Bluetooth hands-free call itself.
 *
 * MediaTek players have a separate audio path for Bluetooth calls ("BT CVSD"). The bridge switches
 * it on, tells the audio system a Bluetooth call headset is present, and then copies sound in two
 * directions: phone → player speaker, and player microphone → phone.
 * Every step logs under the "PhoneLink" tag so a failed step shows up in the troubleshooting log.
 */
@SuppressLint("MissingPermission")
class AudioBridge(private val context: Context) {

    private val audio = context.getSystemService(AudioManager::class.java)
    /** Starting and stopping wait for the audio system: done in order, off the main thread. */
    private val worker = Executors.newSingleThreadExecutor()
    private var threads: List<Thread> = emptyList()
    private var address: String? = null
    private var previousMode = AudioManager.MODE_NORMAL

    @Volatile
    private var running = false

    /** What was last asked for (the worker may still be getting there). */
    @Volatile
    private var wanted = false

    fun start(phoneAddress: String) {
        if (wanted) return
        wanted = true
        worker.execute { begin(phoneAddress) }
    }

    fun stop() {
        if (!wanted) return
        wanted = false
        worker.execute { end() }
    }

    /** The phone reopened the call audio after it was moved away: switch the Bluetooth path on again. */
    fun audioReturned() {
        if (!wanted) return
        worker.execute {
            if (!running) return@execute
            val apk = context.applicationInfo.sourceDir
            val r = SystemCheck.root(
                "CLASSPATH='$apk' app_process /system/bin com.offline.phonelink.root.AudioPolicyTool sco-on ${address ?: ""}",
                20,
            )
            log("audio returned, sco-on: exit ${r.code}: ${r.output.replace("\n", " | ")}")
        }
    }

    fun release() {
        stop()
        worker.shutdown()
    }

    private fun begin(phoneAddress: String) {
        if (running) return
        running = true
        address = phoneAddress
        previousMode = audio.mode
        log("start for $phoneAddress (mode was $previousMode)")
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        setScoDevices(phoneAddress, available = true)

        val scoIn = waitForDevice(AudioManager.GET_DEVICES_INPUTS, AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
        val scoOut = waitForDevice(AudioManager.GET_DEVICES_OUTPUTS, AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
        val mic = audio.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
        val speaker = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        log("devices: scoIn=${scoIn?.id} scoOut=${scoOut?.id} mic=${mic?.id} speaker=${speaker?.id}")

        threads = listOf(
            // Communication audio is routed to the Bluetooth call headset, so this records the phone's side.
            pump("phone→speaker", MediaRecorder.AudioSource.VOICE_COMMUNICATION, scoIn, AudioAttributes.USAGE_MEDIA, speaker, farEnd = true),
            pump("mic→phone", MediaRecorder.AudioSource.MIC, mic, AudioAttributes.USAGE_VOICE_COMMUNICATION, scoOut, farEnd = false),
        )
        threads.forEach { it.start() }
    }

    private fun end() {
        if (!running) return
        running = false
        threads.forEach { runCatching { it.join(1_000) } }
        threads = emptyList()
        address?.let { setScoDevices(it, available = false) }
        audio.mode = previousMode
        log("stopped")
    }

    /** Copies sound from one device to another until [stop]. */
    private fun pump(
        name: String,
        source: Int,
        from: AudioDeviceInfo?,
        usage: Int,
        to: AudioDeviceInfo?,
        farEnd: Boolean,
    ): Thread = Thread({
        val rate = SAMPLE_RATE
        val inFormat = AudioFormat.CHANNEL_IN_MONO
        val outFormat = AudioFormat.CHANNEL_OUT_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val inSize = AudioRecord.getMinBufferSize(rate, inFormat, encoding).coerceAtLeast(rate / 5)
        val outSize = AudioTrack.getMinBufferSize(rate, outFormat, encoding).coerceAtLeast(rate / 5)
        var record: AudioRecord? = null
        var track: AudioTrack? = null
        try {
            record = AudioRecord(source, rate, inFormat, encoding, inSize * 2)
            if (from != null) record.preferredDevice = from
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(usage)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(outFormat).setEncoding(encoding).build())
                .setBufferSizeInBytes(outSize * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                // Both tracks on the primary output share one device, and while Bluetooth call audio
                // is on that device is Bluetooth. Media in power-saving mode plays on a separate
                // (deep buffer) output, which can go to the speaker at the same time.
                .setPerformanceMode(
                    if (usage == AudioAttributes.USAGE_MEDIA) AudioTrack.PERFORMANCE_MODE_POWER_SAVING
                    else AudioTrack.PERFORMANCE_MODE_NONE,
                )
                .build()
            if (to != null) track.preferredDevice = to
            record.startRecording()
            track.play()
            log("$name: running (in=${record.routedDevice?.type}, out=${track.routedDevice?.type})")
            val buffer = ShortArray(inSize / 2)
            var loud = 0L
            var frames = 0L
            var suppressed = 0L
            var gain = 1f
            var routeLogged = false
            val started = System.currentTimeMillis()
            while (running) {
                if (!routeLogged && System.currentTimeMillis() - started > 1_500) {
                    routeLogged = true
                    log("$name: after start in=${record.routedDevice?.type}, out=${track.routedDevice?.type}")
                }
                val n = record.read(buffer, 0, buffer.size)
                if (n <= 0) continue
                val level = rms(buffer, n)
                if (farEnd) {
                    // The other side is talking: remember it for the microphone direction.
                    if (level > FAR_END_TALKING) farEndUntil = System.currentTimeMillis() + FAR_END_HOLD_MS
                } else {
                    // Echo suppression: while the other side talks, the player's microphone mostly hears
                    // the player's own speaker. Sending that back makes the other side hear itself and
                    // can build up into a howl, so the microphone is turned down until they stop.
                    val target = if (System.currentTimeMillis() < farEndUntil) SUPPRESSED_GAIN else 1f
                    gain = applyGain(buffer, n, gain, target)
                    if (target < 1f) suppressed += n
                }
                track.write(buffer, 0, n)
                frames += n
                if (buffer.take(n).any { it > 500 || it < -500 }) loud += n
                if (frames >= rate * 5L) {
                    val extra = if (farEnd) "" else ", mic turned down ${suppressed * 100 / frames}%"
                    log("$name: 5s copied, ${loud * 100 / frames}% with sound$extra")
                    frames = 0
                    loud = 0
                    suppressed = 0
                }
            }
        } catch (t: Throwable) {
            log("$name failed: $t")
        } finally {
            runCatching { record?.stop() }
            runCatching { record?.release() }
            runCatching { track?.stop() }
            runCatching { track?.release() }
        }
    }, "PhoneLink-$name")

    /** Until when the other side counts as talking (for echo suppression). */
    @Volatile
    private var farEndUntil = 0L

    private fun rms(buffer: ShortArray, n: Int): Double {
        var sum = 0.0
        for (i in 0 until n) sum += buffer[i].toDouble() * buffer[i]
        return kotlin.math.sqrt(sum / n)
    }

    /** Scales the block, moving smoothly from [from] to [to] so the change does not click. */
    private fun applyGain(buffer: ShortArray, n: Int, from: Float, to: Float): Float {
        if (from == 1f && to == 1f) return 1f
        for (i in 0 until n) {
            val g = from + (to - from) * (i + 1) / n
            buffer[i] = (buffer[i] * g).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return to
    }

    private fun waitForDevice(flag: Int, type: Int): AudioDeviceInfo? {
        repeat(10) {
            audio.getDevices(flag).firstOrNull { it.type == type }?.let { return it }
            Thread.sleep(100)
        }
        return null
    }

    /**
     * Tells the audio system that a Bluetooth call headset is (or is no longer) connected and routes
     * communication audio to it. The audio policy accepts this only from system processes, so it runs
     * as root in a separate process (see AudioPolicyTool).
     */
    private fun setScoDevices(address: String, available: Boolean) {
        val apk = context.applicationInfo.sourceDir
        val verb = if (available) "connect" else "disconnect"
        val result = SystemCheck.root(
            "CLASSPATH='$apk' app_process /system/bin com.offline.phonelink.root.AudioPolicyTool $verb $address",
            20,
        )
        log("policy $verb: exit ${result.code}: ${result.output.replace("\n", " | ")}")
    }

    private fun log(msg: String) = Log.i("PhoneLink", "AudioBridge: $msg")

    companion object {
        /** Bluetooth call audio here is narrow-band (CVSD, 8 kHz). */
        private const val SAMPLE_RATE = 8_000

        /** Level (RMS) above which the other side counts as talking. */
        private const val FAR_END_TALKING = 600.0
        /** How long the microphone stays down after the other side's last loud moment. */
        private const val FAR_END_HOLD_MS = 300L
        /** Microphone level while the other side talks (about -20 dB). */
        private const val SUPPRESSED_GAIN = 0.1f
        private const val ROLE_INPUT = 1
        private const val ROLE_OUTPUT = 2
        private const val DEVICE_OUT_BLUETOOTH_SCO = 0x10
        private const val DEVICE_IN_BLUETOOTH_SCO_HEADSET = 0x80000008.toInt()

        private const val PREFS = "settings"
        private const val KEY_ENABLED = "audio_bridge"

        fun isEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

        fun setEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
        }
    }
}
