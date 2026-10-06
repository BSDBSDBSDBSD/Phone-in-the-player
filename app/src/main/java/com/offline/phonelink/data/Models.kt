package com.offline.phonelink.data

data class Contact(val name: String, val numbers: List<String>)

enum class CallDirection { INCOMING, OUTGOING, MISSED }

data class HistoryEntry(
    val number: String,
    val name: String?,
    val direction: CallDirection,
    val time: Long,
    val durationSec: Long,
)

object History {

    /**
     * Joins the call history pulled from the phone (PBAP) with the calls seen by this app.
     * The same call often shows up in both: entries with the same number within a minute are one call
     * (the phone's copy wins, it knows the real duration). Newest first.
     */
    fun merge(fromPhone: List<HistoryEntry>, fromApp: List<HistoryEntry>, limit: Int = 200): List<HistoryEntry> {
        val result = fromPhone.toMutableList()
        for (own in fromApp) {
            val duplicate = fromPhone.any {
                PhoneNumbers.same(it.number, own.number) && kotlin.math.abs(it.time - own.time) < 60_000
            }
            if (!duplicate) result += own
        }
        return result.sortedByDescending { it.time }.take(limit)
    }

    /** Contacts whose name or number matches the search text (Hebrew letters or digits). */
    fun filter(contacts: List<Contact>, query: String): List<Contact> {
        val q = query.trim()
        if (q.isEmpty()) return contacts
        val digits = PhoneNumbers.clean(q)
        val byDigits = digits.length >= 2 && digits.length >= q.count { !it.isWhitespace() } - 1
        return contacts.filter { c ->
            c.name.contains(q, ignoreCase = true) ||
                (byDigits && c.numbers.any { PhoneNumbers.local(it).contains(PhoneNumbers.local(digits)) })
        }
    }

    fun nameFor(contacts: List<Contact>, number: String): String? =
        contacts.firstOrNull { c -> c.numbers.any { PhoneNumbers.same(it, number) } }?.name
}
