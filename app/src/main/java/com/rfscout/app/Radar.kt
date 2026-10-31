package com.rfscout.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/** One dot on the radar. relDeg is measured clockwise from the top of the phone. */
class RadarDot(
    val key: String, val color: Int, val distM: Double, val relDeg: Double, val known: Boolean,
    val selected: Boolean, val alert: Boolean,
    val ghostDistM: Double? = null, val ghostDeg: Double = 0.0   // mirror-image position when the fit is ambiguous
)

/** Rings around you, a dot per signal. Up = where the phone points. Signals without a direction sit on the lower arc. */
class RadarView(ctx: Context, private val onTap: (String) -> Unit) : View(ctx) {
    var dots: List<RadarDot> = emptyList()
    var rangeM = 50.0
    var caption = ""

    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hit = ArrayList<Triple<Float, Float, String>>()

    override fun onDraw(c: Canvas) {
        c.drawColor(Ui.BG)
        val cx = width / 2f
        val cy = height / 2f - 10 * d
        val r = min(width, height) / 2f - 28 * d
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.2f * d
        p.color = Ui.BORDER
        for (i in 1..4) c.drawCircle(cx, cy, r * i / 4f, p)
        c.drawLine(cx - r, cy, cx + r, cy, p)
        c.drawLine(cx, cy - r, cx, cy + r, p)

        p.style = Paint.Style.FILL
        p.textSize = 10 * d
        p.color = Ui.DIM
        p.textAlign = Paint.Align.RIGHT
        for (i in 1..4) c.drawText(fmtDist(rangeM * i / 4), cx + r * i / 4f - 3 * d, cy - 3 * d, p)
        p.textAlign = Paint.Align.CENTER
        p.color = Ui.ACCENT
        c.drawText("AHEAD", cx, cy - r - 8 * d, p)

        // lower arc marks "direction unknown", drawn only when a signal is actually on it
        if (dots.any { !it.known }) {
            p.style = Paint.Style.STROKE
            p.color = Ui.withAlpha(Ui.WARN, 90)
            p.strokeWidth = 3 * d
            c.drawArc(cx - r - 4 * d, cy - r - 4 * d, cx + r + 4 * d, cy + r + 4 * d, 60f, 60f, false, p)
            p.style = Paint.Style.FILL
            p.color = Ui.WARN
            p.textSize = 9 * d
            p.textAlign = Paint.Align.CENTER
            c.drawText("DIRECTION UNKNOWN", cx, cy + r + 18 * d, p)
        }

        hit.clear()
        for (dot in dots) {
            val frac = (dot.distM / rangeM).coerceIn(0.03, 1.0).toFloat()
            val ang = Math.toRadians(dot.relDeg)
            val x = cx + (r * frac * sin(ang)).toFloat()
            val y = cy - (r * frac * cos(ang)).toFloat()
            hit.add(Triple(x, y, dot.key))
            val rad = if (dot.selected) 7 * d else 5 * d
            val gd = dot.ghostDistM
            if (gd != null) {
                // faint dashed ring: the other possible position (mirror across your walked path)
                val gf = (gd / rangeM).coerceIn(0.03, 1.0).toFloat()
                val ga = Math.toRadians(dot.ghostDeg)
                val gx = cx + (r * gf * sin(ga)).toFloat()
                val gy = cy - (r * gf * cos(ga)).toFloat()
                p.style = Paint.Style.STROKE
                p.strokeWidth = 1.5f * d
                p.color = Ui.withAlpha(dot.color, 150)
                p.pathEffect = android.graphics.DashPathEffect(floatArrayOf(4 * d, 3 * d), 0f)
                c.drawCircle(gx, gy, rad, p)
                p.pathEffect = null
            }
            if (dot.known) {
                p.style = Paint.Style.FILL
                p.color = dot.color
                c.drawCircle(x, y, rad, p)
            } else {
                p.style = Paint.Style.STROKE
                p.strokeWidth = 2 * d
                p.color = dot.color
                c.drawCircle(x, y, rad, p)
            }
            if (dot.alert) {
                p.style = Paint.Style.STROKE
                p.strokeWidth = 2 * d
                p.color = Ui.ALERT
                c.drawCircle(x, y, rad + 4 * d, p)
            }
            if (dot.selected) {
                p.style = Paint.Style.STROKE
                p.strokeWidth = 1.5f * d
                p.color = Ui.TEXT
                c.drawCircle(x, y, rad + 3 * d, p)
            }
        }

        // you
        p.style = Paint.Style.FILL
        p.color = Ui.ACCENT
        c.drawCircle(cx, cy, 5 * d, p)
        p.strokeWidth = 2 * d
        p.style = Paint.Style.STROKE
        c.drawLine(cx, cy, cx, cy - 16 * d, p)

        p.style = Paint.Style.FILL
        p.color = Ui.DIM
        p.textSize = 11 * d
        p.textAlign = Paint.Align.CENTER
        c.drawText(caption, cx, height - 8 * d, p)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_UP) {
            var best: String? = null
            var bd = 30 * d
            for ((x, y, k) in hit) {
                val dd = hypot(e.x - x, e.y - y)
                if (dd < bd) {
                    bd = dd
                    best = k
                }
            }
            best?.let(onTap)
        }
        return true
    }
}
