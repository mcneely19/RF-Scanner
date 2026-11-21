package com.rfscout.app

/**
 * Drone Remote ID (ASTM F3411 / ASD-STAN) message decoding. Pure Kotlin, no Android dependencies.
 * Layouts follow opendroneid-core-c. Every message is 25 bytes; byte 0 = type (high nibble) + version.
 */
class RidState {
    var idType = -1
    var uaType = -1
    var uasId = ""
    var status = -1
    var lat: Double? = null
    var lon: Double? = null
    var altBaro: Double? = null
    var altGeo: Double? = null
    var height: Double? = null
    var speed: Double? = null          // m/s horizontal
    var vspeed: Double? = null
    var direction: Double? = null
    var opLat: Double? = null
    var opLon: Double? = null
    var operatorId = ""
    var selfId = ""

    fun hasPosition() = lat != null && lon != null
    fun hasOperator() = opLat != null && opLon != null
}

object RemoteId {
    const val MSG = 25
    const val BLE_UUID16 = 0xFFFA
    const val BLE_APP_CODE = 0x0D
    val WIFI_OUI = byteArrayOf(0xFA.toByte(), 0x0B, 0xBC.toByte())
    const val WIFI_OUI_TYPE = 0x0D

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun i8(b: ByteArray, i: Int) = b[i].toInt()
    private fun u16(b: ByteArray, i: Int) = u8(b, i) or (u8(b, i + 1) shl 8)
    private fun i32(b: ByteArray, i: Int): Int =
        u8(b, i) or (u8(b, i + 1) shl 8) or (u8(b, i + 2) shl 16) or (u8(b, i + 3) shl 24)

    private fun text(b: ByteArray, from: Int, len: Int): String {
        val sb = StringBuilder()
        for (k in from until from + len) {
            val c = u8(b, k)
            if (c == 0) break
            if (c in 32..126) sb.append(c.toChar())
        }
        return sb.toString().trim()
    }

    private fun alt(raw: Int): Double? = if (raw == 0) null else raw * 0.5 - 1000.0   // 0 = unknown

    private fun validLat(v: Int) = v != 0 && v in -900000000..900000000
    private fun validLon(v: Int) = v in -1800000000..1800000000

    /** Decode one 25-byte message at [off] into [s]. Returns the message type (0..5, 0xF pack) or -1. */
    fun decode(b: ByteArray, off: Int, s: RidState): Int {
        if (off < 0 || off + MSG > b.size) return -1
        val m = if (off == 0 && b.size == MSG) b else b.copyOfRange(off, off + MSG)
        when (val type = u8(m, 0) shr 4) {
            0 -> {                                   // Basic ID
                s.idType = u8(m, 1) shr 4
                s.uaType = u8(m, 1) and 0xF
                val id = text(m, 2, 20)
                if (id.isNotEmpty()) s.uasId = id
                return type
            }
            1 -> {                                   // Location / vector
                val flags = u8(m, 1)
                s.status = flags shr 4
                val ew = (flags shr 1) and 1
                val mult = flags and 1
                val dir = u8(m, 2)
                val sp = u8(m, 3)
                val vs = i8(m, 4)
                val la = i32(m, 5)
                val lo = i32(m, 9)
                s.direction = if (dir <= 179) (dir + 180 * ew).toDouble() else null
                s.speed = if (sp == 255) null else if (mult == 1) sp * 0.75 + 255 * 0.25 else sp * 0.25
                s.vspeed = if (vs == 126 || vs == 63) null else vs * 0.5
                if (validLat(la) && validLon(lo)) {
                    s.lat = la / 1e7; s.lon = lo / 1e7
                }
                s.altBaro = alt(u16(m, 13))
                s.altGeo = alt(u16(m, 15))
                s.height = alt(u16(m, 17))
                return type
            }
            4 -> {                                   // System (operator position)
                val la = i32(m, 2)
                val lo = i32(m, 6)
                if (validLat(la) && validLon(lo)) {
                    s.opLat = la / 1e7; s.opLon = lo / 1e7
                }
                return type
            }
            3 -> {                                   // Self ID
                val t = text(m, 2, 23)
                if (t.isNotEmpty()) s.selfId = t
                return type
            }
            5 -> {                                   // Operator ID
                val t = text(m, 2, 20)
                if (t.isNotEmpty()) s.operatorId = t
                return type
            }
            2 -> return type                         // Authentication: not decoded
            0xF -> {                                 // Message pack
                if (u8(m, 0) shr 4 != 0xF) return -1
                return decodePack(b, off, s)
            }
        }
        return -1
    }

