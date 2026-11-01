package com.rfscout.app

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.cos
import kotlin.math.sin

/** Illustrated "how it works" page. All graphics are drawn in code, so it works offline. */
class HelpActivity : Activity() {

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }

        col.addView(TextView(this).apply {
            text = "How RF Scout works"
            textSize = 22f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        })
        col.addView(body(
            "RF Scout listens to the radios in your phone, guesses how far each signal is, " +
                "and uses your walking path to estimate which direction it is in."
        ))

        section(
            col, "1. The signal list", ListDiagram(this),
            "Everything the phone can hear, strongest first. Tap a row to track it, or long-press a row " +
                "for its details page.\n\ndBm is closer to zero when stronger: -40 is very close, -90 is barely there. " +
                "The distance is a rough guess from one reading and can be off by 2x or more indoors."
        )
        section(
            col, "2. The map", MapDiagram(this),
            "Open the Map tab. Your walked path and the colored sample dots build up as you move. " +
                "The white ring is a range guess around you for the signal you are tracking. " +
                "Tap a flag to open that signal's details page."
        )
        section(
            col, "3. Finding a direction", WalkDiagram(this),
            "A phone has one antenna, so standing still it cannot tell which way a signal comes from. " +
                "While you walk, the app records signal strength against GPS position and finds the spot " +
                "that best explains the readings.\n\nWalk at least 10 m. A curve or loop around where you " +
                "think the signal is works best. A straight line leaves two mirror-image answers, and the " +
                "app warns you. GPS is best outdoors."
        )
        section(
            col, "4. The arrow", ArrowDiagram(this),
            "The arrow points at the estimated position relative to the top of the phone: " +
                "up means straight ahead, right means turn right. It uses the compass sensor, which can drift " +
                "near metal or magnets."
        )
        section(
            col, "5. Track One and Track All", FlagsDiagram(this),
            "Track: One shows only the signal you selected on the map. Track: All shows every signal that " +
                "has a position estimate, each as a flag: Wi-Fi blue, BLE purple, BT indigo, Cell orange. " +
                "With nothing selected, all flags are shown either way.\n\nNeighbor cell towers have no position, so " +
                "their flags sit in a row next to your dot. The tower you are connected to can be placed from a database (see More features)."
        )
        section(
            col, "6. What each type gives you", TableDiagram(this),
            "Cell towers are too far away for a bearing, so you get signal strength only. " +
                "Sub-GHz, ADS-B and Zigbee need external radios and are not part of the app yet."
        )

        section(
            col, "7. ALPR layer and watchlist", AlprDiagram(this),
            "ALPR layer: turn it on, open the Map and zoom in to street level. The app loads license-plate-reader " +
                "cameras that volunteers have mapped in OpenStreetMap (the data projects like DeFlock collect). " +
                "Each red dot is a mapped camera, and the wedge shows which way it faces when that is known. " +
                "Tap a dot for details. Mapped data can be incomplete or out of date. Downloaded areas are saved on the phone, " +
                "load at startup and work offline; areas older than a week refresh when you view them.\n\n" +
                "Watchlist: signals whose name or Bluetooth company ID match patterns reported on Flock cameras " +
                "get an orange list row and a red ALPR? flag. These patterns are community-reported, change over " +
                "time and can match other devices, and many cameras broadcast nothing, so a hit is only a lead.\n\n" +
                "Sightings: logs each hit with its best position and whether a mapped camera is nearby. " +
                "You can share the log as CSV or open the DeFlock report page."
        )

        col.addView(TextView(this).apply {
            text = "More features"
            textSize = 16f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(20), 0, dp(4))
        })
        col.addView(body(
            "• Radar tab: below the radar is a list of every signal. Tap a row to select it, long-press for details; " +
                "tapping a dot scrolls the list to it.\n" +
                "• Radar tab: you are the center and up is where the phone points. A filled dot has a direction estimate; " +
                "a hollow dot on the lower arc has only a distance. Tap a dot for its details. Rings show the range, " +
                "which rescales to fit.\n" +
                "• Signal cards: each shows type, strength, vendor, a category guess (from its name and company; it can be wrong) " +
                "and first/last seen. The list shows the strongest 50.\n" +
                "• Cell tower: add a free OpenCelliD key in Settings. The tower you are connected to then gets a flag, " +
                "a distance and an arrow. It is a crowd-sourced estimate, often off by hundreds of metres. " +
                "Neighbor cells only share a physical ID, so they stay signal-only.\n" +
                "• Calibrate: open a signal's details page, stand a known distance from the real device and tap " +
                "Calibrate. Two different distances also tune the path-loss curve.\n" +
                "• Vendor names: Bluetooth company IDs and Wi-Fi / Bluetooth addresses are matched to makers. " +
                "Phones and many gadgets use private random addresses, which show no vendor.\n" +
                "• Tracker alert: AirTag, SmartTag, Tile and Find Hub style tags that stay in range while you travel " +
                "300 m over 8 minutes raise a notification. Tap \"This is mine\" to silence your own.\n" +
                "• Screen-off: turn on Screen-off scanning, then press Start. A notification with a Stop button stays up " +
                "while scanning. With the screen off only trackers and watchlist Bluetooth are scanned.\n" +
                "• Export: shares two CSV files and a GPX file of your walk and positions."
        ))

        col.addView(TextView(this).apply {
            text = "Tips"
            textSize = 16f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(20), 0, dp(4))
        })
        col.addView(body(
            "• Android limits Wi-Fi scans to about 4 per 2 minutes. For continuous Wi-Fi scanning, turn off " +
                "\"Wi-Fi scan throttling\" in Developer options.\n" +
                "• Location must be on, or Android returns no Wi-Fi, Bluetooth or cell results.\n" +
                "• Bluetooth devices that rotate their address show up as new signals.\n" +
                "• Without Screen-off scanning, scanning pauses when the app is in the background and resumes when you return.\n" +
                "• Map tiles need internet. Everything else works offline."
        ))

        col.addView(Button(this).apply {
            text = "Close"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(20)
        })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Ui.BG)
            addView(col)
        })
    }

    private fun body(s: String) = TextView(this).apply {
        text = s
        textSize = 14f
        setTextColor(0xFFCCCCCC.toInt())
        setPadding(0, dp(6), 0, dp(4))
    }

    private fun section(col: LinearLayout, title: String, diagram: View, text: String) {
        col.addView(TextView(this).apply {
            this.text = title
            textSize = 17f
            setTextColor(0xFF64B5F6.toInt())
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(22), 0, dp(6))
        })
        col.addView(diagram, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        col.addView(body(text))
    }
}

