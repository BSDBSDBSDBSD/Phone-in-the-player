package com.offline.phonelink.bt

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts listening for the phone when the player turns on (and after an update). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            LinkService.start(context)
        }
    }
}
