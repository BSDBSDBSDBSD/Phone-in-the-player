package com.offline.phonelink.data

/** Phone-number helpers: the same number can arrive as "+972 50-123-4567", "0501234567" or "972501234567". */
object PhoneNumbers {

    /** Digits only (keeps a leading '+', '*' and '#' for codes). */
    fun clean(raw: String): String {
        val trimmed = raw.trim()
        val sb = StringBuilder()
        trimmed.forEachIndexed { i, c ->
            when {
                c.isDigit() -> sb.append(c)
                c == '+' && i == 0 -> sb.append(c)
                c == '*' || c == '#' -> sb.append(c)
            }
        }
        return sb.toString()
    }

    /** Local Israeli form: "+972501234567" / "00972…" / "972…" → "0501234567". */
    fun local(raw: String): String {
        val n = clean(raw)
        return when {
            n.startsWith("+972") -> "0" + n.removePrefix("+972")
            n.startsWith("00972") -> "0" + n.removePrefix("00972")
            n.startsWith("972") && n.length >= 11 -> "0" + n.removePrefix("972")
            else -> n
        }
    }

    /** Key for matching two numbers: the last 9 digits (enough to tell numbers apart, ignores prefixes). */
    fun key(raw: String): String {
        val digits = local(raw).filter { it.isDigit() }
        return if (digits.length > 9) digits.takeLast(9) else digits
    }

    fun same(a: String, b: String): Boolean {
        val ka = key(a)
        return ka.isNotEmpty() && ka == key(b)
    }

    /** Readable form for the screen: "050-123-4567", "02-123-4567"; anything else is shown as it came. */
    fun pretty(raw: String): String {
        val n = local(raw)
        if (n.any { !it.isDigit() }) return n
        return when {
            n.length == 10 && n.startsWith("05") -> "${n.take(3)}-${n.substring(3, 6)}-${n.substring(6)}"
            n.length == 10 && n.startsWith("07") -> "${n.take(3)}-${n.substring(3, 6)}-${n.substring(6)}"
            n.length == 9 && n.startsWith("0") -> "${n.take(2)}-${n.substring(2, 5)}-${n.substring(5)}"
            else -> n
        }
    }
}
