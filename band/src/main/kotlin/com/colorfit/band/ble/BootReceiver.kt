package com.colorfit.band.ble

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Brings the foreground service back after a reboot or an app update — only
 * when there's a remembered band the user hasn't disconnected from.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
            -> if (BandPrefs.shouldStayConnected(context)) BandForegroundService.start(context)
        }
    }
}
