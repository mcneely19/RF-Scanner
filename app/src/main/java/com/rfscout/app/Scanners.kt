package com.rfscout.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.bluetooth.le.ScanResult as BleScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import android.telephony.CellIdentityNr
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat

/** All scanners deliver readings on the main thread. */
interface RfScanner {
    /** @return null on success, otherwise a short reason it could not start. */
    fun start(): String?
    fun stop()
}

fun wifiChannel(freqMhz: Int): Int = when {
    freqMhz == 2484 -> 14
    freqMhz in 2412..2472 -> (freqMhz - 2407) / 5
    freqMhz in 5000..5895 -> (freqMhz - 5000) / 5
    freqMhz in 5955..7115 -> (freqMhz - 5950) / 5
    else -> 0
}

// ---------------------------------------------------------------- Wi-Fi

@Suppress("DEPRECATION")
class WifiScanner(private val ctx: Context, private val out: (Reading) -> Unit) : RfScanner {
    private val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val handler = Handler(Looper.getMainLooper())
    private val lastTs = HashMap<String, Long>()
    private var running = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            publish()
        }
    }

    // Android throttles startScan() (about 4 per 2 min in the foreground). Extra calls are harmless.
    // Turn off "Wi-Fi scan throttling" in Developer options for continuous scanning.
    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                wm.startScan()
            } catch (_: SecurityException) {
            }
            handler.postDelayed(this, 10_000)
        }
    }

    override fun start(): String? {
        if (!wm.isWifiEnabled) return "Wi-Fi is off"
        ContextCompat.registerReceiver(
            ctx, receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
            ContextCompat.RECEIVER_EXPORTED
        )
        running = true
        handler.post(tick)
        publish()
        return null
    }

    override fun stop() {
        running = false
        handler.removeCallbacks(tick)
        try {
            ctx.unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) {
        }
    }

    @SuppressLint("MissingPermission")
    private fun publish() {
        val list = try {
            wm.scanResults
        } catch (_: SecurityException) {
            return
        }
        val bootMs = SystemClock.elapsedRealtime()
        val now = System.currentTimeMillis()
        for (r in list) {
            val bssid = r.BSSID ?: continue
            val ts = r.timestamp                       // microseconds since boot
            if (lastTs[bssid] == ts) continue          // not a fresh measurement
            lastTs[bssid] = ts
            val ageMs = bootMs - ts / 1000
            if (ageMs > 30_000) continue
            val ssid = r.SSID.orEmpty().ifBlank { "(hidden)" }
            out(
                Reading(
                    key = "wifi:$bssid", protocol = Protocol.WIFI, name = ssid,
                    rssi = r.level, freqMhz = r.frequency,
                    detail = "ch ${wifiChannel(r.frequency)} · $bssid",
                    timeMs = now - ageMs.coerceAtLeast(0)
                )
            )
        }
    }
}

// ---------------------------------------------------------------- Bluetooth LE

class BleScanner(private val ctx: Context, private val out: (Reading) -> Unit) : RfScanner {
    private val adapter: BluetoothAdapter? =
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private var scanner: BluetoothLeScanner? = null
    private var running = false

