package com.offline.phonelink.bt

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * Android's link between the Bluetooth hands-free and the regular phone app (the Bluetooth app's
 * HfpClientConnectionService). With it on, every call is handed to the phone app, which puts the
 * player into "cellular call" audio mode; on this MediaTek player that crashes the audio service
 * (no sound), and the service itself sometimes crashes the Bluetooth app. Turning it off leaves the
 * calls to PhoneLink alone. Reversible at any time.
 */
object PhoneAppLink {

    private const val SERVICE = "com.android.bluetooth.hfpclient.HfpClientConnectionService"
    private val BLUETOOTH_PACKAGES = listOf("com.android.bluetooth", "com.google.android.bluetooth")

    fun component(context: Context): ComponentName? = BLUETOOTH_PACKAGES
        .map { ComponentName(it, SERVICE) }
        .firstOrNull { c ->
            runCatching { context.packageManager.getServiceInfo(c, PackageManager.MATCH_DISABLED_COMPONENTS) }.isSuccess
        }

    /** null when the player has no such link at all. */
    fun isEnabled(context: Context): Boolean? {
        val c = component(context) ?: return null
        return when (context.packageManager.getComponentEnabledSetting(c)) {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER -> false
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            else -> runCatching { context.packageManager.getServiceInfo(c, PackageManager.MATCH_DISABLED_COMPONENTS).enabled }
                .getOrDefault(true)
        }
    }

    /** Switches the link and restarts Bluetooth so it takes effect. Blocks: call off the main thread. */
    fun setEnabled(context: Context, enabled: Boolean): SystemCheck.ShellResult {
        val c = component(context) ?: return SystemCheck.ShellResult(-1, "HfpClientConnectionService not found")
        val verb = if (enabled) "enable" else "disable"
        return SystemCheck.root(
            "pm $verb ${c.flattenToShortString()} && { cmd bluetooth_manager disable; sleep 3; cmd bluetooth_manager enable; }",
            40,
        )
    }
}
