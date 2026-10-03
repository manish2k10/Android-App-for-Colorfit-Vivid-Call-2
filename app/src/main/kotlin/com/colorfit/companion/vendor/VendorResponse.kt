package com.colorfit.companion.vendor

/**
 * Parsed responses from the watch, dispatched on `bytes[0]` (and
 * sometimes `bytes[1]`) of the incoming notification.
 *
 * Every variant covers one opcode in [VendorOpcodes]. Sealed so the
 * [VendorConnection] consumer can exhaustively handle them.
 *
 * Source of truth: decompiled `BluetoothLeService.java` from
 * NoiseFit Prime 1.1.20.
 */
sealed interface VendorResponse {

    val opcode: Byte

    /** Battery percentage (0-100). */
    data class Battery(val percent: Int) : VendorResponse {
        override val opcode: Byte get() = VendorOpcodes.READ_BATTERY
    }

    /** ASCII BLE firmware version string from the watch (`0xA1`). */
    data class Version(val raw: String) : VendorResponse {
        override val opcode: Byte get() = VendorOpcodes.READ_VERSION
    }

    /** ASCII DSP firmware version (`0xA1 0x01`). */
    data class DspVersion(val raw: String) : VendorResponse {
        override val opcode: Byte get() = VendorOpcodes.READ_VERSION
    }

    /**
     * Live step sample. The watch reports steps **per hour**, not as a
     * running daily total: one packet carries the step count for
     * [hour] on [dateKey]. The daily total is the sum of every hour's
     * bucket — see `VendorConnection.stepAccumulator`.
     */
    data class StepRealtime(
        val dateKey: String,
        val hour: Int,
        val hourSteps: Int,
    ) : VendorResponse {
        override val opcode: Byte get() = 0xB1.toByte()
    }

    /**
     * Historical step record chunk — same per-hour layout as
     * [StepRealtime]. `B2 FD` marks the end of the stream.
     */
    data class StepHistorical(
        val dateKey: String,
        val hour: Int,
        val hourSteps: Int,
        val isLastChunk: Boolean,
    ) : VendorResponse {
        override val opcode: Byte get() = 0xB2.toByte()
    }

    /** Historical sleep record. */
    data class SleepHistorical(
        val timestampUtcSeconds: Long,
        val durationMinutes: Int,
        val deepMinutes: Int,
        val lightMinutes: Int,
        val isLastChunk: Boolean,
    ) : VendorResponse {
        override val opcode: Byte get() = 0xB3.toByte()
    }

    /** Live heart-rate sample. */
    data class HeartRateLive(val bpm: Int) : VendorResponse {
        override val opcode: Byte get() = VendorOpcodes.RATE_TEST
    }

    /** Historical HR record (daily aggregate). */
    data class HeartRateHistorical(
        val timestampUtcSeconds: Long,
        val avgBpm: Int,
        val minBpm: Int,
        val maxBpm: Int,
    ) : VendorResponse {
        override val opcode: Byte get() = VendorOpcodes.SYNC_RATE_LEGACY
    }

    /** SpO2 measurement result. */
    data class Oxygen(val percent: Int) : VendorResponse {
        override val opcode: Byte get() = VendorOpcodes.OXYGEN_TEST
    }

    /** Blood-pressure measurement result (mmHg). [finished] marks the final reading. */
    data class BloodPressure(
        val systolic: Int,
        val diastolic: Int,
        val finished: Boolean,
    ) : VendorResponse {
        override val opcode: Byte get() = VendorOpcodes.BLOOD_PRESSURE_TEST
    }

    /** Push-display bitfield. Two 32-bit ints: which apps the watch accepts. */
    data class PushDisplay(
        val mask1: Int,
        val mask2: Int,
    ) : VendorResponse {
        override val opcode: Byte get() = VendorOpcodes.QUERY_PUSH_DISPLAY
    }

    /** Interface / capability bitfield from the watch. */
    data class Interface(
        val raw: ByteArray,
    ) : VendorResponse {
        override val opcode: Byte get() = VendorOpcodes.QUERY_INTERFACE
    }

    /** Auth handshake: success, captcha required, or failed. */
    sealed interface Auth : VendorResponse {
        override val opcode: Byte get() = VendorOpcodes.PASSWORD_QUERY
        data object Success : Auth
        data class CaptchaRequired(val displayCode: String) : Auth
        /** `33 04 03/04` — the watch is showing a pairing prompt to accept. */
        data object ConfirmOnWatch : Auth
        data object Failed : Auth
    }

