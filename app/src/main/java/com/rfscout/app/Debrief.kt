package com.rfscout.app

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A plain-text summary of what the scan has seen so far. Built on the phone, no network. No Android
 * classes here, so it can be tested on a desktop. The same text can optionally be sent to Claude.
 */
object Debrief {
    private val macRe = Regex("(?i)\\b([0-9a-f]{2})[:-]([0-9a-f]{2})[:-]([0-9a-f]{2})[:-][0-9a-f]{2}[:-][0-9a-f]{2}[:-][0-9a-f]{2}\\b")

    /**
     * @param distanceOf distance and bearing text for a signal ("45 m @ 120°"), or null if unknown
     * @param maskAddresses hide the last three bytes of any MAC address that appears in names (for sending out)
     */
    fun build(
        tracks: Collection<Track>,
        now: Long,
        distanceOf: (Track) -> String?,
        maskAddresses: Boolean = false
    ): String {
        if (tracks.isEmpty()) return "No signals yet. Press Start and scan, then come back."
        val fmt = SimpleDateFormat("HH:mm", Locale.US)
        val start = tracks.minOf { it.firstSeenMs }
        val sb = StringBuilder()
        sb.append("Scan from ${fmt.format(Date(start))} to ${fmt.format(Date(now))} (${(now - start) / 60_000} min)\n")
        sb.append("${tracks.size} signals: ")
        sb.append(Protocol.values().joinToString(", ") { p -> "${tracks.count { it.protocol == p }} ${p.label}" })
        sb.append("\n")
        val located = tracks.count { it.fix != null }
        sb.append("$located have a position estimate.\n")

        val follow = tracks.filter { it.following }
        val alpr = tracks.filter { it.watch != null }
        val mine = tracks.filter { it.myHit != null }
        sb.append("\nNeeds a look\n")
        if (follow.isEmpty() && alpr.isEmpty() && mine.isEmpty()) {
            sb.append("• Nothing flagged.\n")
        }
        for (t in follow) {
            sb.append("• ${t.trackerKind ?: "Bluetooth tag"} \"${t.name}\" has stayed with you (about ${t.maxTravelM.toInt()} m of travel)\n")
        }
        for (t in alpr) {
            sb.append("• \"${t.name}\" [${t.protocol.label}] matches a reported ALPR pattern: ${t.watch!!.label} (${t.watch!!.confidence} confidence, a lead only)\n")
        }
        for (t in mine) {
            val e = t.myHit!!
            sb.append("• \"${t.name}\" [${t.protocol.label}] is on your watchlist (${e.pattern}${if (e.note.isNotBlank()) ": " + e.note else ""}), last heard ${ago(now - t.lastSeenMs)} ago")
            distanceOf(t)?.let { sb.append(", $it") }
            sb.append("\n")
        }

        val air = tracks.filter { it.protocol == Protocol.ADSB }.sortedByDescending { it.rssiSmooth }
        if (air.isNotEmpty()) {
            sb.append("\nAircraft (${air.size})\n")
            for (t in air.take(8)) {
                sb.append("• ${t.name}: ${t.detail.substringBefore(" · level")}")
                distanceOf(t)?.let { sb.append(", $it") }
                sb.append("\n")
            }
        }

        val drones = tracks.filter { it.protocol == Protocol.RID }.sortedByDescending { it.rssiSmooth }
        if (drones.isNotEmpty()) {
            sb.append("\nDrones (Remote ID, ${drones.size})\n")
            for (t in drones.take(8)) {
                sb.append("• ${t.name}: ${t.detail}")
                distanceOf(t)?.let { sb.append(", $it") }
                sb.append("\n")
            }
        }

        val others = tracks.filter { it.protocol != Protocol.ADSB && it.protocol != Protocol.RID }.sortedByDescending { it.rssiSmooth }.take(6)
        if (others.isNotEmpty()) {
            sb.append("\nStrongest signals\n")
            for (t in others) {
                sb.append("• \"${t.name}\" [${t.protocol.label}] ${t.rssi} dBm")
                Category.of(t)?.let { sb.append(" · $it") }
                distanceOf(t)?.let { sb.append(" · $it") }
                sb.append("\n")
            }
        }

        val cats = tracks.mapNotNull { Category.of(it) }.map { if (it.startsWith("On your watchlist")) "On your watchlist" else it }.groupingBy { it }.eachCount().entries
            .sortedByDescending { it.value }.take(6)
        if (cats.isNotEmpty()) {
            sb.append("\nWhat the guesses add up to\n")
            for (c in cats) sb.append("• ${c.value} × ${c.key}\n")
        }
        sb.append("\nNames and categories are guesses, and a pattern match is a lead, not proof.")
        val text = sb.toString()
        return if (maskAddresses) macRe.replace(text) { m -> "${m.groupValues[1]}:${m.groupValues[2]}:${m.groupValues[3]}:xx:xx:xx" } else text
    }

    /** The message sent to Claude: instructions plus the (address-masked) summary. */
    fun claudePrompt(summary: String): String =
        "Below is a summary of a passive radio scan done with a phone (Wi-Fi, Bluetooth, cell towers and aircraft " +
            "broadcasts). Please write a short debrief for the person who did the scan: what stands out, what is " +
            "probably ordinary, and what is worth checking again or walking toward. Names and categories are guesses " +
            "and pattern matches are only leads, so do not claim to identify anyone or any device with certainty. " +
            "Keep it under 200 words, in plain language.\n\n" + summary

    private fun ago(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s < 90) "${s}s" else "${s / 60} min"
    }
}
