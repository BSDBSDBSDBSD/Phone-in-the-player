package com.offline.phonelink.bt

import android.content.Context
import java.io.File

/**
 * Gathers what is needed to understand a problem on the player (crashes, Bluetooth, call audio,
 * chip type) into Download/PhoneLink-log.txt, using root because other apps' logs are not visible otherwise.
 */
object LogCollector {

    const val OUTPUT = "/sdcard/Download/PhoneLink-log.txt"

    /** [file] is a copy in the app's cache, for sharing. */
    class Result(val ok: Boolean, val file: File, val error: String)

    fun collect(context: Context): Result {
        // Root writes into a file this app created, so the app can read it back.
        val copy = File(context.cacheDir, "log.txt").apply { writeText(""); setWritable(true, false) }
        val script = File(context.cacheDir, "log.sh")
        script.writeText(
            """
            {
            echo "== device"
            getprop | grep -E 'ro\.product\.(model|manufacturer|board|device)|ro\.board\.platform|ro\.hardware|ro\.soc|ro\.build\.version\.(release|sdk|incremental)|ro\.build\.fingerprint|bluetooth|\.bt\.|\.bt_|audio\.|hfp|sco' | head -n 300
            echo
            echo "== crashes"
            logcat -d -b crash -v time | tail -n 600
            echo
            echo "== log (bluetooth, calls, audio)"
            logcat -d -b main,system -v time | grep -iE 'bluetooth|bt_|bta_|hfp|headset|hf_client|sco|cvsd|AudioALSA|APM_|audio_hw|audiohal|AudioFlinger|AudioPolicy|primary|telecom|incall|dialer|phonelink|AndroidRuntime|FATAL|DEBUG' | tail -n 3000
            echo
            echo "== bluetooth state"
            dumpsys bluetooth_manager | grep -iE -A30 'HeadsetClient|HfpClient' | head -n 200
            echo
            echo "== audio state"
            dumpsys audio | grep -iE 'sco|bluetooth|mode|call|routing' | head -n 120
            echo
            echo "== telecom"
            dumpsys telecom | grep -iE 'PhoneAccount|hfp|bluetooth|Call ' | head -n 60
            } > $OUTPUT 2>&1
            cat $OUTPUT > '${copy.absolutePath}'
            """.trimIndent() + "\n",
        )
        script.setReadable(true, false)
        val result = SystemCheck.root("sh '${script.absolutePath}'", 90)
        return Result(result.ok && copy.length() > 0, copy, result.output)
    }
}
