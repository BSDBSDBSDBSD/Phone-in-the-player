package com.offline.phonelink.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Bundle
import android.util.Log
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.lang.reflect.Method

/** One call on the phone, as the hands-free link reports it. */
data class PhoneCall(
    val id: Int,
    val state: Int,
    val number: String,
    val outgoing: Boolean,
    val multiParty: Boolean,
    /** The framework's BluetoothHeadsetClientCall, needed to hang up this exact call. */
    val raw: Any?,
) {
    val isRinging get() = state == STATE_INCOMING || state == STATE_WAITING
    val isActive get() = state == STATE_ACTIVE
    val isHeld get() = state == STATE_HELD || state == STATE_HELD_BY_RESPONSE_AND_HOLD
    val isDialing get() = state == STATE_DIALING || state == STATE_ALERTING
    val isOver get() = state == STATE_TERMINATED

    companion object {
        // BluetoothHeadsetClientCall.CALL_STATE_*
        const val STATE_ACTIVE = 0
        const val STATE_HELD = 1
        const val STATE_DIALING = 2
        const val STATE_ALERTING = 3
        const val STATE_INCOMING = 4
        const val STATE_WAITING = 5
        const val STATE_HELD_BY_RESPONSE_AND_HOLD = 6
        const val STATE_TERMINATED = 7
    }
}

/**
 * Android's hands-free *client* (the role a car plays toward a phone) and phonebook client.
 * Both are hidden framework APIs, switched off on phones and players: they work only when the
 * Bluetooth profiles are enabled (bluetooth.profile.hfp.hf.enabled / pbap.client.enabled) and this
 * app is a privileged system app holding BLUETOOTH_PRIVILEGED. Everything goes through reflection,
 * so a missing method on some Android build fails softly instead of crashing.
 */
@SuppressLint("MissingPermission")
class HfpClient(private val context: Context, private val onChange: () -> Unit) {

    var hfp: BluetoothProfile? = null
        private set
    var pbap: BluetoothProfile? = null
        private set

    /** Whether Android agreed to hand out the profiles at all (false = the profile is switched off). */
    var hfpRequested = false
        private set
    var pbapRequested = false
        private set

