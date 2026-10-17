package com.colorfit.band.ui.home

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanResult
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.colorfit.band.BandSession
import com.colorfit.band.ble.BandForegroundService
import com.colorfit.band.ble.BandScanner
import com.colorfit.band.ble.BlePermissions
import com.colorfit.band.ble.PowerSettings
import com.colorfit.band.weather.WeatherService
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
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    application: Application,
    private val session: BandSession,
    private val scanner: BandScanner,
    private val weather: WeatherService,
) : AndroidViewModel(application) {

    val band: StateFlow<BandSession.State> = session.state
    val weatherLocation = weather.location
    val weatherStatus = weather.status

    private val _scanResults = MutableStateFlow<List<ScanResult>>(emptyList())
    val scanResults: StateFlow<List<ScanResult>> = _scanResults.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _scanError = MutableStateFlow<String?>(null)
    val scanError: StateFlow<String?> = _scanError.asStateFlow()

    private val permissions = MutableStateFlow(BlePermissions.snapshot(application))
    val allPermissionsGranted: StateFlow<Boolean> =
        permissions.map { it.allGranted }.stateIn(viewModelScope, SharingStarted.Eagerly, permissions.value.allGranted)

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
            _scanError.value = "Turn Bluetooth on and grant the permissions first."
            return
        }
        _scanError.value = null
        _scanResults.value = emptyList()
        _scanning.value = true
        scanJob = viewModelScope.launch {
            try {
                scanner.scan().collect { result ->
                    // Named devices only — the band always advertises its name.
                    if (runCatching { result.device.name }.getOrNull().isNullOrBlank()) return@collect
                    _scanResults.update { list ->
                        if (list.any { it.device.address == result.device.address }) list else list + result
                    }
                }
            } catch (e: Exception) {
                _scanError.value = e.message
            } finally {
                _scanning.value = false
            }
        }
        viewModelScope.launch {
            delay(SCAN_TIMEOUT_MS)
            stopScan()
        }
    }

    private fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        _scanning.value = false
    }

    fun connect(device: BluetoothDevice) {
        stopScan()
        session.connect(device)
        BandForegroundService.start(getApplication())
    }

    fun disconnect() {
        session.disconnect()
        BandForegroundService.stop(getApplication())
    }

    fun reconnectNow() {
        session.reconnectNow()
        BandForegroundService.start(getApplication())
    }

    fun forget() {
        session.forget()
        BandForegroundService.stop(getApplication())
    }

    fun syncNow() = session.syncNow()
    fun findBand() = session.findBand()
    fun measureHeartRate() = session.measureHeartRate()
    fun measureBloodPressure() = session.measureBloodPressure()
    fun measureBloodOxygen() = session.measureBloodOxygen()
    fun stopMeasurement() = session.stopMeasurement()
    fun setAutoHeartRate(steps: Int) = session.setAutoHeartRate(steps)

    fun hasLocationPermission(): Boolean = weather.hasLocationPermission()
    fun weatherFromDevice() { viewModelScope.launch { weather.useDeviceLocation() } }
    fun weatherFromCity(name: String) { viewModelScope.launch { weather.useCityName(name) } }
    fun pushWeather() { viewModelScope.launch { weather.refreshAndPush() } }

    fun isIgnoringBatteryOptimizations(): Boolean = PowerSettings.isIgnoringBatteryOptimizations(getApplication())

    override fun onCleared() {
        stopScan()
        super.onCleared()
    }

    companion object {
        private const val SCAN_TIMEOUT_MS = 15_000L
    }
}
