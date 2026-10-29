package com.colorfit.companion.ui.home

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanResult
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.colorfit.companion.ble.BleConnectionManager
import com.colorfit.companion.ble.BleForegroundService
import com.colorfit.companion.ble.BlePermissions
import com.colorfit.companion.ble.BleScanner
import com.colorfit.companion.data.GattDumper
import com.colorfit.companion.domain.WatchState
import com.colorfit.companion.vendor.DailyWeather
import com.colorfit.companion.vendor.NotificationAppId
import com.colorfit.companion.vendor.ReconnectStatus
import com.colorfit.companion.vendor.VendorConnection
import com.colorfit.companion.vendor.VendorState
import com.colorfit.companion.vendor.WeatherService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    application: Application,
    private val scanner: BleScanner,
    private val connection: BleConnectionManager,
    private val dumper: GattDumper,
    private val vendor: VendorConnection,
    private val weather: WeatherService,
) : AndroidViewModel(application) {

    private val _scanResults = MutableStateFlow<List<ScanResult>>(emptyList())
    val scanResults: StateFlow<List<ScanResult>> = _scanResults.asStateFlow()

    private val _status = MutableStateFlow<UiStatus>(UiStatus.Idle)
    val status: StateFlow<UiStatus> = _status.asStateFlow()

    private val _lastDumpPath = MutableStateFlow<String?>(null)
    val lastDumpPath: StateFlow<String?> = _lastDumpPath.asStateFlow()

    val state: StateFlow<WatchState> = connection.state
    val vendorState: StateFlow<VendorState> = vendor.state
    val weatherLocation = weather.location
    val lastWeatherFetch = weather.lastFetch
    val weatherStatus = weather.status
    val capabilities = vendor.capabilities

    /**
     * True when the user explicitly tapped Disconnect. Distinguishes
     * "I'm done, go back to the scan screen" from "the link dropped,
     * we're auto-reconnecting — stay on the connected view with a
     * reconnecting banner." The user only lands back on the scan
     * screen when they ask to. Owned by [VendorConnection], which also
     * holds off auto-reconnecting while it is set.
     */
    val userInitiatedDisconnect: StateFlow<Boolean> = vendor.userDisconnected

    /**
     * What auto-reconnect is doing: retrying, waiting for Bluetooth, or
     * stopped. Drives the reconnect banner so it only says "Reconnecting…"
     * while a retry is really pending.
     */
    val reconnectStatus: StateFlow<ReconnectStatus> = vendor.reconnectStatus

    /**
     * True while a previously-paired watch is dropped and not deliberately
     * disconnected, so the UI stays on the connected view with a banner
     * instead of bouncing back to the scan card.
     */
    val reconnecting: StateFlow<Boolean> = vendor.reconnectStatus
        .map { it != ReconnectStatus.IDLE }
        .stateIn(viewModelScope, SharingStarted.Eagerly, vendor.reconnectStatus.value != ReconnectStatus.IDLE)

    private val permissions = MutableStateFlow(BlePermissions.snapshot(application))
    val allRequiredPermissionsGranted: StateFlow<Boolean> =
        permissions.map { it.allGranted }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private var scanJob: Job? = null

    fun refreshPermissions() {
        permissions.value = BlePermissions.snapshot(getApplication())
    }

    fun toggleScan() {
        if (scanJob?.isActive == true) stopScan() else startScan()
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        if (!scanner.isReady()) {
            _status.value = UiStatus.Error("Bluetooth not ready or permissions missing")
            return
        }
        _scanResults.value = emptyList()
        _status.value = UiStatus.Scanning
        scanJob = viewModelScope.launch {
            try {
                // WILDCARD — show every nearby BLE device. Cheap Chinese
                // watches rename themselves across firmware versions so a
                // strict name filter is more likely to hide yours than
                // help. The user picks from the list.
                scanner.scan(BleScanner.WILDCARD).collect { result ->
                    val mac = result.device.address
                    val seen = _scanResults.value
                    if (seen.none { it.device.address == mac }) {
                        _scanResults.update { it + result }
                    }
                }
            } finally {
                if (isActive) _status.value = UiStatus.Idle
            }
        }
        // Auto-stop after 15s — Android BLE scans drain battery and most cheap
        // watches re-advertise every ~200ms so 15s is plenty.
        viewModelScope.launch {
            delay(SCAN_TIMEOUT_MS)
            stopScan()
        }
    }

    private fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        if (_status.value is UiStatus.Scanning) _status.value = UiStatus.Idle
    }

    fun connect(device: BluetoothDevice) {
        stopScan()
        vendor.connect(device)
        BleForegroundService.start(getApplication())
    }

    fun disconnect() {
        vendor.disconnect()
        BleForegroundService.start(getApplication())
    }

    fun dumpGattTable() {
        val file = dumper.export(connection.state.value)
        _lastDumpPath.value = file.absolutePath
    }

    // ───────── Vendor / weather actions ───────────────────────────────────

    fun pushTestNotification() {
        vendor.pushNotification(
            title = "Test",
            body = "Hello from Colorfit Companion — this is a test push.",
        )
    }

    fun startHr() = vendor.startHrStream()
    fun stopHr() = vendor.stopHrStream()
    fun startSpo2() = vendor.startSpo2Measurement()
    fun findWatch() = vendor.findWatch()

    fun setWeatherLocation(latitude: Double, longitude: Double, label: String) {
        weather.setLocation(latitude, longitude, label)
    }

    fun refreshWeather() {
        viewModelScope.launch {
            val ok = weather.refreshAndPush()
            Timber.tag("Home").i("Weather refresh ok=$ok")
        }
    }

    /** True if the app has ACCESS_FINE_LOCATION granted. */
    fun hasLocationPermission(): Boolean = weather.hasLocationPermission()

    /**
     * Use the phone's last known GPS position as the weather source and
     * push to the watch. Caller should request
     * `Manifest.permission.ACCESS_FINE_LOCATION` first if
     * [hasLocationPermission] returns false.
     */
    fun useDeviceLocation() {
        viewModelScope.launch {
            val ok = weather.useDeviceLocation()
            Timber.tag("Home").i("Device-location weather ok=$ok")
        }
    }

    /** Geocode a city name via Open-Meteo and push weather. */
    fun useCityName(city: String) {
        viewModelScope.launch {
            val ok = weather.useCityName(city)
            Timber.tag("Home").i("City '$city' weather ok=$ok")
        }
    }

    /** Force a reconnect to the last bonded watch. */
    fun reconnectNow() {
        vendor.reconnectNow()
    }

    /** Forget the bonded device — scan card comes back on next launch. */
    fun forgetWatch() {
        vendor.clearLastMac()
        connection.disconnect()
        // Nothing left to keep alive for — tear the service down so the
        // persistent notification goes away.
        BleForegroundService.stop(getApplication())
    }

    /** True if the app is exempt from Doze / battery optimization. */
    fun isIgnoringBatteryOptimizations(): Boolean =
        com.colorfit.companion.ble.PowerSettings.isIgnoringBatteryOptimizations(getApplication())

    /** True if a fresh HR reading arrived in the last 15s. */
    fun heartRateStreaming(): Boolean = vendor.heartRateStreaming

    init {
        // Whenever the vendor handshake completes, auto-push weather if
        // we already have a location set. Saves the user a tap every
        // time they put the watch on.
        viewModelScope.launch {
            vendor.readyEvents.collect {
                if (weather.location.value != null) {
                    val ok = weather.refreshAndPush()
                    Timber.tag("Home").i("Auto weather push on connect: ok=$ok")
                }
            }
        }
    }

    fun syncHistoryNow() {
        viewModelScope.launch { vendor.syncHistory() }
    }

    override fun onCleared() {
        stopScan()
        super.onCleared()
    }

    companion object {
        private const val SCAN_TIMEOUT_MS = 15_000L
    }
}

sealed interface UiStatus {
    data object Idle : UiStatus
    data object Scanning : UiStatus
    data class Error(val message: String) : UiStatus
}
