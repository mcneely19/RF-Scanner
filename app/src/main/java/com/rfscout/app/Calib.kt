package com.rfscout.app

import org.json.JSONArray
import kotlin.math.log10

/**
 * Distance calibration. Stand at a known distance from a signal and tell the app; it nudges the
 * distance model for that kind of signal (Wi-Fi, BLE, Bluetooth Classic).
 *
 * Each point is stored as: x = -10*log10(distance), y = measured RSSI minus the model's default
 * level at 1 m. With one distance only the level offset is tuned. With two or more distances that
 * differ by at least 2x, the path-loss exponent is fitted as well (clamped to a sane range).
 * Cell towers are not calibrated: they are kilometres away.
 */
object Calib {
    private class Pt(val x: Double, val y: Double)

    private val pts = HashMap<Protocol, MutableList<Pt>>()
    private val offset = HashMap<Protocol, Double>()
    private val exponent = HashMap<Protocol, Double>()

    fun offsetDb(p: Protocol): Double = offset[p] ?: 0.0

    /** Fitted exponent, or null to use the default. */
    fun exponentFor(p: Protocol): Double? = exponent[p]

    fun isCalibrated(p: Protocol): Boolean = !pts[p].isNullOrEmpty()

    fun pointCount(p: Protocol): Int = pts[p]?.size ?: 0

    /** @return a short description of the result. */
    fun add(t: Track, distanceM: Double): String? {
        if (t.protocol == Protocol.CELL) return null
        val base = Rf.baseRef1m(t) ?: return null
        val list = pts.getOrPut(t.protocol) { ArrayList() }
        list.add(Pt(-10.0 * log10(distanceM), t.rssiSmooth - base))
        while (list.size > 8) list.removeAt(0)
        refit(t.protocol)
        return describe(t.protocol)
    }

    fun reset(p: Protocol) {
        pts.remove(p)
        offset.remove(p)
        exponent.remove(p)
    }

    fun resetAll() {
        for (p in Protocol.values()) reset(p)
    }

    fun describe(p: Protocol): String {
        if (!isCalibrated(p)) return "${p.label}: default model"
        val n = exponentFor(p) ?: Rf.defaultExponent(p)
        return "${p.label}: level %+.1f dB, exponent %.1f (%d point%s)".format(
            offsetDb(p), n, pointCount(p), if (pointCount(p) == 1) "" else "s"
        )
    }

    private fun refit(p: Protocol) {
        val list = pts[p]
        if (list.isNullOrEmpty()) {
            offset.remove(p); exponent.remove(p)
            return
        }
        val n0 = Rf.defaultExponent(p)
        val xMin = list.minOf { it.x }
        val xMax = list.maxOf { it.x }
        if (xMax - xMin >= 3.0 && list.size >= 2) {
            // least squares y = off + n*x
            val mx = list.map { it.x }.average()
            val my = list.map { it.y }.average()
            var sxx = 0.0
            var sxy = 0.0
            for (q in list) {
                sxx += (q.x - mx) * (q.x - mx)
                sxy += (q.x - mx) * (q.y - my)
            }
            val n = (sxy / sxx).coerceIn(1.6, 4.5)
            exponent[p] = n
            offset[p] = my - n * mx
        } else {
            exponent.remove(p)
            offset[p] = list.map { it.y - n0 * it.x }.average()
        }
    }

    fun toJson(): String {
        val root = org.json.JSONObject()
        for ((p, list) in pts) {
            val arr = JSONArray()
            for (q in list) arr.put(JSONArray().put(q.x).put(q.y))
            root.put(p.name, arr)
        }
        return root.toString()
    }

    fun fromJson(text: String) {
        pts.clear(); offset.clear(); exponent.clear()
        try {
            val root = org.json.JSONObject(text)
            for (p in Protocol.values()) {
                val arr = root.optJSONArray(p.name) ?: continue
                val list = ArrayList<Pt>()
                for (i in 0 until arr.length()) {
                    val a = arr.getJSONArray(i)
                    list.add(Pt(a.getDouble(0), a.getDouble(1)))
                }
                if (list.isNotEmpty()) {
                    pts[p] = list
                    refit(p)
                }
            }
        } catch (_: Exception) {
        }
    }
}
