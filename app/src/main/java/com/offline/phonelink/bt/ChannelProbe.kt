package com.offline.phonelink.bt

import android.content.Context
import java.io.File
import java.io.RandomAccessFile

/**
 * During a call, listens directly (with root, through tinyalsa) to the chip's Bluetooth-related capture
 * channels for a few seconds each and reports which ones carry sound. It also records the chip's mixer
 * settings at that moment. Read-only: no setting is changed.
 *
 * This answers where the phone's voice actually arrives: the software Bluetooth channel (BTCVSD) that the
 * audio system reads, or the hardware ones (VOIP_Call_BT / MRGRX) that it does not use for this call.
 */
object ChannelProbe {

    const val OUTPUT = "/sdcard/Download/PhoneLink-channels.txt"
    private const val TOOLS_DIR = "/data/local/tmp/phonelink"
    private val TOOLS = listOf("tinycap", "tinymix", "tinypcminfo")

    /** ALSA capture devices on card 0 to listen to, with what they are on this MediaTek chip. */
    val CHANNELS = listOf(
        28 to "BTCVSD (software Bluetooth channel)",
        12 to "VOIP_Call_BT_Capture (hardware Bluetooth channel)",
        6 to "MRGRX_CAPTURE (merge interface)",
        5 to "MRGRX_PLayback (merge interface)",
    )

    /** Formats tried in order until the channel accepts one: channels to rate. */
    private val FORMATS = listOf(1 to 8000, 1 to 16000, 2 to 16000, 2 to 32000, 2 to 48000)

    class ChannelResult(val device: Int, val name: String, val format: String?, val error: String?, val soundPercent: Int?, val peak: Int?)
    class Result(val ok: Boolean, val file: File, val channels: List<ChannelResult>, val error: String)

    /** Copies the tinyalsa tools to [TOOLS_DIR] (as root) and returns that folder. */
    fun installTools(context: Context): String {
        val cache = context.cacheDir
        for (tool in TOOLS) {
            val f = File(cache, tool)
            context.assets.open("tools/$tool").use { input -> f.outputStream().use { input.copyTo(it) } }
            f.setReadable(true, false)
        }
        SystemCheck.root(
            "mkdir -p $TOOLS_DIR; for t in ${TOOLS.joinToString(" ")}; do cp '${cache.absolutePath}'/${'$'}t $TOOLS_DIR/${'$'}t; chmod 755 $TOOLS_DIR/${'$'}t; done",
            20,
        )
        return TOOLS_DIR
    }

