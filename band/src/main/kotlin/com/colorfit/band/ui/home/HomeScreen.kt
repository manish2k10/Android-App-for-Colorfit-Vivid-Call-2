package com.colorfit.band.ui.home

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanResult
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.colorfit.band.BandSession
import com.colorfit.band.ble.BandGatt
import com.colorfit.band.ble.PowerSettings
import com.colorfit.band.protocol.BandCommands
import com.colorfit.band.protocol.BandParsers
import com.colorfit.band.weather.WeatherService
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberPermissionState
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(viewModel: HomeViewModel, onOpenNotificationApps: () -> Unit) {
    val band by viewModel.band.collectAsStateWithLifecycle()
    val scanResults by viewModel.scanResults.collectAsStateWithLifecycle()
    val scanning by viewModel.scanning.collectAsStateWithLifecycle()
    val scanError by viewModel.scanError.collectAsStateWithLifecycle()
    val weatherLocation by viewModel.weatherLocation.collectAsStateWithLifecycle()
    val weatherStatus by viewModel.weatherStatus.collectAsStateWithLifecycle()

    // Re-checked on every resume so the card goes away once the user allows it.
    var batteryOptimized by remember { mutableStateOf(!viewModel.isIgnoringBatteryOptimizations()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) batteryOptimized = !viewModel.isIgnoringBatteryOptimizations()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ColorFit Band") },
                actions = {
                    IconButton(onClick = onOpenNotificationApps) {
                        Icon(Icons.Default.Notifications, contentDescription = "Band notifications")
                    }
                },
            )
        },
    ) { padding ->
        val remembered = band.bandMac != null
        val showScan = !remembered || (band.userDisconnected && !band.connected)
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (showScan) {
                if (remembered) item { ReconnectCard(band, viewModel::reconnectNow, viewModel::forget) }
                item { ScanCard(scanning, scanResults, scanError, viewModel::toggleScan, viewModel::connect) }
            } else {
                item { ConnectionCard(band, viewModel::disconnect, viewModel::reconnectNow, viewModel::forget) }
            }
            if (band.errors.isNotEmpty()) item { ErrorsCard(band.errors) }
            if (batteryOptimized && remembered) item { BatteryCard() }
            if (remembered && !band.userDisconnected) {
                item { TodayCard(band) }
                item {
                    MeasureCard(
                        band,
                        onHeartRate = viewModel::measureHeartRate,
                        onBloodPressure = viewModel::measureBloodPressure,
                        onBloodOxygen = viewModel::measureBloodOxygen,
                        onStop = viewModel::stopMeasurement,
                    )
                }
                item { SleepCard(band) }
                item { HeartRateCard(band, viewModel::setAutoHeartRate) }
                item {
                    WeatherCard(
                        location = weatherLocation,
                        status = weatherStatus,
                        connected = band.ready,
                        hasLocationPermission = viewModel::hasLocationPermission,
                        onUseDevice = viewModel::weatherFromDevice,
                        onUseCity = viewModel::weatherFromCity,
                        onPush = viewModel::pushWeather,
                    )
                }
                item { ActionsCard(band.ready, viewModel::findBand, viewModel::syncNow, onOpenNotificationApps) }
                item { DeviceCard(band) }
            }
        }
    }
}

// ───────── Connection ──────────────────────────────────────────────────────

@Composable
private fun ConnectionCard(band: BandSession.State, onDisconnect: () -> Unit, onRetry: () -> Unit, onForget: () -> Unit) {
    val (status, active) = when (val link = band.link) {
        is BandGatt.Link.Connected -> (if (band.ready) "Connected" else "Connected — setting up…") to true
        is BandGatt.Link.Connecting -> "Waiting for the band to come in range…" to false
        else -> "Reconnecting…" to false
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(band.bandName ?: "ColorFit band", style = MaterialTheme.typography.titleLarge)
            band.bandMac?.let { Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
            Text(status, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onDisconnect) { Text("Disconnect") }
                if (!active) FilledTonalButton(onClick = onRetry) { Text("Retry now") }
            }
            TextButton(onClick = onForget) { Text("Forget this band") }
        }
    }
}

