package com.rfscout.app

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/*
 * ADS-B (Mode S extended squitter, 1090 MHz) decoding. Pure Kotlin, no Android classes, so it can be
 * tested on a desktop JVM. Input is 8-bit IQ from an RTL-SDR at 2 MS/s (as served by rtl_tcp).
 */

/** One CRC-valid 112-bit downlink message and how strong it was. */
class ModeSFrame(val bytes: ByteArray, val level: Int)

object ModeS {
    const val LONG_BITS = 112
    const val PREAMBLE_SAMPLES = 16          // 8 microseconds at 2 MS/s
    const val FRAME_SAMPLES = PREAMBLE_SAMPLES + LONG_BITS * 2

    private const val GENERATOR = 0xFFF409   // Mode S 24-bit CRC polynomial (x^24 term implied)

    /** True if the 112-bit message's parity field matches the CRC of its first 88 bits. */
    fun crcOk(m: ByteArray): Boolean {
        var r = 0
        for (i in 0 until 88) {
            val bit = (m[i shr 3].toInt() shr (7 - (i and 7))) and 1
            val fb = ((r shr 23) and 1) xor bit
            r = (r shl 1) and 0xFFFFFF
            if (fb == 1) r = r xor GENERATOR
        }
        val parity = ((m[11].toInt() and 0xFF) shl 16) or ((m[12].toInt() and 0xFF) shl 8) or (m[13].toInt() and 0xFF)
        return r == parity
    }

    fun df(m: ByteArray): Int = (m[0].toInt() and 0xFF) shr 3

    fun icao(m: ByteArray): Int =
        ((m[1].toInt() and 0xFF) shl 16) or ((m[2].toInt() and 0xFF) shl 8) or (m[3].toInt() and 0xFF)

    fun typeCode(m: ByteArray): Int = (m[4].toInt() and 0xFF) shr 3
}

/**
 * Turns a stream of unsigned 8-bit IQ pairs (2 MS/s) into CRC-valid DF17/DF18 frames.
 * Feed arbitrary-sized chunks; frames that straddle a chunk boundary are handled.
 */
class AdsbDemod(private val onFrame: (ModeSFrame) -> Unit) {
    private var mag = IntArray(0)
    private var carry = 0                     // samples kept from the previous chunk
    private var oddByte = 0                   // a lone I byte waiting for its Q byte
    private var haveOdd = false

    /** @param len number of valid bytes in [data]; any length, an odd trailing byte is kept for the next call */
    fun feed(data: ByteArray, len: Int) {
        val pairs = (len + (if (haveOdd) 1 else 0)) / 2
        if (mag.size < carry + pairs) mag = mag.copyOf(carry + pairs + 4096)
        var w = carry
        var p = 0
        if (haveOdd && len > 0) {
            val q = data[0].toInt() and 0xFF
            mag[w++] = LUT[abs(oddByte - 127) * 129 + abs(q - 127)]
            haveOdd = false
            p = 1
        }
        while (p + 1 < len) {
            val i = data[p].toInt() and 0xFF
            val q = data[p + 1].toInt() and 0xFF
            mag[w++] = LUT[abs(i - 127) * 129 + abs(q - 127)]
            p += 2
        }
        if (p < len) {
            oddByte = data[p].toInt() and 0xFF
            haveOdd = true
        }
        detect(w)
    }

