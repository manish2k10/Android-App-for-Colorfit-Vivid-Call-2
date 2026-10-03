package com.colorfit.companion.data

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Standard Bluetooth SIG GATT characteristic parsers.
 *
 * These are tiny because the spec is tiny. Phase 2 vendor parsers live in
 * [com.colorfit.companion.data.vendor] once we know the Noise protocol.
 */
object StandardParsers {

    /**
     * GATT Spec 0x2A19 — Battery Level (uint8 percent).
     *
     * The spec defines 0-100 only; 101-255 are reserved. Cheap watches
     * sometimes put something else entirely here (a raw voltage, say), so
     * anything out of range is rejected rather than shown as a percentage.
     * The vendor `0xA2` reading is the authoritative source either way.
     */
    fun parseBatteryLevel(value: ByteArray): Int? {
        if (value.isEmpty()) return null
        return (value[0].toInt() and 0xFF).takeIf { it in 0..100 }
    }

    /**
     * GATT Spec 0x2A37 — Heart Rate Measurement.
     * Format: flags (uint8), then {uint8 | uint16 LE} HR depending on flag bit 0.
     *
     *   bit 0: 0 = uint8 HR, 1 = uint16 LE HR
     *   bit 1-2: Sensor Contact Status (informational)
     *   bit 3: Energy Expended Status present (uint16 LE)
     *   bit 4: RR-intervals present (one or more uint16 LE)
     */
    data class HeartRateMeasurement(
        val bpm: Int,
        val sensorContactDetected: Boolean?,
        val energyExpendedKj: Int?,
    )

    fun parseHeartRateMeasurement(value: ByteArray): HeartRateMeasurement? {
        if (value.isEmpty()) return null
        val flags = value[0].toInt() and 0xFF
        val wide = (flags and 0x01) != 0
        val buf = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN)

        buf.get() // consume flags
        val bpm = if (wide) {
            val v = buf.short.toInt() and 0xFFFF
            if (v == 0xFFFF) return null
            v
        } else {
            val v = buf.get().toInt() and 0xFF
            if (v == 0xFF) return null
            v
        }

        val sensorContact = when ((flags shr 1) and 0x03) {
            0b10 -> false
            0b11 -> true
            else -> null
        }
        val energyExpended = if ((flags and 0x08) != 0) {
            buf.short.toInt() and 0xFFFF
        } else null

        return HeartRateMeasurement(bpm, sensorContact, energyExpended)
    }

    /**
     * GATT Spec 0x2A38 — Body Sensor Location (uint8 enum).
     *   0..6 = Other / Chest / Wrist / Finger / Hand / Ear / Foot.
     */
    fun parseBodySensorLocation(value: ByteArray): String = when (value.firstOrNull()?.toInt()?.and(0xFF)) {
        0 -> "Other"
        1 -> "Chest"
        2 -> "Wrist"
        3 -> "Finger"
        4 -> "Hand"
        5 -> "Ear"
        6 -> "Foot"
        else -> "Unknown"
    }
}
