package com.colorfit.companion.domain

/**
 * Snapshot of what we know about the connected watch.
 *
 * Designed to be UI-safe — only primitive or immutable types — so it can ride
 * a StateFlow without leaking the underlying BluetoothGatt.
 */
data class WatchState(
    val connection: ConnectionState = ConnectionState.Disconnected,
    /** BLE advertised name. Never overwritten by the manufacturer string. */
    val deviceName: String? = null,
    val macAddress: String? = null,
    /** Negotiated ATT MTU, once the watch has answered the request. */
    val mtu: Int? = null,
    // ── Standard Device Information service (0x180A) ──────────────────
    // Many cheap watches don't expose 0x180A at all, in which case these
    // all stay null and the vendor 0xA1 firmware version is the only
    // identifying string available.
    val manufacturer: String? = null,
    val modelNumber: String? = null,
    val firmwareRevision: String? = null,
    val softwareRevision: String? = null,
    val hardwareRevision: String? = null,
    val serialNumber: String? = null,
    val batteryPercent: Int? = null,
    val lastHeartRateBpm: Int? = null,
    val gattTable: List<GattServiceInfo> = emptyList(),
    val errors: List<String> = emptyList(),
) {
    val isConnected: Boolean
        get() = connection is ConnectionState.Connected

    /** True if the watch exposed any standard Device Information at all. */
    val hasDeviceInformation: Boolean
        get() = manufacturer != null || modelNumber != null ||
            firmwareRevision != null || serialNumber != null ||
            softwareRevision != null || hardwareRevision != null
}

sealed interface ConnectionState {
    data object Idle : ConnectionState
    data object Scanning : ConnectionState
    data class Connecting(val deviceName: String) : ConnectionState
    data class Connected(
        val deviceName: String,
        val macAddress: String,
    ) : ConnectionState
    data object Disconnected : ConnectionState
    data class Error(val message: String) : ConnectionState
}

/**
 * Lightweight, JSON-serialisable description of a GATT service discovered on the
 * watch. This is the data the GATT dumper writes to disk for the Phase 0 recon.
 */
data class GattServiceInfo(
    val uuid: String,
    val type: ServiceType,
    val characteristics: List<GattCharacteristicInfo>,
)

enum class ServiceType { STANDARD, VENDOR, UNKNOWN }

data class GattCharacteristicInfo(
    val uuid: String,
    val properties: List<CharProperty>,
    val descriptors: List<GattDescriptorInfo>,
    /** Hex-encoded value snapshot, or null if read failed / not yet read. */
    val valueHex: String? = null,
)

enum class CharProperty { READ, WRITE, WRITE_NO_RESPONSE, NOTIFY, INDICATE, SIGNED_WRITE }

data class GattDescriptorInfo(
    val uuid: String,
    val valueHex: String?,
)
