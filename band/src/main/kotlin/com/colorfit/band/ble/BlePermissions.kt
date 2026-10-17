package com.colorfit.band.ble

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Runtime permissions the app needs: BLUETOOTH_SCAN + BLUETOOTH_CONNECT on
 * Android 12+, fine location before that, and POST_NOTIFICATIONS on 13+ for
 * the foreground-service notification.
 */
data class PermissionSnapshot(
    val needsLocation: Boolean,
    val needsScanConnect: Boolean,
    val needsPostNotifications: Boolean,
    val granted: Set<String>,
) {
    val allGranted: Boolean get() = granted.containsAll(required())

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
        val snapshot = PermissionSnapshot(
            needsLocation = api < Build.VERSION_CODES.S,
            needsScanConnect = api >= Build.VERSION_CODES.S,
            needsPostNotifications = api >= Build.VERSION_CODES.TIRAMISU,
            granted = emptySet(),
        )
        val granted = snapshot.required().filterTo(mutableSetOf()) {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        return snapshot.copy(granted = granted)
    }
}
