package com.colorfit.companion.vendor

import java.util.UUID

/**
 * BLE GATT identifiers for the Noise (yc.pedometer) vendor protocol.
 *
 * All values come from the decompiled `UUIDUtils` class in NoiseFit Prime
 * 1.1.20. The watch typically exposes one or both of:
 *
 * - **Legacy channel** — service `0x55FF`, write `0x33F1`, read/notify `0x33F2`
 * - **BLE 5 channel**  — service `0x56FF`, write `0x34F1`, read/notify `0x34F2`
 *
 * Older firmwares only expose the legacy channel. Newer firmwares expose
 * both and route bigger payloads (multi-day weather, watch face push) over
 * the BLE 5 channel for the 240-byte MTU.
 *
 * The watch also exposes a secondary write on the BP service (`0xFFF0` /
 * `0xFFF6`) which is used by some long commands regardless of protocol
 * variant. We write to whichever characteristic the watch exposes.
 */
object VendorGattSpec {

    // ── Legacy (BLE 4.x) channel ──────────────────────────────────────────
    val SERVICE_LEGACY: UUID = UUID.fromString("000055ff-0000-1000-8000-00805f9b34fb")
    val WRITE_LEGACY: UUID = UUID.fromString("000033f1-0000-1000-8000-00805f9b34fb")
    val READ_LEGACY: UUID = UUID.fromString("000033f2-0000-1000-8000-00805f9b34fb")

    // ── BLE 5 channel (extended payload, MTU 240) ─────────────────────────
    val SERVICE_BLE5: UUID = UUID.fromString("000056ff-0000-1000-8000-00805f9b34fb")
    val WRITE_BLE5: UUID = UUID.fromString("000034f1-0000-1000-8000-00805f9b34fb")
    val READ_BLE5: UUID = UUID.fromString("000034f2-0000-1000-8000-00805f9b34fb")

    // ── Secondary "BP" channel — used for some long payloads ──────────────
    val SERVICE_BP: UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
    val WRITE_BP: UUID = UUID.fromString("0000fff6-0000-1000-8000-00805f9b34fb")

    // ── OTA firmware update (Dialog Semi SPOTA / Realtek) ─────────────────
    val SERVICE_OTA_SPOTA: UUID = UUID.fromString("0000fef5-0000-1000-8000-00805f9b34fb")
    val SERVICE_OTA_RK: UUID = UUID.fromString("0000d0ff-3c17-d293-8e48-14fe2e4da212")
    val CHAR_OTA_PATCH: UUID = UUID.fromString("0000ffd3-0000-1000-8000-00805f9b34fb")

    // ── Alipay (irrelevant for Colorfit Vivid Call 2 but exposed by SDK) ─
    val SERVICE_ALIPAY: UUID = UUID.fromString("000057ff-0000-1000-8000-00805f9b34fb")
    val WRITE_ALIPAY: UUID = UUID.fromString("000035f1-0000-1000-8000-00805f9b34fb")
    val READ_ALIPAY: UUID = UUID.fromString("000035f2-0000-1000-8000-00805f9b34fb")

    // ── Standard SIG services (still useful, kept in vendor file for completeness)
    val CLIENT_CHARACTERISTIC_CONFIG: UUID =
        UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** Vendor read/notify characteristics we will subscribe to. */
    val candidateReadCharacteristics: List<UUID> = listOf(
        READ_BLE5,
        READ_LEGACY,
    )
}
