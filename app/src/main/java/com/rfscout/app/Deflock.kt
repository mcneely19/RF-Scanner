package com.rfscout.app

import android.content.Context
import android.os.Handler
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.floor

/** One mapped license-plate-reader camera from OpenStreetMap (the data the DeFlock project maintains). */
class Cam(
    val type: String,           // node / way / relation
    val id: Long,
    val lat: Double,
    val lon: Double,
    val dirDeg: Float?,         // direction the camera faces, clockwise from north, if tagged
    val manufacturer: String,
    val operator: String,
    val tags: Map<String, String>
)

/**
 * Loads ALPR camera locations from OpenStreetMap through the public Overpass API and keeps them.
 *
 * The world is split into 0.05 degree cells (about 5 km). Every downloaded cell is saved in the
 * app's permanent storage and loaded again at startup, so saved cameras show immediately and work
 * offline. A cell older than a week is refreshed in the background when you view it, and the saved
 * copy is only replaced when a new download succeeds. Requests run one at a time to stay polite to
 * the shared Overpass servers. All state is touched on the main thread; only network and disk work
 * runs in the background.
 */
class Deflock(ctx: Context, private val handler: Handler, private val onUpdate: () -> Unit) {

    var camList: List<Cam> = emptyList()
        private set

    private val dir = File(ctx.filesDir, "deflock").apply { mkdirs() }
    private val exec = Executors.newSingleThreadExecutor()
    private val loaded = HashMap<String, Long>()          // cell key -> when its data was downloaded
    private val cellCams = HashMap<String, List<Cam>>()
    private val pending = HashSet<String>()
    private val failedAt = HashMap<String, Long>()
    private var zoomedOut = false
    private var lastError: String? = null

    fun setZoomedOut(v: Boolean) {
        zoomedOut = v
    }

    fun statusLine(): String {
        val err = lastError
        val newest = loaded.values.maxOrNull()
        val age = if (newest != null) " · last updated ${ageText(System.currentTimeMillis() - newest)} ago" else ""
        return when {
            zoomedOut -> "ALPR layer: zoom in to load cameras (${camList.size} saved$age)"
            pending.isNotEmpty() -> "ALPR layer: loading... (${camList.size} cameras saved)"
            err != null -> "ALPR layer: $err, showing ${camList.size} saved cameras$age"
            else -> "ALPR layer: ${camList.size} cameras$age"
        }
    }

    private fun ageText(ms: Long): String {
        val minutes = ms / 60_000
        return when {
            minutes < 60 -> "${minutes.coerceAtLeast(0)} min"
            minutes < 48 * 60 -> "${minutes / 60} h"
            else -> "${minutes / (24 * 60)} days"
        }
    }

    /** Read every saved block from storage. Call once at startup. */
    fun loadStored() {
        exec.execute {
            val files = dir.listFiles { f -> f.name.endsWith(".json") } ?: emptyArray()
            for (f in files) {
                val k = f.name.removeSuffix(".json")
                val parsed = try {
                    parse(f.readText())
                } catch (_: Exception) {
                    null
                }
                val stamp = f.lastModified()
                if (parsed != null) handler.post { install(k, parsed, stamp, false) }
            }
        }
    }

    /** Load every cell that overlaps the given box. Refuses areas that are too large. */
    fun ensureBounds(south: Double, west: Double, north: Double, east: Double) {
        val i0 = cellOf(south)
        val i1 = cellOf(north)
        val j0 = cellOf(west)
        val j1 = cellOf(east)
        val count = (i1 - i0 + 1).toLong() * (j1 - j0 + 1).toLong()
        if (count > MAX_CELLS) {
            zoomedOut = true
            return
        }
        zoomedOut = false
        for (i in i0..i1) for (j in j0..j1) request(i, j)
    }

    fun ensureAround(lat: Double, lon: Double) = request(cellOf(lat), cellOf(lon))

    fun isLoaded(lat: Double, lon: Double): Boolean = loaded.containsKey(key(cellOf(lat), cellOf(lon)))

    /** Closest mapped camera and its distance in metres, or null if none are loaded. */
    fun nearest(lat: Double, lon: Double): Pair<Cam, Double>? {
        var best: Cam? = null
        var bestD = Double.MAX_VALUE
        for (c in camList) {
            val d = Rf.meters(lat, lon, c.lat, c.lon)
            if (d < bestD) {
                bestD = d
                best = c
            }
        }
        val b = best ?: return null
        return b to bestD
    }

    // ------------------------------------------------------------------ loading

    private fun cellOf(v: Double): Int = floor(v / CELL).toInt()

    private fun key(i: Int, j: Int) = "${i}_$j"

