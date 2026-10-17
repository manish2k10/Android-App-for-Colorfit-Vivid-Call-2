package com.colorfit.band

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.colorfit.band.ble.BandForegroundService
import com.colorfit.band.ble.BandPrefs
import com.colorfit.band.weather.WeatherService
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class BandApp : Application() {

    // Created with the process, not with the UI: the session reconnects to the
    // remembered band on start, and the weather service re-pushes on each connect.
    @Inject lateinit var session: BandSession
    @Inject lateinit var weather: WeatherService

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) Timber.plant(Timber.DebugTree())
        createChannel()
        if (BandPrefs.shouldStayConnected(this)) BandForegroundService.start(this)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_BLE,
            getString(R.string.notif_channel_ble),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notif_channel_ble_desc)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_BLE = "colorfit_band_channel"
    }
}
