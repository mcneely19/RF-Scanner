package com.rfscout.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator

/** Notifications and vibration: the ongoing "scanning" notice and unknown-tracker alerts. */
object Alerts {
    const val CH_SCAN = "scan"
    const val CH_ALERT = "alerts"
    const val ID_SCAN = 1
    const val ID_ALERT = 2
    const val ACTION_STOP = "com.rfscout.app.STOP"

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CH_SCAN, "Scanning", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while RF Scout scans with the screen off"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERT, "Tracker alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Unknown Bluetooth tag that keeps appearing as you move"
            }
        )
    }

    private fun openApp(ctx: Context): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(ctx, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** The always-visible notification of the scanning service, with a Stop button. */
    fun scanNotification(ctx: Context, text: String): Notification {
        ensureChannels(ctx)
        val stop = PendingIntent.getService(
            ctx, 1, Intent(ctx, ScanService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(ctx, CH_SCAN)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("RF Scout is scanning")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(ctx))
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    fun updateScanText(ctx: Context, text: String) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(ID_SCAN, scanNotification(ctx, text))
    }

    @Suppress("DEPRECATION")
    fun trackerAlert(ctx: Context, t: Track, minutes: Long, metres: Int) {
        ensureChannels(ctx)
        val kind = t.trackerKind ?: "Bluetooth tag"
        val n = Notification.Builder(ctx, CH_ALERT)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Unknown tracker may be following you")
            .setContentText("$kind has stayed in range for $minutes min and about $metres m of travel")
            .setStyle(
                Notification.BigTextStyle().bigText(
                    "$kind has stayed in range for $minutes min and about $metres m of travel. " +
                        "Open RF Scout, tap its flag or row, and mark it as yours if it is."
                )
            )
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx))
            .build()
        try {
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(ID_ALERT, n)
        } catch (_: SecurityException) {
        }
        try {
            val v = ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (v != null && v.hasVibrator()) {
                v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 200, 400, 200, 400), -1))
            }
        } catch (_: Exception) {
        }
    }
}
