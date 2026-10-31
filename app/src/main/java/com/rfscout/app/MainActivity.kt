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
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
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
private const val ALERT_REPEAT_MS = 30L * 60 * 1000

class MainActivity : Activity(), SensorEventListener, LocationListener {

    private val tracks = LinkedHashMap<String, Track>()
    private val protoOn = HashMap<Protocol, Boolean>().apply { Protocol.values().forEach { put(it, true) } }
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
    private val alertedAt = HashMap<String, Long>()
    private var bgScan = false
    private var serviceActive = false
    private var inBackground = false
    private var trackerAlerts = true
    private var lastNotifMs = 0L

    private lateinit var countText: TextView
    private lateinit var radar: RadarView
    private var mode = 0
    private var lastVisible: List<Track> = emptyList()
    private lateinit var modeBtn: Button
    private lateinit var sheet: InfoSheet
    private lateinit var startBtn: Button
    private lateinit var listView: ListView
    private lateinit var map: MapView
    private lateinit var statusText: TextView
    private lateinit var targetText: TextView
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
        alprOn = prefs.getBoolean("alpr_on", false)
        bgScan = prefs.getBoolean("bg_scan", false)
        trackerAlerts = prefs.getBoolean("tracker_alerts", true)
        towers.apiKey = prefs.getString("ocid_key", "") ?: ""
        mine.addAll(prefs.getStringSet("mine", emptySet()) ?: emptySet())
        Calib.fromJson(prefs.getString("calib", "{}") ?: "{}")
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
            breadcrumb.clear()
            SessionLog.clear()
            selectedKey = null
            closeSheet()
            refreshUi()
        }
        val helpBtn = styled("Help") { startActivity(Intent(this@MainActivity, HelpActivity::class.java)) }
        val moreBtn = styled("More") { showMore() }
        root.addView(row(startBtn, clearBtn, helpBtn, moreBtn))

        // protocol toggles
        val toggles = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (p in Protocol.values()) {
            toggles.addView(
                CheckBox(this).apply {
                    text = p.label.substringBefore(' ')
                    textSize = 12f
                    setTextColor(Ui.TEXT)
                    // blue when ticked, grey when not
                    buttonTintList = ColorStateList(
                        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                        intArrayOf(0xFF2196F3.toInt(), 0xFF9E9E9E.toInt())
                    )
                    isChecked = true
                    setOnCheckedChangeListener { _, on ->
                        protoOn[p] = on
                        if (running) {
                            if (on) startScanner(p) else scanners[p]?.stop()
                        }
                    }
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
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
                selectedKey = this@MainActivity.adapter.items[pos].key
                this@MainActivity.adapter.notifyDataSetChanged()
                updateTarget()
            }
            setOnItemLongClickListener { _, _, pos, _ ->
                openSheet(this@MainActivity.adapter.items[pos].key)
                true
            }
        }
        radar = RadarView(this) { key ->
            selectedKey = key
            openSheet(key)
            refreshUi()
        }.apply { visibility = View.GONE }
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
        content.addView(radar, FrameLayout.LayoutParams(-1, -1))
        content.addView(map, FrameLayout.LayoutParams(-1, -1))
        sheet = InfoSheet(
            this,
            onTrack = { key ->
                selectedKey = key
                refreshUi()
            },
            onCalibrate = { key -> showCalibrate(key) },
            onMine = { key -> markMine(key) },
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
        root.addView(panel, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(4)
            bottomMargin = dp(4)
        })
        return root
    }

    private fun row(vararg views: View): LinearLayout {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (v in views) {
            r.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(2), dp(2), dp(2), dp(2))
            })
        }
        return r
    }

    /** 0 = signal list, 1 = radar, 2 = map */
    private fun showMode(m: Int) {
        mode = m
        listView.visibility = if (m == 0) View.VISIBLE else View.GONE
        radar.visibility = if (m == 1) View.VISIBLE else View.GONE
        map.visibility = if (m == 2) View.VISIBLE else View.GONE
        countText.visibility = if (m == 0) View.VISIBLE else View.GONE
        refreshUi()
        if (m == 2) handler.postDelayed({ maybeLoadCameras() }, 400)
    }

    private fun showMap(show: Boolean) = showMode(if (show) 2 else 0)

    private fun showMore() {
        val items = arrayOf(
            "ALPR camera layer: ${if (alprOn) "On" else "Off"}",
            "Sightings log",
            "Export session (CSV + GPX)",
            "Screen-off scanning: ${if (bgScan) "On" else "Off"}",
            "Settings"
        )
        AlertDialog.Builder(this)
            .setTitle("More")
            .setItems(items) { _, i ->
                when (i) {
                    0 -> toggleAlpr()
                    1 -> showSightings()
                    2 -> exportSession()
                    3 -> toggleBgScan()
                    4 -> showSettings()
                }
            }
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
        startLocation(lm)
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
                Protocol.WIFI -> WifiScanner(app, this::onReading)
                Protocol.BLE -> BleScanner(app, this::onReading)
                Protocol.BT_CLASSIC -> BtClassicScanner(app, this::onReading)
                Protocol.CELL -> CellScanner(app, this::onReading)
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

        if (location.accuracy <= 50f) SessionLog.addPath(location.latitude, location.longitude, location.accuracy)
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
        sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let {
            sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
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
        val az = Math.toRadians(Math.toDegrees(o[0].toDouble()) + declination)
        headingX = 0.8 * headingX + 0.2 * cos(az)
        headingY = 0.8 * headingY + 0.2 * sin(az)
        headingDeg = (Math.toDegrees(atan2(headingY, headingX)) + 360.0) % 360.0

        val now = System.currentTimeMillis()
        if (now - lastArrowMs > 100) {
            lastArrowMs = now
            updateArrow()
            if (mode == 1 && !inBackground) updateRadar()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ------------------------------------------------------------------ readings

    private fun onReading(r: Reading) {
        val t = tracks.getOrPut(r.key) { Track(r.key, r.protocol) }
        val gapMs = if (t.count > 0) r.timeMs - t.lastSeenMs else 0L
        t.update(r)

        val l = loc
        SessionLog.addReading(t, l?.latitude, l?.longitude, l?.accuracy ?: 0f)
        if (t.trackerKind != null) trackFollow(t, r, gapMs)

        if (l == null) return
        if (t.protocol == Protocol.CELL) return
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
        tracks.values.removeAll { now - it.lastSeenMs > 300_000 && it.key != selectedKey }
        val visible = tracks.values
            .filter { protoOn[it.protocol] == true && now - it.lastSeenMs < 60_000 }
            .sortedByDescending { it.rssiSmooth }

        // re-fit positions only for the strongest few + the selected one, and only when new samples arrived
        val toFit = LinkedHashSet<Track>(visible.take(6))
        selectedKey?.let { k -> tracks[k]?.let { toFit.add(it) } }
        sheetKey?.let { k -> tracks[k]?.let { toFit.add(it) } }
        for (t in toFit) {
            if (t.protocol == Protocol.CELL) continue
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
        if (mode == 1) updateRadar()
        if (map.visibility == View.VISIBLE) updateMap(visible)
    }

    /** Place each signal on the radar: by fit direction when known, otherwise by distance on the lower arc. */
    private fun updateRadar() {
        val l = loc
        val items = ArrayList<RadarDot>()
        val dists = ArrayList<Double>()
        val vis = lastVisible.filter { (selectedKey == null || trackAll || it.key == selectedKey) }
        var unknownIdx = 0
        var unknownN = 0
        var withDir = 0
        for (t in vis) {
            val f = t.fix
            val sel = t.key == selectedKey
            val alert = t.following || t.watch != null
            if (f != null && l != null) {
                val (d, b) = Rf.distanceBearing(l.latitude, l.longitude, f.lat, f.lon)
                items.add(RadarDot(t.key, t.protocol.color or 0xFF000000.toInt(), d, relAngle(b - headingDeg), true, sel, alert))
                dists.add(d)
                withDir++
            } else {
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
        radar.caption = "$withDir with direction · $unknownN by distance only · range ${fmtDist(radar.rangeM)}"
        radar.invalidate()
    }

    /** Look up the serving cell's tower and use it as that cell's position. Neighbors stay signal-only. */
    private fun applyTowers(visible: List<Track>) {
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
        if (!t.hasFirstPos) {
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
        t.following = t.key !in mine && t.maxTravelM >= FOLLOW_DIST_M && minutes >= FOLLOW_MIN
        if (t.following && trackerAlerts) {
            val last = alertedAt[t.key]
            if (last == null || r.timeMs - last > ALERT_REPEAT_MS) {
                alertedAt[t.key] = r.timeMs
                t.alerted = true
                Alerts.trackerAlert(applicationContext, t, minutes, t.maxTravelM.roundToInt())
            }
        }
    }

    private fun markMine(key: String) {
        val t = tracks[key] ?: return
        mine.add(t.key)
        t.following = false
        prefs.edit().putStringSet("mine", HashSet(mine)).apply()
        toast("Marked as yours. It will not raise alerts. (Tags that change their address will look new.)")
        refreshUi()
    }

    // ------------------------------------------------------------------ info sheet

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
        if (w != null) {
            parts.add(
                "⚑ Possible ALPR signature: ${w.label} (${w.confidence} confidence).\n" +
                    "These patterns are community-reported and can match other devices, so treat this as a lead, not proof.\n" +
                    (sightings[t.key]?.let { "Map check: ${mappedStatus(it)}" } ?: "")
            )
        }
        sheet.bind(t, loc, t.key == selectedKey, if (parts.isEmpty()) null else parts.joinToString("\n\n"))
    }

    private fun updateStatus(visible: List<Track>) {
        val counts = Protocol.values().joinToString(" · ") { p ->
            "${p.label.substringBefore(' ')} ${visible.count { it.protocol == p }}"
        }
        val gps = loc?.let { "GPS ±${it.accuracy.roundToInt()} m" } ?: "no GPS fix yet"
        val err = errors.entries.joinToString("; ") { "${it.key.label}: ${it.value}" }
        val alpr = visible.count { it.watch != null }
        val follow = visible.count { it.following }
        statusText.text = counts + "\n" + gps + " · heading ${headingDeg.roundToInt()}°" +
            (if (bgScan) " · screen-off scan on" else "") +
            (if (err.isNotEmpty()) "\n⚠ $err" else "") +
            (if (alpr > 0) "\n⚑ $alpr possible ALPR signature(s) nearby" else "") +
            (if (follow > 0) "\n⚠ $follow tag(s) may be following you" else "") +
            (if (alprOn) "\n${deflock.statusLine()} · © OpenStreetMap contributors" else "")
    }

    private fun updateTarget() {
        val t = selectedKey?.let { tracks[it] }
        if (t == null) {
            targetBearing = null
            targetText.text = "Tap a signal to track it"
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
            if (f.tower) {
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
                } else {
                    "Walk 10 m or more to get a bearing (${t.samples.size} points)"
                }
            )
        }
        targetText.text = sb
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
        if (t.protocol == Protocol.CELL) {
            toast("Cell towers are not calibrated")
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

    private fun showSettings() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        box.addView(TextView(this).apply {
            text = "OpenCelliD API key (free at opencellid.org/register). Used to look up where the tower you are connected to stands."
            textSize = 13f
        })
        val keyEdit = EditText(this).apply {
            setText(towers.apiKey)
            hint = "API key"
            isSingleLine = true
        }
        box.addView(keyEdit)
        val trackerBox = CheckBox(this).apply {
            text = "Alert when an unknown tag keeps appearing as I move"
            textSize = 13f
            isChecked = trackerAlerts
        }
        box.addView(trackerBox)
        box.addView(TextView(this).apply {
            text = "Distance calibration\n" +
                Protocol.values().filter { it != Protocol.CELL }.joinToString("\n") { Calib.describe(it) } +
                "\n\nVendor registry: " + Vendors.registryState() +
                "\nTags marked as yours: ${mine.size}"
            textSize = 12f
            setPadding(0, dp(12), 0, 0)
        })
        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                towers.apiKey = keyEdit.text.toString().trim()
                trackerAlerts = trackerBox.isChecked
                prefs.edit().putString("ocid_key", towers.apiKey).putBoolean("tracker_alerts", trackerAlerts).apply()
                refreshUi()
            }
            .setNeutralButton("Reset calibration") { _, _ ->
                Calib.resetAll()
                saveCalib()
                refreshUi()
            }
            .setNegativeButton("Cancel", null)
            .show()
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