    private fun detect(count: Int) {
        val m = mag
        val last = count - ModeS.FRAME_SAMPLES
        var j = 0
        val bits = IntArray(ModeS.LONG_BITS)
        while (j < last) {
            // shape of the 8 us preamble: pulses at 0, 1, 3.5 and 4.5 us (samples 0, 2, 7, 9)
            if (!(m[j] > m[j + 1] && m[j + 1] < m[j + 2] && m[j + 2] > m[j + 3] && m[j + 3] < m[j] &&
                    m[j + 4] < m[j] && m[j + 5] < m[j] && m[j + 6] < m[j] &&
                    m[j + 7] > m[j + 8] && m[j + 8] < m[j + 9] && m[j + 9] > m[j + 6])
            ) {
                j++
                continue
            }
            val high = (m[j] + m[j + 2] + m[j + 7] + m[j + 9]) / 6
            if (m[j + 4] >= high || m[j + 5] >= high ||
                m[j + 11] >= high || m[j + 12] >= high || m[j + 13] >= high || m[j + 14] >= high
            ) {
                j++
                continue
            }

            // one bit = two samples: pulse in the first half is a 1, in the second half a 0
            var bad = false
            var df = 0
            var i = 0
            while (i < ModeS.LONG_BITS * 2) {
                val lo = m[j + ModeS.PREAMBLE_SAMPLES + i]
                val hi = m[j + ModeS.PREAMBLE_SAMPLES + i + 1]
                val delta = abs(lo - hi)
                val b = i shr 1
                bits[b] = when {
                    b > 0 && delta < 256 -> bits[b - 1]
                    lo == hi -> 2
                    lo > hi -> 1
                    else -> 0
                }
                if (bits[b] == 2) { bad = true; break }
                if (b == 4) {                     // first 5 bits are the downlink format
                    df = (bits[0] shl 4) or (bits[1] shl 3) or (bits[2] shl 2) or (bits[3] shl 1) or bits[4]
                    if (df != 17 && df != 18) { bad = true; break }
                }
                i += 2
            }
            if (bad) {
                j++
                continue
            }

            val msg = ByteArray(ModeS.LONG_BITS / 8)
            for (b in 0 until ModeS.LONG_BITS) {
                if (bits[b] == 1) msg[b shr 3] = (msg[b shr 3].toInt() or (0x80 shr (b and 7))).toByte()
            }
            if (ModeS.crcOk(msg)) {
                onFrame(ModeSFrame(msg, high * 6 / 4))
                j += ModeS.FRAME_SAMPLES          // skip over the frame we just decoded
            } else {
                j++
            }
        }
        // keep the tail so a frame that began near the end can complete with the next chunk
        val keep = (count - j).coerceIn(0, count).coerceAtMost(ModeS.FRAME_SAMPLES)
        System.arraycopy(m, count - keep, m, 0, keep)
        carry = keep
    }

    companion object {
        /** magnitude of an IQ sample from |I-127| and |Q-127|, scaled like dump1090 */
        private val LUT = IntArray(129 * 129).also {
            for (i in 0 until 129) for (q in 0 until 129) {
                it[i * 129 + q] = Math.round(sqrt((i * i + q * q).toDouble()) * 360.0).toInt()
            }
        }

        /** Level in dB relative to full scale (always negative or zero). */
        fun levelDb(level: Int): Int {
            val full = 129.0 * 1.4142 * 360.0
            return (20.0 * log10(max(level, 1) / full)).toInt()
        }
    }
}

/** What we currently know about one aircraft. NaN / null mean "not received yet". */
class Aircraft(val icao: Int) {
    var callsign: String? = null
    var altFt: Int? = null
    var lat = Double.NaN
    var lon = Double.NaN
    var speedKt = Double.NaN
    var headingDeg = Double.NaN
    var vrateFpm: Int? = null
    var lastMs = 0L
    var level = 0

    internal var evenLat = 0
    internal var evenLon = 0
    internal var evenMs = 0L
    internal var oddLat = 0
    internal var oddLon = 0
    internal var oddMs = 0L

    fun hex(): String = "%06X".format(icao)
    fun hasPos(): Boolean = !lat.isNaN() && !lon.isNaN()
}

/** Keeps one [Aircraft] per ICAO address and decodes identification, position, altitude and velocity. */
class AdsbTracker {
    val aircraft = HashMap<Int, Aircraft>()

    /** Where the receiver is (the phone). Used to place an aircraft from a single frame and to drop bogus positions. */
    var refLat = Double.NaN
    var refLon = Double.NaN

    fun onFrame(f: ModeSFrame, nowMs: Long): Aircraft {
        val m = f.bytes
        val a = aircraft.getOrPut(ModeS.icao(m)) { Aircraft(ModeS.icao(m)) }
        a.lastMs = nowMs
        a.level = f.level
        val tc = ModeS.typeCode(m)
        when {
            tc in 1..4 -> a.callsign = callsign(m)
            tc in 9..18 || tc in 20..22 -> airbornePosition(a, m, nowMs, tc in 9..18)
            tc == 19 -> velocity(a, m)
        }
        return a
    }

