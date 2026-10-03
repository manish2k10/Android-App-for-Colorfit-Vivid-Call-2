package com.colorfit.companion.vendor

/**
 * The watch's feature bitfield ("function list"), which decides how several
 * commands must be encoded.
 *
 * ### Where it comes from
 *
 * Not from a command response — the SDK **reads the vendor write
 * characteristics** during connection bring-up
 * (`BluetoothLeService.writeNotifyCommandIndex` cases 11/12, then
 * `onCharacteristicRead`). Each returns a 20-byte blob:
 *
 * ```
 * 0x33F1  (legacy write char, a.k.a. PASS_WORD_CHARACTERISTIC_UUID)
 *   [0..1]   word 7      (2 bytes, big-endian)
 *   [2..4]   word 6      (3 bytes, big-endian)
 *   [5..7]   word 5
 *   [8..10]  word 4
 *   [11..13] word 3
 *   [14..16] word 2
 *   [17..19] word 1
 *
 * 0x34F1  (BLE 5 write char)
 *   [0..1]   maxCommunicationLength  (max payload the watch accepts)
 *   [2..4]   word 13
 *   [5..7]   word 12
 *   [8..10]  word 11
 *   [11..13] word 10
 *   [14..16] word 9
 *   [17..19] word 8
 * ```
 *
 * Words are indexed from the **end** of the blob, which is why word 1 — the
 * one `GetFunctionList.isSupportFunction()` uses — sits at the tail.
 *
 * All flags default to "unsupported" when the watch hasn't answered, so the
 * app falls back to the conservative baseline encoding.
 */
