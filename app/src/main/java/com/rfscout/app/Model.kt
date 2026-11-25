package com.rfscout.app

enum class Protocol(val label: String, val color: Int, val tag: String) {
    WIFI("Wi-Fi", 0xFF1E88E5.toInt(), "Wi-Fi"),
    BLE("BLE", 0xFF8E24AA.toInt(), "BLE"),
    BT_CLASSIC("BT Classic", 0xFF3949AB.toInt(), "BT"),
    CELL("Cell", 0xFFEF6C00.toInt(), "Cell"),
    ADSB("ADS-B", 0xFF00897B.toInt(), "ADS-B"),     // aircraft heard through an external RTL-SDR
    RID("Drone ID", 0xFFD81B60.toInt(), "RID")     // drones broadcasting ASTM F3411 over BLE / Wi-Fi
}

/** True for protocols whose transmitters report their own position, so no walk-and-fit is done. */
val Protocol.reported: Boolean get() = this == Protocol.ADSB || this == Protocol.RID

/** One point of the RSSI-over-time history shown on the info page. */
class HistPt(val t: Long, val rssi: Int)

/** Full identity of a cell, as far as Android reports it. Neighbor cells usually lack most of it. */
class CellIdent(
    val radio: String,          // LTE, NR, UMTS, GSM
    val mcc: String,
    val mnc: String,
    val area: Int,              // TAC or LAC
    val cid: Long,
    val registered: Boolean     // true for the cell the phone is connected to
) {
    fun usable(): Boolean =
        mcc.toIntOrNull() != null && mnc.toIntOrNull() != null &&
            area >= 0 && area != Int.MAX_VALUE &&
            cid >= 0 && cid != Long.MAX_VALUE && cid != Int.MAX_VALUE.toLong()

    fun lookupKey(): String = "$radio-$mcc-$mnc-$area-$cid"
}

/** One raw measurement from a scanner. */
data class Reading(
    val key: String,            // unique per emitter (BSSID, MAC, cell id ...)
    val protocol: Protocol,
    val name: String,
    val rssi: Int,              // dBm
    val freqMhz: Int = 0,       // 0 = unknown
    val txPower: Int? = null,   // advertised TX power in dBm, if any
    val detail: String = "",
    val mfrId: Int? = null,     // Bluetooth company ID from manufacturer data, if advertised
    val tracker: String? = null,// kind of item tracker this advertisement looks like
    val cell: CellIdent? = null,
    val lat: Double? = null,    // position the emitter itself reported (ADS-B), if any
    val lon: Double? = null,
    val uuids: List<String> = emptyList(),  // advertised BLE service UUIDs (16-bit ones in short form)
    val timeMs: Long = System.currentTimeMillis()
)

/** RSSI measured at a known place while walking. */
data class Sample(val lat: Double, val lon: Double, val rssi: Double)

/** Estimated transmitter position from the walked samples. */
data class Fix(
    val lat: Double,
    val lon: Double,
    val rmsDb: Double,
    val ambiguous: Boolean,
    val tower: Boolean = false,     // true = position taken from a cell tower database, not from walking
    val reported: Boolean = false   // true = the emitter broadcast its own position (ADS-B)
)

/** Everything we know about one emitter. */
class Track(val key: String, val protocol: Protocol) {
    var name = ""
    var detail = ""
    var freqMhz = 0
    var txPower: Int? = null
    var mfrId: Int? = null
    var trackerKind: String? = null
    var cell: CellIdent? = null
    var watch: WatchHit? = null
    var hint: WatchHit? = null      // low-confidence guess about what this is (Signatures)
    var uuids: Set<String> = emptySet()
    var myHit: MyEntry? = null      // entry on your own watchlist this signal matches
    var rssi = 0
    var rssiSmooth = 0.0
    var lastSeenMs = 0L
    var firstSeenMs = 0L
    var count = 0

    val history = ArrayList<HistPt>()
    val samples = ArrayList<Sample>()
    var sampleSeq = 0           // bumps on every new sample
    var fixSeq = -1             // sampleSeq the current fix was computed from
    var fix: Fix? = null
    var fixNote = ""            // why there is no fix yet (shown instead of a made-up position)

    // cell towers: where the database says the tower is
    var towerNote = ""
    var towerRangeM = 0

    // item-tracker watch: how far the user has travelled while this tag stayed in range
    var firstLat = 0.0
    var firstLon = 0.0
    var hasFirstPos = false
    var followSinceMs = 0L
    var maxTravelM = 0.0
    var following = false
    var alerted = false

    fun update(r: Reading) {
        if (name.isEmpty() || !r.name.startsWith("(")) name = r.name
        if (r.detail.isNotEmpty()) detail = r.detail
        if (r.freqMhz > 0) freqMhz = r.freqMhz
        txPower = r.txPower ?: txPower
        mfrId = r.mfrId ?: mfrId
        trackerKind = r.tracker ?: trackerKind
        cell = r.cell ?: cell
        if (r.uuids.isNotEmpty() && uuids.size < 12) uuids = uuids + r.uuids
        if (r.lat != null && r.lon != null) fix = Fix(r.lat, r.lon, 0.0, false, false, true)
        rssi = r.rssi
        rssiSmooth = if (count == 0) r.rssi.toDouble() else 0.35 * r.rssi + 0.65 * rssiSmooth
        lastSeenMs = r.timeMs
        if (firstSeenMs == 0L) firstSeenMs = r.timeMs
        watch = if (MyWatch.isMuted(key)) null else Watch.match(this)
        myHit = MyWatch.match(this)
        hint = Signatures.match(this)
        if (history.isEmpty() || r.timeMs - history[history.size - 1].t >= 500) {
            history.add(HistPt(r.timeMs, r.rssi))
            if (history.size > MAX_HISTORY) history.removeAt(0)
        }
        count++
    }

    fun addSample(lat: Double, lon: Double) {
        samples.add(Sample(lat, lon, rssiSmooth))
        sampleSeq++
        if (samples.size > MAX_SAMPLES) samples.removeAt(0)
    }

    companion object {
        const val MAX_SAMPLES = 150
        const val MAX_HISTORY = 240
    }
}