// ============================================================ diagrams

internal abstract class Diagram(ctx: Context, private val heightDp: Int) : View(ctx) {
    protected val d = resources.displayMetrics.density
    protected val p = Paint(Paint.ANTI_ALIAS_FLAG)

    protected fun dp(v: Float) = v * d

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), (heightDp * d).toInt())
    }

    protected fun tw(s: String, sp: Float, bold: Boolean = false): Float {
        p.textSize = sp * d
        p.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        return p.measureText(s)
    }

    protected fun text(
        c: Canvas, s: String, x: Float, y: Float, sp: Float, color: Int,
        align: Paint.Align = Paint.Align.LEFT, bold: Boolean = false
    ) {
        p.style = Paint.Style.FILL
        p.color = color
        p.textSize = sp * d
        p.textAlign = align
        p.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        c.drawText(s, x, y, p)
    }

    protected fun dot(c: Canvas, x: Float, y: Float, r: Float, color: Int) {
        p.style = Paint.Style.FILL
        p.color = color
        c.drawCircle(x, y, r, p)
    }

    protected fun ring(c: Canvas, x: Float, y: Float, r: Float, color: Int, width: Float = 1.5f) {
        p.style = Paint.Style.STROKE
        p.strokeWidth = width * d
        p.color = color
        c.drawCircle(x, y, r, p)
    }

    protected fun line(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, color: Int, width: Float = 2f) {
        p.style = Paint.Style.STROKE
        p.strokeWidth = width * d
        p.color = color
        c.drawLine(x1, y1, x2, y2, p)
    }

    protected fun badge(c: Canvas, n: Int, x: Float, y: Float) {
        dot(c, x, y, dp(9f), 0xFF1E88E5.toInt())
        text(c, "$n", x, y + dp(4f), 11f, Color.WHITE, Paint.Align.CENTER, true)
    }

    /** Full-size flag whose pole base sits at (x, y). */
    protected fun flag(c: Canvas, x: Float, y: Float, protocol: Protocol) {
        val fd = FlagDrawable(protocol.tag, protocol.color, d, false)
        val l = (x - 2 * d).toInt()
        val t = (y - fd.intrinsicHeight).toInt()
        fd.setBounds(l, t, l + fd.intrinsicWidth, t + fd.intrinsicHeight)
        fd.draw(c)
    }

    protected fun panel(c: Canvas, r: RectF) {
        p.style = Paint.Style.FILL
        p.color = 0xFF1E1E1E.toInt()
        c.drawRoundRect(r, dp(8f), dp(8f), p)
    }
}