    /**
     * Android blocks unfiltered BLE scans while the screen is off, so in background mode only
     * item-tracker and watchlist advertisements are requested (they are the ones that matter then).
     */
    var backgroundMode = false
        set(v) {
            if (field == v) return
            field = v
            if (running) {
                stopScan()
                startScan()
            }
        }

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: BleScanResult) {
            publish(result)
        }

        override fun onBatchScanResults(results: MutableList<BleScanResult>) {
            for (r in results) publish(r)
        }
    }

    @SuppressLint("MissingPermission")
    override fun start(): String? {
        val a = adapter ?: return "No Bluetooth"
        if (!a.isEnabled) return "Bluetooth is off"
        if (a.bluetoothLeScanner == null) return "BLE scanner unavailable"
        running = true
        return startScan()
    }

    @SuppressLint("MissingPermission")
    private fun startScan(): String? {
        val s = adapter?.bluetoothLeScanner ?: return "BLE scanner unavailable"
        return try {
            val mode = if (backgroundMode) ScanSettings.SCAN_MODE_BALANCED else ScanSettings.SCAN_MODE_LOW_LATENCY
            s.startScan(
                if (backgroundMode) backgroundFilters() else null,
                ScanSettings.Builder().setScanMode(mode).build(),
                callback
            )
            scanner = s
            null
        } catch (_: SecurityException) {
            "Bluetooth permission denied"
        } catch (_: IllegalStateException) {
            "Bluetooth turned off"
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        try {
            scanner?.stopScan(callback)
        } catch (_: Exception) {
        }
        scanner = null
    }

    override fun stop() {
        running = false
        stopScan()
    }

    private fun backgroundFilters(): List<ScanFilter> {
        val none = ByteArray(0)
        val list = ArrayList<ScanFilter>()
        // Apple Find My network tags (AirTag style): manufacturer data starts 0x12 0x19
        list.add(
            ScanFilter.Builder()
                .setManufacturerData(Trackers.APPLE_ID, byteArrayOf(0x12, 0x19), byteArrayOf(-1, -1))
                .build()
        )
        // Samsung SmartTag and Tile: service data under their 16-bit UUIDs
        for (u in listOf(Trackers.UUID_SAMSUNG, Trackers.UUID_TILE_A, Trackers.UUID_TILE_B)) {
            list.add(ScanFilter.Builder().setServiceData(u, none, none).build())
        }
        // Google Find Hub network tags: service data 0xFEAA with frame type 0x40 or 0x41
        list.add(ScanFilter.Builder().setServiceData(Trackers.UUID_GOOGLE, byteArrayOf(0x40), byteArrayOf(-1)).build())
        list.add(ScanFilter.Builder().setServiceData(Trackers.UUID_GOOGLE, byteArrayOf(0x41), byteArrayOf(-1)).build())
        // watchlist company ID
        list.add(ScanFilter.Builder().setManufacturerData(Watch.BLE_COMPANY_ID, none, none).build())
        return list
    }

    @SuppressLint("MissingPermission")
    private fun publish(r: BleScanResult) {
        val rec = r.scanRecord
        val name = rec?.deviceName?.takeIf { it.isNotBlank() }
            ?: (try { r.device.name } catch (_: SecurityException) { null })?.takeIf { it.isNotBlank() }
            ?: "(unnamed)"
        val mfr = rec?.manufacturerSpecificData
        val vendor = if (mfr != null && mfr.size() > 0) "mfr 0x%04X".format(mfr.keyAt(0)) else ""
        val mfrId = if (mfr != null && mfr.size() > 0) mfr.keyAt(0) else null
        val tx = r.txPower.takeIf { it != BleScanResult.TX_POWER_NOT_PRESENT }
        val addr = r.device.address
        out(
            Reading(
                key = "ble:$addr", protocol = Protocol.BLE, name = name, rssi = r.rssi,
                freqMhz = 2440, txPower = tx, mfrId = mfrId,
                tracker = Trackers.kindOf(rec),
                detail = listOf(addr, vendor).filter { it.isNotEmpty() }.joinToString(" · ")
            )
        )
    }
}

/**
 * Advertisement formats used by item trackers. Reported formats, not guaranteed: vendors change
 * them, and other products can reuse them. A match means "looks like a tracker".
 */
object Trackers {
    const val APPLE_ID = 0x004C
    val UUID_SAMSUNG: ParcelUuid = ParcelUuid.fromString("0000fd5a-0000-1000-8000-00805f9b34fb")
    val UUID_TILE_A: ParcelUuid = ParcelUuid.fromString("0000feed-0000-1000-8000-00805f9b34fb")
    val UUID_TILE_B: ParcelUuid = ParcelUuid.fromString("0000feec-0000-1000-8000-00805f9b34fb")
    val UUID_GOOGLE: ParcelUuid = ParcelUuid.fromString("0000feaa-0000-1000-8000-00805f9b34fb")

    fun kindOf(rec: android.bluetooth.le.ScanRecord?): String? {
        if (rec == null) return null
        val apple = rec.getManufacturerSpecificData(APPLE_ID)
        if (apple != null && apple.size >= 2 && apple[0] == 0x12.toByte() && apple[1] == 0x19.toByte()) {
            return "Apple Find My tag (AirTag style)"
        }
        if (rec.getServiceData(UUID_SAMSUNG) != null) return "Samsung SmartTag style"
        if (rec.getServiceData(UUID_TILE_A) != null || rec.getServiceData(UUID_TILE_B) != null) return "Tile style"
        val g = rec.getServiceData(UUID_GOOGLE)
        if (g != null && g.isNotEmpty() && (g[0] == 0x40.toByte() || g[0] == 0x41.toByte())) {
            return "Google Find Hub tag style"
        }
        return null
    }
}

// ---------------------------------------------------------------- Bluetooth Classic

class BtClassicScanner(private val ctx: Context, private val out: (Reading) -> Unit) : RfScanner {
    private val adapter: BluetoothAdapter? =
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private var running = false