@Composable
private fun ReconnectCard(band: BandSession.State, onReconnect: () -> Unit, onForget: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Disconnected", style = MaterialTheme.typography.titleMedium)
            Text(
                "${band.bandName ?: "Your band"} stays disconnected until you reconnect.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onReconnect) { Text("Reconnect") }
                TextButton(onClick = onForget) { Text("Forget") }
            }
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun ScanCard(
    scanning: Boolean,
    results: List<ScanResult>,
    error: String?,
    onToggle: () -> Unit,
    onConnect: (BluetoothDevice) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Find your band", style = MaterialTheme.typography.titleMedium)
            Text(
                "Your band advertises as \"ColorFit\". If NoiseFit Track is also connected to it, " +
                    "close that app first so the two don't talk to the band at once.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = onToggle, modifier = Modifier.fillMaxWidth()) { Text(if (scanning) "Stop scanning" else "Scan") }
            if (scanning) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            results.sortedByDescending { it.rssi }.forEach { r ->
                val name = runCatching { r.device.name }.getOrNull() ?: "Unknown"
                val likely = name.contains("colorfit", ignoreCase = true) && !name.contains("pro", ignoreCase = true)
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(name, style = MaterialTheme.typography.bodyLarge, fontWeight = if (likely) FontWeight.SemiBold else null)
                        Text("${r.device.address} · ${r.rssi} dBm", style = MaterialTheme.typography.bodySmall)
                    }
                    FilledTonalButton(onClick = { onConnect(r.device) }) { Text("Connect") }
                }
            }
        }
    }
}

@Composable
private fun ErrorsCard(errors: List<String>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            errors.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer) }
        }
    }
}

@Composable
private fun BatteryCard() {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Keep running in the background", style = MaterialTheme.typography.titleSmall)
            Text(
                "Android may close the app to save battery, which drops the band. Allow it to run unrestricted.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = {
                runCatching { context.startActivity(PowerSettings.requestIgnoreBatteryOptimizationsIntent(context)) }
                    .onFailure {
                        runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                    }
            }) { Text("Allow unrestricted battery") }
        }
    }
}

// ───────── Today ───────────────────────────────────────────────────────────

@Composable
private fun TodayCard(band: BandSession.State) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Today", style = MaterialTheme.typography.titleMedium)
            val steps = band.stepsToday
            Row(verticalAlignment = Alignment.Bottom) {
                Text(steps?.steps?.toString() ?: "—", style = MaterialTheme.typography.displaySmall)
                Spacer(Modifier.size(8.dp))
                Text(band.goal?.let { "steps of $it" } ?: "steps", style = MaterialTheme.typography.bodyMedium)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Metric("Distance", steps?.let { "%.2f km".format(it.distanceMeters / 1000.0) } ?: "—")
                Metric("Calories", steps?.let { "${it.calories} kcal" } ?: "—")
                Metric("Battery", band.battery?.let { "$it%" } ?: "—")
            }
            val history = listOfNotNull(
                band.stepsYesterday?.let { "Yesterday ${it.steps}" },
                band.stepsDayBefore?.let { "Day before ${it.steps}" },
            )
            if (history.isNotEmpty()) {
                Text(history.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}

// ───────── Measurements ────────────────────────────────────────────────────

@Composable
private fun MeasureCard(
    band: BandSession.State,
    onHeartRate: () -> Unit,
    onBloodPressure: () -> Unit,
    onBloodOxygen: () -> Unit,
    onStop: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Measure", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Metric("Heart rate", band.lastHeartRate?.let { "$it bpm" } ?: "—")
                Metric("Blood pressure", band.bloodPressure?.let { "${it.first}/${it.second}" } ?: "—")
                Metric("SpO₂", band.bloodOxygen?.let { "$it%" } ?: "—")
            }
            val measuring = band.measuring
            if (measuring != null) {
                val what = when (measuring) {
                    BandSession.Measurement.HEART_RATE -> "heart rate"
                    BandSession.Measurement.BLOOD_PRESSURE -> "blood pressure"
                    BandSession.Measurement.BLOOD_OXYGEN -> "SpO₂"
                }
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    "Measuring $what — keep your wrist still." + (band.liveHeartRate?.let { "  Now: $it bpm" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(onClick = onStop) { Text("Stop") }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = onHeartRate, enabled = band.ready) { Text("Heart rate") }
                    FilledTonalButton(onClick = onBloodPressure, enabled = band.ready) { Text("BP") }
                    FilledTonalButton(onClick = onBloodOxygen, enabled = band.ready) { Text("SpO₂") }
                }
            }
            Text(
                "Readings are the band's optical estimates, not medical measurements.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ───────── Sleep ───────────────────────────────────────────────────────────

@Composable
private fun SleepCard(band: BandSession.State) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Sleep", style = MaterialTheme.typography.titleMedium)
            SleepRow("Last night", band.sleepLastNight)
            SleepRow("Night before", band.sleepNightBefore)
            SleepRow("Two nights ago", band.sleepTwoNightsAgo)
        }
    }
}

