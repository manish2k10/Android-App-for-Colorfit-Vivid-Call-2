package com.colorfit.band.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Plain BLE scan without filters: the band's advertising name varies by
 * firmware ("ColorFit" on this one), so the user picks from the list. The
 * session checks for the `FEEA` service after connecting.
 */
@Singleton
class BandScanner @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val adapter get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    fun isReady(): Boolean = adapter?.isEnabled == true && BlePermissions.snapshot(context).allGranted

    @SuppressLint("MissingPermission")
    fun scan(): Flow<ScanResult> = callbackFlow {
        val scanner = adapter?.bluetoothLeScanner ?: run {
            close(IllegalStateException("Bluetooth is off"))
            return@callbackFlow
        }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                trySend(result)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { trySend(it) }
            }

            override fun onScanFailed(errorCode: Int) {
                close(IllegalStateException("Scan failed: $errorCode"))
            }
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        runCatching { scanner.startScan(null, settings, callback) }.onFailure { close(it) }
        awaitClose { runCatching { scanner.stopScan(callback) } }
    }
}
