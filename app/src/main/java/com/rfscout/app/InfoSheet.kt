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
    private val onCalibrate: (String) -> Unit,
    private val onMine: (String) -> Unit,
    private val onClose: () -> Unit
) : LinearLayout(ctx) {

    private val d = resources.displayMetrics.density
    private val title = TextView(ctx)
    private val graph = RssiGraphView(ctx)
    private val body = TextView(ctx)
    private val trackBtn = Button(ctx)
    private val calBtn = Button(ctx)
    private val mineBtn = Button(ctx)
    private var key: String? = null

    init {
        orientation = VERTICAL
        background = Ui.box(d, 0xF20A1418.toInt(), Ui.BORDER, 12f)
        isClickable = true            // swallow touches so the map underneath does not move
        setPadding(dp(12), dp(8), dp(12), dp(8))

        title.textSize = 16f
        title.setTextColor(Color.WHITE)
        title.isSingleLine = true
        val close = Button(ctx).apply {
            text = "Close"
            Ui.styleButton(ctx, this)
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
        body.setTextColor(Ui.TEXT)
        body.setPadding(0, dp(8), 0, dp(8))
        val scroll = ScrollView(ctx)
        scroll.addView(body)
        addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        trackBtn.setOnClickListener { key?.let(onTrack) }
        calBtn.text = "Calibrate"
        calBtn.setOnClickListener { key?.let(onCalibrate) }
        mineBtn.text = "This is mine"
        mineBtn.setOnClickListener { key?.let(onMine) }
        val buttons = LinearLayout(ctx).apply { orientation = HORIZONTAL }
        for (b in listOf(trackBtn, calBtn, mineBtn)) {
            Ui.styleButton(ctx, b)
            buttons.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })
        }
        addView(buttons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun dp(v: Int): Int = (v * d).toInt()

    private fun ago(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s < 90) "${s}s" else "${s / 60}m ${s % 60}s"
    }

    fun bind(t: Track, loc: Location?, tracking: Boolean, extra: String? = null) {
        key = t.key
        title.text = "${t.name}  [${t.protocol.label}]"
        title.setTextColor(t.protocol.color or 0xFF000000.toInt())
        graph.color = t.protocol.color
        graph.points = ArrayList(t.history)
        graph.invalidate()

        val now = System.currentTimeMillis()
        val sb = StringBuilder()
        if (extra != null) sb.append(extra).append("\n\n")
        if (t.detail.isNotBlank()) sb.append("ID: ").append(t.detail).append('\n')
        val vendor = Vendors.detailLines(t)
        if (vendor.isNotEmpty()) sb.append(vendor).append('\n')
        Category.of(t)?.let { sb.append("Category (guess): ").append(it).append('\n') }
        t.cell?.let { c -> sb.append(cellLines(c)) }

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
            if (f != null && f.tower && loc != null) {
                val (dist, bearing) = Rf.distanceBearing(loc.latitude, loc.longitude, f.lat, f.lon)
                sb.append("tower is about ${fmtDist(dist)} away at ${"%.0f".format(bearing)}° (0° = north)")
                sb.append("\nSource: OpenCelliD database estimate, coverage range about ${fmtDist(t.towerRangeM.toDouble())}.")
                sb.append("\nThis is crowd-sourced; positions are often off by hundreds of metres.")
            } else {
                sb.append("not possible by walking (towers are too far). ")
                sb.append(if (t.towerNote.isNotEmpty()) t.towerNote else "Neighbor cells expose only a physical ID and frequency, so they stay signal-only.")
            }
        } else if (f != null && loc != null) {
            val (dist, bearing) = Rf.distanceBearing(loc.latitude, loc.longitude, f.lat, f.lon)
            sb.append("${fmtDist(dist)} away at ${"%.0f".format(bearing)}° (0° = north)")
            sb.append("\nFit quality: ±${"%.1f".format(f.rmsDb)} dB (lower is better)")
            if (f.ambiguous) sb.append("\nYour path is nearly straight, so the real position could be on the other side. Walk a curve.")
        } else {
            sb.append(
                if (loc != null && loc.accuracy > 30f) "waiting for better GPS (now ±${"%.0f".format(loc.accuracy)} m, needs 30 m or better)."
                else if (t.fixNote.isNotEmpty()) t.fixNote
                else "none yet. Walk 10 m or more; ${t.samples.size} sample points so far (needs 6)."
            )
        }
        sb.append("\nSample points recorded: ${t.samples.size}")
        body.text = sb

        trackBtn.text = if (tracking) "Tracking ✓" else "Track"
        calBtn.visibility = if (t.protocol == Protocol.CELL) GONE else VISIBLE
        mineBtn.visibility = if (t.trackerKind != null) VISIBLE else GONE
    }

    private fun cellLines(c: CellIdent): String {
        val sb = StringBuilder()
        sb.append("Cell: ${c.radio} · MCC ${c.mcc} · MNC ${c.mnc}\n")
        val areaName = if (c.radio == "GSM" || c.radio == "UMTS") "LAC" else "TAC"
        sb.append("$areaName ${c.area} · cell ID ${c.cid}\n")
        when (c.radio) {
            "LTE" -> sb.append("eNodeB ${c.cid shr 8} · sector ${c.cid and 0xFF}\n")
            "UMTS" -> sb.append("RNC ${c.cid shr 16} · cell ${c.cid and 0xFFFF}\n")
        }
        return sb.toString()
    }
}
