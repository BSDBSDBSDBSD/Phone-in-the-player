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
    private var nativeMode = true

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
            if (!running || nativeMode) return@execute
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
        nativeMode = isNative(context)
        previousMode = audio.mode
        log("start for $phoneAddress (mode was $previousMode)")
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        audio.isMicrophoneMute = false
        // Canonical routing so Android carries the call over the Bluetooth SCO link in both directions:
        // the player's mic becomes the SCO uplink to the phone, and the phone's voice comes in over SCO.
        @Suppress("DEPRECATION")
        runCatching { audio.startBluetoothSco() }
        @Suppress("DEPRECATION")
        runCatching { audio.isBluetoothScoOn = true }
        // Bridge mode forces the route at the policy level (via root); native mode uses only the
        // standard framework APIs so the HAL does its normal car-kit routing without a fight.
        if (!nativeMode) setScoDevices(phoneAddress, available = true)
        runCatching {
            val scoDev = audio.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            if (scoDev != null) log("setCommunicationDevice(sco) -> ${audio.setCommunicationDevice(scoDev)}")
            else log("setCommunicationDevice: no SCO device")
        }

        if (nativeMode) {
            // Native mode: the phone + audio HAL carry the whole call over SCO, like a real car kit
            // (including the chip's own hardware echo cancellation). No software pumps.
            log("native call-audio mode: no software pumps")
            return
        }

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
        // Only bridge mode told the policy a SCO headset was connected (via root); undo it only then.
        if (!nativeMode) address?.let { setScoDevices(it, available = false) }
        runCatching { audio.clearCommunicationDevice() }
        @Suppress("DEPRECATION")
        runCatching { audio.isBluetoothScoOn = false }
        @Suppress("DEPRECATION")
        runCatching { audio.stopBluetoothSco() }
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
            // The microphone direction reads small blocks (20 ms) so echo suppression reacts quickly; the
            // Bluetooth direction keeps the larger blocks it worked with.
            val buffer = ShortArray(if (farEnd) inSize / 2 else rate / 50)
            var levelSum = 0.0
            var levelBlocks = 0
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
                levelSum += level
                levelBlocks++
                if (farEnd) {
                    // Remember how loud what we just played to the speaker is: that is the echo the
                    // microphone will hear a moment later. Kept for ~250 ms to cover the acoustic delay.
                    farLevel = level
                    farLevelUntil = System.currentTimeMillis() + FAR_ECHO_HOLD_MS
                } else {
                    // Echo suppression by comparison, not by timing: the expected echo in the mic is
                    // about COUPLING times the far level. If the mic is only as loud as that, it is
                    // almost all echo, so duck it hard. If the mic is clearly louder, that extra is the
                    // user talking, so let it through. This never cuts the user off when they are louder
                    // than the echo, and stays fully open whenever the far side is silent.
                    val far = if (System.currentTimeMillis() < farLevelUntil) farLevel else 0.0
                    val expectedEcho = ECHO_COUPLING * far
                    val target = when {
                        level <= expectedEcho + 1 -> ECHO_FLOOR_GAIN
                        else -> ((level - expectedEcho) / level).toFloat().coerceIn(ECHO_FLOOR_GAIN, 1f)
                    }
                    gain = applyGain(buffer, n, gain, target)
                    if (target < 0.95f) suppressed += n
                }
                track.write(buffer, 0, n)
                frames += n
                if (buffer.take(n).any { it > 500 || it < -500 }) loud += n
                if (frames >= rate * 5L) {
                    val extra = if (farEnd) "" else ", mic turned down ${suppressed * 100 / frames}%"
                    val avg = if (levelBlocks == 0) 0 else (levelSum / levelBlocks).toInt()
                    log("$name: 5s copied, ${loud * 100 / frames}% with sound, average level $avg$extra")
                    levelSum = 0.0
                    levelBlocks = 0
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

    /** Recent level of what was last played to the speaker (the echo reference) and until when it holds. */
    @Volatile
    private var farLevel = 0.0
    @Volatile
    private var farLevelUntil = 0L

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

        /** The echo in the mic is about this fraction of the level played to the speaker. */
        private const val ECHO_COUPLING = 1.0
        /** Lowest gain for the mic when it carries almost only echo (about -22 dB). */
        private const val ECHO_FLOOR_GAIN = 0.08f
        /** How long the far-end echo reference stays valid, covering the acoustic round trip. */
        private const val FAR_ECHO_HOLD_MS = 250L
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

        private const val KEY_NATIVE = "call_audio_native"

        /**
         * true  = native mode: let the phone + audio HAL carry the whole call over SCO, like a real car
         *         kit (includes the chip's own echo cancellation). No software pumps.
         * false = bridge mode: PhoneLink moves the audio itself (two pumps + software echo suppression).
         * Native is the default: it is the standard, least fragile path and gives hardware echo control.
         */
        fun isNative(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_NATIVE, true)

        fun setNative(context: Context, native: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_NATIVE, native).apply()
        }
    }
}