    /**
     * Band-algorithm sleep sync (`31 01` request). The watch answers one
     * `31 01` + date per day, each followed by `32` raw-data chunks, and
     * closes the stream with `31 02`.
     */
    data class SleepDayStart(val dateKey: String) : VendorResponse {
        override val opcode: Byte get() = VendorOpcodes.SYNC_SLEEP_BAND
    }

    /** One `32` chunk of the current day's sleep sections (opcode stripped). */
    data class SleepDayData(val data: ByteArray) : VendorResponse {
        override val opcode: Byte get() = VendorOpcodes.SLEEP_BAND_DATA
    }

    data object SleepSyncEnd : VendorResponse {
        override val opcode: Byte get() = VendorOpcodes.SYNC_SLEEP_BAND
    }

    /**
     * One 24-hour-HR frame (`F7`): twelve 10-minute samples covering the two
     * hours that end at [windowEndUtcSeconds]. [bpms] holds only the valid
     * ones (the watch pads with `FF`).
     */
    data class HeartRate24h(
        val windowEndUtcSeconds: Long,
        val bpms: List<Int>,
    ) : VendorResponse {
        override val opcode: Byte get() = VendorOpcodes.RATE_24H
    }

    /** `F7 FD` — 24-hour HR sync finished. */
    data object HeartRate24hEnd : VendorResponse {
        override val opcode: Byte get() = VendorOpcodes.RATE_24H
    }

    /**
     * The watch's acknowledgement of one pushed-text chunk: `C5 <index>`,
     * or `C5 FD <type> <len>` ([index] == [END]) once the message is complete.
     */
    data class TextPushAck(val index: Int) : VendorResponse {
        override val opcode: Byte get() = VendorOpcodes.PUSH_SMS_TEXT
        companion object { const val END = 0xFD }
    }

    /** A bare echo/ack of a command we sent (time, profile, vibrate, weather…). */
    data class Ack(override val opcode: Byte) : VendorResponse

    /** Generic end-of-stream marker (`0xFD`). */
    data object EndOfStream : VendorResponse {
        override val opcode: Byte get() = VendorOpcodes.SUB_DEFAULT_RESET
    }

    /** Anything we haven't classified yet. */
    data class Unknown(val raw: ByteArray) : VendorResponse {
        override val opcode: Byte get() = raw.getOrNull(0) ?: 0
    }
}

/**
 * Parses bytes from the watch's vendor read characteristic into a
 * [VendorResponse]. Best-effort — unknown frames yield [VendorResponse.Unknown]
 * rather than throwing.
 */
object VendorResponseParser {

