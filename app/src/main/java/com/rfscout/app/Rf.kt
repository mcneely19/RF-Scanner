package com.rfscout.app

import android.location.Location
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/** Signal-strength -> distance / direction maths. All estimates are rough; indoors they can be off by 2x or more. */
object Rf {
    private const val EARTH_R = 6_371_000.0

    fun defaultExponent(p: Protocol): Double = when (p) {
        Protocol.WIFI -> 3.0
        Protocol.CELL -> 3.2
        else -> 2.5
    }

    /** Path-loss exponent in use: the calibrated one if you set it, else the default. */
    fun pathLossExponent(p: Protocol): Double = Calib.exponentFor(p) ?: defaultExponent(p)

    /** Expected RSSI at 1 m including any calibration, or null if there is no sensible model (cell towers). */
    fun refRssi1m(t: Track): Double? = baseRef1m(t)?.plus(Calib.offsetDb(t.protocol))

    /** Uncalibrated expected RSSI at 1 m. */
    fun baseRef1m(t: Track): Double? = when (t.protocol) {
        Protocol.CELL -> null
        // assume ~18 dBm EIRP and free-space loss at 1 m: FSPL = 20log10(f_MHz) - 27.55
        Protocol.WIFI -> if (t.freqMhz > 0) 18.0 - (20 * log10(t.freqMhz.toDouble()) - 27.55) else null
        // BLE / classic: advertised TX power minus ~41 dB at 1 m, else a typical -59 dBm
        else -> t.txPower?.let { it - 41.0 } ?: -59.0
    }

    /** One-shot range from a single RSSI value (log-distance path loss model). */
    fun modelDistance(t: Track): Double? {
        val ref = refRssi1m(t) ?: return null
        val d = 10.0.pow((ref - t.rssiSmooth) / (10 * pathLossExponent(t.protocol)))
        return d.coerceIn(0.3, 2000.0)
    }

    /** Distance in metres and initial bearing in degrees (0..360, clockwise from true north). */
    fun distanceBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Pair<Double, Double> {
        val out = FloatArray(3)
        Location.distanceBetween(lat1, lon1, lat2, lon2, out)
        var b = out[1].toDouble()
        if (b < 0) b += 360.0
        return out[0].toDouble() to b
    }

    fun meters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double =
        distanceBearing(lat1, lon1, lat2, lon2).first

    /**
     * Estimate where the transmitter is from RSSI samples taken along a walked path.
     *
     * Model: rssi_i = A - 10*n*log10(d_i). For a candidate position the best A is the mean of
     * (rssi_i + 10*n*log10(d_i)), and the cost is the variance of that quantity (A itself is left
     * free, because the real transmit power and path loss are unknown; a prior on A made the
     * bearing worse in simulation). A coarse-to-fine grid search finds the best position.
     *
     * The bearing is the dependable output; the distance is rougher. Needs >= 6 samples and a path
     * that spans a few metres. A straight path gives a mirror-image ambiguity, which is reported
     * through Fix.ambiguous.
     */
    fun locate(t: Track): Fix? {
        if (t.protocol == Protocol.CELL) return null
        val s = t.samples
        val n = s.size
        if (n < 6) return null
        val nExp = pathLossExponent(t.protocol)

        val lat0 = s[n - 1].lat
        val lon0 = s[n - 1].lon
        val cosLat = cos(Math.toRadians(lat0))
        val xs = DoubleArray(n)
        val ys = DoubleArray(n)
        val rs = DoubleArray(n)
        for (i in 0 until n) {
            xs[i] = Math.toRadians(s[i].lon - lon0) * EARTH_R * cosLat
            ys[i] = Math.toRadians(s[i].lat - lat0) * EARTH_R
            rs[i] = s[i].rssi
        }

        val mx = xs.average()
        val my = ys.average()
        var sxx = 0.0
        var syy = 0.0
        var sxy = 0.0
        for (i in 0 until n) {
            val dx = xs[i] - mx
            val dy = ys[i] - my
            sxx += dx * dx; syy += dy * dy; sxy += dx * dy
        }
        sxx /= n; syy /= n; sxy /= n
        val tr = sxx + syy
        val det = sxx * syy - sxy * sxy
        val disc = sqrt(max(0.0, tr * tr / 4 - det))
        val major = sqrt(max(0.0, tr / 2 + disc))
        val minor = sqrt(max(0.0, tr / 2 - disc))
        if (major < 4.0) return null   // hasn't moved enough

        val stats = DoubleArray(2)     // [mean, variance] of e_i for the last fit() call
        fun fit(px: Double, py: Double) {
            var sum = 0.0
            var sumSq = 0.0
            for (i in 0 until n) {
                val dx = xs[i] - px
                val dy = ys[i] - py
                val d = max(sqrt(dx * dx + dy * dy), 1.0)
                val e = rs[i] + 10.0 * nExp * log10(d)
                sum += e
                sumSq += e * e
            }
            val mean = sum / n
            stats[0] = mean
            stats[1] = max(0.0, sumSq / n - mean * mean)
        }

        var cx = mx
        var cy = my
        var bx = mx
        var by = my
        var best = Double.MAX_VALUE
        var step = max(80.0, major * 6) / 8.0
        repeat(6) {
            for (i in -8..8) {
                for (j in -8..8) {
                    val px = cx + i * step
                    val py = cy + j * step
                    fit(px, py)
                    val cost = stats[1]
                    if (cost < best) {
                        best = cost; bx = px; by = py
                    }
                }
            }
            cx = bx; cy = by
            step /= 2.5
        }

        fit(bx, by)
        val rms = sqrt(stats[1])
        if (rms > 12.0) return null    // data too noisy to trust

        val lat = lat0 + Math.toDegrees(by / EARTH_R)
        val lon = lon0 + Math.toDegrees(bx / (EARTH_R * cosLat))
        return Fix(lat, lon, rms, minor < 2.5)
    }
}

