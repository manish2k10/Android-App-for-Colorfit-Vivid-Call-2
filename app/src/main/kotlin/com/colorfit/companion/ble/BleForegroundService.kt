package com.colorfit.companion.ble

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.colorfit.companion.App
import com.colorfit.companion.MainActivity
import com.colorfit.companion.R
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber
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
            ACTION_DISCONNECTED -> startInForegroundCompat(
                getString(R.string.notif_ble_title),
                getString(R.string.notif_ble_scanning),
            )
            ACTION_STOP -> {
                connection.close()
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
                // Don't let the system resurrect us after a deliberate stop.
                return START_NOT_STICKY
            }
            // ACTION_START, or a null intent from a sticky restart after the
            // process was killed: just re-assert foreground so we keep living.
            else -> startInForegroundCompat(
                getString(R.string.notif_ble_title),
                getString(R.string.notif_ble_scanning),
            )
        }
        // START_STICKY: if the OS kills us for memory, recreate the service
        // (with a null intent, handled above) as soon as it can.
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
            // Show the notification immediately rather than after the ~10s
            // grace period, so the service is unambiguously foreground.
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(contentIntent)
            .build()

        // A background FGS start can be refused on Android 12+ (throws
        // ForegroundServiceStartNotAllowedException). Never let that crash the
        // process — if it's refused now, the next app launch starts it cleanly.
        runCatching {
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
        }.onFailure { Timber.tag("BleFgs").w(it, "startForeground refused") }
    }

    companion object {
        const val NOTIF_ID = 0xCF01

        const val ACTION_START = "com.colorfit.companion.action.START"
        const val ACTION_CONNECTED = "com.colorfit.companion.action.CONNECTED"
        const val ACTION_DISCONNECTED = "com.colorfit.companion.action.DISCONNECTED"
        const val ACTION_STOP = "com.colorfit.companion.action.STOP"
        const val EXTRA_DEVICE_NAME = "device_name"

        /**
         * Ensure the service is running and foreground. Used at app launch and
         * after boot so the process stays alive while we auto-reconnect in the
         * background — not just while actively connected.
         */
        fun start(ctx: Context) = launch(ctx, ACTION_START)

        fun startConnected(ctx: Context, deviceName: String) = launch(ctx, ACTION_CONNECTED) {
            it.putExtra(EXTRA_DEVICE_NAME, deviceName)
        }

        fun startDisconnected(ctx: Context) = launch(ctx, ACTION_DISCONNECTED)

        fun stop(ctx: Context) = launch(ctx, ACTION_STOP)

        private inline fun launch(ctx: Context, action: String, extras: (Intent) -> Unit = {}) {
            val intent = Intent(ctx, BleForegroundService::class.java).setAction(action).also(extras)
            // startForegroundService (not startService) is required on API 26+;
            // the service then has ~5s to call startForeground, which it does
            // in onCreate / onStartCommand. Guard against the background-start
            // refusal on API 31+ so we never crash the caller.
            runCatching { ContextCompat.startForegroundService(ctx, intent) }
                .onFailure { Timber.tag("BleFgs").w(it, "could not start service ($action)") }
        }
    }
}
