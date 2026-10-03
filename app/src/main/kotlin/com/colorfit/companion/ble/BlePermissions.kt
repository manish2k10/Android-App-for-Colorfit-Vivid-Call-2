package com.colorfit.companion.ble

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Snapshot of the permissions the app currently holds, plus the ones it still needs.
 *
 * The Android BLE permission model has two regimes:
 *   - API 30 and below: BLUETOOTH + BLUETOOTH_ADMIN + ACCESS_FINE_LOCATION.
 *     Scanning for peripherals requires ACCESS_FINE_LOCATION because BLE
 *     advertisements can be used to infer location.
 *   - API 31+ (Android 12): BLUETOOTH_SCAN, BLUETOOTH_CONNECT. The
 *     `neverForLocation` flag in the manifest means we no longer need FINE_LOCATION.
 *
 * Phase 2 will also need BIND_NOTIFICATION_LISTENER_SERVICE (granted via the
 * Settings UI) for notification mirroring — handled separately.
 */
data class PermissionSnapshot(
    val needsLocation: Boolean,
    val needsScanConnect: Boolean,
    val needsPostNotifications: Boolean,
    val granted: Set<String>,
) {
    val allGranted: Boolean
        get() = granted.containsAll(required())
            .let { it && (granted.contains(Manifest.permission.POST_NOTIFICATIONS) || !needsPostNotifications) }

    fun required(): Set<String> = buildSet {
        if (needsLocation) add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (needsScanConnect) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (needsPostNotifications) add(Manifest.permission.POST_NOTIFICATIONS)
    }
}

object BlePermissions {
    fun snapshot(context: Context): PermissionSnapshot {
        val api = Build.VERSION.SDK_INT
        val needsScanConnect = api >= Build.VERSION_CODES.S
        val needsLocation = api < Build.VERSION_CODES.S
        val needsPostNotifs = api >= Build.VERSION_CODES.TIRAMISU

        val granted = mutableSetOf<String>()
        fun has(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

        if (needsLocation && has(Manifest.permission.ACCESS_FINE_LOCATION)) {
            granted.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (needsScanConnect) {
            if (has(Manifest.permission.BLUETOOTH_SCAN)) granted.add(Manifest.permission.BLUETOOTH_SCAN)
            if (has(Manifest.permission.BLUETOOTH_CONNECT)) granted.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (needsPostNotifs && has(Manifest.permission.POST_NOTIFICATIONS)) {
            granted.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        return PermissionSnapshot(
            needsLocation = needsLocation,
            needsScanConnect = needsScanConnect,
            needsPostNotifications = needsPostNotifs,
            granted = granted,
        )
    }
}
