package com.rfscout.app

enum class Protocol(val label: String, val color: Int) {
    WIFI("Wi-Fi", 0xFF2196F3.toInt()),
    BLE("BLE", 0xFFBA68C8.toInt()),
    BT_CLASSIC("BT Classic", 0xFF7986CB.toInt()),
    CELL("Cellular", 0xFFFFB74D.toInt())
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
    var rssi = 0
    var rssiSmooth = 0.0
    var lastSeenMs = 0L
    var count = 0

    val samples = ArrayList<Sample>()
    var sampleSeq = 0           // bumps on every new sample
    var fixSeq = -1             // sampleSeq the current fix was computed from
    var fix: Fix? = null

    fun update(r: Reading) {
        if (name.isEmpty() || !r.name.startsWith("(")) name = r.name
        if (r.detail.isNotEmpty()) detail = r.detail
        if (r.freqMhz > 0) freqMhz = r.freqMhz
        txPower = r.txPower ?: txPower
        rssi = r.rssi
        rssiSmooth = if (count == 0) r.rssi.toDouble() else 0.35 * r.rssi + 0.65 * rssiSmooth
        lastSeenMs = r.timeMs
        count++
    }

    fun addSample(lat: Double, lon: Double) {
        samples.add(Sample(lat, lon, rssiSmooth))
        sampleSeq++
        if (samples.size > MAX_SAMPLES) samples.removeAt(0)
    }

    companion object {
        const val MAX_SAMPLES = 150
    }
}
