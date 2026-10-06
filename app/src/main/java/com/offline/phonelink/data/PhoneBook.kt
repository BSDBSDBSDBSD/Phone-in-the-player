package com.offline.phonelink.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import android.provider.ContactsContract

/**
 * Contacts and call history. When the phonebook profile connects, Android itself copies the phone's
 * contacts and call history into the player's contacts / call log, so they are read from there.
 */
object PhoneBook {

    /** Account type Android uses for contacts copied from a phone over Bluetooth. */
    const val PBAP_ACCOUNT_TYPE = "com.android.bluetooth.pbapsink"

    @Volatile
    private var cache: List<Contact>? = null

    fun invalidate() {
        cache = null
    }

    fun contacts(context: Context): List<Contact> {
        cache?.let { return it }
        if (!granted(context, Manifest.permission.READ_CONTACTS)) return emptyList()
        val byName = LinkedHashMap<String, MutableList<String>>()
        runCatching {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                ),
                null, null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY + " COLLATE LOCALIZED ASC",
            )?.use { c ->
                while (c.moveToNext()) {
                    val number = c.getString(1)?.takeIf { it.isNotBlank() } ?: continue
                    val name = c.getString(0)?.takeIf { it.isNotBlank() } ?: PhoneNumbers.pretty(number)
                    val list = byName.getOrPut(name) { mutableListOf() }
                    if (list.none { PhoneNumbers.same(it, number) }) list += number
                }
            }
        }
        return byName.map { (name, numbers) -> Contact(name, numbers) }.also { cache = it }
    }

    fun nameFor(context: Context, number: String): String? =
        if (number.isBlank()) null else History.nameFor(contacts(context), number)

    /** How many contacts were copied from the phone (for the system check screen). */
    fun copiedFromPhone(context: Context): Int {
        if (!granted(context, Manifest.permission.READ_CONTACTS)) return -1
        return runCatching {
            context.contentResolver.query(
                ContactsContract.RawContacts.CONTENT_URI, arrayOf(ContactsContract.RawContacts._ID),
                ContactsContract.RawContacts.ACCOUNT_TYPE + "=? AND " + ContactsContract.RawContacts.DELETED + "=0",
                arrayOf(PBAP_ACCOUNT_TYPE), null,
            )?.use { it.count } ?: 0
        }.getOrDefault(-1)
    }

    fun history(context: Context): List<HistoryEntry> {
        val fromPhone = mutableListOf<HistoryEntry>()
        if (granted(context, Manifest.permission.READ_CALL_LOG)) {
            runCatching {
                context.contentResolver.query(
                    CallLog.Calls.CONTENT_URI,
                    arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION),
                    null, null, CallLog.Calls.DATE + " DESC LIMIT 200",
                )?.use { c ->
                    while (c.moveToNext()) {
                        val number = c.getString(0) ?: ""
                        val direction = when (c.getInt(2)) {
                            CallLog.Calls.OUTGOING_TYPE -> CallDirection.OUTGOING
                            CallLog.Calls.MISSED_TYPE, CallLog.Calls.REJECTED_TYPE -> CallDirection.MISSED
                            else -> CallDirection.INCOMING
                        }
                        fromPhone += HistoryEntry(number, c.getString(1), direction, c.getLong(3), c.getLong(4))
                    }
                }
            }
        }
        val contacts = contacts(context)
        return History.merge(fromPhone, OwnHistory.load(context)).map {
            if (it.name.isNullOrBlank()) it.copy(name = History.nameFor(contacts, it.number)) else it
        }
    }

    private fun granted(context: Context, permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}