/** Signal list row with numbered callouts. */
internal class ListDiagram(ctx: Context) : Diagram(ctx, 200) {
    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        panel(c, RectF(0f, dp(26f), w, dp(88f)))
        val pad = dp(12f)
        val y1 = dp(52f)
        val y2 = dp(76f)

        val s1 = "▂▄▆█"
        val s2 = "Home-WiFi"
        val s3 = "[Wi-Fi]"
        var x = pad
        val w1 = tw(s1, 15f)
        text(c, s1, x, y1, 15f, 0xFF4CAF50.toInt())
        badge(c, 1, x + w1 / 2, dp(13f))
        x += w1 + dp(10f)
        val w2 = tw(s2, 15f, true)
        text(c, s2, x, y1, 15f, Color.WHITE, bold = true)
        badge(c, 2, x + w2 / 2, dp(13f))
        x += w2 + dp(8f)
        text(c, s3, x, y1, 13f, Protocol.WIFI.color)

        val a = "-48 dBm"
        val b = "~4.2 m"
        val rest = "ch 6 · AA:BB:CC:DD:EE:FF"
        var x2 = pad
        val wa = tw(a, 12f)
        text(c, a, x2, y2, 12f, 0xFFBBBBBB.toInt())
        badge(c, 3, x2 + wa / 2, dp(102f))
        x2 += wa + dp(10f)
        val wb = tw(b, 12f)
        text(c, b, x2, y2, 12f, 0xFFBBBBBB.toInt())
        badge(c, 4, x2 + wb / 2, dp(102f))
        x2 += wb + dp(10f)
        text(c, rest, x2, y2, 12f, 0xFF888888.toInt())

        val lines = listOf(
            "Signal bars: more bars, stronger signal",
            "Name and type of the signal",
            "Strength in dBm: closer to 0 is stronger",
            "Rough distance guess from that strength"
        )
        for ((i, s) in lines.withIndex()) {
            val y = dp(132f) + i * dp(22f)
            badge(c, i + 1, pad + dp(9f), y - dp(4f))
            text(c, s, pad + dp(26f), y, 13f, 0xFFCCCCCC.toInt())
        }
    }
}

/** Mock map with a legend. */
internal class MapDiagram(ctx: Context) : Diagram(ctx, 340) {
    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        p.style = Paint.Style.FILL
        p.color = 0xFF2B3A42.toInt()
        c.drawRoundRect(RectF(0f, 0f, w, dp(180f)), dp(8f), dp(8f), p)

        val p0x = 0.08f * w; val p0y = dp(150f)
        val cx = 0.28f * w; val cy = dp(30f)
        val p2x = 0.56f * w; val p2y = dp(122f)

        // walked path
        val path = Path()
        path.moveTo(p0x, p0y)
        path.quadTo(cx, cy, p2x, p2y)
        p.style = Paint.Style.STROKE
        p.strokeWidth = dp(3f)
        p.color = 0x8890CAF9.toInt()
        c.drawPath(path, p)

        // sample dots, weak (red) to strong (green)
        val cols = intArrayOf(0xFFF44336.toInt(), 0xFFFF9800.toInt(), 0xFFCDDC39.toInt(), 0xFF4CAF50.toInt())
        for (i in 0..7) {
            val t = i / 8f
            val x = (1 - t) * (1 - t) * p0x + 2 * (1 - t) * t * cx + t * t * p2x
            val y = (1 - t) * (1 - t) * p0y + 2 * (1 - t) * t * cy + t * t * p2y
            dot(c, x, y, dp(4f), cols[(i * 4) / 8])
        }

