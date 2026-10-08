package com.colorfit.companion.ui.home

import android.Manifest
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import android.content.Intent
import android.provider.Settings
import com.colorfit.companion.ble.PowerSettings
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.accompanist.permissions.isGranted
import com.colorfit.companion.domain.ConnectionState
import com.colorfit.companion.domain.ServiceType
import com.colorfit.companion.domain.WatchState
import com.colorfit.companion.vendor.VendorState

@OptIn(com.google.accompanist.permissions.ExperimentalPermissionsApi::class)
@androidx.compose.material3.ExperimentalMaterial3Api
@Composable
fun HomeScreen(
    viewModel: HomeViewModel = hiltViewModel(),
    onOpenNotificationApps: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val scanResults by viewModel.scanResults.collectAsStateWithLifecycle()
    val lastDumpPath by viewModel.lastDumpPath.collectAsStateWithLifecycle()
    val vendorState by viewModel.vendorState.collectAsStateWithLifecycle()
    val weatherLocation by viewModel.weatherLocation.collectAsStateWithLifecycle()
    val lastWeatherFetch by viewModel.lastWeatherFetch.collectAsStateWithLifecycle()

    // Re-read the location permission state every time this composable
    // recomposes — when the user grants permission via the system
    // dialog, this flips to true and the "Use my location" button
    // appears.
    var locationGranted by remember { mutableStateOf(viewModel.hasLocationPermission()) }
    val locationPermissionState = com.google.accompanist.permissions.rememberPermissionState(
        android.Manifest.permission.ACCESS_FINE_LOCATION,
    )
    val userInitiatedDisconnect by viewModel.userInitiatedDisconnect.collectAsStateWithLifecycle()
    val reconnecting by viewModel.reconnecting.collectAsStateWithLifecycle()
    val weatherStatus by viewModel.weatherStatus.collectAsStateWithLifecycle()
    val capabilities by viewModel.capabilities.collectAsStateWithLifecycle()

    // Whether the app is exempt from battery optimization. Re-checked every
    // time the screen resumes, so the warning card disappears right after the
    // user grants the exemption in the system dialog.
    var batteryOptimized by remember { mutableStateOf(!viewModel.isIgnoringBatteryOptimizations()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        batteryOptimized = !viewModel.isIgnoringBatteryOptimizations()
    }

    LaunchedEffect(locationPermissionState.status) {
        locationGranted = locationPermissionState.status.isGranted ||
            viewModel.hasLocationPermission()
        if (locationGranted) {
            // Auto-push weather with the fresh location.
            viewModel.useDeviceLocation()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Colorfit Companion") },
                actions = {
                    IconButton(onClick = onOpenNotificationApps) {
                        Icon(Icons.Default.Notifications, contentDescription = "Watch notifications")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { ConnectionCard(state, status, viewModel::disconnect, reconnecting, viewModel::forgetWatch) }

            // Nudge the user to exempt the app from battery optimization —
            // without it, aggressive OEMs (Motorola, Xiaomi, …) kill the
            // process and drop the BLE link after a few minutes in the
            // background. Shown until they grant it.
            if (batteryOptimized) {
                item { BackgroundPermissionCard() }
            }

            // Show the connected view whenever the watch is actually
            // connected OR we're silently auto-reconnecting. Only show
            // the scan card when the user explicitly chose to disconnect
            // (or hasn't connected at all yet).
            val showConnectedView = state.isConnected || (!userInitiatedDisconnect && reconnecting)
            if (showConnectedView) {
                if (reconnecting && !state.isConnected) {
                    item { ReconnectingCard(viewModel::reconnectNow) }
                }
                item { LiveMetricsCard(state, vendorState, isHrStreaming = viewModel.heartRateStreaming()) }
                item { SleepCard(vendorState.historical) }
                item { HeartRateHistoryCard(vendorState.historical) }
                item { DeviceInfoCard(state, vendorState, capabilities) }
                item { VendorActionsCard(viewModel) }
                item { WeatherCard(
                    location = weatherLocation,
                    lastFetch = lastWeatherFetch,
                    statusMessage = weatherStatus,
                    hasLocationPermission = locationGranted,
                    onSetLocation = viewModel::setWeatherLocation,
                    onRefresh = viewModel::refreshWeather,
                    onUseDeviceLocation = viewModel::useDeviceLocation,
                    onRequestLocationPermission = { locationPermissionState.launchPermissionRequest() },
                    onUseCityName = viewModel::useCityName,
                ) }
                item { ReconToolsCard(lastDumpPath, onDump = viewModel::dumpGattTable) }
                // Diagnostics last — it's reference material, not something
                // you need above the live metrics day to day.
                item { GattTableCard(state) }
            } else {
                item { ScanCard(status, scanResults, viewModel::toggleScan, viewModel::connect) }
            }
        }
    }
}

@Composable
private fun BackgroundPermissionCard() {
    val context = LocalContext.current
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Keep running in the background",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                "Android is still allowed to close this app to save battery, which " +
                    "drops the watch connection after a few minutes. Allow it to run " +
                    "unrestricted to stay connected.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Button(
                onClick = {
                    runCatching {
                        context.startActivity(
                            PowerSettings.requestIgnoreBatteryOptimizationsIntent(context),
                        )
                    }.onFailure {
                        // Some OEMs don't honour the direct dialog — fall back to
                        // the battery-optimization list screen.
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                            )
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Allow unrestricted battery") }
            TextButton(
                onClick = {
                    runCatching { context.startActivity(PowerSettings.appDetailsIntent(context)) }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Open app settings") }
            Text(
                "On Motorola, Xiaomi, Oppo and similar, also turn on Auto-launch and " +
                    "turn off \"Background restriction\" under App info.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun ConnectionCard(state: WatchState, status: UiStatus, onDisconnect: () -> Unit, reconnecting: Boolean, onForget: () -> Unit) {
    val connected = state.connection is ConnectionState.Connected
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (connected) MaterialTheme.colorScheme.primaryContainer
            else if (reconnecting) MaterialTheme.colorScheme.tertiaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val (icon, tint, label) = when (val c = state.connection) {
                    is ConnectionState.Connected -> Triple(Icons.Default.BluetoothConnected, MaterialTheme.colorScheme.primary, c.deviceName)
                    is ConnectionState.Connecting -> Triple(Icons.Default.BluetoothSearching, MaterialTheme.colorScheme.tertiary, "Connecting to ${c.deviceName}…")
                    is ConnectionState.Scanning -> Triple(Icons.Default.BluetoothSearching, MaterialTheme.colorScheme.tertiary, "Scanning…")
                    is ConnectionState.Error -> Triple(Icons.Default.Bluetooth, MaterialTheme.colorScheme.error, c.message)
                    ConnectionState.Idle -> Triple(Icons.Default.Bluetooth, MaterialTheme.colorScheme.outline, "Not connected")
                    ConnectionState.Disconnected -> Triple(Icons.Default.Bluetooth, MaterialTheme.colorScheme.outline, "Disconnected")
                }
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(36.dp))
                Spacer(Modifier.size(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.titleMedium)
                    val mac = (state.connection as? ConnectionState.Connected)?.macAddress
                    if (mac != null) {
                        Text(mac, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                    if (reconnecting && !connected) {
                        Text(
                            "Reconnecting to last paired watch…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
                if (connected) {
                    OutlinedButton(onClick = onDisconnect) { Text("Disconnect") }
                } else if (reconnecting) {
                    OutlinedButton(onClick = onDisconnect) { Text("Cancel") }
                }
            }
            if (connected) {
                TextButton(
                    onClick = onForget,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "Forget this watch",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun LiveMetricsCard(state: WatchState, vendorState: VendorState, isHrStreaming: Boolean) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Live metrics", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricTile(
                    icon = Icons.Default.Favorite,
                    label = "Heart rate",
                    value = vendorState.live.heartRateBpm?.let { "$it bpm" }
                        ?: state.lastHeartRateBpm?.let { "$it bpm" } ?: "—",
                    streaming = isHrStreaming,
                    modifier = Modifier.weight(1f),
                )
                val battery = displayBatteryPercent(state, vendorState)
                MetricTile(
                    icon = Icons.Default.Insights,
                    label = "Battery",
                    value = battery?.let { "$it%" } ?: "—",
                    progress = battery?.toFloat()?.div(100f),
                    modifier = Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricTile(
                    icon = Icons.Default.DirectionsWalk,
                    label = "Steps",
                    value = vendorState.live.steps?.toString() ?: "—",
                    modifier = Modifier.weight(1f),
                )
                MetricTile(
                    icon = Icons.Default.Air,
                    label = "SpO₂",
                    value = vendorState.live.spO2Percent?.let { "$it%" } ?: "—",
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun MetricTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    streaming: Boolean? = null,
    caption: String? = null,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.size(8.dp))
                Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                if (streaming != null) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(
                                if (streaming) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant,
                                CircleShape,
                            ),
                    )
                }
            }
            Spacer(Modifier.size(8.dp))
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            if (caption != null) {
                Text(
                    caption,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (streaming == false) {
                Text(
                    "Stale — tap Start HR",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (progress != null) {
                Spacer(Modifier.size(8.dp))
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun DeviceInfoCard(
    state: WatchState,
    vendorState: VendorState,
    capabilities: com.colorfit.companion.vendor.VendorCapabilities,
) {
    // Firmware: prefer the vendor 0xA1 string — many of these watches don't
    // expose the standard Device Information service at all, so the SIG
    // characteristic is the fallback rather than the source of truth.
    val firmware = vendorState.live.firmwareVersion ?: state.firmwareRevision
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Device information", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

            InfoRow("Name", state.deviceName ?: "—")
            InfoRow("Address", state.macAddress ?: "—")
            InfoRow("Firmware", firmware ?: "—")
            vendorState.live.dspVersion?.let { InfoRow("DSP", it) }

            // Only render the SIG rows the watch actually answered.
            state.manufacturer?.let { InfoRow("Manufacturer", it) }
            state.modelNumber?.let { InfoRow("Model", it) }
            state.hardwareRevision?.let { InfoRow("Hardware", it) }
            state.softwareRevision?.let { InfoRow("Software", it) }
            state.serialNumber?.let { InfoRow("Serial", it) }

            displayBatteryPercent(state, vendorState)?.let { InfoRow("Battery", "$it%") }
            state.mtu?.let { InfoRow("MTU", "$it bytes") }
            if (capabilities.isKnown) {
                InfoRow("Features", capabilities.summary())
            }

            if (!state.hasDeviceInformation) {
                Text(
                    "This watch doesn't expose the standard Device Information " +
                        "service (0x180A), so model and serial aren't available — " +
                        "the firmware version above comes from the vendor protocol.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The battery percentage to display, resolved in one place so different
 * cards can't show different numbers for the same thing.
 *
 * The vendor `0xA2` reading is authoritative: these watches often report a
 * non-spec value on the standard `0x2A19` characteristic (it is not always
 * a 0-100 percentage), so that is only a fallback.
 */
private fun displayBatteryPercent(state: WatchState, vendorState: VendorState): Int? =
    vendorState.live.batteryPercent ?: state.batteryPercent

@Composable
private fun InfoRow(label: String, value: String) {
    Row {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun GattTableCard(state: WatchState) {
    val vendorCount = state.gattTable.count { it.type == ServiceType.VENDOR }
    val standardCount = state.gattTable.count { it.type == ServiceType.STANDARD }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("GATT table", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                AssistChip(
                    onClick = {},
                    label = { Text("${state.gattTable.size} services") },
                    colors = AssistChipDefaults.assistChipColors(),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = {}, label = { Text("$standardCount standard") })
                AssistChip(onClick = {}, label = { Text("$vendorCount vendor") })
            }
            Text(
                "Vendor services hold the Noise proprietary protocol — these are the ones we reverse-engineer next.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.gattTable.take(8).forEach { svc ->
                Text(
                    "${svc.uuid}  •  ${svc.type.name.lowercase()}  •  ${svc.characteristics.size} chars",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            if (state.gattTable.size > 8) {
                Text("+ ${state.gattTable.size - 8} more — export the dump for the full table.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ReconToolsCard(lastDumpPath: String?, onDump: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Phase 0 recon", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Dump the full GATT table to JSON and share it back so I can decode the vendor protocol.",
                style = MaterialTheme.typography.bodyMedium,
            )
            FilledTonalButton(onClick = onDump, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Download, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Dump GATT table")
            }
            if (lastDumpPath != null) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            lastDumpPath,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                        OutlinedButton(onClick = { clipboard.setText(AnnotatedString(lastDumpPath)) }) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy path")
                        }
                    }
                }
            }
        }
    }
}

@android.annotation.SuppressLint("MissingPermission")
@Composable
private fun ScanCard(
    status: UiStatus,
    scanResults: List<android.bluetooth.le.ScanResult>,
    onToggleScan: () -> Unit,
    onConnect: (android.bluetooth.BluetoothDevice) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Scan for watch", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Showing every nearby BLE device — pick the one that matches your Colorfit. " +
                    "Empty list? Make sure the watch is unpaired from NoiseFit Prime / system Bluetooth, " +
                    "and your phone is within ~1m.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(
                onClick = onToggleScan,
                modifier = Modifier.fillMaxWidth(),
            ) {
                val scanning = status is UiStatus.Scanning
                Icon(if (scanning) Icons.Default.Stop else Icons.Default.PlayArrow, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(if (scanning) "Stop scan" else "Start scan (15s)")
            }
            if (scanResults.isEmpty() && status !is UiStatus.Scanning) {
                Text(
                    "No devices found yet. Pair NoiseFit Prime first, or check the watch isn't already connected elsewhere.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                scanResults.forEach { result ->
                    val device = result.device
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 2.dp,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(MaterialTheme.colorScheme.primary, CircleShape),
                            )
                            Spacer(Modifier.size(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    device.name ?: "(no name)",
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                Text(
                                    device.address,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                "RSSI ${result.rssi}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.size(8.dp))
                            FilledTonalButton(onClick = { onConnect(device) }) { Text("Connect") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReconnectingCard(onRetryNow: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.material3.CircularProgressIndicator(
                modifier = Modifier.size(28.dp),
                strokeWidth = 3.dp,
                color = MaterialTheme.colorScheme.tertiary,
            )
            Spacer(Modifier.size(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "Reconnecting…",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Watch link dropped. Auto-retrying in the background — your data stays loaded.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
            OutlinedButton(onClick = onRetryNow) { Text("Retry now") }
        }
    }
}

// ───────── Vendor actions / weather ────────────────────────────────────────

@Composable
private fun VendorActionsCard(viewModel: HomeViewModel) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "Vendor actions",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Quick sanity checks. Each button sends one packet to the watch.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = viewModel::pushTestNotification, modifier = Modifier.weight(1f)) {
                    Text("Test push")
                }
                FilledTonalButton(onClick = viewModel::findWatch, modifier = Modifier.weight(1f)) {
                    Text("Find watch")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = viewModel::startHr, modifier = Modifier.weight(1f)) {
                    Text("Start HR")
                }
                OutlinedButton(onClick = viewModel::stopHr, modifier = Modifier.weight(1f)) {
                    Text("Stop HR")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = viewModel::startSpo2, modifier = Modifier.weight(1f)) {
                    Text("Start SpO₂")
                }
                OutlinedButton(onClick = viewModel::syncHistoryNow, modifier = Modifier.weight(1f)) {
                    Text("Sync history")
                }
            }
        }
    }
}

@OptIn(com.google.accompanist.permissions.ExperimentalPermissionsApi::class)
@Composable
private fun WeatherCard(
    location: com.colorfit.companion.vendor.WeatherService.WeatherLocation?,
    lastFetch: Long?,
    statusMessage: String?,
    hasLocationPermission: Boolean,
    onSetLocation: (Double, Double, String) -> Unit,
    onRefresh: () -> Unit,
    onUseDeviceLocation: () -> Unit,
    onRequestLocationPermission: () -> Unit,
    onUseCityName: (String) -> Unit,
) {
    var latText by remember { mutableStateOf(location?.latitude?.toString() ?: "") }
    var lonText by remember { mutableStateOf(location?.longitude?.toString() ?: "") }
    var labelText by remember { mutableStateOf(location?.label ?: "") }
    var cityText by remember { mutableStateOf("") }

    // `remember` captures its initial value once, so when the location is
    // resolved elsewhere (city lookup, "Use my location", or restored from
    // a previous session) these fields would otherwise stay stale/empty and
    // it would look like the lat/lon never arrived. Mirror it in.
    LaunchedEffect(location) {
        location?.let {
            latText = it.latitude.toString()
            lonText = it.longitude.toString()
            labelText = it.label
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "Weather (Open-Meteo)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Free, no API key. Easiest: tap \"Use my location\" below. " +
                    "Or type a city name and we'll look it up. Or paste a manual lat/lon.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Primary: city name → geocode via Open-Meteo, push weather.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.OutlinedTextField(
                    value = cityText,
                    onValueChange = { cityText = it },
                    label = { Text("City name") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                FilledTonalButton(
                    onClick = { onUseCityName(cityText) },
                    enabled = cityText.isNotBlank(),
                ) { Text("Look up") }
            }

            // Secondary: device GPS — needs ACCESS_FINE_LOCATION on Android 12+.
            if (hasLocationPermission) {
                FilledTonalButton(
                    onClick = onUseDeviceLocation,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.LocationOn, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("Use my location")
                }
            } else {
                OutlinedButton(
                    onClick = onRequestLocationPermission,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.LocationOn, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("Grant location for weather")
                }
            }

            Text(
                "Manual (advanced)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.OutlinedTextField(
                    value = latText,
                    onValueChange = { latText = it },
                    label = { Text("Lat") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                androidx.compose.material3.OutlinedTextField(
                    value = lonText,
                    onValueChange = { lonText = it },
                    label = { Text("Lon") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            androidx.compose.material3.OutlinedTextField(
                value = labelText,
                onValueChange = { labelText = it },
                label = { Text("Label (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        val lat = latText.toDoubleOrNull()
                        val lon = lonText.toDoubleOrNull()
                        if (lat != null && lon != null) {
                            onSetLocation(lat, lon, labelText.ifBlank { "Custom" })
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Save manual") }
                Button(
                    onClick = {
                        // Apply anything typed into the lat/lon boxes before
                        // fetching. Previously you had to press "Save manual"
                        // first, and pressing only this did nothing at all.
                        val lat = latText.toDoubleOrNull()
                        val lon = lonText.toDoubleOrNull()
                        if (lat != null && lon != null &&
                            (lat != location?.latitude || lon != location.longitude)
                        ) {
                            onSetLocation(lat, lon, labelText.ifBlank { "Custom" })
                        }
                        onRefresh()
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (lastFetch == null) "Fetch & push" else "Refresh")
                }
            }
            if (location != null) {
                Text(
                    "Source: ${location.describe()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (statusMessage != null) {
                Text(
                    statusMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (lastFetch != null) {
                val mins = ((System.currentTimeMillis() - lastFetch) / 60_000).coerceAtLeast(0)
                Text(
                    "Last pushed $mins min ago",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
