package com.colorfit.band.ui.permissions

import android.Manifest
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.colorfit.band.ble.PermissionSnapshot
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionStatus
import com.google.accompanist.permissions.rememberMultiplePermissionsState

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun PermissionsScreen(current: PermissionSnapshot, onAllGranted: () -> Unit) {
    val ctx = LocalContext.current
    val perms = remember(current) { current.required().toList() }
    val state = rememberMultiplePermissionsState(permissions = perms)

    LaunchedEffect(state.allPermissionsGranted) {
        if (state.allPermissionsGranted) onAllGranted()
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("ColorFit Band needs a few permissions to talk to your band.", style = MaterialTheme.typography.headlineSmall)
        perms.forEach { p ->
            PermissionRow(
                label = humanise(p),
                granted = state.permissions.firstOrNull { it.permission == p }?.status is PermissionStatus.Granted,
            )
        }
        Button(
            onClick = { state.launchMultiplePermissionRequest() },
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.allPermissionsGranted,
        ) { Text(if (state.allPermissionsGranted) "All granted" else "Grant permissions") }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Notification access", style = MaterialTheme.typography.titleSmall)
                Text(
                    "To show phone notifications on the band, turn on \"ColorFit Band\" in Notification access.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(
                    onClick = {
                        ctx.startActivity(
                            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Open notification access settings") }
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
    Manifest.permission.BLUETOOTH_SCAN -> "Find nearby Bluetooth devices"
    Manifest.permission.BLUETOOTH_CONNECT -> "Connect to Bluetooth devices"
    Manifest.permission.ACCESS_FINE_LOCATION -> "Location (needed to scan on Android 11 and older)"
    Manifest.permission.POST_NOTIFICATIONS -> "Show the connection notification"
    else -> permission
}