    fun run(context: Context): Result {
        val cache = context.cacheDir
        installTools(context)
        val report = File(cache, "channels.txt").apply { writeText(""); setWritable(true, false) }
        // Root writes each capture into a file this app created, so the app can read it back.
        val captures = CHANNELS.associate { (dev, _) ->
            dev to File(cache, "cap_$dev.wav").apply { writeText(""); setWritable(true, false) }
        }
        val tryFormats = FORMATS.joinToString(" ") { (c, r) -> "$c:$r" }
        val captureBlocks = CHANNELS.joinToString("\n") { (dev, name) ->
            """
            echo; echo "===== capture device $dev ($name) ====="
            ok=""
            for fmt in $tryFormats; do
              c=${'$'}{fmt%%:*}; r=${'$'}{fmt##*:}
              rm -f $TOOLS_DIR/cap.wav
              if ${'$'}T/tinycap $TOOLS_DIR/cap.wav -D 0 -d $dev -c ${'$'}c -r ${'$'}r -b 16 -t 3 > $TOOLS_DIR/cap.log 2>&1 && [ -s $TOOLS_DIR/cap.wav ]; then
                echo "format ${'$'}c ch ${'$'}r Hz: ok, ${'$'}(wc -c < $TOOLS_DIR/cap.wav) bytes"
                cat $TOOLS_DIR/cap.wav > '${captures.getValue(dev).absolutePath}'
                ok=1; break
              else
                echo "format ${'$'}c ch ${'$'}r Hz: failed: ${'$'}(tail -n 2 $TOOLS_DIR/cap.log | tr '\n' ' ')"
              fi
            done
            [ -n "${'$'}ok" ] || echo "no format worked"
            """.trimIndent()
        }
        val script = File(cache, "channels.sh")
        script.writeText(
            """
            T=$TOOLS_DIR
            mkdir -p ${'$'}T
            for t in ${TOOLS.joinToString(" ")}; do cp '${cache.absolutePath}'/${'$'}t ${'$'}T/${'$'}t; chmod 755 ${'$'}T/${'$'}t; done
            {
            echo "PhoneLink Bluetooth channel test (read-only), ${'$'}(date)"
            echo; echo "===== call state ====="
            dumpsys bluetooth_manager 2>/dev/null | grep -iE 'HeadsetClient|audio state|mAudioState|sco' | head -n 20
            echo; echo "===== pcm info ====="
            for d in ${CHANNELS.joinToString(" ") { it.first.toString() }} 11; do ${'$'}T/tinypcminfo -D 0 -d ${'$'}d 2>&1 | head -n 40; done
            $captureBlocks
            echo; echo "===== mixer (Bluetooth / merge / voice) ====="
            ${'$'}T/tinymix -D 0 contents 2>&1 | grep -iE 'bt|cvsd|mrg|merge|voip|voice|pcm|dai' | head -n 300
            echo; echo "===== mixer (all) ====="
            ${'$'}T/tinymix -D 0 contents 2>&1 | head -n 1500
            } > $OUTPUT 2>&1
            cat $OUTPUT > '${report.absolutePath}'
            """.trimIndent() + "\n",
        )
        script.setReadable(true, false)
        val shell = SystemCheck.root("sh '${script.absolutePath}'", 150)

        val results = CHANNELS.map { (dev, name) ->
            val wav = captures.getValue(dev)
            if (wav.length() <= 44) {
                ChannelResult(dev, name, null, "no capture", null, null)
            } else {
                val (percent, peak, format) = analyze(wav)
                ChannelResult(dev, name, format, null, percent, peak)
            }
        }
        val summary = buildString {
            appendLine()
            appendLine("===== summary (computed by the app) =====")
            for (r in results) {
                appendLine(
                    if (r.soundPercent == null) "device ${r.device} ${r.name}: ${r.error}"
                    else "device ${r.device} ${r.name}: ${r.format}, ${r.soundPercent}% with sound, peak ${r.peak}",
                )
            }
        }
        report.appendText(summary)
        SystemCheck.root("cat '${report.absolutePath}' > $OUTPUT", 10)
        return Result(shell.ok && report.length() > 0, report, results, shell.output)
    }

    /** Share of 20 ms blocks with sound, the peak sample, and the format, from a 16-bit WAV file. */
    private fun analyze(wav: File): Triple<Int, Int, String> {
        RandomAccessFile(wav, "r").use { f ->
            val header = ByteArray(44)
            f.readFully(header)
            val channels = (header[22].toInt() and 0xff) or ((header[23].toInt() and 0xff) shl 8)
            val rate = (header[24].toInt() and 0xff) or ((header[25].toInt() and 0xff) shl 8) or
                ((header[26].toInt() and 0xff) shl 16) or ((header[27].toInt() and 0xff) shl 24)
            val data = ByteArray((f.length() - 44).toInt())
            f.readFully(data)
            val samples = data.size / 2
            val block = (rate * channels / 50).coerceAtLeast(1)
            var blocks = 0
            var loud = 0
            var peak = 0
            var i = 0
            while (i < samples) {
                var max = 0
                val end = minOf(i + block, samples)
                for (s in i until end) {
                    val v = ((data[2 * s].toInt() and 0xff) or (data[2 * s + 1].toInt() shl 8)).toShort().toInt()
                    val a = kotlin.math.abs(v)
                    if (a > max) max = a
                }
                if (max > 500) loud++
                if (max > peak) peak = max
                blocks++
                i = end
            }
            return Triple(if (blocks == 0) 0 else loud * 100 / blocks, peak, "$channels ch $rate Hz")
        }
    }
}