        // transmitter flag + line from you
        val fx = 0.80f * w
        val fy = dp(70f)
        line(c, p2x, p2y, fx, fy, 0xFFFF5252.toInt(), 2.5f)
        flag(c, fx, fy, Protocol.WIFI)

        // range ring, you
        ring(c, p2x, p2y, 0.17f * w, Color.WHITE, 2f)
        dot(c, p2x, p2y, dp(12f), 0x2200E5FF)
        dot(c, p2x, p2y, dp(5f), 0xFF00E5FF.toInt())

        // legend
        val pad = dp(12f)
        val ix = pad + dp(14f)
        val tx = pad + dp(48f)
        val top = dp(206f)
        val gap = dp(22f)

        var y = top
        dot(c, ix, y - dp(4f), dp(9f), 0x2200E5FF); dot(c, ix, y - dp(4f), dp(4f), 0xFF00E5FF.toInt())
        text(c, "You. The pale circle is GPS accuracy.", tx, y, 13f, 0xFFCCCCCC.toInt())

        y += gap
        line(c, ix - dp(12f), y - dp(4f), ix + dp(12f), y - dp(4f), 0x8890CAF9.toInt(), 3f)
        text(c, "Your walked path.", tx, y, 13f, 0xFFCCCCCC.toInt())

        y += gap
        for (i in 0..3) dot(c, ix - dp(12f) + i * dp(8f), y - dp(4f), dp(3.5f), cols[i])
        text(c, "Readings along your path: red weak, green strong.", tx, y, 13f, 0xFFCCCCCC.toInt())

        y += gap
        ring(c, ix, y - dp(4f), dp(8f), Color.WHITE, 2f)
        text(c, "Range guess from one reading. Treat it as rough.", tx, y, 13f, 0xFFCCCCCC.toInt())

        y += gap
        line(c, ix - dp(8f), y + dp(2f), ix - dp(8f), y - dp(13f), 0xFFEEEEEE.toInt(), 2f)
        p.style = Paint.Style.FILL
        p.color = Protocol.WIFI.color
        c.drawRoundRect(RectF(ix - dp(8f), y - dp(13f), ix + dp(10f), y - dp(5f)), dp(2f), dp(2f), p)
        text(c, "Estimated position of a signal. Tap the flag.", tx, y, 13f, 0xFFCCCCCC.toInt())

        y += gap
        line(c, ix - dp(12f), y - dp(4f), ix + dp(12f), y - dp(4f), 0xFFFF5252.toInt(), 3f)
        text(c, "Line from you to the signal you are tracking.", tx, y, 13f, 0xFFCCCCCC.toInt())
    }
}

/** Straight path (two mirror answers) versus a curved path (one answer). */
internal class WalkDiagram(ctx: Context) : Diagram(ctx, 190) {
    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val gap = dp(8f)
        val pw = (w - gap) / 2
        val left = RectF(0f, dp(24f), pw, dp(160f))
        val right = RectF(pw + gap, dp(24f), w, dp(160f))
        panel(c, left)
        panel(c, right)
        text(c, "Straight line", left.centerX(), dp(16f), 13f, 0xFFFFB74D.toInt(), Paint.Align.CENTER, true)
        text(c, "Curved path", right.centerX(), dp(16f), 13f, 0xFF81C784.toInt(), Paint.Align.CENTER, true)

        val cols = intArrayOf(0xFFF44336.toInt(), 0xFFFF9800.toInt(), 0xFFCDDC39.toInt(), 0xFF4CAF50.toInt(), 0xFFCDDC39.toInt(), 0xFFFF9800.toInt(), 0xFFF44336.toInt())

