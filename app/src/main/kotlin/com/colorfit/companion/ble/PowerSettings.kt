package com.colorfit.companion.ble

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Helpers for the OS-level settings that decide whether a background BLE app
 * is allowed to keep running.
 *
 * On most phones — and especially aggressive OEMs like Motorola, Xiaomi,
 * Samsung and Oppo — a foreground service alone is **not** enough: Doze and
 * the vendor's own "cleanup" will still kill the process (and drop the BLE
 * link) after a few minutes once the app is battery-optimized. Being on the
 * battery-optimization allow-list is the single biggest factor in staying
 * connected.
 */
object PowerSettings {

    /** True if the app is exempt from Doze / battery optimization. */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Intent that pops the system dialog asking the user to exempt this app
     * from battery optimization. Requires the
     * `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` permission (declared in the
     * manifest). Must be launched from an Activity context.
     */
    fun requestIgnoreBatteryOptimizationsIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }

    /**
     * Fallback that opens this app's system "App info" page, where the user
     * can find the OEM-specific "Background restriction" / "Auto-launch"
     * toggles that no public intent reliably reaches (common on Motorola).
     */
    fun appDetailsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
}
