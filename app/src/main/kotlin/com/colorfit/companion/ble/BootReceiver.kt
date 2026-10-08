package com.colorfit.companion.ble

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Brings the foreground service back up after a reboot or an app update, but
 * only if the user has a bonded watch — otherwise there's nothing to connect
 * to and we shouldn't show a persistent notification.
 *
 * `BOOT_COMPLETED` is one of the few broadcasts still allowed to start a
 * foreground service from the background on Android 12+, and the
 * `connectedDevice` service type is permitted from it on Android 14+.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON", // HTC/older OEMs
            -> if (WatchPrefs.hasBondedWatch(context)) {
                BleForegroundService.start(context)
            }
        }
    }
}