        // straight: dots on a line, two mirror candidates
        val ly = left.centerY()
        for (i in 0..6) {
            val x = left.left + dp(18f) + i * (left.width() - dp(36f)) / 6
            dot(c, x, ly, dp(4f), cols[i])
        }
        for (dy in floatArrayOf(-dp(42f), dp(42f))) {
            val x = left.centerX()
            p.style = Paint.Style.STROKE
            p.strokeWidth = dp(2f)
            p.color = 0xFF9E9E9E.toInt()
            p.pathEffect = android.graphics.DashPathEffect(floatArrayOf(dp(4f), dp(4f)), 0f)
            c.drawCircle(x, ly + dy, dp(13f), p)
            p.pathEffect = null
            text(c, "?", x, ly + dy + dp(5f), 15f, 0xFF9E9E9E.toInt(), Paint.Align.CENTER, true)
        }

        // curved: dots on an arc, one answer
        val tcx = right.centerX() + dp(4f)
        val tcy = right.centerY() + dp(10f)
        val r = dp(38f)
        for (i in 0..8) {
            val a = Math.toRadians(150.0 + i * 30.0)   // sweeps from left, over the bottom, up the right
            val x = tcx + (r * cos(a)).toFloat()
            val y = tcy - (r * sin(a)).toFloat()
            dot(c, x, y, dp(4f), 0xFF4CAF50.toInt())
        }
        flag(c, tcx, tcy + dp(6f), Protocol.WIFI)

        text(c, "Two mirror answers", left.centerX(), dp(177f), 12f, 0xFFCCCCCC.toInt(), Paint.Align.CENTER)
        text(c, "One answer", right.centerX(), dp(177f), 12f, 0xFFCCCCCC.toInt(), Paint.Align.CENTER)
    }
}

/** The direction arrow relative to the phone's heading. */
internal class ArrowDiagram(ctx: Context) : Diagram(ctx, 150) {
    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val cx = 0.2f * w
        val cy = dp(80f)
        val r = dp(48f)

        ring(c, cx, cy, r, 0xFF666666.toInt(), 3f)
        c.save()
        c.rotate(50f, cx, cy)
        val path = Path().apply {
            moveTo(cx, cy - r * 0.85f)
            lineTo(cx + r * 0.38f, cy + r * 0.5f)
            lineTo(cx, cy + r * 0.25f)
            lineTo(cx - r * 0.38f, cy + r * 0.5f)
            close()
        }
        p.style = Paint.Style.FILL
        p.color = 0xFF4CAF50.toInt()
        c.drawPath(path, p)
        c.restore()
        text(c, "ahead", cx, dp(16f), 12f, 0xFF9E9E9E.toInt(), Paint.Align.CENTER)
        line(c, cx, dp(22f), cx, dp(30f), 0xFF9E9E9E.toInt(), 2f)

        // top-down scene
        val yx = 0.62f * w
        val yy = dp(118f)
        val cone = Path().apply {
            moveTo(yx, yy)
            lineTo(yx - dp(24f), yy - dp(70f))
            lineTo(yx + dp(24f), yy - dp(70f))
            close()
        }
        p.style = Paint.Style.FILL
        p.color = 0x3300E5FF
        c.drawPath(cone, p)
        dot(c, yx, yy, dp(6f), 0xFF00E5FF.toInt())
        text(c, "you, facing up", yx, yy + dp(18f), 12f, 0xFF9E9E9E.toInt(), Paint.Align.CENTER)

        val fx = 0.88f * w
        val fy = dp(62f)
        p.style = Paint.Style.STROKE
        p.strokeWidth = dp(2f)
        p.color = 0xFFFF5252.toInt()
        p.pathEffect = android.graphics.DashPathEffect(floatArrayOf(dp(5f), dp(4f)), 0f)
        c.drawLine(yx, yy, fx, fy, p)
        p.pathEffect = null
        flag(c, fx - dp(10f), fy, Protocol.BLE)
        text(c, "turn right", (yx + fx) / 2 + dp(4f), dp(100f), 12f, 0xFF81C784.toInt(), Paint.Align.CENTER, true)
    }
}

