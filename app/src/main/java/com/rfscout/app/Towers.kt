package com.rfscout.app

import android.content.Context
import android.os.Handler
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors

/** Where a crowd-sourced database says a cell tower is. */
class TowerPos(val lat: Double, val lon: Double, val rangeM: Int, val samples: Int)

/** Outcome of looking up one cell. */
class TowerResult(val pos: TowerPos?, val message: String, val atMs: Long)

/**
 * Looks up the serving cell in the OpenCelliD database (needs a free API key from opencellid.org).
 * Only the cell the phone is connected to has a full cell ID, so only that one is looked up;
 * neighbor cells expose just a physical ID and frequency, which the database cannot resolve.
 *
 * Results are cached in the app's storage: found towers for 30 days, "not in database" for 7 days,
 * so each cell is asked for once. Errors (no network, bad key, rate limit) are retried after a minute.
 */
class Towers(ctx: Context, private val handler: Handler, private val onUpdate: () -> Unit) {

    var apiKey: String = ""

    private val file = File(ctx.filesDir, "towers.json")
    private val exec = Executors.newSingleThreadExecutor()
    private val results = HashMap<String, TowerResult>()
    private val pending = HashSet<String>()
    private val failedAt = HashMap<String, Long>()
    private var dirty = false

    init {
        try {
            if (file.exists()) {
                val root = JSONObject(file.readText())
                val keys = root.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val o = root.getJSONObject(k)
                    val at = o.optLong("at")
                    val pos = if (o.has("lat")) {
                        TowerPos(o.getDouble("lat"), o.getDouble("lon"), o.optInt("range"), o.optInt("samples"))
                    } else {
                        null
                    }
                    results[k] = TowerResult(pos, o.optString("msg"), at)
                }
            }
        } catch (_: Exception) {
        }
    }

    fun supported(c: CellIdent): Boolean = c.radio != "NR" && c.usable()

    fun result(c: CellIdent): TowerResult? = results[c.lookupKey()]?.takeIf { fresh(it) }

    private fun fresh(r: TowerResult): Boolean {
        val age = System.currentTimeMillis() - r.atMs
        return if (r.pos != null) age < FOUND_MS else age < MISSING_MS
    }

    /** Status for the details page when there is no position. */
    fun statusFor(c: CellIdent): String = when {
        c.radio == "NR" -> "5G cells are not looked up (the database lacks reliable 5G data). Your phone's LTE anchor cell may be listed too."
        !c.usable() -> "Android did not report a complete cell ID."
        apiKey.isBlank() -> "Add an OpenCelliD API key in Settings to look up the tower position."
        pending.contains(c.lookupKey()) -> "Looking up the tower..."
        else -> results[c.lookupKey()]?.message ?: "Not looked up yet."
    }

    fun ensure(c: CellIdent) {
        if (apiKey.isBlank() || !supported(c)) return
        val k = c.lookupKey()
        results[k]?.let { if (fresh(it)) return }
        if (pending.contains(k)) return
        val failed = failedAt[k]
        if (failed != null && System.currentTimeMillis() - failed < RETRY_MS) return
        pending.add(k)
        val key = apiKey
        exec.execute {
            var res: TowerResult? = null
            var problem: String? = null
            try {
                res = fetch(c, key)
            } catch (ex: Exception) {
                problem = ex.message ?: "network error"
            }
            val r = res
            val p = problem
            handler.post { finish(k, r, p) }
        }
    }

    private fun finish(k: String, r: TowerResult?, problem: String?) {
        pending.remove(k)
        if (r != null) {
            failedAt.remove(k)
            results[k] = r
            save()
        } else {
            failedAt[k] = System.currentTimeMillis()
            results[k] = TowerResult(null, "Lookup failed: $problem", 0L)   // atMs 0 = stale, shows the message only
        }
        onUpdate()
    }

    /** Message of the last failed attempt even though the entry counts as stale. */
    fun lastMessage(c: CellIdent): String? = results[c.lookupKey()]?.message

    private fun save() {
        try {
            val root = JSONObject()
            for ((k, r) in results) {
                if (r.atMs == 0L) continue
                val o = JSONObject().put("at", r.atMs).put("msg", r.message)
                if (r.pos != null) {
                    o.put("lat", r.pos.lat).put("lon", r.pos.lon).put("range", r.pos.rangeM).put("samples", r.pos.samples)
                }
                root.put(k, o)
            }
            file.writeText(root.toString())
        } catch (_: Exception) {
        }
    }

    private fun fetch(c: CellIdent, key: String): TowerResult {
        val url = String.format(
            Locale.US,
            "https://opencellid.org/cell/get?key=%s&mcc=%d&mnc=%d&lac=%d&cellid=%d&radio=%s&format=json",
            java.net.URLEncoder.encode(key, "UTF-8"),
            c.mcc.toInt(), c.mnc.toInt(), c.area, c.cid, c.radio
        )
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("User-Agent", "RFScout/0.1 (Android; personal use)")
            val code = conn.responseCode
            val body = try {
                (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            } catch (_: Exception) {
                ""
            }
            val now = System.currentTimeMillis()
            val json = try {
                JSONObject(body)
            } catch (_: Exception) {
                null
            }
            if (json != null && json.has("lat") && json.has("lon")) {
                val pos = TowerPos(
                    json.getDouble("lat"), json.getDouble("lon"),
                    json.optInt("range"), json.optInt("samples")
                )
                return TowerResult(pos, "Found in OpenCelliD", now)
            }
            val err = json?.optString("error").orEmpty()
            return when {
                code == 404 || err.contains("not found", ignoreCase = true) ->
                    TowerResult(null, "This cell is not in the OpenCelliD database", now)
                code == 401 || code == 403 || err.contains("token", true) || err.contains("key", true) ->
                    throw IllegalStateException("OpenCelliD rejected the API key")
                code == 429 || err.contains("limit", true) ->
                    throw IllegalStateException("OpenCelliD daily limit reached")
                else -> throw IllegalStateException(
                    "unexpected answer (HTTP $code${if (err.isNotEmpty()) ": $err" else ""})"
                )
            }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val FOUND_MS = 30L * 24 * 3600 * 1000
        private const val MISSING_MS = 7L * 24 * 3600 * 1000
        private const val RETRY_MS = 60_000L
    }
}
