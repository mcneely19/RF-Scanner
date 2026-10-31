package com.rfscout.app

/**
 * A guess at what kind of thing a signal is, from its name, advertised company and tracker/watchlist
 * matches. Names can be anything, so this is a hint, never an identification.
 */
object Category {
    private fun has(s: String, vararg words: String) = words.any { s.contains(it) }

    fun of(t: Track): String? {
        if (t.trackerKind != null) return "Item tracker (${t.trackerKind})"
        if (t.watch != null) return "Possible ALPR signature (a lead, not proof)"
        val n = t.name.lowercase()
        return when (t.protocol) {
            Protocol.CELL -> if (t.detail.startsWith("serving")) "Cell tower you are connected to" else "Neighbor cell tower"
            Protocol.WIFI -> when {
                n == "(hidden)" -> "Hidden network"
                has(n, "iphone", "galaxy", "pixel", "android", "'s phone", "’s phone") -> "Phone hotspot"
                has(n, "mifi", "jetpack", "inseego", "cradlepoint", "novatel", "sierra", "netgear lte", "verizon", "tmobile", "t-mobile", "att", "5g", "lte") ->
                    "Cellular router or hotspot"
                has(n, "direct-", "print", "epson", "brother", "canon", "hp-") -> "Printer"
                has(n, "roku", "chromecast", "vizio", "firetv", "fire tv", "webos", "bravia", "[tv]", "tv") -> "TV or streaming device"
                has(n, "guest") -> "Guest network"
                has(n, "cam", "ring-", "arlo", "wyze", "nest") -> "Camera or smart-home device"
                else -> "Wi-Fi access point"
            }
            Protocol.BLE -> when {
                has(n, "buds", "airpods", "watch", "band", "fit", "headphone", "earbud") -> "Wearable or audio device"
                t.mfrId == 0x004C -> "Apple device (phone, watch, earbuds...)"
                t.mfrId == 0x0075 -> "Samsung device"
                t.mfrId == 0x00E0 -> "Google device"
                t.mfrId == 0x0006 -> "Microsoft device"
                t.mfrId == 0x0087 -> "Garmin device"
                else -> "Bluetooth LE device"
            }
            Protocol.BT_CLASSIC -> when {
                has(n, "car", "jbl", "bose", "speaker", "sound", "audio") -> "Speaker or car audio"
                has(n, "phone", "galaxy", "iphone", "pixel") -> "Phone"
                else -> "Bluetooth device"
            }
        }
    }
}
