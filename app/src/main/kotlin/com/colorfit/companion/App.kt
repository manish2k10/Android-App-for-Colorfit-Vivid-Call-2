package com.colorfit.companion

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.colorfit.companion.ble.BleForegroundService
import com.colorfit.companion.ble.WatchPrefs
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

@HiltAndroidApp
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        createBleChannel()
        // If we already know a watch, bring the foreground service up as soon
        // as the process starts so it keeps living while VendorConnection
        // auto-reconnects in the background — not only while actively connected.
        // (Refused silently if the process happened to start in the background
        // on API 31+; the next user launch starts it cleanly.)
        if (WatchPrefs.hasBondedWatch(this)) {
            BleForegroundService.start(this)
        }
    }

    private fun createBleChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_BLE,
            getString(R.string.notif_channel_ble),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notif_channel_ble_desc)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_BLE = "colorfit_ble_channel"
    }
}
