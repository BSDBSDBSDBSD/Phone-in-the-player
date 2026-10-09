package com.offline.phonelink.bt

import android.Manifest
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import java.util.concurrent.TimeUnit

/** Reads how the player is set up, and fixes it with root where it can. */
object SystemCheck {

    val PROFILE_PROPS = listOf(
        "bluetooth.profile.hfp.hf.enabled",
        "bluetooth.profile.pbap.client.enabled",
        "bluetooth.profile.map.client.enabled",
    )

    fun prop(name: String): String = runCatching {
        Class.forName("android.os.SystemProperties")
            .getMethod("get", String::class.java)
            .invoke(null, name) as String
    }.getOrDefault("")

    fun isSystemApp(context: Context): Boolean =
        context.applicationInfo.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0

    fun hasPrivilegedBluetooth(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_PRIVILEGED) == PackageManager.PERMISSION_GRANTED

    /** [code] is the exit code (-1 when su could not run or timed out). */
    class ShellResult(val code: Int, val output: String) {
        val ok get() = code == 0
    }

    /** Runs [command] as root (via su). Blocks: call it off the main thread. */
    fun root(command: String, timeoutSec: Long = 20): ShellResult = try {
        val p = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        val finished = p.waitFor(timeoutSec, TimeUnit.SECONDS)
        if (!finished) p.destroyForcibly()
        val out = p.inputStream.bufferedReader().readText().trim()
        ShellResult(if (finished) p.exitValue() else -1, out)
    } catch (t: Throwable) {
        ShellResult(-1, t.message ?: t.javaClass.simpleName)
    }

    fun hasRoot(): Boolean = root("id", 10).let { it.ok && it.output.contains("uid=0") }

    /**
     * Turns the hands-free and phonebook profiles on until the next restart, then restarts Bluetooth
     * so they take effect. (The Magisk module makes this permanent.)
     */
    fun enableProfilesNow(): ShellResult {
        val cmd = PROFILE_PROPS.joinToString("; ") { "setprop $it true" } +
            "; cmd bluetooth_manager disable; sleep 3; cmd bluetooth_manager enable"
        return root(cmd, 30)
    }

    /** Android's switch for using the basic SCO link instead of enhanced eSCO for call audio. */
    private const val BASIC_SCO_PROP = "bluetooth.sco.disable_esco"

    fun basicScoEnabled(): Boolean = prop(BASIC_SCO_PROP) == "true"

    /**
     * Experiment: with the basic SCO link the Bluetooth chip may route call audio differently. Lasts until
     * the next restart (setprop), and restarts Bluetooth so it takes effect.
     */
    fun setBasicSco(on: Boolean): ShellResult =
        root("setprop $BASIC_SCO_PROP ${if (on) "true" else "false"}; cmd bluetooth_manager disable; sleep 3; cmd bluetooth_manager enable", 30)
}
