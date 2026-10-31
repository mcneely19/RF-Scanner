package com.rfscout.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.view.View
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
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
