package com.offline.phonelink.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.os.ParcelUuid

/**
 * Lists which Bluetooth services the paired phone offers. Each phone opens a different set, so this
 * decides what PhoneLink can actually do with it (read/send messages, transfer files, and so on).
 * The services are read from the device's advertised UUIDs; nothing is sent to the phone.
 */
object ServiceProbe {

    /** A standard Bluetooth service, its 16-bit id, and what PhoneLink could do with it. */
    data class Service(val name: String, val shortUuid: String, val use: String, val supported: Boolean)

    // 16-bit service identifiers (the xxxx in 0000xxxx-0000-1000-8000-00805F9B34FB).
    private val KNOWN = listOf(
        Triple("111E", "שיחות (דיבורית)", "לחייג, לענות ולנתק — כבר עובד"),
        Triple("1112", "שיחות (Headset)", "חיוג ומענה בסיסיים"),
        Triple("112F", "אנשי קשר (PBAP)", "לקרוא אנשי קשר והיסטוריית שיחות — כבר עובד"),
        Triple("1132", "הודעות (MAP)", "לקרוא ולשלוח SMS מהנגן"),
        Triple("1133", "הודעות (MAP server)", "גישה להודעות"),
        Triple("1105", "שליחת קבצים (OPP)", "לשלוח ולקבל קובץ בודד (תמונה, איש קשר)"),
        Triple("1106", "ניהול קבצים (FTP)", "לעיין בקבצים שבטלפון ולהעביר אותם"),
        Triple("1104", "סנכרון (IrMC)", "לסנכרן ולכתוב אנשי קשר ויומן — בטלפונים ישנים"),
        Triple("110A", "נגן מוזיקה (A2DP)", "להשמיע מהטלפון בנגן"),
        Triple("110E", "שלט מוזיקה (AVRCP)", "לשלוט בנגינה"),
        Triple("1000", "מידע כללי (SDP)", ""),
    )

    @SuppressLint("MissingPermission")
    fun services(device: BluetoothDevice): List<Service> {
        val uuids: Array<ParcelUuid> = runCatching { device.uuids }.getOrNull()?.filterNotNull()?.toTypedArray() ?: emptyArray()
        val present = uuids.map { it.uuid.toString().substring(4, 8).uppercase() }.toSet()
        return KNOWN.filter { it.third.isNotEmpty() }.map { (uuid, name, use) ->
            Service(name, uuid, use, uuid in present)
        }
    }

    /** Asks the phone to list its services again (uuids can be stale after pairing). Result arrives later. */
    @SuppressLint("MissingPermission")
    fun refresh(device: BluetoothDevice) {
        runCatching { device.fetchUuidsWithSdp() }
    }

    const val ACTION_UUID = BluetoothDevice.ACTION_UUID
}
