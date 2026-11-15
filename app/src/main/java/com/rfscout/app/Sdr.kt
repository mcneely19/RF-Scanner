package com.rfscout.app

import android.os.Handler
import android.os.Looper
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * ADS-B aircraft from an RTL-SDR, read over the rtl_tcp protocol.
 *
 * rtl_tcp is the standard way to share an RTL-SDR: a small server owns the USB dongle and streams raw
 * 8-bit IQ over TCP. On Android a free SDR driver app can run that server on 127.0.0.1:1234; the same
 * code also works with rtl_tcp on a PC or Raspberry Pi on your network (set its address in Settings).
 * This class tunes to 1090 MHz, decodes aircraft broadcasts and reports each aircraft as a signal that
 * already carries its real position, so no walking is needed to place it.
 *
 * All callbacks arrive on the main thread, like the other scanners.
 */
class SdrAdsbScanner(
    private val host: String,
    private val port: Int,
    private val refPos: () -> Pair<Double, Double>?,
    private val out: (Reading) -> Unit,
    private val status: (String?) -> Unit
) : RfScanner {

    private val main = Handler(Looper.getMainLooper())
    private val tracker = AdsbTracker()
    @Volatile private var running = false
    @Volatile private var socket: Socket? = null
    private var thread: Thread? = null

    override fun start(): String? {
        if (running) return null
        running = true
        val t = Thread({ run() }, "rfscout-sdr")
        t.isDaemon = true
        thread = t
        t.start()
        return null                       // connecting happens in the background; failures arrive via status()
    }

    override fun stop() {
        running = false
        try {
            socket?.close()
        } catch (_: IOException) {
        }
        socket = null
        thread = null
    }

    private fun report(msg: String?) {
        main.post { status(msg) }
    }

    private fun run() {
        val s = Socket()
        try {
            s.connect(InetSocketAddress(host, port), 4000)
            s.soTimeout = 8000
            s.tcpNoDelay = true
            s.receiveBufferSize = 1 shl 20
        } catch (e: Exception) {
            try {
                s.close()
            } catch (_: IOException) {
            }
            if (running) report("SDR server not reachable at $host:$port. Start the SDR driver app (rtl_tcp) first.")
            running = false
            return
        }
        socket = s
        try {
            val input = s.getInputStream()
            val header = ByteArray(12)
            readFully(input, header)
            if (header[0] != 'R'.code.toByte() || header[1] != 'T'.code.toByte() ||
                header[2] != 'L'.code.toByte() || header[3] != '0'.code.toByte()
            ) {
                report("$host:$port is not an rtl_tcp server")
                return
            }
            val cmd = DataOutputStream(s.getOutputStream())
            command(cmd, 0x02, 2_000_000)      // sample rate: 2 MS/s, one bit = two samples
            command(cmd, 0x01, 1_090_000_000)  // centre frequency: 1090 MHz
            command(cmd, 0x03, 1)              // manual gain
            command(cmd, 0x04, GAIN_TENTHS_DB) // tuner gain, tenths of a dB (the server picks the nearest step)
            command(cmd, 0x08, 0)              // RTL AGC off
            report(null)

            val demod = AdsbDemod { frame -> onFrame(frame) }
            val buf = ByteArray(1 shl 16)
            var lastPrune = System.currentTimeMillis()
            while (running) {
                val n = input.read(buf)
                if (n < 0) throw IOException("closed")
                demod.feed(buf, n)
                val now = System.currentTimeMillis()
                if (now - lastPrune > 30_000) {
                    lastPrune = now
                    tracker.prune(now)
                }
            }
        } catch (e: Exception) {
            if (running) report("SDR connection lost (${e.message ?: "error"})")
        } finally {
            try {
                s.close()
            } catch (_: IOException) {
            }
            socket = null
            running = false
        }
    }

    private fun onFrame(frame: ModeSFrame) {
        val ref = refPos()
        tracker.refLat = ref?.first ?: Double.NaN
        tracker.refLon = ref?.second ?: Double.NaN
        val now = System.currentTimeMillis()
        val a = tracker.onFrame(frame, now)

        val detail = StringBuilder("ICAO ${a.hex()}")
        a.altFt?.let { detail.append(" · ").append(if (it >= 18_000) "FL${it / 100}" else "$it ft") }
        if (!a.speedKt.isNaN()) detail.append(" · ${a.speedKt.toInt()} kt")
        if (!a.headingDeg.isNaN()) detail.append(" · ${a.headingDeg.toInt()}°")
        a.vrateFpm?.let { if (Math.abs(it) >= 64) detail.append(if (it > 0) " · climbing" else " · descending") }
        detail.append(" · level is relative to full scale, not dBm")

        val r = Reading(
            key = "adsb:${a.hex()}",
            protocol = Protocol.ADSB,
            name = a.callsign ?: a.hex(),
            rssi = AdsbDemod.levelDb(a.level),
            freqMhz = 1090,
            detail = detail.toString(),
            lat = if (a.hasPos()) a.lat else null,
            lon = if (a.hasPos()) a.lon else null,
            timeMs = now
        )
        main.post { out(r) }
    }

    private fun command(o: DataOutputStream, cmd: Int, param: Int) {
        o.writeByte(cmd)
        o.writeInt(param)                  // rtl_tcp expects big-endian, which DataOutputStream writes
        o.flush()
    }

    private fun readFully(i: java.io.InputStream, b: ByteArray) {
        var off = 0
        while (off < b.size) {
            val n = i.read(b, off, b.size - off)
            if (n < 0) throw IOException("closed")
            off += n
        }
    }

    companion object {
        const val DEFAULT_HOST = "127.0.0.1"
        const val DEFAULT_PORT = 1234

        /** 49.6 dB is the top of the R820T tuner's range; lower it if strong nearby aircraft overload the receiver. */
        private const val GAIN_TENTHS_DB = 496
    }
}
