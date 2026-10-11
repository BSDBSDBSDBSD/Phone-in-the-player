package com.offline.phonelink.bt

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import androidx.core.app.NotificationCompat
import com.offline.phonelink.R
import com.offline.phonelink.data.HistoryEntry
import com.offline.phonelink.data.PhoneBook
import com.offline.phonelink.data.PhoneNumbers
import com.offline.phonelink.ui.MainActivity
import com.offline.phonelink.ui.PhoneStatusActivity

/**
 * Notifications about the phone: its battery running low, and calls it missed. Missed calls come both
 * from calls the player saw ringing and from the call history copied from the phone (so calls missed while
 * the player was away show up too, the next time the history syncs).
 */
object PhoneAlerts {

    private const val CHANNEL = "phone_alerts"
    private const val NOTIF_BATTERY = 10
    private const val NOTIF_MISSED = 11
    private const val PREFS = "alerts"
    private const val KEY_LAST_MISSED = "last_missed"

    /** Battery level (0-5, as phones report it over Bluetooth) at or below which we warn. */
    private const val LOW_BATTERY = 1
    private const val BATTERY_RECOVERED = 3

    private var batteryWarned = false
    private var observer: ContentObserver? = null
    private val handler = Handler(Looper.getMainLooper())

    fun start(context: Context) {
        val app = context.applicationContext
        createChannel(app)
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // First run: only calls missed from now on count, not the whole old history.
        if (!prefs.contains(KEY_LAST_MISSED)) prefs.edit().putLong(KEY_LAST_MISSED, System.currentTimeMillis()).apply()
        if (observer == null && granted(app)) {
            val check = Runnable { checkMissed(app) }
            observer = object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean) {
                    // The history arrives in a burst of changes: look once it settles.
                    handler.removeCallbacks(check)
                    handler.postDelayed(check, 3_000)
                }
            }
            runCatching { app.contentResolver.registerContentObserver(CallLog.Calls.CONTENT_URI, true, observer!!) }
            handler.postDelayed(check, 5_000)
        }
    }

    fun stop(context: Context) {
        observer?.let { runCatching { context.applicationContext.contentResolver.unregisterContentObserver(it) } }
        observer = null
    }

    fun onStatus(context: Context, s: LinkSnapshot) {
        val battery = s.battery ?: return
        if (!s.connected) return
        if (battery <= LOW_BATTERY && !batteryWarned) {
            batteryWarned = true
            notify(
                context, NOTIF_BATTERY,
                context.getString(R.string.alert_battery_title, s.phoneName ?: ""),
                context.getString(R.string.alert_battery_text),
                Intent(context, PhoneStatusActivity::class.java),
            )
        } else if (battery >= BATTERY_RECOVERED && batteryWarned) {
            batteryWarned = false
            context.getSystemService(NotificationManager::class.java).cancel(NOTIF_BATTERY)
        }
    }

    /** A call that rang on the player and was not answered. */
    fun missedNow(context: Context, entry: HistoryEntry) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putLong(KEY_LAST_MISSED, maxOf(prefs.getLong(KEY_LAST_MISSED, 0), System.currentTimeMillis())).apply()
        showMissed(context, listOf(entry.name ?: PhoneNumbers.pretty(entry.number)))
    }

    /** New missed calls in the history copied from the phone. */
    private fun checkMissed(context: Context) {
        if (!granted(context)) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val since = prefs.getLong(KEY_LAST_MISSED, System.currentTimeMillis())
        val who = mutableListOf<String>()
        var newest = since
        runCatching {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.DATE),
                CallLog.Calls.TYPE + "=? AND " + CallLog.Calls.DATE + ">?",
                arrayOf(CallLog.Calls.MISSED_TYPE.toString(), since.toString()),
                CallLog.Calls.DATE + " DESC",
            )?.use { c ->
                while (c.moveToNext()) {
                    val number = c.getString(0) ?: ""
                    who += c.getString(1)?.takeIf { it.isNotBlank() }
                        ?: PhoneBook.nameFor(context, number)
                        ?: PhoneNumbers.pretty(number).ifEmpty { context.getString(R.string.unknown_number) }
                    newest = maxOf(newest, c.getLong(2))
                }
            }
        }
        if (who.isEmpty()) return
        prefs.edit().putLong(KEY_LAST_MISSED, newest).apply()
        showMissed(context, who)
    }

    private fun showMissed(context: Context, who: List<String>) {
        val title = if (who.size == 1) context.getString(R.string.alert_missed_one)
        else context.getString(R.string.alert_missed_many, who.size)
        notify(
            context, NOTIF_MISSED, title, who.distinct().joinToString(", "),
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_RECENTS),
        )
    }

    private fun notify(context: Context, id: Int, title: String, text: String, open: Intent) {
        createChannel(context)
        val pending = PendingIntent.getActivity(
            context, id, open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_call)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(id, n) }
    }

    private fun createChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.channel_alerts), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    private fun granted(context: Context) =
        context.checkSelfPermission(Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED
}