data class VendorCapabilities(
    /** Function-list word index (1-13) → value. Missing = not reported. */
    val words: Map<Int, Int> = emptyMap(),
    /** Max payload the watch accepts, from the BLE 5 characteristic. */
    val maxCommunicationLength: Int? = null,
) {

    val isKnown: Boolean get() = words.isNotEmpty()

    /**
     * `(word & bit) == bit`, matching `GetFunctionList.isSupportFunction*`.
     *
     * Words 1-7 come from the `0x33F1` read, which Android refuses on the
     * user's phone (see `BleConnectionManager.restrictedServices`). When a
     * word is missing we fall back to the values the Vivid Call 2 reported to
     * the original app ([VIVID_CALL_2_WORDS]) rather than to "nothing
     * supported", which would pick GB2312 text and the legacy sync commands.
     */
    fun supports(word: Int, bit: Int): Boolean {
        val value = words[word] ?: VIVID_CALL_2_WORDS[word] ?: return false
        return (value and bit) == bit
    }

    /** True when words 1-7 were assumed rather than read from the watch. */
    val isAssumed: Boolean get() = (1..7).any { it !in words }

    /**
     * `isSupportFunction(4)` — the watch has the full character set, so
     * pushed text is UTF-16 (`GBUtils.string2unicode`). Without it the SDK
     * falls back to GB2312, which is a completely different byte stream.
     */
    val supportsFullCharset: Boolean get() = supports(1, FULL_CHARSET)

    /**
     * `isSupportFunction_Sixth(2048)` — the watch takes the 21-byte weather
     * packet (with humidity + UV) on the BLE 5 channel. Otherwise it expects
     * the 19-byte packet, and sending 21 makes it discard the frame.
     */
    val supportsExtendedWeather: Boolean get() = supports(6, EXTENDED_WEATHER)

    /** `isSupportFunction_Second(4096)` — height-based stride for distance. */
    val supportsHeightStride: Boolean get() = supports(2, HEIGHT_STRIDE)

    /** `isSupportFunction_Third(64)` — profile carries fractional weight. */
    val supportsFractionalWeight: Boolean get() = supports(3, FRACTIONAL_WEIGHT)

    /** `isSupportFunction_Fifth(2048)` — per-app push-display gating. */
    val supportsPushDisplayGating: Boolean get() = supports(5, PUSH_DISPLAY_GATING)

    /** `isSupportFunction(1)` — the watch wants the `0xD5` pairing password. */
    val supportsPassword: Boolean get() = supports(1, PASSWORD)

    /** `isSupportFunction_Fourth(262144)` — sleep syncs via `31 01`, not `B3 FA`. */
    val supportsBandSleep: Boolean get() = supports(4, BAND_SLEEP)

    /** `isSupportFunction_Fourth(8192)` — sync requests carry a last-sync timestamp. */
    val supportsSyncTimestamp: Boolean get() = supports(4, SYNC_TIMESTAMP)

    /** Short human-readable summary for the diagnostics UI. */
    fun summary(): String = buildString {
        append(if (supportsFullCharset) "unicode text" else "GB2312 text")
        append(if (supportsExtendedWeather) ", 21B weather" else ", 19B weather")
        maxCommunicationLength?.let { append(", max $it B") }
        if (isAssumed) append(" (assumed)")
    }

    companion object {
        val UNKNOWN = VendorCapabilities()

        /**
         * Words 1-7 as the Vivid Call 2 (firmware RH281LDCBV005877) reported
         * them to NoiseFit Prime — `08084632ED2C3947756FFFFAD921005F784BE1DC`
         * in the captured log.
         */
        private val VIVID_CALL_2_WORDS: Map<Int, Int> = mapOf(
            1 to 0x4BE1DC,
            2 to 0x005F78,
            3 to 0xFAD921,
            4 to 0x756FFF,
            5 to 0x2C3947,
            6 to 0x4632ED,
            7 to 0x0808,
        )

        private const val FULL_CHARSET = 4
        private const val EXTENDED_WEATHER = 2048
        private const val HEIGHT_STRIDE = 4096
        private const val FRACTIONAL_WEIGHT = 64
        private const val PUSH_DISPLAY_GATING = 2048
        private const val PASSWORD = 1
        private const val BAND_SLEEP = 262144
        private const val SYNC_TIMESTAMP = 8192

        /** A capability blob is exactly 20 bytes (40 hex chars in the SDK). */
        const val BLOB_SIZE = 20

        /**
         * Parse the blob read from the **legacy** write characteristic
         * (`0x33F1`) into words 1-7. Returns null if it isn't the expected
         * 20 bytes, matching the SDK's `length2 == 40` guard.
         */
        fun parseLegacyBlob(value: ByteArray): Map<Int, Int>? {
            if (value.size != BLOB_SIZE) return null
            return mapOf(
                7 to beInt(value, 0, 2),
                6 to beInt(value, 2, 3),
                5 to beInt(value, 5, 3),
                4 to beInt(value, 8, 3),
                3 to beInt(value, 11, 3),
                2 to beInt(value, 14, 3),
                1 to beInt(value, 17, 3),
            )
        }

        /**
         * Parse the blob read from the **BLE 5** write characteristic
         * (`0x34F1`) into words 8-13. The first two bytes are the watch's
         * max payload length, not a feature word — they are read separately
         * by [parseMaxCommunicationLength] and are valid even when the rest
         * of the blob isn't 20 bytes.
         */
        fun parseBle5Blob(value: ByteArray): Map<Int, Int>? {
            if (value.size != BLOB_SIZE) return null
            return mapOf(
                13 to beInt(value, 2, 3),
                12 to beInt(value, 5, 3),
                11 to beInt(value, 8, 3),
                10 to beInt(value, 11, 3),
                9 to beInt(value, 14, 3),
                8 to beInt(value, 17, 3),
            )
        }

        /** `((bArr[0] << 8) & 0xFF00) | (bArr[1] & 0xFF)` from the SDK. */
        fun parseMaxCommunicationLength(value: ByteArray): Int? {
            if (value.size < 2) return null
            return beInt(value, 0, 2).takeIf { it > 0 }
        }

        /** Big-endian unsigned integer of [length] bytes starting at [offset]. */
        private fun beInt(bytes: ByteArray, offset: Int, length: Int): Int {
            var acc = 0
            for (i in 0 until length) {
                acc = (acc shl 8) or (bytes[offset + i].toInt() and 0xFF)
            }
            return acc
        }
    }
}
