package com.offline.phonelink.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneNumbersTest {

    @Test
    fun cleansSeparators() {
        assertEquals("0501234567", PhoneNumbers.clean(" 050-123 45 67 "))
        assertEquals("+972501234567", PhoneNumbers.clean("+972 (50) 123-4567"))
        assertEquals("*3370#", PhoneNumbers.clean("*3370#"))
    }

    @Test
    fun internationalBecomesLocal() {
        assertEquals("0501234567", PhoneNumbers.local("+972501234567"))
        assertEquals("0501234567", PhoneNumbers.local("00972501234567"))
        assertEquals("0501234567", PhoneNumbers.local("972501234567"))
        assertEquals("026543210", PhoneNumbers.local("+97226543210"))
    }

    @Test
    fun sameNumberInDifferentForms() {
        assertTrue(PhoneNumbers.same("+972 50-123-4567", "0501234567"))
        assertTrue(PhoneNumbers.same("02-654-3210", "+97226543210"))
        assertFalse(PhoneNumbers.same("0501234567", "0501234568"))
        assertFalse(PhoneNumbers.same("", ""))
    }

    @Test
    fun prettyFormats() {
        assertEquals("050-123-4567", PhoneNumbers.pretty("+972501234567"))
        assertEquals("02-654-3210", PhoneNumbers.pretty("026543210"))
        assertEquals("*3370", PhoneNumbers.pretty("*3370"))
        assertEquals("1700500500", PhoneNumbers.pretty("1700500500"))
    }

    @Test
    fun historyMergeDropsDuplicates() {
        val t = 1_700_000_000_000L
        val phone = listOf(HistoryEntry("+972501234567", "דוד", CallDirection.OUTGOING, t, 65))
        val app = listOf(
            HistoryEntry("0501234567", null, CallDirection.OUTGOING, t + 5_000, 60),
            HistoryEntry("0529999999", null, CallDirection.MISSED, t + 600_000, 0),
        )
        val merged = History.merge(phone, app)
        assertEquals(2, merged.size)
        assertEquals("0529999999", merged[0].number)
        assertEquals(65, merged[1].durationSec)
    }

    @Test
    fun contactSearchByNameAndDigits() {
        val contacts = listOf(
            Contact("אבא", listOf("0501234567")),
            Contact("סבתא רחל", listOf("+97226543210")),
        )
        assertEquals(listOf("סבתא רחל"), History.filter(contacts, "רחל").map { it.name })
        assertEquals(listOf("סבתא רחל"), History.filter(contacts, "0265").map { it.name })
        assertEquals(2, History.filter(contacts, "").size)
        assertEquals("אבא", History.nameFor(contacts, "+972-50-123-4567"))
        assertNull(History.nameFor(contacts, "0500000000"))
    }
}
