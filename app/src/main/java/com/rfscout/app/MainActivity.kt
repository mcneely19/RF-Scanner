package com.rfscout.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.OvalShape
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import org.osmdroid.config.Configuration
import org.osmdroid.events.DelayedMapListener
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/** A watchlist hit counts as "near a mapped camera" when one is mapped within this many metres. */
private const val MAPPED_RADIUS_M = 150.0

/** Most signal cards shown at once; the rest are still tracked, mapped and exported. */
private const val MAX_ROWS = 50

/** An unknown tag counts as "following" after this much time in range, this far from where first seen. */
private const val FOLLOW_MIN = 8L
private const val FOLLOW_DIST_M = 300.0
private const val FOLLOW_GAP_MS = 180_000L
private const val MY_ALERT_REPEAT_MS = 10L * 60 * 1000
private const val ALERT_REPEAT_MS = 30L * 60 * 1000
private const val MAX_TRACKS = 1500

class MainActivity : Activity(), SensorEventListener, LocationListener {

    private val tracks = LinkedHashMap<String, Track>()
    private val protoShown = HashMap<Protocol, Boolean>().apply { Protocol.values().forEach { put(it, true) } }
    private val protoBoxes = HashMap<Protocol, CheckBox>()
    private val protoOn = HashMap<Protocol, Boolean>().apply { Protocol.values().forEach { put(it, it != Protocol.ADSB) } }
    private val scanners = HashMap<Protocol, RfScanner>()
    private val errors = HashMap<Protocol, String>()
    private val breadcrumb = ArrayList<GeoPoint>()
    private val handler = Handler(Looper.getMainLooper())

    private var running = false
    private var resumeOnStart = false
    private var selectedKey: String? = null
    private var loc: Location? = null
    private var firstFixDone = false
    private var headingX = 1.0
    private var headingY = 0.0
    private var headingDeg = 0.0
    private var declination = 0f
    private var targetBearing: Double? = null
    private var lastArrowMs = 0L
    private var trackAll = false
    private var sheetKey: String? = null
    private var alprOn = false
    private var sightDirty = false
    private var lastSightSave = 0L
    private val sightings = LinkedHashMap<String, Sighting>()
    private val prefs by lazy { getSharedPreferences("rfscout", MODE_PRIVATE) }
    private val deflock by lazy { Deflock(applicationContext, handler) { refreshUi() } }
    private val towers by lazy { Towers(applicationContext, handler) { refreshUi() } }
    private val mine = HashSet<String>()
    private val alertedAt = AlertGate(ALERT_REPEAT_MS)
    private val myAlertedAt = AlertGate(MY_ALERT_REPEAT_MS)
    private val gate = ReadingGate()
    private var lastPruneMs = 0L
    private var watchSound = true
    private val secrets by lazy { getSharedPreferences("secrets", MODE_PRIVATE) }   // kept out of cloud backups
    private var bgScan = false
    private var serviceActive = false
    private var inBackground = false
    private var trackerAlerts = true
    private var locatedOnly = true
    private lateinit var locatedSwitch: Switch
    private var lastNotifMs = 0L

    private lateinit var countText: TextView
    private lateinit var radar: RadarView
    private lateinit var radarBox: LinearLayout
    private lateinit var radarList: ListView
    private val radarAdapter = RadarRowAdapter()
    private var mode = 0
    private var indoor = false
    private val indoorTracker = IndoorTracker()
    private var stepCalibFrom: Int? = null
    private var lastVisible: List<Track> = emptyList()
    private lateinit var modeBtn: Button
    private lateinit var sheet: InfoSheet
    private lateinit var startBtn: Button
    private lateinit var listView: ListView
    private lateinit var map: MapView
    private lateinit var statusText: TextView
    private lateinit var targetText: TextView
    private lateinit var stopBtn: Button
    private lateinit var arrow: ArrowView
    private val adapter = SignalAdapter()

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cfg = Configuration.getInstance()
        cfg.load(applicationContext, getSharedPreferences("osmdroid", MODE_PRIVATE))
        cfg.userAgentValue = packageName
        val base = File(cacheDir, "osmdroid")
        val tiles = File(base, "tiles")
        tiles.mkdirs()
        cfg.osmdroidBasePath = base
        cfg.osmdroidTileCache = tiles
        setContentView(buildUi())
        for (s in sightingsFromJson(prefs.getString("sightings", "[]") ?: "[]")) sightings[s.key] = s

