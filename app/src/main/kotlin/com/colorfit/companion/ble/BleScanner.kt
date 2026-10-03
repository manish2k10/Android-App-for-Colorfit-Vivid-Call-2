package com.colorfit.companion.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin wrapper around [BluetoothLeScanner].
 *
 *   - Filters by exact device name (Android's [ScanFilter.setDeviceName] only
 *     matches exact strings), with a fallback to the Battery service UUID when
 *     the name is left blank.
 *   - Exposes results as a cold Flow.
 *   - Cleans up when the collector cancels.
 *
 * The collector decides scan duration — cancel the Flow to stop. The
 * ViewModel uses a `take(15.seconds)` style timeout.
 */
@Singleton
class BleScanner @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val manager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    private val adapter: BluetoothAdapter? get() = manager?.adapter

    fun isReady(): Boolean {
        val currentAdapter = adapter
        if (currentAdapter == null || !currentAdapter.isEnabled) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val scanGranted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_SCAN,
            ) == PackageManager.PERMISSION_GRANTED
            val connectGranted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_CONNECT,
            ) == PackageManager.PERMISSION_GRANTED
            if (!scanGranted || !connectGranted) return false
        } else {
            val locationGranted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
            if (!locationGranted) return false
        }
        return true
    }

    @SuppressLint("MissingPermission")
    fun scan(nameFilter: String? = WATCH_NAME_FILTER): Flow<ScanResult> = callbackFlow {
        val scanner = adapter?.bluetoothLeScanner
            ?: run {
                close(IllegalStateException("Bluetooth adapter not available"))
                return@callbackFlow
            }

        val filters: List<ScanFilter> = when {
            // Exact name match (rarely useful — cheap Chinese watches rename
            // themselves per batch, so "ColorFit Vivid Call 2" is just one
            // of many possibilities).
            !nameFilter.isNullOrBlank() && nameFilter != WILDCARD -> listOf(
                ScanFilter.Builder().setDeviceName(nameFilter).build()
            )
            // Battery service UUID — most BLE wearables advertise this in
            // their scan record even if they don't expose Battery as a
            // primary GATT service.
            nameFilter != WILDCARD -> listOf(
                ScanFilter.Builder()
                    .setServiceUuid(ParcelUuid(GattSpec.BATTERY))
                    .build()
            )
            // WILDCARD — return all devices. Useful when you don't know
            // the exact advertising name.
            else -> emptyList()
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .build()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                trySend(result)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { trySend(it) }
            }

            override fun onScanFailed(errorCode: Int) {
                close(IllegalStateException("Scan failed: errorCode=$errorCode"))
            }
        }

        runCatching { scanner.startScan(filters, settings, callback) }
            .onFailure { close(it) }

        awaitClose {
            runCatching { scanner.stopScan(callback) }
        }
    }

    companion object {
        /**
         * Sentinel value: pass as [scan] nameFilter to get every nearby
         * device back (no name or service-UUID filter at all). Cheap
         * Chinese BLE watches rename themselves across firmware versions,
         * and we don't want a stale filter to hide yours.
         */
        const val WILDCARD: String = "*"

        /**
         * Soft preference — what the Colorfit Vivid Call 2 typically
         * advertises as in the firmware the SDK was built against. We
         * don't actually use this as a strict filter (too brittle);
         * [WILDCARD] is the default. Kept here as documentation.
         */
        const val WATCH_NAME_FILTER: String = "vivid call 2"
    }
}
