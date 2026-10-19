package com.colorfit.band.ble

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.colorfit.band.BandApp
import com.colorfit.band.BandSession
import com.colorfit.band.MainActivity
import com.colorfit.band.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Keeps the process alive so the band stays connected with the app in the
 * background. It doesn't manage the connection — [BandSession] does — it only
 * mirrors the connection state in its notification.
 */
@AndroidEntryPoint
class BandForegroundService : LifecycleService() {

    @Inject lateinit var session: BandSession

    override fun onCreate() {
        super.onCreate()
        startInForeground(getString(R.string.notif_ble_idle))
        lifecycleScope.launch {
            session.state
                .map { s -> (s.link as? BandGatt.Link.Connected)?.let { it.name ?: s.bandName ?: it.address } }
                .distinctUntilChanged()
                .collect { connectedTo ->
                    val text = connectedTo?.let { getString(R.string.notif_ble_text, it) }
                        ?: getString(R.string.notif_ble_idle)
                    notify(text)
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        // ACTION_START, or a null intent from a sticky restart.
        startInForeground(getString(R.string.notif_ble_idle))
        return START_STICKY
    }

    private fun build(text: String) = NotificationCompat.Builder(this, BandApp.CHANNEL_BLE)
        .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
        .setContentTitle(getString(R.string.notif_ble_title))
        .setContentText(text)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        .build()

    private fun startInForeground(text: String) {
        // A background start can be refused on Android 12+; never crash for it.
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(this, NOTIF_ID, build(text), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(NOTIF_ID, build(text))
            }
        }.onFailure { Timber.tag(TAG).w(it, "startForeground refused") }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun notify(text: String) {
        runCatching { NotificationManagerCompat.from(this).notify(NOTIF_ID, build(text)) }
    }

    companion object {
        private const val TAG = "BandFgs"
        private const val NOTIF_ID = 0xBA01
        private const val ACTION_START = "com.colorfit.band.action.START"
        private const val ACTION_STOP = "com.colorfit.band.action.STOP"

        fun start(context: Context) = launch(context, ACTION_START)

        fun stop(context: Context) = launch(context, ACTION_STOP)

        private fun launch(context: Context, action: String) {
            val intent = Intent(context, BandForegroundService::class.java).setAction(action)
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { Timber.tag(TAG).w(it, "could not start service ($action)") }
        }
    }
}
