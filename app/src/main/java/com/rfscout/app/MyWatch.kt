package com.rfscout.app

/** One entry on your own watchlist: a name fragment or an address (prefix), plus an optional note. */
data class MyEntry(val pattern: String, val note: String = "")

/**
 * Your own watchlist and mute list. No Android classes here, so the matching rules can be tested on a desktop.
 *
 * Matching is deliberately simple and case-insensitive:
 *  - a pattern written like a MAC address (with ":" or "-") matches the start of a signal's address;
 *  - any other pattern matches if it appears in the signal's name, or, when it is 6 to 12 hex digits, if it
 *    is the start of the signal's address (so "A4C138" or an aircraft's ICAO code works too).
 * Signals that rotate their address (many Bluetooth devices) will not keep matching an address entry.
 */
object MyWatch {
    const val MIN_LEN = 3

    val entries = ArrayList<MyEntry>()

    /** Signal keys (as in Track.key) that never raise alerts or flags. */
    val muted = LinkedHashSet<String>()

    fun isMuted(key: String): Boolean = key in muted

    private fun norm(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

    private fun isHex(s: String): Boolean = s.isNotEmpty() && s.all { it in '0'..'9' || it in 'a'..'f' }

    /** The address part of a key like "wifi:aa:bb:cc:dd:ee:ff" or "adsb:4840D6", or null for signals without one. */
    fun addressOf(t: Track): String? = when (t.protocol) {
        Protocol.WIFI, Protocol.BLE, Protocol.BT_CLASSIC, Protocol.ADSB -> t.key.substringAfter(':', "")
        else -> null
    }?.takeIf { it.isNotEmpty() }

    fun matches(e: MyEntry, t: Track): Boolean {
        val p = e.pattern.trim().lowercase()
        if (p.length < MIN_LEN) return false
        val addr = addressOf(t)?.let { norm(it) }
        val pn = norm(p)
        val addressStyle = (p.contains(':') || p.contains('-')) && isHex(pn)
        if (addressStyle) return addr != null && addr.startsWith(pn)
        if (t.name.lowercase().contains(p)) return true
        return pn.length in 6..12 && isHex(pn) && addr != null && addr.startsWith(pn)
    }

    /** The first watchlist entry this signal matches, or null (also null while the signal is muted). */
    fun match(t: Track): MyEntry? {
        if (entries.isEmpty() || t.key in muted) return null
        for (e in entries) if (matches(e, t)) return e
        return null
    }

    /** Suggested patterns for a signal, as (label, pattern) pairs for the "Watch" chooser. */
    fun suggestions(t: Track): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val addr = addressOf(t)
        val named = t.name.isNotBlank() && !t.name.startsWith("(")
        if (named) out.add("This name: \"${t.name}\"" to t.name)
        if (addr != null) {
            val label = if (t.protocol == Protocol.ADSB) "This aircraft (ICAO $addr)" else "This address: $addr"
            out.add(label to addr)
            val parts = addr.split(':', '-')
            if (parts.size == 6) out.add("Same maker (first 3 bytes): ${parts.take(3).joinToString(":")}" to parts.take(3).joinToString(":"))
        }
        return out
    }
}