        // settings that persist between runs
        for (p in Protocol.values()) {
            protoShown[p] = prefs.getBoolean("proto_shown_${p.name}", true)
            protoOn[p] = protoShown[p] == true && prefs.getBoolean("proto_${p.name}", p != Protocol.ADSB)
        }
        alprOn = prefs.getBoolean("alpr_on", false)
        bgScan = prefs.getBoolean("bg_scan", false)
        trackerAlerts = prefs.getBoolean("tracker_alerts", true)
        locatedOnly = prefs.getBoolean("located_only", true)
        locatedSwitch.isChecked = locatedOnly
        indoorTracker.stepLenM = prefs.getFloat("step_len", 0.7f).toDouble()
        indoor = prefs.getBoolean("indoor", false)
        if (indoor) loc = indoorTracker.location()
        towers.apiKey = prefs.getString("ocid_key", "") ?: ""
        mine.addAll(prefs.getStringSet("mine", emptySet()) ?: emptySet())
        Calib.fromJson(prefs.getString("calib", "{}") ?: "{}")
        MyWatchStore.load(prefs)
        watchSound = prefs.getBoolean("watch_sound", true)
        Vendors.init(applicationContext)
        Alerts.ensureChannels(applicationContext)
        deflock.loadStored()                       // saved cameras show immediately, even offline
        ScanService.stopRequest = {
            resumeOnStart = false
            stopAll()
        }
    }

    override fun onStart() {
        super.onStart()
        if (inBackground) exitBackground()
        if (resumeOnStart) {
            resumeOnStart = false
            startAll()
        }
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
    }

    override fun onPause() {
        map.onPause()
        super.onPause()
    }

    override fun onStop() {
        saveSightings(true)
        if (running) {
            if (bgScan && serviceActive && !isChangingConfigurations) {
                enterBackground()                  // keep scanning with the screen off
            } else {
                resumeOnStart = true
                stopAll()
            }
        }
        super.onStop()
    }

    override fun onDestroy() {
        ScanService.stopRequest = null
        stopAll()
        map.onDetach()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ UI

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
            setPadding(dp(6), dp(4), dp(6), 0)
        }
        fun styled(label: String, primary: Boolean = false, onClick: () -> Unit) = Button(this).apply {
            text = label
            Ui.styleButton(this@MainActivity, this, primary)
            setOnClickListener { onClick() }
        }

        // header: start/stop, clear, help, more
        startBtn = styled("Start", true) { onStartStop() }
        val clearBtn = styled("Clear") {
            tracks.clear()
            gate.clear()
            breadcrumb.clear()
            SessionLog.clear()
            selectedKey = null
            closeSheet()
            refreshUi()
        }
        val helpBtn = styled("Help") { startActivity(Intent(this@MainActivity, HelpActivity::class.java)) }
        val moreBtn = styled("More") { showMore() }
        root.addView(row(startBtn, clearBtn, helpBtn, moreBtn))

        // protocol tick boxes (each can be hidden from Settings > Signal tick boxes)
        val toggles = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (p in Protocol.values()) {
            val box = CheckBox(this).apply {
                text = shortName(p)
                textSize = 11f
                maxLines = 1
                setPadding(0, 0, 0, 0)
                setAutoSizeTextTypeUniformWithConfiguration(8, 11, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
                setTextColor(Ui.TEXT)
                // blue when ticked, grey when not
                buttonTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(0xFF2196F3.toInt(), 0xFF9E9E9E.toInt())
                )
                isChecked = protoOn[p] == true
                visibility = if (protoShown[p] == true) View.VISIBLE else View.GONE
                setOnCheckedChangeListener { _, on -> setProto(p, on) }
            }
            protoBoxes[p] = box
            toggles.addView(box, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(toggles)

        // tabs
        val tabSignals = styled("Signals") { showMode(0) }
        val tabRadar = styled("Radar") { showMode(1) }
        val tabMap = styled("Map") { showMode(2) }
        val centerBtn = styled("Center") {
            loc?.let { map.controller.setCenter(GeoPoint(it.latitude, it.longitude)) }
            showMode(2)
        }
        modeBtn = styled("Track: One") {
            trackAll = !trackAll
            modeBtn.text = if (trackAll) "Track: All" else "Track: One"
            refreshUi()
        }.apply {
            // the label is the longest in the row: keep it on one line (shrinking if needed) and centred
            maxLines = 1
            gravity = Gravity.CENTER
            setAutoSizeTextTypeUniformWithConfiguration(9, 12, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
        }
        root.addView(row(tabSignals, tabRadar, tabMap, centerBtn, modeBtn))

        countText = TextView(this).apply {
            setPadding(dp(8), dp(6), dp(8), 0)
            textSize = 11f
            setTextColor(Ui.ACCENT)
        }
        root.addView(countText)

        statusText = TextView(this).apply {
            setPadding(dp(8), dp(2), dp(8), dp(4))
            textSize = 11f
            setTextColor(Ui.DIM)
            text = "Press Start, then tap a signal to track it.\nWalk around: direction needs readings from several places."
        }
        root.addView(statusText)

        // content: list, radar or map
        listView = ListView(this).apply {
            adapter = this@MainActivity.adapter
            divider = ColorDrawable(Color.TRANSPARENT)
            dividerHeight = dp(6)
            selector = ColorDrawable(Color.TRANSPARENT)
            setPadding(dp(2), dp(4), dp(2), dp(4))
            clipToPadding = false
            setOnItemClickListener { _, _, pos, _ ->
                selectedKey = toggledSelection(this@MainActivity.adapter.items[pos].key)
                this@MainActivity.adapter.notifyDataSetChanged()
                updateTarget()
            }
            setOnItemLongClickListener { _, _, pos, _ ->
                openSheet(this@MainActivity.adapter.items[pos].key)
                true
            }
        }
        radar = RadarView(this) { key ->
            // tapping a dot selects it (tapping the selected one again deselects) and scrolls the list to its row
            selectedKey = toggledSelection(key)
            refreshUi()
            val idx = radarAdapter.items.indexOfFirst { it.key == key }
            if (idx >= 0) radarList.smoothScrollToPosition(idx)
        }
        radarList = ListView(this).apply {
            adapter = radarAdapter
            divider = ColorDrawable(Ui.BORDER)
            dividerHeight = 1
            selector = ColorDrawable(Color.TRANSPARENT)
            setOnItemClickListener { _, _, pos, _ ->
                selectedKey = toggledSelection(radarAdapter.items[pos].key)
                refreshUi()
            }
            setOnItemLongClickListener { _, _, pos, _ ->
                openSheet(radarAdapter.items[pos].key)
                true
            }
        }
        locatedSwitch = Switch(this).apply {
            text = "Located only (hide signals with no direction yet)"
            textSize = 12f
            setTextColor(Ui.TEXT)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            isChecked = true
            setOnCheckedChangeListener { _, on ->
                locatedOnly = on
                prefs.edit().putBoolean("located_only", on).apply()
                if (mode == 1) updateRadar(false)
            }
        }
        radarBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(radar, LinearLayout.LayoutParams(-1, 0, 1.15f))
            addView(locatedSwitch, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(radarList, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        map = MapView(this).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            setTilesScaledToDpi(true)
            controller.setZoom(19.0)
            visibility = View.GONE
        }
        // load ALPR cameras for wherever the map ends up after a pan or zoom settles
        map.addMapListener(
            DelayedMapListener(
                object : MapListener {
                    override fun onScroll(event: ScrollEvent?): Boolean {
                        maybeLoadCameras()
                        return false
                    }

                    override fun onZoom(event: ZoomEvent?): Boolean {
                        maybeLoadCameras()
                        return false
                    }
                },
                700
            )
        )
        val content = FrameLayout(this)
        content.addView(listView, FrameLayout.LayoutParams(-1, -1))
        content.addView(radarBox, FrameLayout.LayoutParams(-1, -1))
        content.addView(map, FrameLayout.LayoutParams(-1, -1))
        sheet = InfoSheet(
            this,
            onTrack = { key ->
                selectedKey = toggledSelection(key)
                refreshUi()
            },
            onCalibrate = { key -> showCalibrate(key) },
            onMine = { key -> markMine(key) },
            onWatch = { key -> watchSignal(key) },
            onMute = { key -> toggleMute(key) },
            onClose = { closeSheet() }
        )
        sheet.visibility = View.GONE
        content.addView(
            sheet,
            FrameLayout.LayoutParams(-1, (resources.displayMetrics.heightPixels * 0.42f).toInt(), Gravity.BOTTOM)
        )
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))

        // target panel
        arrow = ArrowView(this)
        targetText = TextView(this).apply {
            textSize = 13f
            setTextColor(Ui.TEXT)
            text = "Tap a signal to track it"
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = Ui.box(resources.displayMetrics.density, Ui.PANEL, Ui.BORDER, 10f)
        }
        panel.addView(arrow, LinearLayout.LayoutParams(dp(84), dp(84)))
        panel.addView(targetText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = dp(12)
        })
        stopBtn = styled("Stop") { stopTracking() }.apply { visibility = View.GONE }
        panel.addView(stopBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(8)
        })
        root.addView(panel, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(4)
            bottomMargin = dp(4)
        })
        return root
    }

    private fun row(vararg views: View): LinearLayout {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        for (v in views) {
            r.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(2), dp(2), dp(2), dp(2))
            })
        }
        return r
    }

    /** 0 = signal list, 1 = radar, 2 = map */
    private fun showMode(requested: Int) {
        var m = requested
        if (m == 2 && indoor) {
            toast("The map is off in indoor mode (positions are not real map positions). Showing the radar.")
            m = 1
        }
        mode = m
        listView.visibility = if (m == 0) View.VISIBLE else View.GONE
        radarBox.visibility = if (m == 1) View.VISIBLE else View.GONE
        map.visibility = if (m == 2) View.VISIBLE else View.GONE
        countText.visibility = if (m == 0) View.VISIBLE else View.GONE
        refreshUi()
        if (m == 2) handler.postDelayed({ maybeLoadCameras() }, 400)
    }

    private fun showMap(show: Boolean) = showMode(if (show) 2 else 0)

    private fun showMore() {
        val ctx = this
        val col = MenuUi.column(ctx)
        var dlg: android.app.Dialog? = null
        fun go(action: () -> Unit): () -> Unit = {
            dlg?.dismiss()
            action()
        }
        col.addView(MenuUi.section(ctx, "Modes"))
        col.addView(MenuUi.toggle(ctx, "ALPR camera layer", { alprOn }) { toggleAlpr() })
        col.addView(MenuUi.toggle(ctx, "Screen-off scanning", { bgScan }) { toggleBgScan() })
        col.addView(MenuUi.toggle(ctx, "Indoor mode (steps, no GPS)", { indoor }) { toggleIndoor() })
        col.addView(MenuUi.toggle(ctx, "Watchlist alert sound", { watchSound }) {
            watchSound = !watchSound
            prefs.edit().putBoolean("watch_sound", watchSound).apply()
        })
        col.addView(MenuUi.section(ctx, "Data"))
        col.addView(MenuUi.row(ctx, "Sightings log", null, "${sightings.size}", go { showSightings() }))
        col.addView(MenuUi.row(ctx, "My watchlist", null, "${MyWatch.entries.size}", go { showMyWatch() }))
        col.addView(MenuUi.row(ctx, "Muted signals", null, "${MyWatch.muted.size}", go { showMuted() }))
        col.addView(MenuUi.row(ctx, "Session debrief", null, null, go { showDebrief() }))
        col.addView(MenuUi.row(ctx, "Scan health", null, if (gate.safeMode) "SAFE MODE" else null, go { showHealth() }))
        col.addView(MenuUi.row(ctx, "Tags (item trackers)", null, "${tracks.values.count { it.trackerKind != null }}", go { showTags() }))
        col.addView(MenuUi.row(ctx, "Export session (CSV + GPX)", null, null, go { exportSession() }))
        col.addView(MenuUi.section(ctx, "Setup"))
        col.addView(MenuUi.row(ctx, "Indoor options (reset start, step length)", null, null, go { showIndoorOptions() }))
        col.addView(MenuUi.row(ctx, "Settings", null, null, go { showSettings() }))
        col.addView(MenuUi.note(ctx, "${MenuUi.ON} ON   ${MenuUi.OFF} OFF   tap a row to switch it or open it"))
        val shown = MenuUi.dialog(ctx, "More", col, listOf(MenuUi.button(ctx, "Close") { dlg?.dismiss() }))
        dlg = shown
        shown.show()
    }

    // ------------------------------------------------------------------ indoor mode

    private fun toggleIndoor() {
        if (indoor) {
            setIndoor(false)
            return
        }
        val sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        if (sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR) == null || sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) == null) {
            toast("This phone has no step sensor or gyroscope orientation, so indoor mode is not available")
            return
        }
        if (Build.VERSION.SDK_INT >= 29 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.ACTIVITY_RECOGNITION), 2)
            return
        }
        setIndoor(true)
    }

    private fun setIndoor(on: Boolean) {
        indoor = on
        prefs.edit().putBoolean("indoor", on).apply()
        // positions from one source are meaningless in the other, so start clean
        resetPositions()
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (running) {
            if (on) lm.removeUpdates(this) else startLocation(lm)
            restartSensors()
        }
        if (on) {
            indoorTracker.reset()
            loc = indoorTracker.location()
            toast("Indoor mode on. Hold the phone in front of you and walk; press More > Indoor options > Reset start point when you begin.")
            if (mode == 2) showMode(1)
        } else {
            loc = null
            toast("Indoor mode off. Waiting for a GPS fix.")
        }
        refreshUi()
    }

    /** Forget walk data: it was collected in a different position frame. */
    private fun resetPositions() {
        breadcrumb.clear()
        for (t in tracks.values) {
            t.samples.clear()
            t.sampleSeq = 0
            t.fixSeq = -1
            t.fix = null
            t.fixNote = ""
            // tag-following distances were measured in the other position frame
            t.hasFirstPos = false
            t.maxTravelM = 0.0
            t.followSinceMs = 0L
            t.following = false
        }
    }

    private fun showIndoorOptions() {
        if (!indoor) {
            toast("Turn on indoor mode first")
            return
        }
        val items = arrayOf(
            "Reset start point (clears walk data)",
            "Step length: short, 0.6 m",
            "Step length: average, 0.7 m",
            "Step length: long, 0.8 m",
            "Calibrate step length: walk 10 m"
        )
        AlertDialog.Builder(this)
            .setTitle("Indoor options (now ${"%.2f".format(indoorTracker.stepLenM)} m per step)")
            .setItems(items) { _, i ->
                when (i) {
                    0 -> {
                        indoorTracker.reset()
                        resetPositions()
                        loc = indoorTracker.location()
                        toast("Start point reset")
                        refreshUi()
                    }
                    1 -> setStepLength(0.6)
                    2 -> setStepLength(0.7)
                    3 -> setStepLength(0.8)
                    4 -> calibrateStepLength()
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun setStepLength(m: Double) {
        indoorTracker.stepLenM = m
        prefs.edit().putFloat("step_len", m.toFloat()).apply()
        toast("Step length ${"%.2f".format(m)} m")
    }

    private fun calibrateStepLength() {
        stepCalibFrom = indoorTracker.steps
        AlertDialog.Builder(this)
            .setTitle("Walk exactly 10 m")
            .setMessage("Walk 10 m in a straight line at your normal pace, then tap Done. Steps are counted from now.")
            .setPositiveButton("Done") { _, _ ->
                val n = indoorTracker.steps - (stepCalibFrom ?: indoorTracker.steps)
                stepCalibFrom = null
                if (n >= 8) {
                    setStepLength((10.0 / n).coerceIn(0.4, 1.1))
                } else {
                    toast("Only $n steps counted. Try again, walking a bit further.")
                }
            }
            .setNegativeButton("Cancel") { _, _ -> stepCalibFrom = null }
            .setCancelable(false)
            .show()
    }

    private fun toggleAlpr() {
        alprOn = !alprOn
        prefs.edit().putBoolean("alpr_on", alprOn).apply()
        if (alprOn) {
            showMap(true)
            handler.postDelayed({ maybeLoadCameras() }, 400)
        } else {
            refreshUi()
        }
        toast("ALPR layer ${if (alprOn) "on" else "off"}")
    }

    /** Compact one-line-plus rows under the radar. */
    private inner class RadarRowAdapter : BaseAdapter() {
        var items: List<Track> = emptyList()

        override fun getCount() = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val tv = (convertView as? TextView) ?: TextView(this@MainActivity).apply {
                setPadding(dp(10), dp(6), dp(10), dp(6))
                textSize = 13f
                setTextColor(Ui.TEXT)
            }
            val t = items[position]
            val l = loc
            val f = t.fix
            val dist = if (f != null && l != null) fmtDist(Rf.meters(l.latitude, l.longitude, f.lat, f.lon))
            else Rf.modelDistance(t)?.let { "~" + fmtDist(it) } ?: "n/a"
            val bearing = if (f != null && l != null) " · @${"%.0f".format(Rf.distanceBearing(l.latitude, l.longitude, f.lat, f.lon).second)}°" else ""
            val flag = if (t.following || t.watch != null) "  ⚠" else if (t.myHit != null) "  ★" else ""
            val sb = SpannableStringBuilder()
            sb.append("● ")
            sb.setSpan(ForegroundColorSpan(t.protocol.color or 0xFF000000.toInt()), 0, 1, 0)
            val st = sb.length
            sb.append(t.name).append(flag)
            sb.setSpan(StyleSpan(Typeface.BOLD), st, sb.length, 0)
            sb.append("\n${t.protocol.label} · ${t.rssi} dBm · $dist$bearing")
            sb.setSpan(ForegroundColorSpan(Ui.DIM), st + t.name.length + flag.length + 1, sb.length, 0)
            tv.text = sb
            tv.setBackgroundColor(if (t.key == selectedKey) Ui.PANEL_HI else Color.TRANSPARENT)
            return tv
        }
    }

    private inner class SignalAdapter : BaseAdapter() {
        var items: List<Track> = emptyList()

        override fun getCount() = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int) = position.toLong()

        private fun ago(ms: Long): String {
            val s = (ms / 1000).coerceAtLeast(0)
            return if (s < 90) "${s}s" else if (s < 5400) "${s / 60}m" else "${s / 3600}h"
        }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val tv = (convertView as? TextView) ?: TextView(this@MainActivity).apply {
                setPadding(dp(12), dp(8), dp(12), dp(8))
                textSize = 13f
                setTextColor(Ui.TEXT)
            }
            val t = items[position]
            val now = System.currentTimeMillis()
            val dist = Rf.modelDistance(t)?.let { fmtDist(it) } ?: "n/a"
            val watch = t.watch
            val vendor = Vendors.shortLine(t)
            val cat = Category.of(t)

            val sb = SpannableStringBuilder()
            fun line(text: String, color: Int, bold: Boolean = false, size: Float = 1f) {
                val st = sb.length
                sb.append(text).append('\n')
                sb.setSpan(ForegroundColorSpan(color), st, sb.length, 0)
                if (bold) sb.setSpan(StyleSpan(Typeface.BOLD), st, sb.length, 0)
                if (size != 1f) sb.setSpan(RelativeSizeSpan(size), st, sb.length, 0)
            }
            val nameColor = if (watch != null || t.following) Ui.ALERT else lighten(t.protocol.color or 0xFF000000.toInt())
            line("${t.name}   ${signalBars(t.rssiSmooth)}", nameColor, true, 1.1f)
            line("${t.protocol.label} · ${t.rssi} dBm · ~$dist" + (if (t.freqMhz > 0 && t.protocol == Protocol.WIFI) " · ${t.freqMhz} MHz" else ""), Ui.TEXT)
            if (t.detail.isNotBlank()) line(t.detail, Ui.DIM, false, 0.9f)
            if (vendor != null) line(vendor, Ui.DIM, false, 0.9f)
            if (cat != null) line("Category (guess): $cat", Ui.ACCENT, false, 0.9f)
            if (t.following) line("⚠ Has stayed with you: possible tracker", Ui.ALERT, true, 0.95f)
            if (watch != null) line("⚑ Possible ALPR: ${watch.label}", Ui.WARN, false, 0.9f)
            line("First seen ${ago(now - t.firstSeenMs)} ago · last ${ago(now - t.lastSeenMs)} ago", Ui.DIM, false, 0.85f)
            sb.delete(sb.length - 1, sb.length)
            tv.text = sb

            val d = resources.displayMetrics.density
            val border = when {
                t.following -> Ui.ALERT
                watch != null -> Ui.WARN
                t.key == selectedKey -> Ui.ACCENT
                else -> Ui.withAlpha(t.protocol.color or 0xFF000000.toInt(), 150)
            }
            val fill = when {
                t.following -> 0xFF2A0C12.toInt()
                t.key == selectedKey -> Ui.PANEL_HI
                else -> Ui.PANEL
            }
            tv.background = Ui.box(d, fill, border, 8f, if (t.key == selectedKey) 2f else 1.2f)
            return tv
        }

        /** Brighten a protocol color so it reads on the dark background. */
        private fun lighten(c: Int): Int =
            Color.rgb((Color.red(c) + 255) / 2, (Color.green(c) + 255) / 2, (Color.blue(c) + 255) / 2)
    }

    // ------------------------------------------------------------------ start / stop

    private fun neededPermissions(): List<String> {
        val p = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_PHONE_STATE
        )
        if (Build.VERSION.SDK_INT >= 31) {
            p.add(Manifest.permission.BLUETOOTH_SCAN)
            p.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= 33) p.add(Manifest.permission.POST_NOTIFICATIONS)   // tracker alerts, screen-off notice
        return p
    }

    private fun onStartStop() {
        if (running) {
            stopAll()
            return
        }
        val missing = neededPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startAll() else requestPermissions(missing.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 2) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) setIndoor(true)
            else toast("Indoor mode needs the Physical activity permission to count steps")
            return
        }
        if (requestCode != 1) return
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        if (fine == PackageManager.PERMISSION_GRANTED) startAll() else toast("Precise location permission is required")
    }

    private fun startAll() {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (!LocationManagerCompat.isLocationEnabled(lm)) {
            toast("Turn on Location (needed for Wi-Fi, Bluetooth and cell scans)")
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            return
        }
        running = true
        startBtn.text = "Stop"
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (!indoor) startLocation(lm)
        startSensors()
        for (p in Protocol.values()) if (protoOn[p] == true) startScanner(p)
        handler.post(uiTick)
        if (bgScan) startBackgroundService()
    }

    private fun stopAll() {
        running = false
        inBackground = false
        if (serviceActive) {
            ScanService.stop(applicationContext)
            serviceActive = false
        }
        handler.removeCallbacks(uiTick)
        for (s in scanners.values) s.stop()
        (getSystemService(Context.LOCATION_SERVICE) as LocationManager).removeUpdates(this)
        (getSystemService(Context.SENSOR_SERVICE) as SensorManager).unregisterListener(this)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        startBtn.text = "Start"
    }

    // ------------------------------------------------------------------ screen-off scanning

    private fun toggleBgScan() {
        bgScan = !bgScan
        prefs.edit().putBoolean("bg_scan", bgScan).apply()
        if (running) {
            if (bgScan) startBackgroundService()
            else if (serviceActive) {
                ScanService.stop(applicationContext)
                serviceActive = false
            }
        }
        if (bgScan) toast("Scanning will continue with the screen off (a notification with a Stop button is shown)")
    }

    private fun startBackgroundService() {
        if (serviceActive) return
        val err = ScanService.start(applicationContext)
        if (err == null) {
            serviceActive = true
        } else {
            toast("Could not start screen-off scanning: $err")
        }
    }

    private fun enterBackground() {
        inBackground = true
        (scanners[Protocol.BLE] as? BleScanner)?.backgroundMode = true
        (getSystemService(Context.SENSOR_SERVICE) as SensorManager).unregisterListener(this)
    }

    private fun exitBackground() {
        inBackground = false
        (scanners[Protocol.BLE] as? BleScanner)?.backgroundMode = false
        if (running) startSensors()
        refreshUi()
    }

    private fun startScanner(p: Protocol) {
        val app = applicationContext
        val s = scanners.getOrPut(p) {
            when (p) {
                Protocol.WIFI -> WifiScanner(app, { protoOn[Protocol.RID] != false }, this::onReading)
                Protocol.BLE -> BleScanner(app, { protoOn[Protocol.RID] != false }, this::onReading)
                Protocol.RID -> object : RfScanner {      // Remote ID rides on the Wi-Fi and BLE scanners
                    override fun start(): String? = null
                    override fun stop() {}
                }
                Protocol.BT_CLASSIC -> BtClassicScanner(app, this::onReading)
                Protocol.CELL -> CellScanner(app, this::onReading)
                Protocol.ADSB -> SdrAdsbScanner(
                    prefs.getString("sdr_host", SdrAdsbScanner.DEFAULT_HOST) ?: SdrAdsbScanner.DEFAULT_HOST,
                    prefs.getInt("sdr_port", SdrAdsbScanner.DEFAULT_PORT),
                    { loc?.let { it.latitude to it.longitude } },
                    this::onReading,
                    { msg ->
                        if (msg == null) errors.remove(Protocol.ADSB) else errors[Protocol.ADSB] = msg
                        refreshUi()
                    }
                )
            }
        }
        val err = s.start()
        if (err != null) errors[p] = err else errors.remove(p)
    }

    // ------------------------------------------------------------------ location + heading

    @SuppressLint("MissingPermission")
    private fun startLocation(lm: LocationManager) {
        try {
            val all = lm.allProviders
            if (all.contains(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this)
            }
            if (all.contains(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000L, 0f, this)
            }
        } catch (_: SecurityException) {
            toast("Location permission denied")
        }
    }

    override fun onLocationChanged(location: Location) {
        if (indoor) return              // GPS is not trusted indoors; steps provide the position
        val cur = loc
        // prefer GPS: ignore a network fix while a recent GPS fix exists
        if (cur != null && cur.provider == LocationManager.GPS_PROVIDER &&
            location.provider != LocationManager.GPS_PROVIDER &&
            System.currentTimeMillis() - cur.time < 5000
        ) return
        loc = location
        declination = GeomagneticField(
            location.latitude.toFloat(), location.longitude.toFloat(),
            location.altitude.toFloat(), System.currentTimeMillis()
        ).declination

        if (location.accuracy <= 50f && !indoor) SessionLog.addPath(location.latitude, location.longitude, location.accuracy)
        if (location.accuracy <= 30f) {
            val last = breadcrumb.lastOrNull()
            if (last == null || Rf.meters(last.latitude, last.longitude, location.latitude, location.longitude) >= 2.0) {
                breadcrumb.add(GeoPoint(location.latitude, location.longitude))
                if (breadcrumb.size > 2000) breadcrumb.removeAt(0)
            }
        }
        if (!firstFixDone && !inBackground) {
            firstFixDone = true
            map.controller.setCenter(GeoPoint(location.latitude, location.longitude))
        }
    }

    // LocationListener methods that older Android versions still call
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    private fun startSensors() {
        val sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        // indoors the heading must not depend on the magnetic compass
        val type = if (indoor) Sensor.TYPE_GAME_ROTATION_VECTOR else Sensor.TYPE_ROTATION_VECTOR
        sm.getDefaultSensor(type)?.let {
            sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        if (indoor) {
            sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)?.let {
                sm.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
            }
        }
    }

    private fun restartSensors() {
        (getSystemService(Context.SENSOR_SERVICE) as SensorManager).unregisterListener(this)
        headingX = 1.0
        headingY = 0.0
        if (running && !inBackground) startSensors()
    }

    private fun onStep() {
        indoorTracker.onStep(headingDeg)
        loc = indoorTracker.location()
        val p = GeoPoint(loc!!.latitude, loc!!.longitude)
        val last = breadcrumb.lastOrNull()
        if (last == null || Rf.meters(last.latitude, last.longitude, p.latitude, p.longitude) >= 1.5) {
            breadcrumb.add(p)
            if (breadcrumb.size > 2000) breadcrumb.removeAt(0)
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_STEP_DETECTOR) {
            if (indoor) onStep()
            return
        }
        val want = if (indoor) Sensor.TYPE_GAME_ROTATION_VECTOR else Sensor.TYPE_ROTATION_VECTOR
        if (event.sensor.type != want) return
        val rm = FloatArray(9)
        val o = FloatArray(3)
        SensorManager.getRotationMatrixFromVector(rm, event.values)
        SensorManager.getOrientation(rm, o)
        if (abs(Math.toDegrees(o[1].toDouble())) > 50) {
            // phone held upright: use the direction the back of the phone faces
            val r2 = FloatArray(9)
            SensorManager.remapCoordinateSystem(rm, SensorManager.AXIS_X, SensorManager.AXIS_Z, r2)
            SensorManager.getOrientation(r2, o)
        }
        val az = Math.toRadians(Math.toDegrees(o[0].toDouble()) + (if (indoor) 0f else declination))
        headingX = 0.8 * headingX + 0.2 * cos(az)
        headingY = 0.8 * headingY + 0.2 * sin(az)
        headingDeg = (Math.toDegrees(atan2(headingY, headingX)) + 360.0) % 360.0

        val now = System.currentTimeMillis()
        if (now - lastArrowMs > 100) {
            lastArrowMs = now
            updateArrow()
            if (mode == 1 && !inBackground) updateRadar(false)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ------------------------------------------------------------------ readings

    private fun onReading(r: Reading) {
        val wall = System.currentTimeMillis()
        if (!gate.admit(r.protocol.label, r.key, wall, ReadingGate.gapFor(r.protocol))) return
        if (tracks.size > MAX_TRACKS && wall - lastPruneMs > 5_000) pruneTracks(wall)
        val t = tracks.getOrPut(r.key) { Track(r.key, r.protocol) }
        val gapMs = if (t.count > 0) r.timeMs - t.lastSeenMs else 0L
        t.update(r)
        t.myHit?.let { alertMine(t, it, r.timeMs) }

        val l = loc
        SessionLog.addReading(t, if (indoor) null else l?.latitude, if (indoor) null else l?.longitude, l?.accuracy ?: 0f)
        if (t.trackerKind != null) trackFollow(t, r, gapMs)

        if (l == null) return
        if (t.protocol == Protocol.CELL || t.protocol.reported) return
        if (l.accuracy > 30f || System.currentTimeMillis() - l.time > 10_000) return
        val last = t.samples.lastOrNull()
        if (last == null || Rf.meters(last.lat, last.lon, l.latitude, l.longitude) >= 3.0) {
            t.addSample(l.latitude, l.longitude)
        }
    }

    // ------------------------------------------------------------------ periodic refresh

    private val uiTick = object : Runnable {
        override fun run() {
            refreshUi()
            if (running) handler.postDelayed(this, 1000)
        }
    }

    private fun refreshUi() {
        val now = System.currentTimeMillis()
        if (indoor && running) loc = indoorTracker.location()      // keeps the position 'fresh' while standing still
        tracks.values.removeAll { now - it.lastSeenMs > 300_000 && it.key != selectedKey }
        val visible = tracks.values
            .filter { protoOn[it.protocol] == true && now - it.lastSeenMs < 60_000 }
            .sortedByDescending { it.rssiSmooth }

        // re-fit positions only for the strongest few + the selected one, and only when new samples arrived
        val toFit = LinkedHashSet<Track>(visible.take(6))
        selectedKey?.let { k -> tracks[k]?.let { toFit.add(it) } }
        sheetKey?.let { k -> tracks[k]?.let { toFit.add(it) } }
        for (t in toFit) {
            if (t.protocol == Protocol.CELL || t.protocol.reported) continue
            if (t.sampleSeq != t.fixSeq) {
                t.fixSeq = t.sampleSeq
                t.fix = Rf.locate(t)
            }
        }
        applyTowers(visible)
        updateSightings(now)

        if (inBackground) {
            // screen is off: skip drawing, only keep the notification text fresh
            if (now - lastNotifMs > 30_000) {
                lastNotifMs = now
                val tags = visible.count { it.trackerKind != null }
                val follow = visible.count { it.following }
                Alerts.updateScanText(
                    applicationContext,
                    "${visible.size} signals" + (if (tags > 0) " · $tags tags" else "") +
                        (if (follow > 0) " · $follow may be following you" else "")
                )
            }
            return
        }

        lastVisible = visible
        val shown = ArrayList<Track>(visible.take(MAX_ROWS))
        selectedKey?.let { k -> tracks[k]?.let { sel -> if (sel in visible && sel !in shown) shown.add(sel) } }
        adapter.items = shown
        adapter.notifyDataSetChanged()
        countText.text = if (visible.size > shown.size) "Showing top ${shown.size} of ${visible.size} (strongest first)"
        else "Showing all ${visible.size} (strongest first)"
        updateStatus(visible)
        updateTarget()
        if (sheetKey != null) bindSheet()
        if (mode == 1) updateRadar(true)
        if (map.visibility == View.VISIBLE) updateMap(visible)
    }

    /** Place each signal on the radar: by fit direction when known, otherwise by distance on the lower arc. */
    private fun updateRadar(refreshList: Boolean) {
        val l = loc
        val items = ArrayList<RadarDot>()
        val dists = ArrayList<Double>()
        val vis = lastVisible.filter { (selectedKey == null || trackAll || it.key == selectedKey) }
        var unknownIdx = 0
        var unknownN = 0
        var withDir = 0
        var hiddenN = 0
        for (t in vis) {
            val f = t.fix
            val sel = t.key == selectedKey
            val alert = t.following || t.watch != null || t.myHit != null
            if (f != null && l != null) {
                val (d, b) = Rf.distanceBearing(l.latitude, l.longitude, f.lat, f.lon)
                var gDist: Double? = null
                var gDeg = 0.0
                if (f.ambiguous && !f.tower) {
                    mirrorFix(t, f)?.let { (mlat, mlon) ->
                        val (md, mb) = Rf.distanceBearing(l.latitude, l.longitude, mlat, mlon)
                        gDist = md
                        gDeg = relAngle(mb - headingDeg)
                    }
                }
                items.add(RadarDot(t.key, t.protocol.color or 0xFF000000.toInt(), d, relAngle(b - headingDeg), true, sel, alert, gDist, gDeg))
                dists.add(d)
                withDir++
            } else {
                if (locatedOnly && !sel) {          // the signal you are tracking always stays on the radar
                    hiddenN++
                    continue
                }
                val d = Rf.modelDistance(t) ?: continue
                unknownN++
                items.add(RadarDot(t.key, t.protocol.color or 0xFF000000.toInt(), d, 0.0, false, sel, alert))
                dists.add(d)
            }
        }
        // spread direction-unknown dots along the lower arc so they do not stack
        val spread = ArrayList<RadarDot>(items.size)
        for (dot in items) {
            if (dot.known) {
                spread.add(dot)
            } else {
                val ang = if (unknownN <= 1) 180.0 else 150.0 + 60.0 * unknownIdx / (unknownN - 1)
                unknownIdx++
                spread.add(RadarDot(dot.key, dot.color, dot.distM, ang, false, dot.selected, dot.alert))
            }
        }
        val sorted = dists.sorted()
        val ref = if (sorted.isEmpty()) 20.0 else sorted[((sorted.size - 1) * 0.8).toInt()]
        val steps = doubleArrayOf(10.0, 25.0, 50.0, 100.0, 250.0, 500.0, 1000.0, 2500.0, 5000.0, 20000.0, 100000.0)
        radar.rangeM = steps.firstOrNull { it >= ref } ?: steps.last()
        radar.dots = spread
        radar.caption = "$withDir with direction" +
            (if (unknownN > 0) " · $unknownN by distance only" else "") +
            (if (hiddenN > 0) " · $hiddenN not located yet (see list)" else "") +
            " · range ${fmtDist(radar.rangeM)}"
        radar.invalidate()
        if (refreshList) {
            // the list shows every signal; Track: One / All only changes which dots the radar draws
            radarAdapter.items = ArrayList(lastVisible.take(MAX_ROWS)).also { shown ->
                selectedKey?.let { k -> tracks[k]?.let { sel -> if (sel in lastVisible && sel !in shown) shown.add(sel) } }
            }
            radarAdapter.notifyDataSetChanged()
        }
    }

    /** Look up the serving cell's tower and use it as that cell's position. Neighbors stay signal-only. */
    private fun applyTowers(visible: List<Track>) {
        if (indoor) {
            for (t in visible) if (t.protocol == Protocol.CELL) {
                t.fix = null
                t.towerNote = "Tower position needs GPS (indoor mode is on)."
            }
            return
        }
        for (t in visible) {
            if (t.protocol != Protocol.CELL) continue
            val c = t.cell
            if (c == null || !t.detail.startsWith("serving")) continue
            towers.ensure(c)
            val pos = towers.result(c)?.pos
            if (pos != null) {
                val old = t.fix
                if (old == null || !old.tower || old.lat != pos.lat || old.lon != pos.lon) {
                    t.fix = Fix(pos.lat, pos.lon, 0.0, false, true)
                }
                t.towerRangeM = pos.rangeM
                t.towerNote = ""
            } else {
                t.fix = null
                t.towerNote = towers.statusFor(c)
            }
        }
    }

    // ------------------------------------------------------------------ tracker watch

    /** Flags an item tracker that stays in range while you travel. Called for every tracker advertisement. */
    private fun trackFollow(t: Track, r: Reading, gapMs: Long) {
        if (gapMs > FOLLOW_GAP_MS) {
            t.hasFirstPos = false
            t.maxTravelM = 0.0
            t.followSinceMs = 0L
            t.following = false
        }
        val l = loc ?: return
        if (l.accuracy > 50f || r.timeMs - l.time > 15_000) return
        val frame = l.provider == "indoor"
        // the indoor step tracker uses its own frame near 0,0: never mix it with real GPS positions,
        // and never trust a start point that is more than 100 km away (a bad or stale fix)
        if (t.hasFirstPos && (t.firstIndoor != frame ||
                Rf.meters(t.firstLat, t.firstLon, l.latitude, l.longitude) > 100_000.0)) {
            t.hasFirstPos = false
            t.maxTravelM = 0.0
            t.followSinceMs = 0L
            t.following = false
        }
        if (!t.hasFirstPos) {
            t.firstIndoor = frame
            t.firstLat = l.latitude
            t.firstLon = l.longitude
            t.hasFirstPos = true
            t.followSinceMs = r.timeMs
            t.maxTravelM = 0.0
            return
        }
        val d = Rf.meters(t.firstLat, t.firstLon, l.latitude, l.longitude)
        if (d > t.maxTravelM) t.maxTravelM = d
        val minutes = (r.timeMs - t.followSinceMs) / 60_000
        t.following = t.key !in mine && !MyWatch.isMuted(t.key) && t.maxTravelM >= FOLLOW_DIST_M && minutes >= FOLLOW_MIN
        if (t.following && trackerAlerts) {
            if (alertedAt.allow(t.key, r.timeMs, System.currentTimeMillis())) {
                t.alerted = true
                Alerts.trackerAlert(applicationContext, t, minutes, t.maxTravelM.roundToInt())
            }
        }
    }

    private fun durText(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return when {
            s < 90 -> "${s}s"
            s < 5400 -> "${s / 60} min"
            else -> "%.1f h".format(s / 3600.0)
        }
    }

    /** Every item tracker heard so far: how long it has been near you and how far you have moved since. */
    private fun showTags() {
        val now = System.currentTimeMillis()
        val list = tracks.values.filter { it.trackerKind != null }
            .sortedWith(compareByDescending<Track> { it.following }.thenByDescending { it.lastSeenMs })
        if (list.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Tags (item trackers)")
                .setMessage("No item trackers heard yet. Start scanning with Bluetooth LE on; AirTags, Tile, SmartTags and similar tags show up here when they are nearby.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val rows = ArrayList<CharSequence>()
        for (t in list) {
            val since = if (t.hasFirstPos && t.followSinceMs > 0) t.followSinceMs else t.firstSeenMs
            val dist = Rf.modelDistance(t)?.let { "~" + fmtDist(it) } ?: "n/a"
            val sb = StringBuilder()
            sb.append(if (t.following) "⚠ " else "").append(t.trackerKind ?: t.name)
            if (t.key in mine) sb.append("  (marked yours)")
            sb.append("\n").append("${t.rssi} dBm · $dist · last heard ${durText(now - t.lastSeenMs)} ago")
            sb.append("\n").append("Near you for ${durText(t.lastSeenMs - since)}")
            if (t.hasFirstPos) sb.append(" · you moved up to ${fmtDist(t.maxTravelM)} from where you first met it")
            else sb.append(" · no GPS fix yet, so no distance travelled")
            sb.append("\n").append(
                when {
                    t.following -> "Possible tracker: stayed with you"
                    t.key in mine -> "Will not raise alerts"
                    else -> "Alert after $FOLLOW_MIN min and ${FOLLOW_DIST_M.roundToInt()} m of travel"
                }
            )
            rows.add(sb.toString())
        }
        val lv = ListView(this)
        lv.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, rows)
        val dlg = AlertDialog.Builder(this)
            .setTitle("Tags (item trackers): ${list.size}")
            .setView(lv)
            .setPositiveButton("Close", null)
            .create()
        lv.setOnItemClickListener { _, _, pos, _ ->
            dlg.dismiss()
            openSheet(list[pos].key)
        }
        dlg.show()
    }

    private fun markMine(key: String) {
        val t = tracks[key] ?: return
        mine.add(t.key)
        t.following = false
        prefs.edit().putStringSet("mine", HashSet(mine)).apply()
        toast("Marked as yours. It will not raise alerts. (Tags that change their address will look new.)")
        refreshUi()
    }

    // ------------------------------------------------------------------ my watchlist, mute list, debrief

    /** Forget the least recently heard signals once there are too many, never the ones you care about. */
    private fun pruneTracks(now: Long) {
        lastPruneMs = now
        val drop = TrackPruner.pick(tracks.values.map { it.key to it.lastSeenMs }, MAX_TRACKS) { k ->
            val t = tracks[k]
            k == selectedKey || k == sheetKey || k in mine || t == null ||
                t.myHit != null || t.watch != null || t.following || t.trackerKind != null || t.protocol == Protocol.CELL
        }
        for (k in drop) tracks.remove(k)
        gate.noteEvicted(drop.size)
    }

    private fun healthText(): String {
        val now = System.currentTimeMillis()
        val sb = StringBuilder()
        sb.append("Incoming: %.0f readings/s".format(gate.totalRate()))
        if (gate.safeMode) sb.append("  ·  SAFE MODE (thinned to 1 per signal per second)")
        sb.append("\nTracked signals: ${tracks.size} (limit $MAX_TRACKS), forgotten so far: ${gate.evicted}")
        if (gate.safeEntries > 0) sb.append("\nSafe mode has switched on ${gate.safeEntries} time(s)")
        sb.append("\n")
        for (p in Protocol.values()) {
            val st = gate.stat(p.label)
            val on = protoOn[p] == true
            sb.append("\n").append(p.label).append(if (on) "" else " (off)").append(": ")
            if (st == null) sb.append("nothing heard yet")
            else sb.append("${st.received} heard, ${st.dropped} thinned, %.1f/s, last ${durText(now - st.lastMs)} ago".format(st.rate))
            errors[p]?.let { sb.append("\n   ⚠ ").append(it) }
        }
        sb.append("\n\nGPS: ").append(loc?.let { "±${it.accuracy.roundToInt()} m, ${durText(now - it.time)} old" } ?: "no fix")
        sb.append("\nScreen-off scanning: ").append(if (bgScan) "on" else "off")
        sb.append("\nWi-Fi scans are throttled by Android; low Wi-Fi rates are normal unless you turn off Wi-Fi scan throttling in Developer options.")
        return sb.toString()
    }

    private fun showHealth() {
        val col = MenuUi.column(this)
        col.addView(MenuUi.note(this, healthText()).apply { setTextIsSelectable(true) })
        var dlg: android.app.Dialog? = null
        dlg = MenuUi.dialog(this, "Scan health", col, listOf(
            MenuUi.button(this, "Reset counters") { gate.clear(); dlg?.dismiss(); showHealth() },
            MenuUi.button(this, "Close") { dlg?.dismiss() }
        ))
    }

    private fun saveMyWatch() = MyWatchStore.save(prefs)

    /** Re-evaluate every known signal after the watchlist or mute list changed. */
    private fun rematchAll() {
        for (t in tracks.values) {
            t.watch = if (MyWatch.isMuted(t.key)) null else Watch.match(t)
            t.myHit = MyWatch.match(t)
            t.hint = Signatures.match(t)
            if (MyWatch.isMuted(t.key)) t.following = false
        }
    }

    private fun alertMine(t: Track, e: MyEntry, now: Long) {
        if (!myAlertedAt.allow(t.key, now, System.currentTimeMillis())) return
        Alerts.watchAlert(applicationContext, t, e, watchSound)
    }

    private fun addWatch(pattern: String, note: String) {
        val p = pattern.trim()
        if (p.length < MyWatch.MIN_LEN) {
            toast("Use at least ${MyWatch.MIN_LEN} characters")
            return
        }
        if (MyWatch.entries.any { it.pattern.equals(p, ignoreCase = true) }) {
            toast("Already on your watchlist")
            return
        }
        MyWatch.entries.add(MyEntry(p, note.trim()))
        myAlertedAt.clear()
        saveMyWatch()
        rematchAll()
        toast("Watching for \"$p\"")
        refreshUi()
    }

    /** Details-page button: remove the entry this signal matches, or choose what to watch for. */
    private fun watchSignal(key: String) {
        val t = tracks[key] ?: return
        val hit = t.myHit
        if (hit != null) {
            MyWatch.entries.remove(hit)
            saveMyWatch()
            rematchAll()
            toast("Removed \"${hit.pattern}\" from your watchlist")
            refreshUi()
            return
        }
        val opts = MyWatch.suggestions(t)
        if (opts.isEmpty()) {
            toast("There is nothing stable to watch for on this signal")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Watch for...")
            .setItems(opts.map { it.first }.toTypedArray()) { _, i -> addWatch(opts[i].second, "") }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toggleMute(key: String) {
        val t = tracks[key] ?: return
        if (MyWatch.isMuted(key)) {
            MyWatch.muted.remove(key)
            toast("Unmuted")
        } else {
            MyWatch.muted.add(key)
            alertedAt.remove(key)
            toast("Muted: no alerts or flags for this signal. (Devices that change their address will look new.)")
        }
        saveMyWatch()
        rematchAll()
        refreshUi()
    }

    private fun showMyWatch() {
        val ctx = this
        val col = MenuUi.column(ctx)
        var dlg: android.app.Dialog? = null
        col.addView(MenuUi.note(ctx, "A signal whose name or address matches an entry gets an amber WATCH flag and a notification. Names match any part of the name; addresses match from the start (aa:bb:cc or a full address). Tap an entry to remove it."))
        if (MyWatch.entries.isEmpty()) col.addView(MenuUi.note(ctx, "Nothing yet. Add a name fragment, an address or address prefix, or an aircraft callsign, or press Watch on a signal's details page."))
        for (e in MyWatch.entries.toList()) {
            col.addView(MenuUi.row(ctx, e.pattern, null, e.note.ifBlank { null }) {
                MyWatch.entries.remove(e)
                saveMyWatch()
                rematchAll()
                dlg?.dismiss()
                showMyWatch()
                refreshUi()
            })
        }
        val add = MenuUi.button(ctx, "Add", true) {
            dlg?.dismiss()
            showAddWatch()
        }
        val shown = MenuUi.dialog(ctx, "My watchlist", col, listOf(add, MenuUi.button(ctx, "Close") { dlg?.dismiss() }))
        dlg = shown
        shown.show()
    }

    private fun showAddWatch() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
        }
        val pat = EditText(this).apply {
            hint = "Name, address or prefix (3+ characters)"
            isSingleLine = true
        }
        val note = EditText(this).apply {
            hint = "Note (optional)"
            isSingleLine = true
        }
        box.addView(pat)
        box.addView(note)
        AlertDialog.Builder(this)
            .setTitle("Add to watchlist")
            .setView(box)
            .setPositiveButton("Add") { _, _ -> addWatch(pat.text.toString(), note.text.toString()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showMuted() {
        val ctx = this
        val col = MenuUi.column(ctx)
        var dlg: android.app.Dialog? = null
        col.addView(MenuUi.note(ctx, "Muted signals raise no alerts and get no flags. Tap one to unmute it."))
        if (MyWatch.muted.isEmpty()) col.addView(MenuUi.note(ctx, "Nothing muted. Press Mute on a signal's details page."))
        for (k in MyWatch.muted.toList()) {
            val t = tracks[k]
            col.addView(MenuUi.row(ctx, t?.name ?: k, null, t?.protocol?.label ?: "not in range") {
                MyWatch.muted.remove(k)
                saveMyWatch()
                rematchAll()
                dlg?.dismiss()
                showMuted()
                refreshUi()
            })
        }
        val clear = MenuUi.button(ctx, "Unmute all") {
            MyWatch.muted.clear()
            saveMyWatch()
            rematchAll()
            dlg?.dismiss()
            refreshUi()
        }
        val shown = MenuUi.dialog(ctx, "Muted signals", col, listOf(clear, MenuUi.button(ctx, "Close") { dlg?.dismiss() }))
        dlg = shown
        shown.show()
    }

    private fun debriefDistance(t: Track): String? {
        val f = t.fix ?: return null
        val l = loc ?: return null
        val (d, b) = Rf.distanceBearing(l.latitude, l.longitude, f.lat, f.lon)
        return "${fmtDist(d)} @ ${b.roundToInt()}°"
    }

    private fun showDebrief() {
        val ctx = this
        val now = System.currentTimeMillis()
        val text = Debrief.build(tracks.values, now, this::debriefDistance)
        val col = MenuUi.column(ctx)
        var dlg: android.app.Dialog? = null
        col.addView(MenuUi.note(ctx, text).apply { setTextIsSelectable(true) })
        val claudeOut = MenuUi.note(ctx, "").apply {
            visibility = View.GONE
            setTextIsSelectable(true)
        }
        col.addView(claudeOut)
        val share = MenuUi.button(ctx, "Share") {
            val i = Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "RF Scout debrief")
                .putExtra(Intent.EXTRA_TEXT, text + (if (claudeOut.visibility == View.VISIBLE) "\n\nClaude's read:\n" + claudeOut.text else ""))
            startActivity(Intent.createChooser(i, "Share debrief"))
        }
        val ask = MenuUi.button(ctx, "Ask Claude") {
            val key = secrets.getString("claude_key", "") ?: ""
            if (key.isBlank()) {
                toast("Add your Claude API key in Settings first")
            } else if (tracks.isEmpty()) {
                toast("Nothing to send yet")
            } else {
                val outgoing = Debrief.build(tracks.values, System.currentTimeMillis(), this::debriefDistance, maskAddresses = true)
                AlertDialog.Builder(this)
                    .setTitle("Send this to Claude?")
                    .setMessage("This exact text goes to Anthropic's API with a short instruction. It has no GPS coordinates, but it does include signal names.\n\n$outgoing")
                    .setPositiveButton("Send") { _, _ ->
                        claudeOut.visibility = View.VISIBLE
                        claudeOut.text = "Asking Claude..."
                        ClaudeApi.ask(key, Debrief.claudePrompt(outgoing)) { reply, err ->
                            claudeOut.text = if (reply != null) "Claude's read:\n$reply" else (err ?: "No answer")
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
        val shown = MenuUi.dialog(ctx, "Session debrief", col, listOf(share, ask, MenuUi.button(ctx, "Close") { dlg?.dismiss() }))
        dlg = shown
        shown.show()
    }

    // ------------------------------------------------------------------ info sheet

    /** Tapping the signal that is already tracked stops tracking it; anything else selects it. */
    private fun toggledSelection(key: String): String? = if (key == selectedKey) null else key

    private fun stopTracking() {
        selectedKey = null
        refreshUi()
    }

    private fun openSheet(key: String) {
        sheetKey = key
        sheet.visibility = View.VISIBLE
        bindSheet()
    }

    private fun closeSheet() {
        sheetKey = null
        sheet.visibility = View.GONE
    }

    private fun bindSheet() {
        val t = sheetKey?.let { tracks[it] }
        if (t == null) {
            closeSheet()
            return
        }
        val w = t.watch
        val parts = ArrayList<String>()
        if (t.following) {
            val mins = (System.currentTimeMillis() - t.followSinceMs) / 60_000
            parts.add(
                "⚠ This tag has stayed in range for $mins min while you moved about ${t.maxTravelM.roundToInt()} m. " +
                    "If it is not yours, it may be tracking you. Tap \"This is mine\" if it is yours."
            )
        } else if (t.trackerKind != null) {
            parts.add(
                if (t.key in mine) "Marked as yours: no alerts for this tag."
                else "Item tracker: an alert is raised if it stays with you for $FOLLOW_MIN min and ${FOLLOW_DIST_M.roundToInt()} m of travel."
            )
        }
        t.myHit?.let { e ->
            parts.add("★ On your watchlist: \"${e.pattern}\"" + (if (e.note.isNotBlank()) " (${e.note})" else "") + ". Tap Unwatch to remove it.")
        }
        if (MyWatch.isMuted(t.key)) parts.add("Muted: no alerts or flags for this signal. (Devices that change their address will look new.)")
        t.hint?.let { h ->
            parts.add("Hint (${h.confidence} confidence): ${h.label}.\nBasis: ${h.basis}.\n${h.limits}".trim())
        }
        if (w != null) {
            parts.add(
                "⚑ Possible ALPR signature: ${w.label} (${w.confidence} confidence, ${w.basis}).\n" +
                    "These patterns are community-reported and can match other devices, so treat this as a lead, not proof.\n" +
                    (sightings[t.key]?.let { "Map check: ${mappedStatus(it)}" } ?: "")
            )
        }
        sheet.bind(t, loc, t.key == selectedKey, if (parts.isEmpty()) null else parts.joinToString("\n\n"))
    }

    private fun updateStatus(visible: List<Track>) {
        val counts = Protocol.values().filter { protoOn[it] == true }.joinToString(" · ") { p ->
            "${shortName(p)} ${visible.count { it.protocol == p }}"
        }.ifEmpty { "All signal types are off (tick a box above)" }
        val gps = if (indoor) "Indoor: ${indoorTracker.steps} steps · ${indoorTracker.distFromStart().roundToInt()} m from start"
        else loc?.let { "GPS ±${it.accuracy.roundToInt()} m" } ?: "no GPS fix yet"
        val err = errors.entries.joinToString("; ") { "${it.key.label}: ${it.value}" }
        val alpr = visible.count { it.watch != null }
        val mineN = visible.count { it.myHit != null }
        val follow = visible.count { it.following }
        statusText.text = counts + "\n" + gps + " · hdg ${headingDeg.roundToInt()}°" +
            (if (bgScan) " · screen-off" else "") +
            (if (err.isNotEmpty()) "\n⚠ $err" else "") +
            (if (gate.safeMode) "\n⚠ Very busy: safe mode is thinning readings" else "") +
            (if (alpr > 0) "\n⚑ $alpr possible ALPR signature(s) nearby" else "") +
            (if (mineN > 0) "\n★ $mineN watchlist match(es) in range" else "") +
            (if (follow > 0) "\n⚠ $follow tag(s) may be following you" else "") +
            (if (alprOn) "\n${deflock.statusLine()} · © OpenStreetMap contributors" else "")
    }

    private fun updateTarget() {
        val t = selectedKey?.let { tracks[it] }
        if (t == null) {
            targetBearing = null
            targetText.text = "Tap a signal to track it"
            stopBtn.visibility = View.GONE
            updateArrow()
            return
        }
        val l = loc
        val sb = StringBuilder()
        sb.append(t.name).append("  [").append(t.protocol.label).append("]\n")
        sb.append("${t.rssi} dBm (avg ${"%.0f".format(t.rssiSmooth)})")
        Rf.modelDistance(t)?.let { sb.append(" · model ~${fmtDist(it)}") }
        sb.append('\n')

        val f = t.fix
        if (f != null && l != null) {
            val (d, b) = Rf.distanceBearing(l.latitude, l.longitude, f.lat, f.lon)
            targetBearing = b
            if (f.reported) {
                sb.append("Reported position: ${fmtDist(d)} @ ${"%.0f".format(b)}°")
            } else if (f.tower) {
                sb.append("Tower (database estimate): ${fmtDist(d)} @ ${"%.0f".format(b)}°")
                if (t.towerRangeM > 0) sb.append(" · range ~${fmtDist(t.towerRangeM.toDouble())}")
            } else {
                sb.append("Fix: ${fmtDist(d)} @ ${"%.0f".format(b)}° · fit ±${"%.1f".format(f.rmsDb)} dB")
                if (f.ambiguous) sb.append("\nPath too straight: walk a curve to pick the side")
            }
        } else {
            targetBearing = null
            sb.append(
                if (t.protocol == Protocol.CELL) {
                    if (t.towerNote.isNotEmpty()) t.towerNote else "Neighbor cell: signal strength only"
                } else if (l != null && l.accuracy > 30f) {
                    "Waiting for better GPS (now ±${l.accuracy.roundToInt()} m, needs 30 m or better)"
                } else if (t.fixNote.isNotEmpty()) {
                    t.fixNote
                } else {
                    "Walk 10 m or more to get a bearing (${t.samples.size} points)"
                }
            )
        }
        targetText.text = sb
        stopBtn.visibility = View.VISIBLE
        updateArrow()
    }

    private fun updateArrow() {
        val b = targetBearing
        if (b == null) {
            arrow.active = false
        } else {
            arrow.active = true
            arrow.angle = relAngle(b - headingDeg).toFloat()
        }
        arrow.invalidate()
    }

    // ------------------------------------------------------------------ map

    private fun dot(color: Int, px: Int): ShapeDrawable = ShapeDrawable(OvalShape()).apply {
        setIntrinsicWidth(px)
        setIntrinsicHeight(px)
        paint.color = color
    }

    private fun flagFor(t: Track, selected: Boolean): FlagDrawable {
        val d = resources.displayMetrics.density
        return when {
            t.following -> FlagDrawable("TRK?", 0xFFD32F2F.toInt(), d, selected)
            t.watch != null -> FlagDrawable("ALPR?", 0xFFD32F2F.toInt(), d, selected)
            t.myHit != null -> FlagDrawable("WATCH", 0xFFFFA726.toInt(), d, selected)
            else -> FlagDrawable(t.protocol.tag, t.protocol.color, d, selected)
        }
    }

    private fun updateMap(visible: List<Track>) {
        val ov = map.overlays
        ov.clear()
        val l = loc
        val sel = selectedKey?.let { tracks[it] }?.takeIf { protoOn[it.protocol] == true }
        val onlySel = sel != null && !trackAll
        val density = resources.displayMetrics.density

        // lowest layers first: path, samples, range ring, line to the tracked signal, your accuracy circle
        if (breadcrumb.size > 1) {
            ov.add(Polyline().apply {
                setPoints(ArrayList(breadcrumb))
                outlinePaint.color = 0x8890CAF9.toInt()
                outlinePaint.strokeWidth = 5f
            })
        }
        if (sel != null && sel.samples.isNotEmpty()) {
            ov.add(SampleOverlay(ArrayList(sel.samples), dp(4).toFloat()))
        }

        // model range ring around you for the selected signal
        if (l != null && sel != null) {
            Rf.modelDistance(sel)?.let { d ->
                ov.add(Polygon().apply {
                    setPoints(Polygon.pointsAsCircle(GeoPoint(l.latitude, l.longitude), d))
                    fillPaint.color = Color.argb(20, 255, 255, 255)
                    outlinePaint.color = sel.protocol.color
                    outlinePaint.strokeWidth = 3f
                })
            }
        }

        // coverage range around a looked-up tower
        val selFix = sel?.fix
        if (sel != null && selFix != null && selFix.tower && sel.towerRangeM > 0) {
            ov.add(Polygon().apply {
                setPoints(Polygon.pointsAsCircle(GeoPoint(selFix.lat, selFix.lon), sel.towerRangeM.toDouble()))
                fillPaint.color = Color.argb(15, 239, 108, 0)
                outlinePaint.color = Color.argb(120, 239, 108, 0)
                outlinePaint.strokeWidth = 2f
            })
        }

        // line from you to the selected fix
        val sf = sel?.fix
        if (l != null && sf != null) {
            ov.add(Polyline().apply {
                setPoints(listOf(GeoPoint(l.latitude, l.longitude), GeoPoint(sf.lat, sf.lon)))
                outlinePaint.color = 0xFFFF5252.toInt()
                outlinePaint.strokeWidth = 6f
            })
        }

        if (l != null) {
            ov.add(Polygon().apply {
                setPoints(Polygon.pointsAsCircle(GeoPoint(l.latitude, l.longitude), max(l.accuracy.toDouble(), 2.0)))
                fillPaint.color = Color.argb(30, 0, 200, 255)
                outlinePaint.color = Color.argb(120, 0, 200, 255)
                outlinePaint.strokeWidth = 1f
            })
        }

        // mapped ALPR cameras (OpenStreetMap data, as collected by projects like DeFlock)
        if (alprOn && deflock.camList.isNotEmpty()) {
            ov.add(CameraOverlay(deflock.camList, density) { onCameraTap(it) })
        }

        // estimated transmitter positions, one flag per signal. Track: One hides all but the selected signal.
        for (t in visible) {
            if (onlySel && t.key != selectedKey) continue
            val f = t.fix ?: continue
            ov.add(Marker(map).apply {
                position = GeoPoint(f.lat, f.lon)
                setAnchor(FlagDrawable.POLE_ANCHOR_X, Marker.ANCHOR_BOTTOM)
                icon = flagFor(t, t.key == selectedKey)
                setOnMarkerClickListener { _, _ ->
                    openSheet(t.key)
                    true
                }
            })
        }

        // cell towers have no position: park their flags in a row beside your dot
        if (l != null) {
            val cells = visible.filter {
                it.protocol == Protocol.CELL && it.fix == null && (!onlySel || it.key == selectedKey)
            }.take(4)
            for ((k, t) in cells.withIndex()) {
                ov.add(Marker(map).apply {
                    position = GeoPoint(l.latitude, l.longitude)
                    setAnchor(-0.35f - k * 1.05f, Marker.ANCHOR_BOTTOM)
                    icon = flagFor(t, t.key == selectedKey)
                    setOnMarkerClickListener { _, _ ->
                        openSheet(t.key)
                        true
                    }
                })
            }
        }

        // you
        if (l != null) {
            ov.add(Marker(map).apply {
                position = GeoPoint(l.latitude, l.longitude)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                icon = dot(0xFF00E5FF.toInt(), dp(14))
                title = "You"
            })
        }
        map.invalidate()
    }

    // ------------------------------------------------------------------ calibration, settings, export

    private fun saveCalib() {
        prefs.edit().putString("calib", Calib.toJson()).apply()
    }

    private fun showCalibrate(key: String) {
        val t = tracks[key] ?: return
        if (t.protocol == Protocol.CELL || t.protocol.reported) {
            toast("${t.protocol.label} signals are not calibrated")
            return
        }
        val names = arrayOf("About 1 m", "About 3 m", "About 5 m", "About 10 m", "About 20 m", "Reset ${t.protocol.label} calibration")
        val dists = doubleArrayOf(1.0, 3.0, 5.0, 10.0, 20.0)
        AlertDialog.Builder(this)
            .setTitle("Stand still near \"${t.name}\". How far away are you?")
            .setItems(names) { _, which ->
                if (which >= dists.size) {
                    Calib.reset(t.protocol)
                    saveCalib()
                    toast("${t.protocol.label} calibration reset")
                } else {
                    val msg = Calib.add(t, dists[which])
                    saveCalib()
                    toast(msg ?: "Could not calibrate this signal")
                }
                refreshUi()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Short name used next to the tick boxes and in the status line. */
    private fun shortName(p: Protocol): String = if (p == Protocol.RID) "Drone" else p.tag

    private fun setProto(p: Protocol, on: Boolean) {
        protoOn[p] = on
        prefs.edit().putBoolean("proto_${p.name}", on).apply()
        protoBoxes[p]?.let { if (it.isChecked != on) it.isChecked = on }
        if (on) {
            if (running) startScanner(p)
        } else {
            scanners[p]?.stop()
            errors.remove(p)
            if (selectedKey?.let { tracks[it]?.protocol } == p) selectedKey = null
        }
        refreshUi()
    }

    private fun showSettings() {
        val ctx = this
        val col = MenuUi.column(ctx)

        col.addView(MenuUi.section(ctx, "Signal tick boxes on the main screen"))
        col.addView(MenuUi.note(ctx, "ON shows that tick box on the main screen. OFF hides the box and switches that signal type off (it stops scanning and disappears from the list, radar and map). Drone ID is read through the Wi-Fi and Bluetooth scanners, so it needs one of them on. ADS-B needs an RTL-SDR."))
        for (p in Protocol.values()) {
            col.addView(MenuUi.toggle(ctx, p.label, { protoShown[p] == true }) {
                val show = protoShown[p] != true
                protoShown[p] = show
                prefs.edit().putBoolean("proto_shown_${p.name}", show).apply()
                if (!show) setProto(p, false)
                protoBoxes[p]?.visibility = if (show) View.VISIBLE else View.GONE
            })
        }


        col.addView(MenuUi.section(ctx, "Cell tower lookup"))
        col.addView(MenuUi.status(ctx, towers.apiKey.isNotBlank(), if (towers.apiKey.isNotBlank()) "OpenCelliD key set" else "No OpenCelliD key: tower lookup is off"))
        col.addView(MenuUi.note(ctx, "Free key at opencellid.org/register. Used to look up where the tower you are connected to stands."))
        val keyEdit = EditText(ctx).apply {
            setText(towers.apiKey)
            hint = "API key"
            isSingleLine = true
            textSize = 14f
            setTextColor(Ui.TEXT)
            setHintTextColor(Ui.DIM)
            background = Ui.box(resources.displayMetrics.density, Ui.PANEL, Ui.BORDER, 8f)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        col.addView(keyEdit)

        col.addView(MenuUi.section(ctx, "SDR (ADS-B aircraft)"))
        col.addView(MenuUi.note(ctx, "Tick ADS-B above to show aircraft heard by an RTL-SDR. It connects to an rtl_tcp server: a free SDR driver app on this phone (127.0.0.1, port 1234), or rtl_tcp running on a PC or Raspberry Pi (use its IP address). Use a 1090 MHz antenna."))
        val sdrHostEdit = EditText(ctx).apply {
            setText(prefs.getString("sdr_host", SdrAdsbScanner.DEFAULT_HOST) ?: SdrAdsbScanner.DEFAULT_HOST)
            hint = "rtl_tcp address"
            isSingleLine = true
            textSize = 14f
            setTextColor(Ui.TEXT)
            setHintTextColor(Ui.DIM)
            background = Ui.box(resources.displayMetrics.density, Ui.PANEL, Ui.BORDER, 8f)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        col.addView(sdrHostEdit)
        val sdrPortEdit = EditText(ctx).apply {
            setText(prefs.getInt("sdr_port", SdrAdsbScanner.DEFAULT_PORT).toString())
            hint = "port"
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            textSize = 14f
            setTextColor(Ui.TEXT)
            setHintTextColor(Ui.DIM)
            background = Ui.box(resources.displayMetrics.density, Ui.PANEL, Ui.BORDER, 8f)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        col.addView(sdrPortEdit)

        col.addView(MenuUi.section(ctx, "Claude debrief (optional)"))
        col.addView(MenuUi.note(ctx, "Paste your own Claude API key to get an \"Ask Claude\" button in the session debrief. Nothing is sent unless you tap it, and you see the exact text first (Wi-Fi/Bluetooth addresses are shortened). The key stays on this phone and is excluded from cloud backups."))
        val claudeKeyEdit = EditText(ctx).apply {
            setText(secrets.getString("claude_key", "") ?: "")
            hint = "Claude API key (optional)"
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            textSize = 14f
            setTextColor(Ui.TEXT)
            setHintTextColor(Ui.DIM)
            background = Ui.box(resources.displayMetrics.density, Ui.PANEL, Ui.BORDER, 8f)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        col.addView(claudeKeyEdit)

        col.addView(MenuUi.section(ctx, "Alerts"))
        val trackerSwitch = Switch(ctx).apply {
            text = "Alert when an unknown tag keeps appearing as I move"
            textSize = 13f
            setTextColor(Ui.TEXT)
            isChecked = trackerAlerts
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        col.addView(trackerSwitch)

        col.addView(MenuUi.section(ctx, "Wi-Fi scanning"))
        addWifiThrottleControls(col)

        col.addView(MenuUi.section(ctx, "Status"))
        col.addView(MenuUi.status(ctx, Vendors.registryState().startsWith("loaded"), "Vendor registry: " + Vendors.registryState()))
        col.addView(MenuUi.status(ctx, mine.isNotEmpty(), "Tags marked as yours: ${mine.size}"))
        col.addView(MenuUi.section(ctx, "Distance calibration"))
        col.addView(MenuUi.note(ctx, Protocol.values().filter { it != Protocol.CELL && !it.reported }.joinToString("\n") { Calib.describe(it) }))

        var dlg: android.app.Dialog? = null
        val save = MenuUi.button(ctx, "Save", true) {
            towers.apiKey = keyEdit.text.toString().trim()
            trackerAlerts = trackerSwitch.isChecked
            prefs.edit().putString("ocid_key", towers.apiKey).putBoolean("tracker_alerts", trackerAlerts).apply()
            secrets.edit().putString("claude_key", claudeKeyEdit.text.toString().trim()).apply()
            val newHost = sdrHostEdit.text.toString().trim().ifEmpty { SdrAdsbScanner.DEFAULT_HOST }
            val newPort = sdrPortEdit.text.toString().trim().toIntOrNull()?.takeIf { it in 1..65535 } ?: SdrAdsbScanner.DEFAULT_PORT
            if (newHost != prefs.getString("sdr_host", SdrAdsbScanner.DEFAULT_HOST) || newPort != prefs.getInt("sdr_port", SdrAdsbScanner.DEFAULT_PORT)) {
                prefs.edit().putString("sdr_host", newHost).putInt("sdr_port", newPort).apply()
                scanners.remove(Protocol.ADSB)?.stop()         // a new scanner picks up the new address
                errors.remove(Protocol.ADSB)
                if (running && protoOn[Protocol.ADSB] == true) startScanner(Protocol.ADSB)
            }
            dlg?.dismiss()
            refreshUi()
        }
        val reset = MenuUi.button(ctx, "Reset calib.") {
            Calib.resetAll()
            saveCalib()
            dlg?.dismiss()
            refreshUi()
        }
        val cancel = MenuUi.button(ctx, "Cancel") { dlg?.dismiss() }
        val shown = MenuUi.dialog(ctx, "Settings", col, listOf(save, reset, cancel))
        dlg = shown
        shown.show()
    }

    private val throttleKey = "wifi_scan_throttle_enabled"

    private fun canWriteSecure() =
        ContextCompat.checkSelfPermission(this, "android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED

    /** Android limits Wi-Fi scans to ~4 per 2 minutes. Developer options can turn that off. */
    private fun addWifiThrottleControls(box: LinearLayout) {
        val current = try {
            Settings.Global.getInt(contentResolver, throttleKey, 1)
        } catch (_: Exception) {
            -1
        }
        val state = when (current) {
            1 -> "Throttling is on (scans are limited)"
            0 -> "Throttling is off (continuous scanning)"
            else -> "Throttling state unknown"
        }
        box.addView(MenuUi.status(this, current == 0, state))
        if (canWriteSecure()) {
            box.addView(Switch(this).apply {
                text = "Throttle Wi-Fi scans (turn off for continuous scanning)"
                textSize = 13f
                setTextColor(Ui.TEXT)
                setPadding(dp(4), dp(4), dp(4), dp(4))
                isChecked = current != 0
                setOnCheckedChangeListener { _, on ->
                    try {
                        Settings.Global.putInt(contentResolver, throttleKey, if (on) 1 else 0)
                        toast("Wi-Fi scan throttling ${if (on) "on" else "off"}")
                    } catch (ex: Exception) {
                        toast("Could not change it: ${ex.message}")
                    }
                }
            })
        } else {
            box.addView(MenuUi.note(this,
                "The app can open Developer options for you. Turn off \"Wi-Fi scan throttling\" under Networking. " +
                    "To flip it from inside the app instead, grant a one-time permission from a computer:\n" +
                    "adb shell pm grant com.rfscout.app android.permission.WRITE_SECURE_SETTINGS"))
            box.addView(MenuUi.button(this, "Open Developer options") {
                try {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                } catch (_: Exception) {
                    toast("Developer options are off. Turn them on: Settings > About phone > Software information > tap Build number 7 times.")
                    try {
                        startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS))
                    } catch (_: Exception) {
                    }
                }
            })
        }
    }

    private fun exportSession() {
        if (SessionLog.isEmpty() && tracks.isEmpty()) {
            toast("Nothing to export yet. Press Start and scan first.")
            return
        }
        val err = Export.share(this, tracks.values, sightings.values)
        if (err != null) toast("Export failed: $err")
    }

    // ------------------------------------------------------------------ ALPR layer + sightings

    /** Fetch camera locations for the part of the map on screen, when the layer is on and zoomed in enough. */
    private fun maybeLoadCameras() {
        if (!alprOn || map.visibility != View.VISIBLE || map.width == 0) return
        if (map.zoomLevelDouble < 14.0) {
            deflock.setZoomedOut(true)
        } else {
            val bb = map.boundingBox
            deflock.ensureBounds(bb.latSouth, bb.lonWest, bb.latNorth, bb.lonEast)
        }
        refreshUi()
    }

    /** Keep a log of watchlist hits with the best position known for each. */
    private fun updateSightings(now: Long) {
        if (indoor) return              // indoor positions are not real map positions
        for (t in tracks.values) {
            val w = t.watch ?: continue
            val f = t.fix
            val best = t.samples.maxByOrNull { it.rssi }
            val l = loc
            val pos: Triple<Double, Double, String> = when {
                f != null -> Triple(f.lat, f.lon, "fit")
                best != null -> Triple(best.lat, best.lon, "strongest reading")
                l != null -> Triple(l.latitude, l.longitude, "phone position when heard")
                else -> continue
            }
            val old = sightings[t.key]
            val better = when {
                old == null -> true
                sightingRank(pos.third) != sightingRank(old.source) -> sightingRank(pos.third) > sightingRank(old.source)
                pos.third == "fit" -> Rf.meters(old.lat, old.lon, pos.first, pos.second) > 1.0
                else -> t.rssiSmooth > old.rssi + 1.0
            }
            if (better) {
                sightings[t.key] = Sighting(
                    key = t.key, name = t.name, protocol = t.protocol.label, label = w.label,
                    confidence = w.confidence, lat = pos.first, lon = pos.second, source = pos.third,
                    rssi = t.rssiSmooth, timeMs = now
                )
                sightDirty = true
                if (old == null && alprOn) deflock.ensureAround(pos.first, pos.second)
            }
        }
        while (sightings.size > 500) sightings.remove(sightings.keys.first())
        if (sightDirty) saveSightings(false)
    }

    private fun saveSightings(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastSightSave < 5000) return
        if (!force && !sightDirty) return
        lastSightSave = now
        sightDirty = false
        prefs.edit().putString("sightings", sightingsToJson(sightings.values)).apply()
    }

    private fun mappedStatus(s: Sighting): String {
        val n = deflock.nearest(s.lat, s.lon)
        return when {
            n != null && n.second <= MAPPED_RADIUS_M -> "near a mapped camera (${n.second.roundToInt()} m away)"
            deflock.isLoaded(s.lat, s.lon) ->
                "no mapped camera within ${MAPPED_RADIUS_M.roundToInt()} m, so possibly unmapped"
            else -> "map data not loaded here (turn on the ALPR layer and view this spot)"
        }
    }

    private fun showSightings() {
        val list = sightings.values.sortedByDescending { it.timeMs }
        val sb = StringBuilder()
        if (list.isEmpty()) {
            sb.append("No possible ALPR signatures logged yet.\n\n")
            sb.append("A sighting is logged when a Wi-Fi or Bluetooth signal matches a reported camera pattern. ")
            sb.append("Walk around it to improve the position.")
        }
        for (s in list) {
            sb.append("⚑ ").append(s.name).append("  [").append(s.protocol).append("]\n")
            sb.append(s.label).append(" (").append(s.confidence).append(" confidence)\n")
            sb.append(String.format(Locale.US, "%.5f, %.5f", s.lat, s.lon))
            sb.append(" · from ").append(s.source).append('\n')
            sb.append("Map check: ").append(mappedStatus(s)).append("\n\n")
        }
        AlertDialog.Builder(this)
            .setTitle("Possible ALPR sightings (${list.size})")
            .setMessage(sb.toString())
            .setPositiveButton("Share CSV") { _, _ -> shareSightings(list) }
            .setNeutralButton("DeFlock report") { _, _ -> openUrl("https://deflock.me/report") }
            .setNegativeButton("Clear list") { _, _ -> confirmClearSightings() }
            .show()
    }

    private fun confirmClearSightings() {
        if (sightings.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("Clear sightings?")
            .setMessage("Delete all ${sightings.size} logged sightings from this phone?")
            .setPositiveButton("Delete") { _, _ ->
                sightings.clear()
                sightDirty = true
                saveSightings(true)
            }
            .setNegativeButton("Keep", null)
            .show()
    }

    private fun shareSightings(list: List<Sighting>) {
        if (list.isEmpty()) {
            toast("Nothing to share yet")
            return
        }
        val text = sightingsToCsv(list) { mappedStatus(it) }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "RF Scout sightings")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(send, "Share sightings"))
    }

    private fun onCameraTap(c: Cam) {
        val sb = StringBuilder()
        if (c.manufacturer.isNotBlank()) sb.append("Manufacturer: ").append(c.manufacturer).append('\n')
        if (c.operator.isNotBlank()) sb.append("Operator: ").append(c.operator).append('\n')
        c.dirDeg?.let { sb.append("Faces: ").append(it.roundToInt()).append("° (0° = north)\n") }
        sb.append(String.format(Locale.US, "Position: %.5f, %.5f\n", c.lat, c.lon))
        sb.append("OpenStreetMap: ").append(c.type).append('/').append(c.id).append("\n\nTags:\n")
        for ((k, v) in c.tags.entries.take(25)) sb.append(k).append('=').append(v).append('\n')
        sb.append("\nData © OpenStreetMap contributors (ODbL), collected by volunteers. ")
        sb.append("It can be incomplete or out of date.")
        AlertDialog.Builder(this)
            .setTitle(if (c.manufacturer.isNotBlank()) "${c.manufacturer} ALPR camera" else "ALPR camera")
            .setMessage(sb.toString())
            .setPositiveButton("Open in OSM") { _, _ -> openUrl("https://www.openstreetmap.org/${c.type}/${c.id}") }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            toast("No app can open $url")
        }
    }
}