@Composable
private fun SleepRow(label: String, night: BandParsers.SleepNight?) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (night == null || night.asleepMinutes == 0) {
            Text("No sleep recorded", style = MaterialTheme.typography.bodyMedium)
        } else {
            val span = listOfNotNull(night.startMinute, night.endMinute).joinToString("–") { clock(it) }
            Text("${hm(night.asleepMinutes)}  ($span)", style = MaterialTheme.typography.titleSmall)
            Text(
                "Deep ${hm(night.deepMinutes)} · Light ${hm(night.lightMinutes)}" +
                    (if (night.remMinutes > 0) " · REM ${hm(night.remMinutes)}" else "") +
                    (if (night.awakeMinutes > 0) " · Awake ${hm(night.awakeMinutes)}" else ""),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

// ───────── Heart rate ──────────────────────────────────────────────────────

@Composable
private fun HeartRateCard(band: BandSession.State, onSetAuto: (Int) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Heart rate today", style = MaterialTheme.typography.titleMedium)
            val readings = band.heartRateToday.filter { it > 0 }
            if (readings.isEmpty()) {
                Text(
                    if (band.autoHeartRate == 0) "Auto heart-rate is off, so the band keeps no history. Turn it on below."
                    else "No readings yet today.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Text(
                    "${readings.min()}–${readings.max()} bpm · average ${readings.average().toInt()}",
                    style = MaterialTheme.typography.titleSmall,
                )
                HourlyChart(band.heartRateToday)
            }
            Text("Measure automatically every", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BandCommands.AUTO_HEART_RATE_STEPS.forEach { steps ->
                    FilterChip(
                        selected = band.autoHeartRate == steps,
                        onClick = { onSetAuto(steps) },
                        enabled = band.ready,
                        label = { Text(if (steps == 0) "Off" else "${steps * 5}m") },
                    )
                }
            }
        }
    }
}

/** 24 bars, one per hour, each the average of that hour's five-minute readings. */
@Composable
private fun HourlyChart(samples: List<Int>) {
    val hourly = (0 until 24).map { h ->
        samples.drop(h * 12).take(12).filter { it > 0 }.takeIf { it.isNotEmpty() }?.average()?.toFloat() ?: 0f
    }
    val top = hourly.maxOrNull()?.takeIf { it > 0f } ?: return
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier = Modifier.fillMaxWidth().height(80.dp)) {
        val slot = size.width / 24f
        hourly.forEachIndexed { i, v ->
            if (v <= 0f) return@forEachIndexed
            val h = size.height * (v / top)
            drawRect(color, topLeft = Offset(i * slot + slot * 0.15f, size.height - h), size = Size(slot * 0.7f, h))
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        listOf("00", "06", "12", "18", "24").forEach { Text(it, style = MaterialTheme.typography.labelSmall) }
    }
}

// ───────── Weather ─────────────────────────────────────────────────────────

@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun WeatherCard(
    location: WeatherService.Location?,
    status: String?,
    connected: Boolean,
    hasLocationPermission: () -> Boolean,
    onUseDevice: () -> Unit,
    onUseCity: (String) -> Unit,
    onPush: () -> Unit,
) {
    var city by remember { mutableStateOf("") }
    val locationPermission = rememberPermissionState(Manifest.permission.ACCESS_FINE_LOCATION) { granted ->
        if (granted) onUseDevice()
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Weather", style = MaterialTheme.typography.titleMedium)
            Text(location?.label ?: "No location yet", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = {
                    if (hasLocationPermission()) onUseDevice() else locationPermission.launchPermissionRequest()
                }) { Text("Use my location") }
                OutlinedButton(onClick = onPush, enabled = location != null && connected) { Text("Send now") }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = city,
                    onValueChange = { city = it },
                    label = { Text("City") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = { onUseCity(city) }, enabled = city.isNotBlank()) { Text("Look up") }
            }
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

// ───────── Actions & device ────────────────────────────────────────────────

@Composable
private fun ActionsCard(ready: Boolean, onFind: () -> Unit, onSync: () -> Unit, onNotifications: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onFind, enabled = ready) { Text("Find band") }
                FilledTonalButton(onClick = onSync, enabled = ready) { Text("Sync now") }
            }
            OutlinedButton(onClick = onNotifications) { Text("Choose notification apps") }
        }
    }
}

@Composable
private fun DeviceCard(band: BandSession.State) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Device", style = MaterialTheme.typography.titleMedium)
            InfoRow("Firmware", band.firmware)
            InfoRow("Manufacturer", band.manufacturer)
            InfoRow("Model", band.model)
            InfoRow("Last sync", band.lastSyncAt?.let { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it)) })
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String?) {
    Row {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value ?: "—", style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
    }
}

private fun hm(minutes: Int): String = if (minutes >= 60) "${minutes / 60} h ${minutes % 60} m" else "$minutes m"

private fun clock(minute: Int): String = "%02d:%02d".format((minute / 60) % 24, minute % 60)
