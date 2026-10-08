package com.colorfit.companion.ble

import android.content.Context

/**
 * Single source of truth for the "which watch are we bonded to" preference,
 * shared by [BleForegroundService], [BootReceiver] and [com.colorfit.companion.App]
 * so the process can decide — without constructing the whole Hilt graph —
 * whether it should bring the foreground service up on launch or after boot.
 *
 * The values intentionally match the prefs that
 * [com.colorfit.companion.vendor.VendorConnection] reads/writes.
 */
object WatchPrefs {
    const val PREFS_NAME = "colorfit_vendor_prefs"
    const val PREF_LAST_MAC = "last_connected_mac"

    fun lastMac(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREF_LAST_MAC, null)

    /** True once the user has connected to a watch we should keep reconnecting to. */
    fun hasBondedWatch(context: Context): Boolean = !lastMac(context).isNullOrBlank()
}