/**
 * The mirror-image position of an ambiguous fix: the fix reflected across the line of the walked path.
 * A nearly straight path cannot tell which side of it the transmitter is on, so both are possible.
 */
fun mirrorFix(t: Track, f: Fix): Pair<Double, Double>? {
    val s = t.samples
    if (s.size < 3) return null
    val lat0 = f.lat
    val lon0 = f.lon
    val cosLat = cos(Math.toRadians(lat0))
    val k = 6_371_000.0
    val xs = DoubleArray(s.size) { Math.toRadians(s[it].lon - lon0) * k * cosLat }
    val ys = DoubleArray(s.size) { Math.toRadians(s[it].lat - lat0) * k }
    val mx = xs.average()
    val my = ys.average()
    var sxx = 0.0
    var syy = 0.0
    var sxy = 0.0
    for (i in xs.indices) {
        val dx = xs[i] - mx
        val dy = ys[i] - my
        sxx += dx * dx; syy += dy * dy; sxy += dx * dy
    }
    if (sxx + syy < 1e-6) return null
    val ang = 0.5 * kotlin.math.atan2(2 * sxy, sxx - syy)     // direction of the path
    val ux = cos(ang)
    val uy = kotlin.math.sin(ang)
    // fix sits at (0,0) here; reflect it across the line through (mx,my) with direction (ux,uy)
    val px = 0.0 - mx
    val py = 0.0 - my
    val along = px * ux + py * uy
    val rx = 2 * along * ux - px + mx
    val ry = 2 * along * uy - py + my
    return (lat0 + Math.toDegrees(ry / k)) to (lon0 + Math.toDegrees(rx / (k * cosLat)))
}

fun rssiColor(rssi: Double): Int = when {
    rssi > -55 -> 0xFF4CAF50.toInt()
    rssi > -70 -> 0xFFCDDC39.toInt()
    rssi > -85 -> 0xFFFF9800.toInt()
    else -> 0xFFF44336.toInt()
}

fun signalBars(rssi: Double): String = when {
    rssi > -55 -> "▂▄▆█"
    rssi > -67 -> "▂▄▆▁"
    rssi > -80 -> "▂▄▁▁"
    rssi > -90 -> "▂▁▁▁"
    else -> "▁▁▁▁"
}

fun fmtDist(m: Double): String = when {
    m < 10 -> "%.1f m".format(m)
    m < 1000 -> "%.0f m".format(m)
    else -> "%.1f km".format(m / 1000)
}

/** Normalise an angle difference to -180..180. */
fun relAngle(deg: Double): Double {
    var a = deg % 360.0
    if (a > 180) a -= 360.0
    if (a < -180) a += 360.0
    return if (abs(a) < 1e-9) 0.0 else a
}
