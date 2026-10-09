package com.offline.phonelink.bt

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder

/**
 * Checks whether the player's Bluetooth chip passes call audio at all, in the opposite (ordinary) role:
 * a Bluetooth headset connected to the player, its microphone recorded the normal Android way.
 * If this works, the chip can deliver Bluetooth call audio and only the hands-free link set-up differs.
 */
object HeadsetMicTest {

    class Result(val found: Boolean, val routed: Boolean, val soundPercent: Int, val peak: Int, val chipFlags: String)

    @SuppressLint("MissingPermission")
    fun run(context: Context): Result {
        val am = context.getSystemService(AudioManager::class.java)
        val headset = am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            ?: return Result(false, false, 0, 0, "")
        val previousMode = am.mode
        var record: AudioRecord? = null
        try {
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            am.setCommunicationDevice(headset)
            var routed = false
            repeat(40) {
                if (am.communicationDevice?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) routed = true
                if (routed) return@repeat
                Thread.sleep(100)
            }
            Thread.sleep(1_000) // let the Bluetooth call audio link open
            val rate = 8_000
            val size = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(rate)
            record = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size)
            record.startRecording()
            val block = ShortArray(rate / 50)
            var blocks = 0
            var loud = 0
            var peak = 0
            val end = System.currentTimeMillis() + 4_000
            while (System.currentTimeMillis() < end) {
                val n = record.read(block, 0, block.size)
                if (n <= 0) continue
                var max = 0
                for (i in 0 until n) max = maxOf(max, kotlin.math.abs(block[i].toInt()))
                if (max > 500) loud++
                peak = maxOf(peak, max)
                blocks++
            }
            val tools = ChannelProbe.installTools(context)
            val flags = SystemCheck.root(
                "$tools/tinymix -D 0 contents 2>&1 | grep -i 'BTCVSD'",
                15,
            ).output
            return Result(true, routed, if (blocks == 0) 0 else loud * 100 / blocks, peak, flags)
        } finally {
            runCatching { record?.stop() }
            runCatching { record?.release() }
            runCatching { am.clearCommunicationDevice() }
            am.mode = previousMode
        }
    }
}