/** All four flag types. */
internal class FlagsDiagram(ctx: Context) : Diagram(ctx, 110) {
    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        panel(c, RectF(0f, 0f, w, dp(110f)))
        val protos = Protocol.values()
        val colW = w / protos.size
        for ((i, pr) in protos.withIndex()) {
            val x = colW * i + colW / 2 - dp(20f)
            flag(c, x, dp(50f), pr)
            text(c, pr.label, colW * i + colW / 2, dp(78f), 12f, 0xFFCCCCCC.toInt(), Paint.Align.CENTER)
        }
        text(c, "Tap a flag for its details page", w / 2, dp(100f), 12f, 0xFF9E9E9E.toInt(), Paint.Align.CENTER)
    }
}

/** What each signal type can give you. */
internal class TableDiagram(ctx: Context) : Diagram(ctx, 170) {
    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        panel(c, RectF(0f, 0f, w, dp(170f)))
        val pad = dp(12f)
        val c1 = w * 0.52f
        val c2 = w * 0.80f
        val head = 0xFF9E9E9E.toInt()
        text(c, "Type", pad, dp(24f), 12f, head, bold = true)
        text(c, "Distance", c1, dp(24f), 12f, head, Paint.Align.CENTER, true)
        text(c, "Direction", c2, dp(24f), 12f, head, Paint.Align.CENTER, true)
        line(c, pad, dp(32f), w - pad, dp(32f), 0xFF3A3A3A.toInt(), 1f)

        val rows = listOf(
            Triple(Protocol.WIFI, "guess", "walk"),
            Triple(Protocol.BLE, "guess", "walk"),
            Triple(Protocol.BT_CLASSIC, "guess", "walk"),
            Triple(Protocol.CELL, "none", "none")
        )
        for ((i, r) in rows.withIndex()) {
            val y = dp(56f) + i * dp(28f)
            dot(c, pad + dp(5f), y - dp(4f), dp(5f), r.first.color)
            text(c, r.first.label, pad + dp(18f), y, 14f, Color.WHITE)
            val okColor = 0xFF81C784.toInt()
            val noColor = 0xFF757575.toInt()
            text(c, r.second, c1, y, 13f, if (r.second == "none") noColor else okColor, Paint.Align.CENTER)
            text(c, r.third, c2, y, 13f, if (r.third == "none") noColor else okColor, Paint.Align.CENTER)
        }
        text(c, "guess = from signal strength, rough.   walk = needs you to walk 10 m+", pad, dp(162f), 11f, head)
    }
}

/** Mapped camera dot with its facing wedge, and the red watchlist flag. */
internal class AlprDiagram(ctx: Context) : Diagram(ctx, 150) {
    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        panel(c, RectF(0f, 0f, w, dp(150f)))
        val pad = dp(12f)
        val ix = pad + dp(26f)
        val tx = pad + dp(66f)

        // camera dot with a wedge pointing up-right
        val cx = ix
        val cy = dp(52f)
        val len = dp(30f)
        val wedge = Path().apply {
            moveTo(cx, cy)
            val a1 = Math.toRadians(23.0)
            val a2 = Math.toRadians(67.0)
            lineTo(cx + len * sin(a1).toFloat(), cy - len * cos(a1).toFloat())
            lineTo(cx + len * sin(a2).toFloat(), cy - len * cos(a2).toFloat())
            close()
        }
        p.style = Paint.Style.FILL
        p.color = 0x66FF5252
        c.drawPath(wedge, p)
        dot(c, cx, cy, dp(6f), 0xFFB71C1C.toInt())
        ring(c, cx, cy, dp(6f), Color.WHITE, 1.5f)
        text(c, "Mapped camera. The wedge shows the way it faces.", tx, cy + dp(4f), 13f, 0xFFCCCCCC.toInt())

        // watchlist flag
        val fy = dp(120f)
        val fd = FlagDrawable("ALPR?", 0xFFD32F2F.toInt(), d, false)
        val l = (ix - dp(2f)).toInt()
        val t = (fy - fd.intrinsicHeight).toInt()
        fd.setBounds(l, t, l + fd.intrinsicWidth, t + fd.intrinsicHeight)
        fd.draw(c)
        text(c, "Signal matching a reported pattern.", tx, fy - dp(14f), 13f, 0xFFCCCCCC.toInt())
        text(c, "A lead, not proof.", tx, fy + dp(2f), 13f, 0xFF9E9E9E.toInt())
    }
}
