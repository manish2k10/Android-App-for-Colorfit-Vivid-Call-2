package com.colorfit.band.ble

import android.content.Context

/**
 * The remembered band and whether the user chose to stay disconnected. Kept
 * outside the Hilt graph so [com.colorfit.band.BandApp] and [BootReceiver] can
 * read it cheaply.
 */
object BandPrefs {
    private const val PREFS_NAME = "colorfit_band_prefs"
    private const val KEY_MAC = "band_mac"
    private const val KEY_NAME = "band_name"
    private const val KEY_USER_DISCONNECTED = "user_disconnected"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun mac(context: Context): String? = prefs(context).getString(KEY_MAC, null)

    fun name(context: Context): String? = prefs(context).getString(KEY_NAME, null)

    fun remember(context: Context, mac: String, name: String?) {
        prefs(context).edit().putString(KEY_MAC, mac).putString(KEY_NAME, name).apply()
    }

    fun forget(context: Context) {
        prefs(context).edit().remove(KEY_MAC).remove(KEY_NAME).remove(KEY_USER_DISCONNECTED).apply()
    }

    /** True after the user tapped Disconnect, until they connect again. */
    fun userDisconnected(context: Context): Boolean = prefs(context).getBoolean(KEY_USER_DISCONNECTED, false)

    fun setUserDisconnected(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_USER_DISCONNECTED, value).apply()
    }

    /** A band to keep the service up for: remembered and not left disconnected. */
    fun shouldStayConnected(context: Context): Boolean = mac(context) != null && !userDisconnected(context)
}
