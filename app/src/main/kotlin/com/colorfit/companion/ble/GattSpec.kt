package com.colorfit.companion.ble

import java.util.UUID

/**
 * Bluetooth SIG standard 16-bit GATT identifiers we care about for Phase 1.
 * The full list lives at https://www.bluetooth.com/specifications/assigned-numbers/
 *
 * Anything with a custom 128-bit UUID belongs to a vendor protocol and goes through
 * [VendorGatt] (Phase 2).
 */
object GattSpec {
    // Services
    val GENERIC_ACCESS: UUID = UUID.fromString("00001800-0000-1000-8000-00805f9b34fb")
    val GENERIC_ATTRIBUTE: UUID = UUID.fromString("00001801-0000-1000-8000-00805f9b34fb")
    val DEVICE_INFORMATION: UUID = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
    val BATTERY: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
    val HEART_RATE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")

    // Characteristics
    val DEVICE_NAME: UUID = UUID.fromString("00002a00-0000-1000-8000-00805f9b34fb")
    val BATTERY_LEVEL: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
    val HEART_RATE_MEASUREMENT: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
    val BODY_SENSOR_LOCATION: UUID = UUID.fromString("00002a38-0000-1000-8000-00805f9b34fb")
    val MANUFACTURER_NAME: UUID = UUID.fromString("00002a29-0000-1000-8000-00805f9b34fb")
    val MODEL_NUMBER: UUID = UUID.fromString("00002a24-0000-1000-8000-00805f9b34fb")
    val FIRMWARE_REVISION: UUID = UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb")
    val SERIAL_NUMBER: UUID = UUID.fromString("00002a25-0000-1000-8000-00805f9b34fb")
    val HARDWARE_REVISION: UUID = UUID.fromString("00002a27-0000-1000-8000-00805f9b34fb")
    val SOFTWARE_REVISION: UUID = UUID.fromString("00002a28-0000-1000-8000-00805f9b34fb")

    // Descriptors
    val CLIENT_CHARACTERISTIC_CONFIGURATION: UUID =
        UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** Service UUIDs we treat as standard / readable on Phase 1. */
    val standardServices: Set<UUID> = setOf(
        GENERIC_ACCESS,
        GENERIC_ATTRIBUTE,
        DEVICE_INFORMATION,
        BATTERY,
        HEART_RATE,
    )
}

/**
 * Vendor (Noise) service UUIDs discovered from the decompiled
 * `com.yc.pedometer.utils.UUIDUtils` class.
 */
object VendorGatt {
    // From UUIDUtils.java (decompiled NoiseFit Prime 1.1.20)
    val NOISE_LEGACY: UUID = UUID.fromString("000055ff-0000-1000-8000-00805f9b34fb")
    val NOISE_BLE5: UUID = UUID.fromString("000056ff-0000-1000-8000-00805f9b34fb")
    val NOISE_BP: UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
    val NOISE_ALIPAY: UUID = UUID.fromString("000057ff-0000-1000-8000-00805f9b34fb")

    val knownVendorServices: Set<UUID> = setOf(
        NOISE_LEGACY,
        NOISE_BLE5,
        NOISE_BP,
        NOISE_ALIPAY,
    )
}
