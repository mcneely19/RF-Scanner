package com.rfscout.app

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager

/**
 * Keeps the app alive and visibly "scanning" while the screen is off. The scanning itself still
 * runs in MainActivity (same process); this service holds the foreground notification, a partial
 * wake lock, and the location-type foreground status that lets location and Bluetooth callbacks
 * keep arriving. The notification's Stop button asks MainActivity to stop everything.
 */
class ScanService : Service() {

    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == Alerts.ACTION_STOP) {
            val cb = stopRequest
            if (cb != null) Handler(Looper.getMainLooper()).post { cb() }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val n = Alerts.scanNotification(this, "Looking for signals and trackers")
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(Alerts.ID_SCAN, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(Alerts.ID_SCAN, n)
        }
        if (wake == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "rfscout:scan").apply {
                setReferenceCounted(false)
                acquire(8L * 3600 * 1000)     // safety timeout: 8 hours
            }
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // swiped away from recents: the scanning state lives in the activity, so end everything
        val cb = stopRequest
        if (cb != null) Handler(Looper.getMainLooper()).post { cb() }
        stopSelf()
    }

    override fun onDestroy() {
        wake?.let { if (it.isHeld) it.release() }
        wake = null
        super.onDestroy()
    }

    companion object {
        /** Set by MainActivity: stop all scanning. */
        @Volatile
        var stopRequest: (() -> Unit)? = null

        /** @return null when started, otherwise a short reason it was not. */
        fun start(ctx: Context): String? = try {
            val i = Intent(ctx, ScanService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            null
        } catch (ex: Exception) {
            ex.message ?: "could not start"
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, ScanService::class.java))
        }
    }
}
