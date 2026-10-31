package com.rfscout.app

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class LogRow(
    val t: Long, val protocol: String, val key: String, val name: String, val rssi: Int,
    val freqMhz: Int, val lat: Double, val lon: Double, val acc: Float, val distM: Double?
)

class PathPt(val t: Long, val lat: Double, val lon: Double, val acc: Float)

/** Everything recorded since the last Clear, for export. Touched on the main thread only. */
object SessionLog {
    private const val MAX_ROWS = 60_000
    private const val MAX_PATH = 20_000
    val rows = ArrayDeque<LogRow>()
    val path = ArrayDeque<PathPt>()
    private val lastRow = HashMap<String, Long>()
    private var lastPath = 0L

    fun clear() {
        rows.clear(); path.clear(); lastRow.clear(); lastPath = 0L
    }

    fun isEmpty() = rows.isEmpty() && path.isEmpty()

    /** One row per signal at most every 2 s, with the phone position when known. */
    fun addReading(t: Track, lat: Double?, lon: Double?, acc: Float) {
        val now = t.lastSeenMs
        val prev = lastRow[t.key]
        if (prev != null && now - prev < 2000) return
        lastRow[t.key] = now
        rows.addLast(
            LogRow(
                now, t.protocol.label, t.key, t.name, t.rssi, t.freqMhz,
                lat ?: Double.NaN, lon ?: Double.NaN, acc, Rf.modelDistance(t)
            )
        )
        if (rows.size > MAX_ROWS) rows.removeFirst()
    }

    fun addPath(lat: Double, lon: Double, acc: Float) {
        val now = System.currentTimeMillis()
        if (now - lastPath < 1000) return
        lastPath = now
        path.addLast(PathPt(now, lat, lon, acc))
        if (path.size > MAX_PATH) path.removeFirst()
    }
}

object Export {
    private fun iso(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))

    private fun csv(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

    private fun xml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun num(v: Double): String = if (v.isNaN()) "" else String.format(Locale.US, "%.6f", v)

    fun readingsCsv(): String {
        val sb = StringBuilder("time_utc,protocol,id,name,rssi_dbm,freq_mhz,phone_lat,phone_lon,gps_accuracy_m,model_distance_m\n")
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        for (r in SessionLog.rows) {
            sb.append(f.format(Date(r.t))).append(',').append(csv(r.protocol)).append(',').append(csv(r.key)).append(',')
                .append(csv(r.name)).append(',').append(r.rssi).append(',').append(r.freqMhz).append(',')
                .append(num(r.lat)).append(',').append(num(r.lon)).append(',')
                .append(String.format(Locale.US, "%.0f", r.acc)).append(',')
                .append(r.distM?.let { String.format(Locale.US, "%.1f", it) } ?: "").append('\n')
        }
        return sb.toString()
    }

    fun signalsCsv(tracks: Collection<Track>): String {
        val sb = StringBuilder(
            "protocol,id,name,vendor,avg_rssi_dbm,model_distance_m,position_source,position_lat,position_lon," +
                "fit_rms_db,tower_range_m,samples,first_seen_utc,last_seen_utc,tracker_kind,watchlist,cell_details\n"
        )
        for (t in tracks.sortedByDescending { it.rssiSmooth }) {
            val fx = t.fix
            val src = when {
                fx == null -> ""
                fx.tower -> "tower database"
                else -> "walk fit"
            }
            val cell = t.cell?.let { "${it.radio} ${it.mcc}-${it.mnc} area ${it.area} cell ${it.cid}" } ?: ""
            sb.append(csv(t.protocol.label)).append(',').append(csv(t.key)).append(',').append(csv(t.name)).append(',')
                .append(csv(Vendors.shortLine(t) ?: "")).append(',')
                .append(String.format(Locale.US, "%.0f", t.rssiSmooth)).append(',')
                .append(Rf.modelDistance(t)?.let { String.format(Locale.US, "%.1f", it) } ?: "").append(',')
                .append(src).append(',').append(num(fx?.lat ?: Double.NaN)).append(',').append(num(fx?.lon ?: Double.NaN)).append(',')
                .append(if (fx != null && !fx.tower) String.format(Locale.US, "%.1f", fx.rmsDb) else "").append(',')
                .append(if (fx != null && fx.tower) t.towerRangeM.toString() else "").append(',')
                .append(t.samples.size).append(',').append(iso(t.firstSeenMs)).append(',').append(iso(t.lastSeenMs)).append(',')
                .append(csv(t.trackerKind ?: "")).append(',').append(csv(t.watch?.label ?: "")).append(',')
                .append(csv(cell)).append('\n')
        }
        return sb.toString()
    }

    fun gpx(tracks: Collection<Track>, sightings: Collection<Sighting>): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"RF Scout\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        for (t in tracks) {
            val fx = t.fix ?: continue
            sb.append(String.format(Locale.US, "<wpt lat=\"%.6f\" lon=\"%.6f\">\n", fx.lat, fx.lon))
            sb.append("  <name>").append(xml("${t.name} [${t.protocol.tag}]")).append("</name>\n")
            val src = if (fx.tower) "tower database (range ${t.towerRangeM} m)" else "walk fit, ±%.1f dB".format(Locale.US, fx.rmsDb)
            sb.append("  <desc>").append(xml("Estimated position, $src. Average ${"%.0f".format(t.rssiSmooth)} dBm."))
                .append("</desc>\n")
            sb.append("</wpt>\n")
        }
        for (s in sightings) {
            sb.append(String.format(Locale.US, "<wpt lat=\"%.6f\" lon=\"%.6f\">\n", s.lat, s.lon))
            sb.append("  <name>").append(xml("Possible ALPR: ${s.name}")).append("</name>\n")
            sb.append("  <desc>").append(xml("${s.label} (${s.confidence} confidence), position from ${s.source}")).append("</desc>\n")
            sb.append("</wpt>\n")
        }
        if (SessionLog.path.isNotEmpty()) {
            sb.append("<trk>\n  <name>RF Scout walk</name>\n  <trkseg>\n")
            for (p in SessionLog.path) {
                sb.append(String.format(Locale.US, "    <trkpt lat=\"%.6f\" lon=\"%.6f\"><time>%s</time></trkpt>\n", p.lat, p.lon, iso(p.t)))
            }
            sb.append("  </trkseg>\n</trk>\n")
        }
        sb.append("</gpx>\n")
        return sb.toString()
    }

    /** Write the session files and open the share sheet. @return null on success, else a reason. */
    fun share(a: Activity, tracks: Collection<Track>, sightings: Collection<Sighting>): String? {
        return try {
            val dir = File(a.cacheDir, "exports").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val files = listOf(
                File(dir, "rfscout-$stamp-readings.csv").apply { writeText(readingsCsv()) },
                File(dir, "rfscout-$stamp-signals.csv").apply { writeText(signalsCsv(tracks)) },
                File(dir, "rfscout-$stamp-session.gpx").apply { writeText(gpx(tracks, sightings)) }
            )
            val uris = ArrayList(files.map { FileProvider.getUriForFile(a, "${a.packageName}.files", it) })
            val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                putExtra(Intent.EXTRA_SUBJECT, "RF Scout session $stamp")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = ClipData.newRawUri("RF Scout session", uris[0]).also { c ->
                    for (i in 1 until uris.size) c.addItem(ClipData.Item(uris[i]))
                }
            }
            a.startActivity(Intent.createChooser(send, "Share session").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            null
        } catch (ex: Exception) {
            ex.message ?: "export failed"
        }
    }
}