    fun parse(bytes: ByteArray): VendorResponse {
        if (bytes.isEmpty()) return VendorResponse.Unknown(bytes)
        val opcode = bytes[0]
        return when (opcode) {
            VendorOpcodes.READ_BATTERY -> {
                // Decompiled DataProcessing.getBleBattery():
                //     return Math.min(bArr[1] & 255, 100);
                // The percent is ALWAYS the single byte at [1], clamped to
                // 100 — longer frames carry unrelated trailing bytes. (Reading
                // [1..2] as a 16-bit LE value is what produced "334%".)
                if (bytes.size < 2) VendorResponse.Unknown(bytes)
                else VendorResponse.Battery((bytes[1].toInt() and 0xFF).coerceIn(0, 100))
            }

            VendorOpcodes.READ_VERSION -> {
                // BluetoothLeService: "A101" -> DSP version (ASCII from byte 2),
                // any other A1 frame -> BLE firmware version (ASCII from byte 1).
                // Safe to discriminate on byte[1] because a version string is
                // printable ASCII and can never begin with 0x01.
                val isDsp = bytes.size >= 2 && bytes[1] == 0x01.toByte()
                val from = if (isDsp) 2 else 1
                if (bytes.size <= from) VendorResponse.Unknown(bytes)
                else {
                    val text = String(bytes.copyOfRange(from, bytes.size), Charsets.US_ASCII)
                        .trim { it <= ' ' || it == 0.toChar() }
                    if (isDsp) VendorResponse.DspVersion(text) else VendorResponse.Version(text)
                }
            }

            0xB1.toByte() -> {
                // Decompiled DataProcessing.stepRealTimeDataOperate():
                //   getBleDate()          -> year BE at [1][2], month [3], day [4]
                //   getBleCurrentHour()   -> [5]
                //   getBleCurrentHourStep -> ((bArr[6] << 8) & 0xFF00) | (bArr[7] & 0xFF)
                // i.e. steps for THAT HOUR, big-endian — not a daily total.
                val d = parseDateHour(bytes) ?: return VendorResponse.Unknown(bytes)
                VendorResponse.StepRealtime(d.dateKey, d.hour, d.hourSteps)
            }

            0xB2.toByte() -> {
                // Same per-hour layout as 0xB1; "B2 FD" ends the stream.
                val isLast = bytes.size >= 2 && bytes[1] == 0xFD.toByte()
                if (isLast) {
                    VendorResponse.StepHistorical("", 0, 0, isLastChunk = true)
                } else {
                    val d = parseDateHour(bytes) ?: return VendorResponse.Unknown(bytes)
                    VendorResponse.StepHistorical(d.dateKey, d.hour, d.hourSteps, isLastChunk = false)
                }
            }

            0xB3.toByte() -> {
                val isLast = bytes.size >= 2 && bytes[1] == 0xFD.toByte()
                if (isLast) {
                    VendorResponse.SleepHistorical(0L, 0, 0, 0, isLastChunk = true)
                } else {
                    val ts = parseTimestamp(bytes, 1) ?: 0L
                    val totalMin = if (bytes.size >= 8) bytes[7].toInt() and 0xFF else 0
                    val deepMin = if (bytes.size >= 9) bytes[8].toInt() and 0xFF else 0
                    val lightMin = if (bytes.size >= 10) bytes[9].toInt() and 0xFF else 0
                    VendorResponse.SleepHistorical(ts, totalMin, deepMin, lightMin, isLastChunk = false)
                }
            }

            VendorOpcodes.RATE_TEST -> {
                // Decompiled DataProcessing.java confirms the format:
                //   byte[0] = 0xE5  (opcode)
                //   byte[1] = sub-opcode (0x11 = live HR, 0x00 = stop, 0xFD = done)
                //   byte[2] = 0x00  (valid flag — bArr[2] == 0 means valid HR)
                //   byte[3] = HR BPM
                // Log evidence: E5 11 00 5D → HR 93
                // Only accept live HR frames where byte[2] == 0 (valid) and byte[1] == 0x11.
                val sub = if (bytes.size >= 2) bytes[1].toInt() and 0xFF else -1
                val validFlag = if (bytes.size >= 3) bytes[2].toInt() and 0xFF else -1
                val bpm = if (bytes.size >= 4 && validFlag == 0 && sub == 0x11) {
                    bytes[3].toInt() and 0xFF
                } else 0
                VendorResponse.HeartRateLive(bpm)
            }

            VendorOpcodes.SYNC_RATE_LEGACY -> {
                // [E6, year-2000?, month, day, hour, min, avg_bpm, min_bpm, max_bpm, …]
                val ts = parseTimestamp(bytes, 1) ?: 0L
                val avg = if (bytes.size >= 7) bytes[6].toInt() and 0xFF else 0
                val min = if (bytes.size >= 8) bytes[7].toInt() and 0xFF else 0
                val max = if (bytes.size >= 9) bytes[8].toInt() and 0xFF else 0
                VendorResponse.HeartRateHistorical(ts, avg, min, max)
            }

            VendorOpcodes.OXYGEN_TEST -> {
                // Decompiled BluetoothLeService.dealWithOxygen():
                //   bArr[1] == 0x00 -> measurement finished
                //        length == 2 -> just "test closed", no value
                //        else        -> value = bArr[3], invalid if bArr[2] != 0
                //   bArr[1] == 0x11 -> test opened (no value yet)
                // Same shape as heart rate: [34, sub, validFlag, value].
                // (Reading bArr[1] as the percent returns the sub-opcode — 0 or 17.)
                val sub = if (bytes.size >= 2) bytes[1].toInt() and 0xFF else -1
                if (sub == 0x00 && bytes.size >= 4) {
                    val valid = (bytes[2].toInt() and 0xFF) == 0
                    val value = bytes[3].toInt() and 0xFF
                    VendorResponse.Oxygen(if (valid) value else 0)
                } else {
                    // "Opened"/"closed" acknowledgement — carries no reading.
                    VendorResponse.Oxygen(0)
                }
            }

            VendorOpcodes.BLOOD_PRESSURE_TEST -> {
                // Decompiled DataProcessing.bloodPressureRealTimeDataOperate():
                //   [C7, finishFlag, validFlag, systolic, diastolic]
                //   finished when byte[1] == 0 (during measuring it is non-zero)
                //   valid    when byte[2] == 0
                // Short status frames (C7 FD/FF, the bare C7 11 start ack, and
                // config acks C7 02/03/04/05) carry no reading.
                val sub = if (bytes.size >= 2) bytes[1].toInt() and 0xFF else -1
                if (bytes.size >= 5) {
                    val finished = sub == 0x00
                    val valid = (bytes[2].toInt() and 0xFF) == 0
                    if (valid) {
                        VendorResponse.BloodPressure(
                            systolic = bytes[3].toInt() and 0xFF,
                            diastolic = bytes[4].toInt() and 0xFF,
                            finished = finished,
                        )
                    } else {
                        VendorResponse.BloodPressure(0, 0, finished = finished)
                    }
                } else {
                    // Start ack / done / abort marker — no values.
                    VendorResponse.BloodPressure(0, 0, finished = sub == 0x00)
                }
            }

            VendorOpcodes.QUERY_PUSH_DISPLAY -> {
                // dealWithPushMessageDisplay(): a 20-byte frame whose masks
                // are 3-byte big-endian words counted from the END —
                // mask1 = [17..19], mask2 = [14..16]. Captured:
                // DB AA 00…00 000001 FFFFFF → mask1 FFFFFF, mask2 000001.
                if (bytes.size != 20) VendorResponse.Unknown(bytes)
                else VendorResponse.PushDisplay(be24(bytes, 17), be24(bytes, 14))
            }

            VendorOpcodes.QUERY_INTERFACE -> {
                VendorResponse.Interface(bytes.copyOfRange(1, bytes.size))
            }

            VendorOpcodes.PASSWORD_QUERY, VendorOpcodes.CAPTCHA_REQUEST -> {
                when (bytes[1].toInt() and 0xFF) {
                    0x00 -> VendorResponse.Auth.Success
                    0x01 -> {
                        // Captcha — bytes after the type are the display code (BCD or ASCII)
                        val code = bytes.copyOfRange(2, bytes.size)
                            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
                            .take(6)
                        VendorResponse.Auth.CaptchaRequired(code)
                    }
                    else -> VendorResponse.Auth.Failed
                }
            }

            VendorOpcodes.ACCOUNT_ID_QUERY -> {
                // BluetoothLeService "3304": 01 = verified, 02 = id stored,
                // 03/04 = confirm the pairing on the watch, 05 = user cancelled.
                if (bytes.size < 3 || bytes[1] != 0x04.toByte()) VendorResponse.Unknown(bytes)
                else when (bytes[2].toInt() and 0xFF) {
                    0x01, 0x02 -> VendorResponse.Auth.Success
                    0x03, 0x04 -> VendorResponse.Auth.ConfirmOnWatch
                    else -> VendorResponse.Auth.Failed
                }
            }

            VendorOpcodes.SYNC_SLEEP_BAND -> {
                // dealWithBandAlgorithm(): [31, 01, yr_hi, yr_lo, month, day, count]
                // opens a day, [31, 02] ends the sync.
                when (if (bytes.size >= 2) bytes[1].toInt() and 0xFF else -1) {
                    0x02 -> VendorResponse.SleepSyncEnd
                    0x01 -> {
                        if (bytes.size < 6) return VendorResponse.Unknown(bytes)
                        val year = ((bytes[2].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF)
                        val month = bytes[4].toInt() and 0xFF
                        val day = bytes[5].toInt() and 0xFF
                        VendorResponse.SleepDayStart("%04d%02d%02d".format(year, month, day))
                    }
                    else -> VendorResponse.Unknown(bytes)
                }
            }

            VendorOpcodes.SLEEP_BAND_DATA ->
                VendorResponse.SleepDayData(bytes.copyOfRange(1, bytes.size))

            VendorOpcodes.RATE_24H -> {
                val sub = if (bytes.size >= 2) bytes[1].toInt() and 0xFF else -1
                when {
                    sub == 0xFD -> VendorResponse.HeartRate24hEnd
                    // F7 01/02 = switch ack, F7 03/04 = realtime / max-min-avg.
                    sub in 0x01..0x04 -> VendorResponse.Ack(opcode)
                    else -> parseRate24h(bytes) ?: VendorResponse.Unknown(bytes)
                }
            }

            VendorOpcodes.PUSH_SMS_TEXT ->
                if (bytes.size < 2) VendorResponse.Unknown(bytes)
                else VendorResponse.TextPushAck(bytes[1].toInt() and 0xFF)

            VendorOpcodes.SYNC_TIME, VendorOpcodes.SET_PROFILE, VendorOpcodes.PUSH_SHORT,
            VendorOpcodes.WEATHER_MULTI, VendorOpcodes.PUSH_QQ_WECHAT_TEXT,
            VendorOpcodes.HAS_CONTENT_PUSH -> VendorResponse.Ack(opcode)

            VendorOpcodes.SUB_DEFAULT_RESET -> VendorResponse.EndOfStream

            else -> VendorResponse.Unknown(bytes.copyOf(bytes.size))
        }.also {
            // Defensive: never leak through with mismatched opcode.
            if (it.opcode != opcode && it !is VendorResponse.Unknown && it !is VendorResponse.EndOfStream) {
                // silently accept — we may have a multi-opcode packet
            }
        }
    }

    private fun be24(b: ByteArray, offset: Int): Int =
        ((b[offset].toInt() and 0xFF) shl 16) or
            ((b[offset + 1].toInt() and 0xFF) shl 8) or
            (b[offset + 2].toInt() and 0xFF)

    /** Decoded per-hour step bucket from a 0xB1 / 0xB2 packet. */
    data class DateHourSteps(val dateKey: String, val hour: Int, val hourSteps: Int)

    /**
     * Decode the date + hour + hourly step count shared by the realtime
     * (`0xB1`) and historical (`0xB2`) step packets.
     *
     * Decompiled `DataProcessing.getBleDate/getBleCurrentHour/getBleCurrentHourStep`:
     * ```
     * [0] opcode
     * [1] year hi   ┐ full year, BIG-endian (e.g. 0x07E8 = 2024) — not year-2000
     * [2] year lo   ┘
     * [3] month (1-based)
     * [4] day
     * [5] hour (0-23)
     * [6] hour steps hi
     * [7] hour steps lo
     * ```
     * The original skips records whose date string starts with "000"
     * (an unset clock), so we reject implausible years the same way.
     */
    private fun parseDateHour(bytes: ByteArray): DateHourSteps? {
        if (bytes.size < 8) return null
        val year = ((bytes[1].toInt() and 0xFF) shl 8) or (bytes[2].toInt() and 0xFF)
        val month = bytes[3].toInt() and 0xFF
        val day = bytes[4].toInt() and 0xFF
        val hour = bytes[5].toInt() and 0xFF
        if (year < 2000 || year > 2200) return null
        if (month !in 1..12 || day !in 1..31 || hour !in 0..23) return null
        val steps = ((bytes[6].toInt() and 0xFF) shl 8) or (bytes[7].toInt() and 0xFF)
        val dateKey = "%04d%02d%02d".format(year, month, day)
        return DateHourSteps(dateKey, hour, steps)
    }

    /**
     * Decompiled `DataProcessing.rate24HourOffLineDataOperate()`:
     * ```
     * [0]      F7
     * [1..4]   year hi, year lo, month, day
     * [5]      hour the window ENDS at (0 = midnight closing the previous day)
     * [6..17]  twelve samples, 10 minutes apart; sample i is at
     *          hour*60 - (17 - i)*10 minutes. Valid when 40 < bpm < 200.
     * ```
     */
    private fun parseRate24h(bytes: ByteArray): VendorResponse.HeartRate24h? {
        if (bytes.size < 7) return null
        val year = ((bytes[1].toInt() and 0xFF) shl 8) or (bytes[2].toInt() and 0xFF)
        val month = bytes[3].toInt() and 0xFF
        val day = bytes[4].toInt() and 0xFF
        val hour = bytes[5].toInt() and 0xFF
        if (year < 2000 || year > 2200) return null
        if (month !in 1..12 || day !in 1..31 || hour !in 0..23) return null
        val bpms = (6 until bytes.size)
            .map { bytes[it].toInt() and 0xFF }
            .filter { it in 41..199 }
        // Hour 0 means "24:00 of the previous day", which is the same instant
        // as 00:00 of this date — so no special case is needed.
        val cal = java.util.Calendar.getInstance().apply {
            clear()
            set(year, month - 1, day, hour, 0, 0)
        }
        return VendorResponse.HeartRate24h(cal.timeInMillis / 1000L, bpms)
    }

    /**
     * Parse the watch's compact timestamp: 6 bytes starting at [offset]:
     *   year_hi, year_lo (BIG-endian, full year), month, day, hour, minute.
     * The watch doesn't encode seconds or timezone; we treat it as local.
     */
    private fun parseTimestamp(bytes: ByteArray, offset: Int): Long? {
        if (bytes.size < offset + 6) return null
        val year = ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
        if (year < 2000 || year > 2200) return null
        val month = (bytes[offset + 2].toInt() and 0xFF).coerceIn(1, 12)
        val day = (bytes[offset + 3].toInt() and 0xFF).coerceIn(1, 31)
        val hour = (bytes[offset + 4].toInt() and 0xFF).coerceIn(0, 23)
        val minute = (bytes[offset + 5].toInt() and 0xFF).coerceIn(0, 59)
        val cal = java.util.Calendar.getInstance().apply {
            clear()
            set(year, month - 1, day, hour, minute, 0)
        }
        return cal.timeInMillis / 1000L
    }
}
