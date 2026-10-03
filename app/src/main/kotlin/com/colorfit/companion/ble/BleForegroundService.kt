package com.colorfit.companion.ble

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import com.colorfit.companion.App
import com.colorfit.companion.MainActivity
import com.colorfit.companion.R
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Foreground service that owns the BLE connection so it survives Activity
 * recreation, screen-off, and brief process backgrounding.
 *
 * Stays out of the way — it doesn't manage the connection itself; it just
 * keeps the process alive while [BleConnectionManager] does its thing.
 */
@AndroidEntryPoint
class BleForegroundService : LifecycleService() {

    @Inject lateinit var connection: BleConnectionManager

    override fun onCreate() {
        super.onCreate()
        startInForegroundCompat("Idle", getString(R.string.notif_ble_scanning))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_CONNECTED -> startInForegroundCompat(
                intent.getStringExtra(EXTRA_DEVICE_NAME) ?: "Watch",
                getString(R.string.notif_ble_text, intent.getStringExtra(EXTRA_DEVICE_NAME) ?: "Watch"),
            )
            ACTION_DISCONNECTED -> startInForegroundCompat("Idle", getString(R.string.notif_ble_scanning))
            ACTION_STOP -> {
                connection.close()
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    private fun startInForegroundCompat(title: String, text: String) {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, App.CHANNEL_BLE)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(contentIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    companion object {
        const val NOTIF_ID = 0xCF01

        const val ACTION_CONNECTED = "com.colorfit.companion.action.CONNECTED"
        const val ACTION_DISCONNECTED = "com.colorfit.companion.action.DISCONNECTED"
        const val ACTION_STOP = "com.colorfit.companion.action.STOP"
        const val EXTRA_DEVICE_NAME = "device_name"

        fun startConnected(ctx: Context, deviceName: String) {
            ctx.startService(
                Intent(ctx, BleForegroundService::class.java)
                    .setAction(ACTION_CONNECTED)
                    .putExtra(EXTRA_DEVICE_NAME, deviceName),
            )
        }

        fun startDisconnected(ctx: Context) {
            ctx.startService(
                Intent(ctx, BleForegroundService::class.java).setAction(ACTION_DISCONNECTED),
            )
        }

        fun stop(ctx: Context) {
            ctx.startService(
                Intent(ctx, BleForegroundService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