    private val receiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(c: Context?, i: Intent?) {
            val intent = i ?: return
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val dev = deviceExtra(intent) ?: return
                    val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
                    if (rssi == Short.MIN_VALUE.toInt()) return
                    val name = (try { dev.name } catch (_: SecurityException) { null })
                        ?: intent.getStringExtra(BluetoothDevice.EXTRA_NAME)
                        ?: "(unnamed)"
                    out(
                        Reading(
                            key = "bt:${dev.address}", protocol = Protocol.BT_CLASSIC, name = name,
                            rssi = rssi, freqMhz = 2440, detail = dev.address
                        )
                    )
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> if (running) restart()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun deviceExtra(i: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= 33) {
            i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

    @SuppressLint("MissingPermission")
    private fun restart() {
        try {
            adapter?.startDiscovery()
        } catch (_: SecurityException) {
        }
    }

    @SuppressLint("MissingPermission")
    override fun start(): String? {
        val a = adapter ?: return "No Bluetooth"
        if (!a.isEnabled) return "Bluetooth is off"
        val filter = IntentFilter(BluetoothDevice.ACTION_FOUND).apply {
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        ContextCompat.registerReceiver(ctx, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        running = true
        return try {
            if (a.startDiscovery()) null else "Discovery refused"
        } catch (_: SecurityException) {
            stop()
            "Bluetooth permission denied"
        }
    }

    @SuppressLint("MissingPermission")
    override fun stop() {
        running = false
        try {
            adapter?.cancelDiscovery()
        } catch (_: SecurityException) {
        }
        try {
            ctx.unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) {
        }
    }
}

// ---------------------------------------------------------------- Cellular

class CellScanner(private val ctx: Context, private val out: (Reading) -> Unit) : RfScanner {
    private val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            poll()
            handler.postDelayed(this, 3000)
        }
    }

    override fun start(): String? {
        if (tm == null) return "No telephony"
        running = true
        handler.post(tick)
        return null
    }

    override fun stop() {
        running = false
        handler.removeCallbacks(tick)
    }

    @SuppressLint("MissingPermission")
    private fun poll() {
        val t = tm ?: return
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                t.requestCellInfoUpdate(
                    ContextCompat.getMainExecutor(ctx),
                    object : TelephonyManager.CellInfoCallback() {
                        override fun onCellInfo(cellInfo: MutableList<CellInfo>) {
                            publish(cellInfo)
                        }
                    }
                )
            } else {
                publish(t.allCellInfo ?: emptyList())
            }
        } catch (_: SecurityException) {
        }
    }

    private fun emit(key: String, name: String, dbm: Int, detail: String, ident: CellIdent?) {
        if (dbm == Int.MAX_VALUE || dbm == Int.MIN_VALUE) return
        out(
            Reading(
                key = "cell:$key", protocol = Protocol.CELL, name = name, rssi = dbm,
                detail = detail, cell = ident
            )
        )
    }

    private fun ident(radio: String, mcc: String?, mnc: String?, area: Int, cid: Long, serving: Boolean): CellIdent? {
        if (!serving) return null          // neighbors only report PCI / frequency, no usable cell ID
        val c = CellIdent(radio, mcc ?: "", mnc ?: "", area, cid, true)
        return if (c.usable()) c else null
    }

    @Suppress("DEPRECATION")
    private fun publish(list: List<CellInfo>) {
        for (ci in list) {
            val serving = ci.isRegistered
            val role = if (serving) "serving" else "neighbor"
            when {
                ci is CellInfoLte -> {
                    val id = ci.cellIdentity
                    val ss = ci.cellSignalStrength
                    val mcc = if (Build.VERSION.SDK_INT >= 28) id.mccString else id.mcc.takeIf { it != Int.MAX_VALUE }?.toString()
                    val mnc = if (Build.VERSION.SDK_INT >= 28) id.mncString else id.mnc.takeIf { it != Int.MAX_VALUE }?.toString()
                    emit(
                        "lte:${id.pci}:${id.earfcn}", "LTE PCI ${id.pci}", ss.dbm,
                        "$role · EARFCN ${id.earfcn} · RSRQ ${ss.rsrq}",
                        ident("LTE", mcc, mnc, id.tac, id.ci.toLong(), serving)
                    )
                }
                Build.VERSION.SDK_INT >= 29 && ci is CellInfoNr -> {
                    val id = ci.cellIdentity as CellIdentityNr
                    emit(
                        "nr:${id.pci}:${id.nrarfcn}", "5G NR PCI ${id.pci}", ci.cellSignalStrength.dbm,
                        "$role · NRARFCN ${id.nrarfcn}",
                        // 5G cells are shown with their IDs but not looked up (see Towers)
                        ident("NR", id.mccString, id.mncString, id.tac, id.nci, serving)
                    )
                }
                ci is CellInfoWcdma -> {
                    val id = ci.cellIdentity
                    val mcc = if (Build.VERSION.SDK_INT >= 28) id.mccString else id.mcc.takeIf { it != Int.MAX_VALUE }?.toString()
                    val mnc = if (Build.VERSION.SDK_INT >= 28) id.mncString else id.mnc.takeIf { it != Int.MAX_VALUE }?.toString()
                    emit(
                        "umts:${id.cid}:${id.lac}", "UMTS PSC ${id.psc}", ci.cellSignalStrength.dbm,
                        "$role · UARFCN ${id.uarfcn}",
                        ident("UMTS", mcc, mnc, id.lac, id.cid.toLong(), serving)
                    )
                }
                ci is CellInfoGsm -> {
                    val id = ci.cellIdentity
                    val mcc = if (Build.VERSION.SDK_INT >= 28) id.mccString else id.mcc.takeIf { it != Int.MAX_VALUE }?.toString()
                    val mnc = if (Build.VERSION.SDK_INT >= 28) id.mncString else id.mnc.takeIf { it != Int.MAX_VALUE }?.toString()
                    emit(
                        "gsm:${id.cid}:${id.lac}", "GSM CID ${id.cid}", ci.cellSignalStrength.dbm,
                        "$role · ARFCN ${id.arfcn}",
                        ident("GSM", mcc, mnc, id.lac, id.cid.toLong(), serving)
                    )
                }
            }
        }
    }
}