    /** Forget aircraft not heard from for [maxAgeMs]. */
    fun prune(nowMs: Long, maxAgeMs: Long = 120_000) {
        aircraft.values.removeAll { nowMs - it.lastMs > maxAgeMs }
    }

    private fun b(m: ByteArray, i: Int) = m[i].toInt() and 0xFF

    private fun callsign(m: ByteArray): String? {
        val c = intArrayOf(
            b(m, 5) shr 2,
            ((b(m, 5) and 3) shl 4) or (b(m, 6) shr 4),
            ((b(m, 6) and 15) shl 2) or (b(m, 7) shr 6),
            b(m, 7) and 63,
            b(m, 8) shr 2,
            ((b(m, 8) and 3) shl 4) or (b(m, 9) shr 4),
            ((b(m, 9) and 15) shl 2) or (b(m, 10) shr 6),
            b(m, 10) and 63
        )
        val sb = StringBuilder()
        for (x in c) sb.append(CHARSET[x])
        val s = sb.toString().replace('?', ' ').trim()
        return s.ifEmpty { null }
    }

    private fun airbornePosition(a: Aircraft, m: ByteArray, nowMs: Long, baro: Boolean) {
        if (baro) {
            val q = b(m, 5) and 1
            if (q == 1) {
                val n = ((b(m, 5) shr 1) shl 4) or ((b(m, 6) and 0xF0) shr 4)
                a.altFt = n * 25 - 1000
            }
        }
        val odd = (b(m, 6) shr 2) and 1
        val latCpr = ((b(m, 6) and 3) shl 15) or (b(m, 7) shl 7) or (b(m, 8) shr 1)
        val lonCpr = ((b(m, 8) and 1) shl 16) or (b(m, 9) shl 8) or b(m, 10)
        if (odd == 1) {
            a.oddLat = latCpr; a.oddLon = lonCpr; a.oddMs = nowMs
        } else {
            a.evenLat = latCpr; a.evenLon = lonCpr; a.evenMs = nowMs
        }

        var pos: DoubleArray? = null
        var fromPair = false
        if (a.evenMs > 0 && a.oddMs > 0 && abs(a.evenMs - a.oddMs) <= 10_000) {
            pos = Cpr.global(a.evenLat, a.evenLon, a.oddLat, a.oddLon, odd == 1)
            fromPair = pos != null
        }
        // a single frame is only unambiguous near the receiver; a pair-based fix replaces it when it arrives
        if (pos == null && !refLat.isNaN() && !refLon.isNaN()) {
            pos = Cpr.local(latCpr, lonCpr, odd == 1, refLat, refLon)
        }
        if (pos != null && !refLat.isNaN() && !refLon.isNaN() &&
            distKm(pos[0], pos[1], refLat, refLon) > MAX_RANGE_KM
        ) {
            pos = null                          // farther than a 1090 MHz signal can plausibly travel
            if (fromPair) {                     // the reliable decode says it is far away: drop any earlier guess
                a.lat = Double.NaN
                a.lon = Double.NaN
            }
        }
        if (pos != null) {
            a.lat = pos[0]
            a.lon = pos[1]
        }
    }

    private fun velocity(a: Aircraft, m: ByteArray) {
        val sub = b(m, 4) and 7
        if (sub == 1 || sub == 2) {
            val ewDir = (b(m, 5) and 4) shr 2
            val ewRaw = ((b(m, 5) and 3) shl 8) or b(m, 6)
            val nsDir = (b(m, 7) and 0x80) shr 7
            val nsRaw = ((b(m, 7) and 0x7F) shl 3) or ((b(m, 8) and 0xE0) shr 5)
            if (ewRaw != 0 && nsRaw != 0) {
                val mult = if (sub == 2) 4 else 1
                val ew = (ewRaw - 1) * mult * (if (ewDir == 1) -1 else 1)
                val ns = (nsRaw - 1) * mult * (if (nsDir == 1) -1 else 1)
                a.speedKt = sqrt((ew * ew + ns * ns).toDouble())
                var h = Math.toDegrees(atan2(ew.toDouble(), ns.toDouble()))
                if (h < 0) h += 360.0
                a.headingDeg = h
            }
            val vrSign = (b(m, 8) and 0x08) shr 3
            val vrRaw = ((b(m, 8) and 7) shl 6) or (b(m, 9) shr 2)
            if (vrRaw != 0) a.vrateFpm = (vrRaw - 1) * 64 * (if (vrSign == 1) -1 else 1)
        }
    }

