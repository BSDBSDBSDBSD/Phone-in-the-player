package com.offline.phonelink.bt

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.offline.phonelink.R
import com.offline.phonelink.data.CallDirection
import com.offline.phonelink.data.HistoryEntry
import com.offline.phonelink.data.OwnHistory
import com.offline.phonelink.data.PhoneBook
import com.offline.phonelink.data.PhoneNumbers
import com.offline.phonelink.ui.InCallActivity
import com.offline.phonelink.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps the link to the phone alive in the background: follows calls, rings on an incoming call,
 * opens the call screen and records the calls in the app's own history.
 */
@SuppressLint("MissingPermission")
class LinkService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var client: HfpClient
    private lateinit var bridge: AudioBridge
    private var bridgeOffSince: Long? = null
    private var lastAudioOnPlayer = false
    private var ringtone: Ringtone? = null
    private var shownIncomingId: Int? = null

    /** Calls seen so far (by id), to know how each one ended. */
    private val tracked = HashMap<Int, Tracked>()

    private class Tracked(val number: String, val outgoing: Boolean, val since: Long) {
        var answeredAt: Long? = null
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == HfpClient.ACTION_AG_EVENT) {
                agExtras = agExtras.copy(
                    signal = intent.intExtraOrNull(HfpClient.EXTRA_SIGNAL) ?: agExtras.signal,
                    battery = intent.intExtraOrNull(HfpClient.EXTRA_BATTERY) ?: agExtras.battery,
                    operator = intent.getStringExtra(HfpClient.EXTRA_OPERATOR) ?: agExtras.operator,
                )
            }
            if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED &&
                intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) == BluetoothAdapter.STATE_ON
            ) client.open()
            refresh()
        }
    }

    private data class AgExtras(val signal: Int? = null, val battery: Int? = null, val operator: String? = null)
    private var agExtras = AgExtras()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        current = this
        createChannels()
        try {
            ServiceCompat.startForeground(
                this, NOTIF_LINK, linkNotification(LinkState.state.value),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "foreground start refused", t)
        }
        bridge = AudioBridge(this)
        client = HfpClient(this) { refresh() }
        client.open()

        val filter = IntentFilter().apply {
            addAction(HfpClient.ACTION_CONNECTION_STATE_CHANGED)
            addAction(HfpClient.ACTION_AUDIO_STATE_CHANGED)
            addAction(HfpClient.ACTION_AG_EVENT)
            addAction(HfpClient.ACTION_CALL_CHANGED)
            addAction(HfpClient.ACTION_PBAP_CONNECTION_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_EXPORTED)

        // Broadcasts may not reach us on every build: also look at the phone regularly.
        scope.launch {
            while (isActive) {
                refresh()
                delay(if (LinkState.state.value.hasCall) 1_000 else 4_000)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ANSWER -> answer()
            ACTION_REJECT -> reject()
            ACTION_HANG_UP -> hangUp()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopRinging()
        bridge.release()
        runCatching { unregisterReceiver(receiver) }
        client.close()
        scope.cancel()
        if (current === this) current = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ actions for the screens

    val hfp: HfpClient get() = client

    fun phone(): BluetoothDevice? = client.connectedPhone()

    fun dial(number: String): Boolean {
        val device = phone() ?: return false
        return client.dial(device, PhoneNumbers.clean(number)).also { refresh() }
    }

    fun answer() {
        val device = phone() ?: return
        val holdOthers = LinkState.state.value.calls.any { it.isActive }
        client.accept(device, holdOthers)
        stopRinging()
        refresh()
    }

    fun reject() {
        val device = phone() ?: return
        client.reject(device)
        stopRinging()
        refresh()
    }

    fun hangUp() {
        val device = phone() ?: return
        val call = LinkState.state.value.mainCall
        if (call != null && call.isRinging) client.reject(device) else client.hangUp(device, call)
        refresh()
    }

    fun sendDigit(c: Char) {
        phone()?.let { client.sendDtmf(it, c) }
    }

    fun toggleAudio() {
        val device = phone() ?: return
        if (client.audioOnPlayer(device)) client.moveAudioToPhone(device) else client.moveAudioToPlayer(device)
        scope.launch { delay(600); refresh() }
    }

    fun connectTo(device: BluetoothDevice): Boolean = client.connect(device).also { refresh() }

    fun resyncPhonebook() {
        phone()?.let { client.resyncPhonebook(it) }
    }

    // ------------------------------------------------------------------ state

    fun refresh() {
        val adapter = client.adapter
        val btOn = adapter?.isEnabled == true
        val device = if (btOn) client.connectedPhone() else null
        val connecting = btOn && device == null && client.pairedDevices().any {
            client.connectionState(it) == BluetoothProfile.STATE_CONNECTING
        }
        val status = device?.let { client.phoneStatus(it) }
        val calls = device?.let { client.currentCalls(it) }.orEmpty().filterNot { it.isOver }
        val snapshot = LinkSnapshot(
            bluetoothOn = btOn,
            profileReady = client.hfp != null,
            phoneName = device?.let { runCatching { it.alias ?: it.name }.getOrNull() },
            phoneAddress = device?.address,
            connected = device != null,
            connecting = connecting,
            calls = calls,
            audioOnPlayer = device?.let { client.audioOnPlayer(it) } ?: false,
            signal = status?.intOrNull(HfpClient.EXTRA_SIGNAL) ?: agExtras.signal,
            battery = status?.intOrNull(HfpClient.EXTRA_BATTERY) ?: agExtras.battery,
            operator = status?.getString(HfpClient.EXTRA_OPERATOR) ?: agExtras.operator,
        )
        if (device == null) agExtras = AgExtras()
        val before = LinkState.state.value
        LinkState.set(snapshot)
        followCalls(snapshot)
        if (before.connected != snapshot.connected || before.phoneName != snapshot.phoneName ||
            before.hasCall != snapshot.hasCall
        ) notifyLink(snapshot)
    }

    private fun followCalls(s: LinkSnapshot) {
        val now = System.currentTimeMillis()
        for (call in s.calls) {
            val t = tracked.getOrPut(call.id) { Tracked(call.number, call.outgoing, now) }
            if (call.isActive && t.answeredAt == null) t.answeredAt = now
        }
        // Calls that disappeared have ended: write them to the history.
        val ended = tracked.keys - s.calls.map { it.id }.toSet()
        for (id in ended) {
            val t = tracked.remove(id) ?: continue
            val direction = when {
                t.outgoing -> CallDirection.OUTGOING
                t.answeredAt != null -> CallDirection.INCOMING
                else -> CallDirection.MISSED
            }
            val duration = t.answeredAt?.let { (now - it) / 1000 } ?: 0
            OwnHistory.add(
                this,
                HistoryEntry(t.number, PhoneBook.nameFor(this, t.number), direction, t.since, duration),
            )
        }

        // Carry the call's sound by software when the player cannot (see AudioBridge). It stays on for
        // the whole call: restarting it when the sound moves between phone and player left it silent.
        val talking = s.calls.any { it.isActive || it.isDialing }
        val bridgeWanted = talking && s.phoneAddress != null &&
            AudioBridge.isEnabled(this) && PhoneAppLink.isEnabled(this) == false &&
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (bridgeWanted) {
            bridgeOffSince = null
            bridge.start(s.phoneAddress!!)
            if (s.audioOnPlayer && !lastAudioOnPlayer) bridge.audioReturned()
        } else {
            // Call states flicker for a moment between calls (hold, waiting): stop only after a pause.
            val since = bridgeOffSince ?: now.also { bridgeOffSince = it }
            if (now - since > 3_000) bridge.stop()
        }
        lastAudioOnPlayer = s.audioOnPlayer

        val ringing = s.ringing
        if (ringing != null && !s.audioOnPlayer && ringing.state == PhoneCall.STATE_INCOMING) startRinging() else stopRinging()

        if (ringing != null && shownIncomingId != ringing.id) {
            shownIncomingId = ringing.id
            showIncoming(ringing)
        }
        if (ringing == null && shownIncomingId != null) {
            shownIncomingId = null
            getSystemService(NotificationManager::class.java).cancel(NOTIF_INCOMING)
        }
    }

    // ------------------------------------------------------------------ ringing & notifications

    private fun startRinging() {
        if (ringtone?.isPlaying == true) return
        val uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_RINGTONE)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        ringtone = runCatching {
            RingtoneManager.getRingtone(this, uri)?.apply {
                audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                isLooping = true
                play()
            }
        }.getOrNull()
    }

    private fun stopRinging() {
        runCatching { ringtone?.stop() }
        ringtone = null
    }

    private fun showIncoming(call: PhoneCall) {
        val who = PhoneBook.nameFor(this, call.number) ?: PhoneNumbers.pretty(call.number).ifEmpty { getString(R.string.unknown_number) }
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, InCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(this, CHANNEL_CALLS)
            .setSmallIcon(R.drawable.ic_call)
            .setContentTitle(getString(R.string.incoming_call))
            .setContentText(who)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setFullScreenIntent(open, true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.reject), serviceIntent(ACTION_REJECT, 2))
            .addAction(0, getString(R.string.answer), serviceIntent(ACTION_ANSWER, 3))
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIF_INCOMING, n)
        // As a privileged app we may also bring the call screen up directly.
        runCatching { startActivity(Intent(this, InCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private fun serviceIntent(action: String, code: Int): PendingIntent =
        PendingIntent.getService(
            this, code, Intent(this, LinkService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun linkNotification(s: LinkSnapshot): Notification {
        val text = when {
            s.hasCall -> getString(R.string.notif_in_call)
            s.connected -> getString(R.string.notif_connected, s.phoneName ?: "")
            else -> getString(R.string.notif_not_connected)
        }
        val target = if (s.hasCall) InCallActivity::class.java else MainActivity::class.java
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_LINK)
            .setSmallIcon(R.drawable.ic_call)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .build()
    }

    private fun notifyLink(s: LinkSnapshot) {
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIF_LINK, linkNotification(s)) }
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_LINK, getString(R.string.channel_link), NotificationManager.IMPORTANCE_MIN),
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_CALLS, getString(R.string.channel_calls), NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
            },
        )
    }

    companion object {
        private const val TAG = "PhoneLink"
        private const val CHANNEL_LINK = "link"
        private const val CHANNEL_CALLS = "calls"
        private const val NOTIF_LINK = 1
        private const val NOTIF_INCOMING = 2

        const val ACTION_ANSWER = "com.offline.phonelink.ANSWER"
        const val ACTION_REJECT = "com.offline.phonelink.REJECT"
        const val ACTION_HANG_UP = "com.offline.phonelink.HANG_UP"

        /** The running service (null until [start] has run). */
        @Volatile
        var current: LinkService? = null
            private set

        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, LinkService::class.java)) }
        }
    }
}

private fun Intent.intExtraOrNull(key: String): Int? =
    if (hasExtra(key)) getIntExtra(key, 0) else null

private fun android.os.Bundle.intOrNull(key: String): Int? =
    if (containsKey(key)) (get(key) as? Int) else null
