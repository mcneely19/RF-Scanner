package com.rfscout.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import kotlin.math.max
import kotlin.math.min

/** Arrow that points at the target relative to where the phone is facing (up = straight ahead). */
internal class ArrowView(context: Context) : View(context) {
    var angle = 0f
    var active = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(cx, cy) * 0.9f

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        paint.color = 0xFF666666.toInt()
        canvas.drawCircle(cx, cy, r, paint)
        if (!active) return

        canvas.save()
        canvas.rotate(angle, cx, cy)
        val path = Path().apply {
            moveTo(cx, cy - r * 0.85f)
            lineTo(cx + r * 0.38f, cy + r * 0.5f)
            lineTo(cx, cy + r * 0.25f)
            lineTo(cx - r * 0.38f, cy + r * 0.5f)
            close()
        }
        paint.style = Paint.Style.FILL
        paint.color = 0xFF4CAF50.toInt()
        canvas.drawPath(path, paint)
        canvas.restore()
    }
}

/** Draws the places where RSSI was sampled, coloured by signal strength. */
internal class SampleOverlay(private val samples: List<Sample>, private val radiusPx: Float) : Overlay() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pt = Point()

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val proj = mapView.projection
        for (s in samples) {
            proj.toPixels(GeoPoint(s.lat, s.lon), pt)
            paint.color = rssiColor(s.rssi)
            canvas.drawCircle(pt.x.toFloat(), pt.y.toFloat(), radiusPx, paint)
        }
    }
}

/**
 * Small flag: a pole with a coloured pennant carrying the protocol tag (Wi-Fi, BLE, BT, Cell).
 * The bottom of the pole is the map position, so use an anchor of x = POLE_ANCHOR_X, y = 1.
 */
internal class FlagDrawable(
    private val label: String,
    private val color: Int,
    private val density: Float,
    private val selected: Boolean
) : Drawable() {
    private val w = (46 * density).toInt()
    private val h = (30 * density).toInt()
    private val poleX = 2 * density
    private val flagH = 16 * density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
        this.color = Color.WHITE
        textSize = 9 * density
    }

    override fun getIntrinsicWidth() = w
    override fun getIntrinsicHeight() = h

    override fun draw(c: Canvas) {
        val b = bounds
        val x0 = b.left + poleX
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2 * density
        p.color = 0xFFEEEEEE.toInt()
        c.drawLine(x0, b.top.toFloat(), x0, b.bottom.toFloat(), p)

        val r = RectF(x0, b.top.toFloat(), b.right.toFloat(), b.top + flagH)
        p.style = Paint.Style.FILL
        p.color = color
        c.drawRoundRect(r, 3 * density, 3 * density, p)
        if (selected) {
            p.style = Paint.Style.STROKE
            p.strokeWidth = 2 * density
            p.color = Color.WHITE
            c.drawRoundRect(r, 3 * density, 3 * density, p)
        }
        val fm = tp.fontMetrics
        c.drawText(label, r.centerX(), r.centerY() - (fm.ascent + fm.descent) / 2, tp)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        /** Horizontal anchor fraction that puts the pole base on the map position. */
        const val POLE_ANCHOR_X = 0.04f
    }
}

/** Line graph of RSSI (dBm) over time for the info page. */
internal class RssiGraphView(context: Context) : View(context) {
    var points: List<HistPt> = emptyList()
    var color = Color.WHITE
    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        p.style = Paint.Style.FILL
        p.color = 0xFF1A1A1A.toInt()
        c.drawRoundRect(RectF(0f, 0f, w, h), 6 * d, 6 * d, p)

        val left = 36 * d
        val right = w - 8 * d
        val top = 8 * d
        val bottom = h - 18 * d
        val lo = -100.0
        val hi = -30.0
        fun yOf(v: Double): Float = (bottom - (v.coerceIn(lo, hi) - lo) / (hi - lo) * (bottom - top)).toFloat()

        p.textSize = 10 * d
        p.textAlign = Paint.Align.RIGHT
        for (g in intArrayOf(-40, -60, -80)) {
            val y = yOf(g.toDouble())
            p.style = Paint.Style.STROKE
            p.strokeWidth = 1f
            p.color = 0xFF3A3A3A.toInt()
            c.drawLine(left, y, right, y, p)
            p.style = Paint.Style.FILL
            p.color = 0xFF9E9E9E.toInt()
            c.drawText("$g", left - 4 * d, y + 3 * d, p)
        }

        if (points.size < 2) {
            p.textAlign = Paint.Align.CENTER
            p.color = 0xFF9E9E9E.toInt()
            c.drawText("collecting readings...", (left + right) / 2, (top + bottom) / 2, p)
            return
        }

        val t0 = points[0].t
        val t1 = points[points.size - 1].t
        val span = max(t1 - t0, 10_000L).toDouble()
        val path = Path()
        for ((i, pt) in points.withIndex()) {
            val x = (left + (pt.t - t0) / span * (right - left)).toFloat()
            val y = yOf(pt.rssi.toDouble())
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2 * d
        p.color = color
        c.drawPath(path, p)

        p.style = Paint.Style.FILL
        p.color = 0xFF9E9E9E.toInt()
        p.textAlign = Paint.Align.LEFT
        c.drawText("dBm", 2 * d, top + 8 * d, p)
        p.textAlign = Paint.Align.RIGHT
        c.drawText("last ${(span / 1000).toInt()} s", right, h - 4 * d, p)
    }
}
