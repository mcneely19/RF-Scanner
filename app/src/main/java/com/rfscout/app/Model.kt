package com.rfscout.app

enum class Protocol(val label: String, val color: Int, val tag: String) {
    WIFI("Wi-Fi", 0xFF1E88E5.toInt(), "Wi-Fi"),
    BLE("BLE", 0xFF8E24AA.toInt(), "BLE"),
    BT_CLASSIC("BT Classic", 0xFF3949AB.toInt(), "BT"),
    CELL("Cellular", 0xFFEF6C00.toInt(), "Cell")
}

/** One point of the RSSI-over-time history shown on the info page. */
class HistPt(val t: Long, val rssi: Int)

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
    val timeMs: Long = System.currentTimeMillis()
)

/** RSSI measured at a known place while walking. */
data class Sample(val lat: Double, val lon: Double, val rssi: Double)

/** Estimated transmitter position from the walked samples. */
data class Fix(val lat: Double, val lon: Double, val rmsDb: Double, val ambiguous: Boolean)

/** Everything we know about one emitter. */
class Track(val key: String, val protocol: Protocol) {
    var name = ""
    var detail = ""
    var freqMhz = 0
    var txPower: Int? = null
    var mfrId: Int? = null
    var watch: WatchHit? = null
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

    fun update(r: Reading) {
        if (name.isEmpty() || !r.name.startsWith("(")) name = r.name
        if (r.detail.isNotEmpty()) detail = r.detail
        if (r.freqMhz > 0) freqMhz = r.freqMhz
        txPower = r.txPower ?: txPower
        mfrId = r.mfrId ?: mfrId
        rssi = r.rssi
        rssiSmooth = if (count == 0) r.rssi.toDouble() else 0.35 * r.rssi + 0.65 * rssiSmooth
        lastSeenMs = r.timeMs
        if (firstSeenMs == 0L) firstSeenMs = r.timeMs
        watch = Watch.match(this)
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