    private fun request(i: Int, j: Int) {
        val k = key(i, j)
        val have = loaded[k]
        if (have != null && System.currentTimeMillis() - have < MAX_AGE_MS) return
        if (pending.contains(k)) return
        val failed = failedAt[k]
        if (failed != null && System.currentTimeMillis() - failed < RETRY_MS) return
        pending.add(k)

        val south = i * CELL
        val west = j * CELL
        val north = south + CELL
        val east = west + CELL
        exec.execute {
            var parsed: List<Cam>? = null
            var problem: String? = null
            try {
                val text = fetch(south, west, north, east)
                parsed = parse(text)
                try {
                    File(dir, "$k.json").writeText(text)
                } catch (_: Exception) {
                }
            } catch (ex: Exception) {
                problem = ex.message ?: "network error"
            }
            val result = parsed
            val err = problem
            handler.post { finish(k, result, err) }
        }
    }

    private fun finish(k: String, parsed: List<Cam>?, problem: String?) {
        pending.remove(k)
        if (parsed != null) {
            failedAt.remove(k)
            lastError = null
            install(k, parsed, System.currentTimeMillis(), true)
        } else {
            // keep whatever was saved for this cell
            failedAt[k] = System.currentTimeMillis()
            lastError = problem ?: "refresh failed"
            onUpdate()
        }
    }

    private fun install(k: String, parsed: List<Cam>, stamp: Long, fresh: Boolean) {
        val existing = loaded[k]
        if (!fresh && existing != null && existing >= stamp) return    // a newer copy is already in
        cellCams[k] = parsed
        loaded[k] = stamp
        val byId = LinkedHashMap<String, Cam>()
        for (list in cellCams.values) for (c in list) byId["${c.type}/${c.id}"] = c
        camList = ArrayList(byId.values)
        onUpdate()
    }

    private fun fetch(south: Double, west: Double, north: Double, east: Double): String {
        val q = String.format(
            Locale.US,
            "[out:json][timeout:25];nwr[\"man_made\"=\"surveillance\"][\"surveillance:type\"=\"ALPR\"](%.5f,%.5f,%.5f,%.5f);out center tags;",
            south, west, north, east
        )
        val conn = URL(ENDPOINT).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 40_000
            conn.setRequestProperty("User-Agent", "RFScout/0.1 (Android; personal use)")
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.outputStream.use { it.write(("data=" + URLEncoder.encode(q, "UTF-8")).toByteArray()) }
            val code = conn.responseCode
            if (code != 200) throw IOException("Overpass returned HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(body: String): List<Cam> {
        val arr = JSONObject(body).optJSONArray("elements") ?: return emptyList()
        val out = ArrayList<Cam>()
        for (n in 0 until arr.length()) {
            val e = arr.getJSONObject(n)
            val type = e.optString("type")
            val id = e.optLong("id")
            var lat = Double.NaN
            var lon = Double.NaN
            if (type == "node") {
                lat = e.optDouble("lat")
                lon = e.optDouble("lon")
            } else {
                val c = e.optJSONObject("center")
                if (c != null) {
                    lat = c.optDouble("lat")
                    lon = c.optDouble("lon")
                }
            }
            if (lat.isNaN() || lon.isNaN()) continue

            val tags = LinkedHashMap<String, String>()
            val tj = e.optJSONObject("tags")
            if (tj != null) {
                val keys = tj.keys()
                while (keys.hasNext()) {
                    val tk = keys.next()
                    tags[tk] = tj.optString(tk)
                }
            }
            out.add(
                Cam(
                    type = type, id = id, lat = lat, lon = lon,
                    dirDeg = parseDirection(tags),
                    manufacturer = tags["manufacturer"] ?: "",
                    operator = tags["operator"] ?: "",
                    tags = tags
                )
            )
        }
        return out
    }

    private fun parseDirection(tags: Map<String, String>): Float? {
        val raw = tags["camera:direction"] ?: tags["direction"] ?: return null
        val first = raw.split(';')[0].trim()
        first.toFloatOrNull()?.let { return ((it % 360f) + 360f) % 360f }
        return CARDINALS[first.uppercase(Locale.US)]
    }

    companion object {
        private const val ENDPOINT = "https://overpass-api.de/api/interpreter"
        private const val CELL = 0.05
        private const val MAX_CELLS = 9L
        private const val MAX_AGE_MS = 7L * 24 * 3600 * 1000
        private const val RETRY_MS = 60_000L

        private val CARDINALS = mapOf(
            "N" to 0f, "NNE" to 22.5f, "NE" to 45f, "ENE" to 67.5f,
            "E" to 90f, "ESE" to 112.5f, "SE" to 135f, "SSE" to 157.5f,
            "S" to 180f, "SSW" to 202.5f, "SW" to 225f, "WSW" to 247.5f,
            "W" to 270f, "WNW" to 292.5f, "NW" to 315f, "NNW" to 337.5f
        )
    }
}