    val adapter: BluetoothAdapter?
        get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    private val listener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            when (profile) {
                HEADSET_CLIENT -> hfp = proxy
                PBAP_CLIENT -> pbap = proxy
            }
            onChange()
        }

        override fun onServiceDisconnected(profile: Int) {
            when (profile) {
                HEADSET_CLIENT -> hfp = null
                PBAP_CLIENT -> pbap = null
            }
            onChange()
        }
    }

    fun open() {
        unlockHiddenApi()
        val a = adapter ?: return
        if (hfp == null) hfpRequested = runCatching { a.getProfileProxy(context, listener, HEADSET_CLIENT) }.getOrDefault(false)
        if (pbap == null) pbapRequested = runCatching { a.getProfileProxy(context, listener, PBAP_CLIENT) }.getOrDefault(false)
    }

    fun close() {
        val a = adapter
        hfp?.let { runCatching { a?.closeProfileProxy(HEADSET_CLIENT, it) } }
        pbap?.let { runCatching { a?.closeProfileProxy(PBAP_CLIENT, it) } }
        hfp = null
        pbap = null
    }

    /** The phone currently linked for calls, if any. */
    fun connectedPhone(): BluetoothDevice? =
        runCatching { hfp?.connectedDevices?.firstOrNull() }.getOrNull()

    fun connectionState(device: BluetoothDevice): Int =
        runCatching { hfp?.getConnectionState(device) }.getOrNull() ?: BluetoothProfile.STATE_DISCONNECTED

    fun pairedDevices(): List<BluetoothDevice> =
        runCatching { adapter?.bondedDevices?.toList() }.getOrNull().orEmpty()

    // ------------------------------------------------------------------ link

    /** Connects calls and phonebook to [device] and makes them reconnect automatically from now on. */
    fun connect(device: BluetoothDevice): Boolean {
        for (p in listOfNotNull(hfp, pbap)) {
            call(p, "setConnectionPolicy", device, CONNECTION_POLICY_ALLOWED)
        }
        val ok = call(hfp, "connect", device) as? Boolean ?: false
        call(pbap, "connect", device)
        return ok
    }

    fun disconnect(device: BluetoothDevice) {
        call(hfp, "disconnect", device)
        call(pbap, "disconnect", device)
    }

    /** Asks the phone for its contacts and call history again. */
    fun resyncPhonebook(device: BluetoothDevice) {
        call(pbap, "disconnect", device)
        call(pbap, "connect", device)
    }

    fun pbapState(device: BluetoothDevice): Int =
        runCatching { pbap?.getConnectionState(device) }.getOrNull() ?: BluetoothProfile.STATE_DISCONNECTED

    // ------------------------------------------------------------------ calls

    fun dial(device: BluetoothDevice, number: String): Boolean =
        call(hfp, "dial", device, number) != null

    fun accept(device: BluetoothDevice, holdOthers: Boolean = false): Boolean =
        call(hfp, "acceptCall", device, if (holdOthers) CALL_ACCEPT_HOLD else CALL_ACCEPT_NONE) as? Boolean ?: false

    fun reject(device: BluetoothDevice): Boolean =
        call(hfp, "rejectCall", device) as? Boolean ?: false

    fun hangUp(device: BluetoothDevice, phoneCall: PhoneCall?): Boolean =
        call(hfp, "terminateCall", device, phoneCall?.raw) as? Boolean ?: false

    fun sendDtmf(device: BluetoothDevice, digit: Char): Boolean =
        call(hfp, "sendDTMF", device, digit.code.toByte()) as? Boolean ?: false

    fun currentCalls(device: BluetoothDevice): List<PhoneCall> {
        val list = call(hfp, "getCurrentCalls", device) as? List<*> ?: return emptyList()
        return list.mapNotNull { toCall(it) }
    }

    // ------------------------------------------------------------------ audio

    /** Sound of the call on the player (true) or on the phone itself (false). */
    fun audioOnPlayer(device: BluetoothDevice): Boolean =
        (call(hfp, "getAudioState", device) as? Int) == AUDIO_STATE_CONNECTED

    fun moveAudioToPlayer(device: BluetoothDevice): Boolean =
        call(hfp, "connectAudio", device) as? Boolean ?: false

    fun moveAudioToPhone(device: BluetoothDevice): Boolean =
        call(hfp, "disconnectAudio", device) as? Boolean ?: false

    /** Signal, battery and network name of the phone (keys are the EXTRA_* names below). */
    fun phoneStatus(device: BluetoothDevice): Bundle? =
        call(hfp, "getCurrentAgEvents", device) as? Bundle

    // ------------------------------------------------------------------ reflection

    private fun toCall(raw: Any?): PhoneCall? {
        raw ?: return null
        fun get(name: String): Any? = runCatching { findMethod(raw.javaClass, name, 0)?.invoke(raw) }.getOrNull()
        return PhoneCall(
            id = get("getId") as? Int ?: return null,
            state = get("getState") as? Int ?: return null,
            number = get("getNumber") as? String ?: "",
            outgoing = get("isOutgoing") as? Boolean ?: false,
            multiParty = get("isMultiParty") as? Boolean ?: false,
            raw = raw,
        )
    }

    private fun call(target: Any?, name: String, vararg args: Any?): Any? {
        target ?: return null
        val method = findMethod(target.javaClass, name, args.size) ?: run {
            Log.w(TAG, "$name(${args.size}) not found on ${target.javaClass.name}")
            return null
        }
        return try {
            method.invoke(target, *args)
        } catch (t: Throwable) {
            Log.w(TAG, "$name failed", t.cause ?: t)
            null
        }
    }

    private fun findMethod(cls: Class<*>, name: String, argCount: Int): Method? {
        val key = "${cls.name}#$name/$argCount"
        methodCache[key]?.let { return it }
        val found = cls.methods.firstOrNull { it.name == name && it.parameterTypes.size == argCount }
            ?: generateSequence(cls) { it.superclass }
                .flatMap { it.declaredMethods.asSequence() }
                .firstOrNull { it.name == name && it.parameterTypes.size == argCount }
        found?.isAccessible = true
        if (found != null) methodCache[key] = found
        return found
    }

    companion object {
        private const val TAG = "PhoneLink"

        // BluetoothProfile ids of the hidden client profiles
        const val HEADSET_CLIENT = 16
        const val PBAP_CLIENT = 17

        private const val CONNECTION_POLICY_ALLOWED = 100
        private const val CALL_ACCEPT_NONE = 0
        private const val CALL_ACCEPT_HOLD = 1
        private const val AUDIO_STATE_CONNECTED = 2

        // BluetoothHeadsetClient broadcasts (sent only to holders of BLUETOOTH_PRIVILEGED)
        const val ACTION_CONNECTION_STATE_CHANGED = "android.bluetooth.headsetclient.profile.action.CONNECTION_STATE_CHANGED"
        const val ACTION_AUDIO_STATE_CHANGED = "android.bluetooth.headsetclient.profile.action.AUDIO_STATE_CHANGED"
        const val ACTION_AG_EVENT = "android.bluetooth.headsetclient.profile.action.AG_EVENT"
        const val ACTION_CALL_CHANGED = "android.bluetooth.headsetclient.profile.action.AG_CALL_CHANGED"
        const val ACTION_PBAP_CONNECTION_STATE_CHANGED = "android.bluetooth.pbapclient.profile.action.CONNECTION_STATE_CHANGED"

        const val EXTRA_SIGNAL = "android.bluetooth.headsetclient.extra.NETWORK_SIGNAL_STRENGTH"
        const val EXTRA_BATTERY = "android.bluetooth.headsetclient.extra.BATTERY_LEVEL"
        const val EXTRA_OPERATOR = "android.bluetooth.headsetclient.extra.OPERATOR_NAME"
        const val EXTRA_NETWORK = "android.bluetooth.headsetclient.extra.NETWORK_STATUS"

        private val methodCache = HashMap<String, Method>()

        @Volatile
        private var unlocked = false

        /** Lifts Android's block on hidden framework methods for this app. */
        fun unlockHiddenApi(): Boolean {
            if (!unlocked) unlocked = runCatching { HiddenApiBypass.addHiddenApiExemptions("L") }.getOrDefault(false)
            return unlocked
        }
    }
}