    /** Decode a message pack starting at [off] (type byte, size byte, count byte, then messages). */
    fun decodePack(b: ByteArray, off: Int, s: RidState): Int {
        if (off < 0 || off + 3 > b.size) return -1
        if (u8(b, off) shr 4 != 0xF) return -1
        if (u8(b, off + 1) != MSG) return -1
        val n = u8(b, off + 2)
        if (n < 1 || n > 9 || off + 3 + n * MSG > b.size) return -1
        var ok = 0
        for (k in 0 until n) {
            val t = decode(b, off + 3 + k * MSG, s)
            if (t in 0..5) ok++
        }
        return if (ok > 0) 0xF else -1
    }

    /**
     * Bluetooth: service data for UUID 0xFFFA is [app code 0x0D][counter][25-byte message] (the UUID itself
     * is not part of the value Android returns).
     */
    fun decodeBle(serviceData: ByteArray, s: RidState): Boolean {
        if (serviceData.size < 2 + MSG) return false
        if (u8(serviceData, 0) != BLE_APP_CODE) return false
        return decode(serviceData, 2, s) >= 0
    }

    /**
     * Wi-Fi beacon: walks the information elements looking for vendor IE (221) with the ASD-STAN OUI and
     * type 0x0D, followed by a counter byte and a message pack (or a single message).
     */
    fun decodeWifiIes(ies: ByteArray, s: RidState): Boolean {
        var i = 0
        var found = false
        while (i + 2 <= ies.size) {
            val id = u8(ies, i)
            val len = u8(ies, i + 1)
            val start = i + 2
            if (start + len > ies.size) break
            if (id == 221 && len >= 4 + 1 + MSG &&
                ies[start] == WIFI_OUI[0] && ies[start + 1] == WIFI_OUI[1] && ies[start + 2] == WIFI_OUI[2] &&
                u8(ies, start + 3) == WIFI_OUI_TYPE
            ) {
                val body = start + 5                  // skip OUI, type, message counter
                if (decode(ies, body, s) >= 0) found = true
            }
            i = start + len
        }
        return found
    }

    val UA_TYPES = arrayOf(
        "Unspecified", "Aeroplane", "Multirotor/helicopter", "Gyroplane", "VTOL", "Ornithopter", "Glider",
        "Kite", "Free balloon", "Captive balloon", "Airship", "Free-fall/parachute", "Rocket", "Tethered",
        "Ground obstacle", "Other"
    )
    val STATUS = arrayOf("Undeclared", "Ground", "Airborne", "Emergency", "Remote ID failure")

    fun uaName(t: Int) = UA_TYPES.getOrElse(t) { "UA" }
    fun statusName(t: Int) = STATUS.getOrElse(t) { "" }
}

/**
 * Keeps one [RidState] per transmitting address (BLE advertisements carry one message each, so the picture
 * builds up over time) and turns it into a [Reading] that reports the drone's own position.
 */
class RidHub {
    private val states = HashMap<String, RidState>()

    fun reading(src: String, s: RidState, rssi: Int, freqMhz: Int, via: String, timeMs: Long): Reading {
        val parts = ArrayList<String>()
        if (s.uaType >= 0) parts.add(RemoteId.uaName(s.uaType))
        RemoteId.statusName(s.status).takeIf { it.isNotEmpty() }?.let { parts.add(it) }
        s.speed?.let { parts.add("%.0f m/s".format(it)) }
        (s.altGeo ?: s.altBaro)?.let { parts.add("alt %.0f m".format(it)) }
        s.height?.let { parts.add("%.0f m AGL".format(it)) }
        if (s.operatorId.isNotEmpty()) parts.add("operator ${s.operatorId}")
        if (s.hasOperator()) parts.add("pilot %.5f, %.5f".format(s.opLat, s.opLon))
        if (s.selfId.isNotEmpty()) parts.add("\"${s.selfId}\"")
        parts.add("via $via")
        return Reading(
            key = "rid:$src", protocol = Protocol.RID,
            name = s.uasId.ifEmpty { "Drone (ID pending)" },
            rssi = rssi, freqMhz = freqMhz, detail = parts.joinToString(" · "),
            lat = s.lat, lon = s.lon, timeMs = timeMs
        )
    }

    /** Decoded state for [src], created on first use. */
    fun stateFor(src: String): RidState {
        if (states.size > 300 && !states.containsKey(src)) states.clear()
        return states.getOrPut(src) { RidState() }
    }
}
