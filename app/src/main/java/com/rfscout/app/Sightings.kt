package com.rfscout.app

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** A signal that matched the watchlist, with the best position we have for it. */
data class Sighting(
    val key: String,
    val name: String,
    val protocol: String,
    val label: String,
    val confidence: String,
    val lat: Double,
    val lon: Double,
    val source: String,       // "fit", "strongest reading" or "phone position when heard"
    val rssi: Double,
    val timeMs: Long
)

/** Higher is a better position source. */
fun sightingRank(source: String): Int = when (source) {
    "fit" -> 3
    "strongest reading" -> 2
    else -> 1
}

fun sightingsToJson(list: Collection<Sighting>): String {
    val arr = JSONArray()
    for (s in list) {
        arr.put(
            JSONObject().apply {
                put("key", s.key)
                put("name", s.name)
                put("protocol", s.protocol)
                put("label", s.label)
                put("confidence", s.confidence)
                put("lat", s.lat)
                put("lon", s.lon)
                put("source", s.source)
                put("rssi", s.rssi)
                put("timeMs", s.timeMs)
            }
        )
    }
    return arr.toString()
}

fun sightingsFromJson(text: String): List<Sighting> {
    val out = ArrayList<Sighting>()
    try {
        val arr = JSONArray(text)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(
                Sighting(
                    key = o.getString("key"), name = o.optString("name"),
                    protocol = o.optString("protocol"), label = o.optString("label"),
                    confidence = o.optString("confidence"),
                    lat = o.getDouble("lat"), lon = o.getDouble("lon"),
                    source = o.optString("source"), rssi = o.optDouble("rssi"),
                    timeMs = o.optLong("timeMs")
                )
            )
        }
    } catch (_: Exception) {
        // a damaged file just means an empty log
    }
    return out
}

private fun csv(s: String): String = "\"" + s.replace("\"", "\"\"") + "\""

/** CSV text for sharing. [mapped] says whether each sighting is near a camera already on the map. */
fun sightingsToCsv(list: Collection<Sighting>, mapped: (Sighting) -> String): String {
    val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
    fmt.timeZone = TimeZone.getTimeZone("UTC")
    val sb = StringBuilder("time_utc,name,type,signature,confidence,lat,lon,position_source,rssi_dbm,map_status\n")
    for (s in list) {
        sb.append(fmt.format(java.util.Date(s.timeMs))).append(',')
            .append(csv(s.name)).append(',')
            .append(csv(s.protocol)).append(',')
            .append(csv(s.label)).append(',')
            .append(csv(s.confidence)).append(',')
            .append(String.format(Locale.US, "%.6f,%.6f", s.lat, s.lon)).append(',')
            .append(csv(s.source)).append(',')
            .append(String.format(Locale.US, "%.0f", s.rssi)).append(',')
            .append(csv(mapped(s))).append('\n')
    }
    return sb.toString()
}
