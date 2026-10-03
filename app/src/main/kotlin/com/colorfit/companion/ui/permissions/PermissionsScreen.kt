package com.colorfit.companion.ui.permissions

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.colorfit.companion.ble.PermissionSnapshot
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.MultiplePermissionsState
import com.google.accompanist.permissions.rememberMultiplePermissionsState

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun PermissionsScreen(
    current: PermissionSnapshot,
    onAllGranted: () -> Unit,
) {
    val ctx = LocalContext.current
    // Build the list of Android permissions we need at runtime.
    val perms = remember(current) {
        buildList {
            if (current.needsLocation) add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (current.needsScanConnect) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (current.needsPostNotifications) add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val state: MultiplePermissionsState = rememberMultiplePermissionsState(permissions = perms)

    // When everything flips to granted, surface the navigation callback.
    var announced by remember { mutableStateOf(false) }
    LaunchedEffect(state.allPermissionsGranted) {
        if (state.allPermissionsGranted && !announced) {
            announced = true
            onAllGranted()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "Colorfit Companion needs a few permissions to talk to your watch.",
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            "Android splits BLE access across multiple runtime permissions, so we ask for all of them at once.",
            style = MaterialTheme.typography.bodyMedium,
        )

        perms.forEach { p ->
            PermissionRow(
                label = humanise(p),
                granted = state.permissions.firstOrNull { it.permission == p }?.status is com.google.accompanist.permissions.PermissionStatus.Granted,
            )
        }

        Spacer(Modifier.height(8.dp))

        Button(
            onClick = { state.launchMultiplePermissionRequest() },
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.allPermissionsGranted,
        ) {
            Text(if (state.allPermissionsGranted) "All granted" else "Grant permissions")
        }

        if (current.needsLocation) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Why location?", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "On Android 11 and earlier, scanning for Bluetooth devices requires location access. " +
                            "We never read or store your location.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Notification access", style = MaterialTheme.typography.titleSmall)
                Text(
                    "To mirror notifications to your watch (messages, calls, app alerts) we need the " +
                        "Notification Access permission. Android shows this in a separate Settings screen — " +
                        "find \"Colorfit Companion\" and toggle it on.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(
                    onClick = {
                        ctx.startActivity(
                            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Open notification access settings")
                }
            }
        }
    }
}

@Composable
private fun PermissionRow(label: String, granted: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            modifier = Modifier.size(24.dp),
            shape = CircleShape,
            color = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.errorContainer,
        ) {
            Icon(
                imageVector = if (granted) Icons.Default.Check else Icons.Default.Close,
                contentDescription = if (granted) "Granted" else "Not granted",
                modifier = Modifier.padding(2.dp),
                tint = if (granted) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onErrorContainer,
            )
        }
        Spacer(Modifier.size(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun humanise(permission: String): String = when (permission) {
    Manifest.permission.BLUETOOTH_SCAN -> "Nearby Bluetooth devices (scan)"
    Manifest.permission.BLUETOOTH_CONNECT -> "Connect to paired Bluetooth devices"
    Manifest.permission.ACCESS_FINE_LOCATION -> "Precise location"
    Manifest.permission.POST_NOTIFICATIONS -> "Show notifications (Android 13+)"
    else -> permission
}