    companion object {
        const val MAX_RANGE_KM = 500.0
        private const val CHARSET = "?ABCDEFGHIJKLMNOPQRSTUVWXYZ????? ???????????????0123456789??????"

        fun distKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6371.0
            val p1 = Math.toRadians(lat1)
            val p2 = Math.toRadians(lat2)
            val dp = p2 - p1
            val dl = Math.toRadians(lon2 - lon1)
            val h = Math.sin(dp / 2) * Math.sin(dp / 2) + cos(p1) * cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2)
            return 2 * r * Math.asin(sqrt(h))
        }
    }
}

/** Compact Position Reporting: how ADS-B packs latitude/longitude into 17 bits per axis. */
object Cpr {
    private fun mod(x: Double, y: Double): Double = x - y * floor(x / y)

    /** Number of longitude zones at a latitude (1..59). */
    fun nl(latIn: Double): Int {
        val lat = abs(latIn)
        if (lat == 0.0) return 59
        if (lat == 87.0) return 2
        if (lat > 87.0) return 1
        val nz = 15.0
        val a = 1 - cos(Math.PI / (2 * nz))
        val b = cos(Math.PI / 180.0 * lat)
        return floor(2 * Math.PI / acos(1 - a / (b * b))).toInt()
    }

    /** Latitude and longitude from one even and one odd frame, or null if they straddle a zone boundary. */
    fun global(evenLat: Int, evenLon: Int, oddLat: Int, oddLon: Int, oddNewest: Boolean): DoubleArray? {
        val lat0 = evenLat.toDouble()
        val lat1 = oddLat.toDouble()
        val lon0 = evenLon.toDouble()
        val lon1 = oddLon.toDouble()
        val j = floor(((59 * lat0 - 60 * lat1) / 131072.0) + 0.5)
        var rlat0 = (360.0 / 60) * (mod(j, 60.0) + lat0 / 131072.0)
        var rlat1 = (360.0 / 59) * (mod(j, 59.0) + lat1 / 131072.0)
        if (rlat0 >= 270) rlat0 -= 360
        if (rlat1 >= 270) rlat1 -= 360
        if (rlat0 < -90 || rlat0 > 90 || rlat1 < -90 || rlat1 > 90) return null
        if (nl(rlat0) != nl(rlat1)) return null

        val lat: Double
        var lon: Double
        if (oddNewest) {
            val ni = max(nl(rlat1) - 1, 1)
            val mm = floor(((lon0 * (nl(rlat1) - 1)) - (lon1 * nl(rlat1))) / 131072.0 + 0.5)
            lon = (360.0 / ni) * (mod(mm, ni.toDouble()) + lon1 / 131072.0)
            lat = rlat1
        } else {
            val ni = max(nl(rlat0), 1)
            val mm = floor(((lon0 * (nl(rlat0) - 1)) - (lon1 * nl(rlat0))) / 131072.0 + 0.5)
            lon = (360.0 / ni) * (mod(mm, ni.toDouble()) + lon0 / 131072.0)
            lat = rlat0
        }
        if (lon > 180) lon -= 360
        return doubleArrayOf(lat, lon)
    }

    /** Latitude and longitude from a single frame, using a nearby reference point (within about 180 NM). */
    fun local(latCpr: Int, lonCpr: Int, odd: Boolean, refLat: Double, refLon: Double): DoubleArray {
        val i = if (odd) 1 else 0
        val dLat = 360.0 / (60 - i)
        val latF = latCpr / 131072.0
        val j = floor(refLat / dLat) + floor(0.5 + mod(refLat, dLat) / dLat - latF)
        val lat = dLat * (j + latF)
        val ni = max(nl(lat) - i, 1)
        val dLon = 360.0 / ni
        val lonF = lonCpr / 131072.0
        val mm = floor(refLon / dLon) + floor(0.5 + mod(refLon, dLon) / dLon - lonF)
        var lon = dLon * (mm + lonF)
        if (lon > 180) lon -= 360
        return doubleArrayOf(lat, lon)
    }
}
