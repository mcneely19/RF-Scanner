package com.rfscout.app

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.widget.Button

/** Shared dark / cyan look. */
object Ui {
    const val BG = 0xFF05090C.toInt()
    const val PANEL = 0xFF0A1418.toInt()
    const val PANEL_HI = 0xFF10262C.toInt()
    const val BORDER = 0xFF0E5F6B.toInt()
    const val ACCENT = 0xFF19E3F0.toInt()
    const val TEXT = 0xFFE3F4F6.toInt()
    const val DIM = 0xFF7FA3A9.toInt()
    const val ALERT = 0xFFFF3D57.toInt()
    const val WARN = 0xFFFFA726.toInt()

    fun box(density: Float, fill: Int = PANEL, stroke: Int = BORDER, radiusDp: Float = 8f, strokeDp: Float = 1.2f): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = radiusDp * density
            setStroke((strokeDp * density).toInt().coerceAtLeast(1), stroke)
        }

    /** Outlined button; primary = filled with the accent color. */
    fun styleButton(ctx: Context, b: Button, primary: Boolean = false) {
        val d = ctx.resources.displayMetrics.density
        val body = if (primary) box(d, ACCENT, ACCENT) else box(d, PANEL, BORDER)
        b.background = RippleDrawable(ColorStateList.valueOf(0x5519E3F0), body, null)
        b.setTextColor(if (primary) 0xFF03181B.toInt() else ACCENT)
        b.textSize = 12f
        b.minHeight = 0
        b.minimumHeight = (40 * d).toInt()
        b.minWidth = 0
        b.minimumWidth = 0
        b.setPadding((6 * d).toInt(), 0, (6 * d).toInt(), 0)
        b.isAllCaps = true
        b.stateListAnimator = null
    }

    fun withAlpha(c: Int, a: Int): Int = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))
}
