package com.rfscout.app

/**
 * Low-confidence hints about what a Bluetooth or Wi-Fi signal might be. These are NOT surveillance
 * flags: they only add a plain-language guess to the info page and the category line. Every entry says
 * where it comes from and what it does not prove. The identifiers were written from public
 * documentation and community reports, and have not been field-validated by this app.
 */
object Signatures {
    /** Meshtastic radios advertise this GATT service while their phone-pairing mode is on. */
    const val UUID_MESHTASTIC = "6ba1b218-15a8-461f-9fa8-5dcae273eafd"

    /** Nordic UART service, used by MeshCore radios and by a great many unrelated hobby devices. */
    const val UUID_NORDIC_UART = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"

    /** "0000fd5a-0000-1000-8000-00805f9b34fb" becomes "fd5a"; other UUIDs are only lower-cased. */
    fun shortUuid(u: String): String {
        val l = u.lowercase()
        return if (l.length == 36 && l.startsWith("0000") && l.endsWith("-0000-1000-8000-00805f9b34fb")) l.substring(4, 8) else l
    }

    /**
     * @param isBle true for BLE advertisements
     * @param isClassic true for Bluetooth Classic names
     */
    fun classify(isBle: Boolean, isClassic: Boolean, name: String, uuids: Set<String>): WatchHit? {
        val n = name.trim()
        val low = n.lowercase()
        if (isBle) {
            if (UUID_MESHTASTIC in uuids) {
                return WatchHit(
                    "Meshtastic radio (phone-pairing service is on)", "medium",
                    "service UUID used by the Meshtastic project",
                    "Hobby and emergency mesh-radio node. It says nothing about who owns it or what it is attached to."
                )
            }
            if (low.startsWith("flipper ") || low == "flipper") {
                return WatchHit(
                    "Name looks like a Flipper Zero", "low",
                    "default advertised name",
                    "Anyone can rename a device, and other products can copy a name. A Flipper is a legal hobby/security tool."
                )
            }
            if (UUID_NORDIC_UART in uuids && low.contains("meshcore")) {
                return WatchHit(
                    "MeshCore radio (name plus Nordic UART service)", "low",
                    "service UUID and name documented by the MeshCore project",
                    "Hobby mesh-radio node. The UART service alone matches many unrelated devices."
                )
            }
        }
        if (isBle || isClassic) {
            if (low == "hc-05" || low == "hc-06" || low == "hc-03") {
                return WatchHit(
                    "Generic serial-module name ($n)", "very low",
                    "default name of a common cheap Bluetooth module",
                    "Some card skimmers use these modules, but so do thousands of hobby, robotics and industrial projects. Not evidence of anything by itself."
                )
            }
        }
        return null
    }

    fun match(t: Track): WatchHit? = classify(
        isBle = t.protocol == Protocol.BLE,
        isClassic = t.protocol == Protocol.BT_CLASSIC,
        name = t.name,
        uuids = t.uuids
    )
}
