package com.offline.phonelink.bt

import android.content.Context
import java.io.File

/**
 * Gathers, read-only and with root, the player's audio and Bluetooth configuration into one text file
 * (Download/PhoneLink-system.txt), to find out why Bluetooth call audio does not reach the processor.
 * Nothing on the player is changed.
 */
object SystemFilesCollector {

    const val OUTPUT = "/sdcard/Download/PhoneLink-system.txt"

    /** Text files copied whole (missing ones are noted and skipped). */
    private val FILES = listOf(
        "/vendor/etc/audio_device.xml",
        "/vendor/etc/audio_policy_configuration.xml",
        "/vendor/etc/audio_param/AudioParamOptions.xml",
        "/vendor/firmware/WMT_SOC.cfg",
        "/vendor/build.prop",
        "/proc/asound/cards",
        "/proc/asound/pcm",
        "/proc/asound/devices",
    )

    /** Folders whose text files are all copied (only files whose names match the pattern). */
    private val FOLDERS = listOf(
        "/vendor/etc" to "audio_policy*.xml bluetooth_audio_policy*.xml *audio*effects*.xml audio_*.xml",
        "/vendor/etc/bluetooth" to "*",
        "/vendor/firmware" to "*bt* *BT* *wmt* *WMT*",
        "/vendor/etc/audio_param" to "*BT* *Bt* *bt* *Speech*Device* *Hfp* *HFP* *Sco* *SCO*",
    )

    class Result(val ok: Boolean, val file: File, val error: String)

    fun collect(context: Context): Result {
        val copy = File(context.cacheDir, "system.txt").apply { writeText(""); setWritable(true, false) }
        val script = File(context.cacheDir, "system.sh")
        val files = FILES.joinToString(" ")
        val folders = FOLDERS.joinToString("\n") { (dir, patterns) ->
            "for p in $patterns; do for f in $dir/${'$'}p; do dump \"${'$'}f\"; done; done"
        }
        script.writeText(
            """
            dump() {
              f="${'$'}1"
              [ -f "${'$'}f" ] || return 0
              case " ${'$'}DONE " in *" ${'$'}f "*) return 0;; esac
              DONE="${'$'}DONE ${'$'}f"
              echo
              echo "===== FILE ${'$'}f ====="
              if [ -s "${'$'}f" ] && ! grep -qI . "${'$'}f" 2>/dev/null; then
                echo "(binary, ${'$'}(wc -c < "${'$'}f") bytes, not copied)"
              else
                head -c 400000 "${'$'}f"
              fi
            }
            section() { echo; echo "===== ${'$'}1 ====="; }
            DONE=""
            {
            echo "PhoneLink system files - read-only snapshot, ${'$'}(date)"
            for f in $files; do
              if [ -e "${'$'}f" ]; then dump "${'$'}f"; else echo; echo "===== MISSING ${'$'}f ====="; fi
            done
            $folders
            section "ls /vendor/etc"; ls -la /vendor/etc
            section "ls /vendor/etc/audio_param"; ls -la /vendor/etc/audio_param 2>&1
            section "ls /vendor/firmware"; ls -la /vendor/firmware 2>&1
            section "ls /vendor/lib/hw /vendor/lib64/hw"; ls -la /vendor/lib/hw /vendor/lib64/hw 2>&1 | grep -iE 'audio|bluetooth|bt'
            section "devices (bt / cvsd / audio)"; ls -la /dev 2>&1 | grep -iE 'cvsd|ebc|bt|stp|wmt|snd'; ls -la /dev/snd 2>&1
            section "kernel modules"; grep -iE 'bt|cvsd|wmt|conn|snd|audio' /proc/modules 2>&1
            section "kernel log (bt / cvsd / sco)"; dmesg 2>&1 | grep -iE 'cvsd|btcvsd|sco|bt_|bluetooth|wmt' | tail -n 400
            section "getprop"; getprop
            section "dumpsys media.audio_policy"; dumpsys media.audio_policy 2>&1 | head -n 1500
            section "dumpsys media.audio_flinger"; dumpsys media.audio_flinger 2>&1 | head -n 800
            section "ALSA mixer controls"; for c in /proc/asound/card*; do echo "${'$'}c"; cat "${'$'}c/id" 2>/dev/null; done; tinymix 2>&1 | head -n 600
            } > $OUTPUT 2>&1
            cat $OUTPUT > '${copy.absolutePath}'
            """.trimIndent() + "\n",
        )
        script.setReadable(true, false)
        val result = SystemCheck.root("sh '${script.absolutePath}'", 120)
        return Result(result.ok && copy.length() > 0, copy, result.output)
    }
}
