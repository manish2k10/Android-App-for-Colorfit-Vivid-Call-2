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
import com.colorfit.companion.domain.ConnectionState
import com.colorfit.companion.domain.WatchState
import com.colorfit.companion.vendor.DailyWeather
import com.colorfit.companion.vendor.NotificationAppId
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
     * screen when they ask to.
     */
    private val _userInitiatedDisconnect = MutableStateFlow(false)
    val userInitiatedDisconnect: StateFlow<Boolean> = _userInitiatedDisconnect.asStateFlow()

    /**
     * True while we're trying to reconnect to a previously-paired
     * watch after a transient drop. Drives the in-app "reconnecting…"
     * banner so the UI doesn't bounce back to the scan card on every
     * 2-second retry cycle.
     */
    private val _reconnecting = MutableStateFlow(false)
    val reconnecting: StateFlow<Boolean> = _reconnecting.asStateFlow()

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
        _userInitiatedDisconnect.value = false
        _reconnecting.value = false
        connection.connect(device)
        BleForegroundService.startConnected(
            getApplication(),
            device.name ?: device.address,
        )
    }

    fun disconnect() {
        _userInitiatedDisconnect.value = true
        _reconnecting.value = false
        connection.disconnect()
        BleForegroundService.startDisconnected(getApplication())
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
        _reconnecting.value = false
        _userInitiatedDisconnect.value = true
    }

    /** True if a fresh HR reading arrived in the last 15s. */
    fun heartRateStreaming(): Boolean = vendor.heartRateStreaming

    init {
        // If we already have a bonded MAC (persisted from a previous
        // session), flip the UI into "reconnecting" immediately so the
        // scan card never flashes for half a second on relaunch. The
        // VendorConnection singleton schedules the actual BLE
        // reconnect attempt in its own init block.
        if (vendor.lastConnectedMac != null) {
            _reconnecting.value = true
        }
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
        // Track reconnecting state for the UI. When the BLE link drops
        // and we have a remembered MAC, mark the UI as "reconnecting"
        // so we don't bounce back to the scan card.
        viewModelScope.launch {
            connection.state.collect { s ->
                val connected = s.connection is ConnectionState.Connected
                val idle = s.connection is ConnectionState.Idle ||
                    s.connection is ConnectionState.Disconnected
                if (idle && !_userInitiatedDisconnect.value && vendor.lastConnectedMac != null) {
                    _reconnecting.value = true
                } else if (connected) {
                    _reconnecting.value = false
                } else {
                    _reconnecting.value = false
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
