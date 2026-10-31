package com.rfscout.app

/** Why a signal is on the watchlist. */
class WatchHit(val label: String, val confidence: String)

/**
 * Watchlist for signals that match patterns people have reported on Flock Safety license-plate
 * cameras. These patterns are community-reported, they change over time, they can match unrelated
 * devices, and plenty of cameras broadcast nothing at all. A hit is a lead, never proof.
 *
 * MAC address prefixes are deliberately not used: the reported ones belong to chip vendors and give
 * mostly false matches.
 */
object Watch {
    private val flockHotspot = Regex("^flock-[a-z0-9]+$", RegexOption.IGNORE_CASE)

    /** Bluetooth company ID reported on Flock hardware (a component maker, so not unique to Flock). */
    const val BLE_COMPANY_ID = 0x09C8

    fun match(t: Track): WatchHit? {
        val n = t.name.trim()
        val low = n.lowercase()
        when (t.protocol) {
            Protocol.WIFI -> {
                if (flockHotspot.matches(n)) {
                    return WatchHit("Wi-Fi name matches the reported Flock hotspot pattern", "medium")
                }
                if (low.startsWith("flock")) {
                    return WatchHit("Wi-Fi name starts with \"Flock\"", "medium")
                }
                if (low == "fs ext battery" || low == "penguin" || low == "pigvision") {
                    return WatchHit("Wi-Fi name \"$n\" has been reported on these cameras", "low")
                }
            }
            Protocol.BLE -> {
                if (t.mfrId == BLE_COMPANY_ID) {
                    return WatchHit("Bluetooth company ID 0x09C8, reported on Flock hardware", "medium")
                }
                if (low.contains("flock") || low.contains("fs ext battery") ||
                    low.startsWith("penguin") || low.startsWith("pigvision")
                ) {
                    return WatchHit("Bluetooth name \"$n\" matches a reported pattern", "low")
                }
            }
            else -> {}
        }
        return null
    }
}
