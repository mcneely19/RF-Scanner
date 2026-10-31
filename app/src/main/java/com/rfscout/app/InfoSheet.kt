package com.rfscout.app

import android.content.Context
import android.graphics.Color
import android.location.Location
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Slide-up information page for one signal. Hidden until a flag or list row opens it. */
internal class InfoSheet(
    ctx: Context,
    private val onTrack: (String) -> Unit,
    private val onClose: () -> Unit
) : LinearLayout(ctx) {

    private val d = resources.displayMetrics.density
    private val title = TextView(ctx)
    private val graph = RssiGraphView(ctx)
    private val body = TextView(ctx)
    private val trackBtn = Button(ctx)
    private var key: String? = null

    init {
        orientation = VERTICAL
        setBackgroundColor(0xF2212121.toInt())
        isClickable = true            // swallow touches so the map underneath does not move
        setPadding(dp(12), dp(8), dp(12), dp(8))

        title.textSize = 16f
        title.setTextColor(Color.WHITE)
        title.isSingleLine = true
        val close = Button(ctx).apply {
            text = "Close"
            setOnClickListener { onClose() }
        }
        val header = LinearLayout(ctx).apply { orientation = HORIZONTAL }
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL
        })
        header.addView(close, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(header)

        addView(graph, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(100)).apply { topMargin = dp(4) })

        body.textSize = 13f
        body.setTextColor(0xFFDDDDDD.toInt())
        body.setPadding(0, dp(8), 0, dp(8))
        val scroll = ScrollView(ctx)
        scroll.addView(body)
        addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        trackBtn.setOnClickListener { key?.let(onTrack) }
        addView(trackBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun dp(v: Int): Int = (v * d).toInt()

    private fun ago(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s < 90) "${s}s" else "${s / 60}m ${s % 60}s"
    }

    fun bind(t: Track, loc: Location?, tracking: Boolean) {
        key = t.key
        title.text = "${t.name}  [${t.protocol.label}]"
        title.setTextColor(t.protocol.color or 0xFF000000.toInt())
        graph.color = t.protocol.color
        graph.points = ArrayList(t.history)
        graph.invalidate()

        val now = System.currentTimeMillis()
        val sb = StringBuilder()
        if (t.detail.isNotBlank()) sb.append("ID: ").append(t.detail).append('\n')

        sb.append("Signal now: ${t.rssi} dBm · average ${"%.0f".format(t.rssiSmooth)} dBm")
        if (t.history.size >= 2) {
            val mn = t.history.minOf { it.rssi }
            val mx = t.history.maxOf { it.rssi }
            sb.append("\nRange seen: $mn to $mx dBm")
        }
        if (t.freqMhz > 0) {
            sb.append("\nFrequency: ${t.freqMhz} MHz")
            if (t.protocol == Protocol.WIFI) sb.append(" (channel ${wifiChannel(t.freqMhz)})")
        }
        t.txPower?.let { sb.append("\nAdvertised TX power: $it dBm") }
        sb.append("\nSeen: first ${ago(now - t.firstSeenMs)} ago · last ${ago(now - t.lastSeenMs)} ago · ${t.count} readings")

        val md = Rf.modelDistance(t)
        sb.append("\n\nDistance guess from signal strength: ")
        sb.append(if (md != null) "about ${fmtDist(md)} (rough; indoors it can be off by 2x or more)" else "not available for this type")

        val f = t.fix
        sb.append("\n\nPosition estimate: ")
        if (t.protocol == Protocol.CELL) {
            sb.append("not possible for cell towers (signal strength only)")
        } else if (f != null && loc != null) {
            val (dist, bearing) = Rf.distanceBearing(loc.latitude, loc.longitude, f.lat, f.lon)
            sb.append("${fmtDist(dist)} away at ${"%.0f".format(bearing)}° (0° = north)")
            sb.append("\nFit quality: ±${"%.1f".format(f.rmsDb)} dB (lower is better)")
            if (f.ambiguous) sb.append("\nYour path is nearly straight, so the real position could be on the other side. Walk a curve.")
        } else {
            sb.append("none yet. Walk 10 m or more; ${t.samples.size} sample points so far (needs 6).")
        }
        sb.append("\nSample points recorded: ${t.samples.size}")
        body.text = sb

        trackBtn.text = if (tracking) "Tracking this signal ✓" else "Track this signal"
    }
}
