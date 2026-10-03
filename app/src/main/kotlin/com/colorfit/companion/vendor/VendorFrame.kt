package com.colorfit.companion.vendor

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset

/**
 * Pure packet builders for the Noise (yc.pedometer) vendor protocol.
 *
 * No BLE / Android types — these are byte-array factories so we can unit
 * test them and reuse them from any caller (BLE connection, RE tools,
 * scripts).
 *
 * All formats are decoded from the decompiled NoiseFit Prime 1.1.20
 * source (see [VendorOpcodes] for source-of-truth citations).
 */
object VendorFrame {

    /**
     * The watch expects UTF-16 **big-endian** for pushed text — see
     * `GBUtils.string2unicode()`, which emits each char as a 4-hex-digit
     * code unit (`'A'` → `"0041"`).
     */
    private val UTF_16BE: Charset = Charsets.UTF_16BE

    private val GB2312: Charset = try {
        Charset.forName("GB2312")
    } catch (_: Throwable) {
        Charsets.UTF_8
    }

    /** Text bytes per push packet (packet is `[opcode, index] + 18 bytes`). */
    private const val TEXT_CHUNK = 18

    /** Cap on total pushed text, matching the SDK's `msgPushMaxLength`. */
    const val MSG_PUSH_MAX_BYTES = 160

    // ───────── Time sync ──────────────────────────────────────────────────

    /**
     * 8-byte packet: `[A3, year_hi, year_lo, month+1, day, hour, minute, second]`.
     *
     * The watch accepts the byte as-is; year is big-endian uint16,
     * everything else is uint8. Month is 1-based (matches `Calendar.MONTH`
     * after +1).
     */
    fun timeSync(
        year: Int,
        monthOneBased: Int,
        day: Int,
        hour: Int,
        minute: Int,
        second: Int,
    ): ByteArray = byteArrayOf(
        VendorOpcodes.SYNC_TIME,
        ((year ushr 8) and 0xFF).toByte(),
        (year and 0xFF).toByte(),
        (monthOneBased and 0xFF).toByte(),
        (day and 0xFF).toByte(),
        (hour and 0xFF).toByte(),
        (minute and 0xFF).toByte(),
        (second and 0xFF).toByte(),
    )

    // ───────── Battery / version queries ──────────────────────────────────

    /** Single byte — watch responds with 0xA2 + battery percent. */
    fun batteryQuery(): ByteArray = byteArrayOf(VendorOpcodes.READ_BATTERY)

    /** Single byte — watch responds with 0xA1 + ASCII version. */
    fun versionQuery(): ByteArray = byteArrayOf(VendorOpcodes.READ_VERSION)

    /** Two bytes — asks for the DSP firmware version on supported watches. */
    fun dspVersionQuery(): ByteArray = byteArrayOf(VendorOpcodes.QUERY_DSP_VERSION, 0x01)

    /**
     * Asks the watch which protocol features it supports. Response includes
     * a capability bitfield we use to decide between legacy vs BLE 5 paths.
     */
    fun interfaceQuery(): ByteArray =
        byteArrayOf(VendorOpcodes.QUERY_INTERFACE, VendorOpcodes.SUB_QUERY)

    // ───────── Auth ───────────────────────────────────────────────────────

    /**
     * Send the pairing password. Most watches ship with "1234" hardcoded.
     * The byte format is `ASCII hex of each digit` packed into 8 bytes —
     * `password.toCharArray().map { it.code.toByte() }` truncated/padded
     * to 8 bytes.
     */
    fun passwordAuth(code: String): ByteArray {
        val out = ByteArray(9)
        out[0] = VendorOpcodes.PASSWORD_QUERY
        val bytes = code.toByteArray(Charsets.US_ASCII)
        val take = minOf(bytes.size, 8)
        System.arraycopy(bytes, 0, out, 1, take)
        // remaining bytes already 0x00
        return out
    }

    /**
     * Request a captcha from the watch. The watch displays a 4-6 digit code
     * which the user must type into the app to complete pairing.
     */
    fun captchaRequest(): ByteArray =
        byteArrayOf(VendorOpcodes.CAPTCHA_REQUEST, 0x02)

    /**
     * `sendAccountIdSuperCmd()` — `[33, 01]`. The handshake opener on watches
     * that bind by account id instead of a password
     * (`isSupportFunction_Fifth(4)`); an already-bound watch answers
     * `33 04 01`.
     */
    fun accountIdQuery(): ByteArray =
        byteArrayOf(VendorOpcodes.ACCOUNT_ID_QUERY, 0x01)

    // ───────── User profile sync ──────────────────────────────────────────

    /**
     * 19-byte profile sync (`0xA9`):
     *   [0] 0xA9
     *   [1] 0x00
     *   [2] stride length (cm × 0.418 — heuristic from Noise SDK)
     *   [3] fractional weight (kg × 100, low byte)
     *   [4] integer weight (kg)
     *   [5] screen-off timeout (s)
     *   [6..9] step target, big-endian uint32
     *   [10] raise-to-wake enabled
     *   [11] high-HR alert enabled (or -1 if disabled)
     *   [12] HR-assisted sleep
     *   [13] age
     *   [14] gender (1 = male, 2 = female)
     *   [15] reserved
     *   [16] language (1 = CN+EN, 2 = others)
     *   [17] temperature unit (1 = C, 2 = F)
     *   [18] low-HR alert enabled (or 0)
     */
    fun userProfile(
        heightCm: Int,
        weightKg: Float,
        age: Int,
        isMale: Boolean,
        screenOffSeconds: Int = 10,
        stepGoal: Int = 8000,
        raiseToWake: Boolean = true,
        highHrAlert: Boolean = false,
        highHrValue: Int = 0,
        lowHrAlert: Boolean = false,
        lowHrValue: Int = 0,
        hrAssistedSleep: Boolean = false,
        temperatureCelsius: Boolean = true,
    ): ByteArray {
        val stride = (heightCm * 0.418f).toInt()
        val weightInt = weightKg.toInt()
        val weightFrac = ((weightKg - weightInt) * 100f).toInt()
        return ByteBuffer.allocate(19).order(ByteOrder.BIG_ENDIAN).apply {
            put(VendorOpcodes.SET_PROFILE)
            put(0x00)
            put((stride and 0xFF).toByte())
            put((weightFrac and 0xFF).toByte())
            put((weightInt and 0xFF).toByte())
            put((screenOffSeconds and 0xFF).toByte())
            // step target — big-endian uint32
            putInt(stepGoal)
            put(if (raiseToWake) 1 else 0)
            put(if (highHrAlert) (highHrValue and 0xFF).toByte() else 0xFF.toByte())
            put(if (hrAssistedSleep) 1 else 0)
            put((age and 0xFF).toByte())
            put(if (isMale) 1 else 2)
            put(0x00)
            put(0x02)   // language: 2 = non-CN/EN firmware
            put(if (temperatureCelsius) 1 else 2)
            put(if (lowHrAlert) (lowHrValue and 0xFF).toByte() else 0)
        }.array()
    }

    // ───────── Live measurement commands ──────────────────────────────────

    fun startHrTest(): ByteArray =
        byteArrayOf(VendorOpcodes.RATE_TEST, 0x11)

    fun stopHrTest(): ByteArray =
        byteArrayOf(VendorOpcodes.RATE_TEST, 0x00)

    /**
     * Alternate-firmware HR start (`0xD6 0x02` — "open rate mode"). Some
     * firmware builds ignore `0xE5 0x11` and only respond to this one.
     * Cheap to send both — the watch will silently drop whichever one
     * it doesn't understand.
     */
    fun startHrModeOpen(): ByteArray =
        byteArrayOf(VendorOpcodes.RATE_MODE_OPEN, 0x02)

    /**
     * All known HR start commands.
     *
     * **Do not send these as a batch.** `0xE5 0x11` is the actual
     * "start continuous HR test" command
     * (`WriteCommandToBLE.sendRateTestCommand(2)`), while `0xD6 0x02` is a
     * *different* feature — `sendKeyOpenDynamicOrStaticRate(2)`, which
     * switches the sensor into dynamic/static mode and cancels the test
     * that `0xE5 0x11` just started. Firing both back-to-back is why the
     * Start-HR button did nothing.
     *
     * Kept only for manual protocol probing; normal callers use
     * [startHrTest].
     */
    val hrStartVariants: List<ByteArray> = listOf(
        startHrTest(),       // 0xE5 0x11 — the real start command
        startHrModeOpen(),   // 0xD6 0x02 — separate mode switch, NOT a start
    )

    fun startSpo2Test(): ByteArray =
        byteArrayOf(VendorOpcodes.OXYGEN_TEST, 0x11)

    fun stopSpo2Test(): ByteArray =
        byteArrayOf(VendorOpcodes.OXYGEN_TEST, 0x00)

    /**
     * Start a blood-pressure measurement.
     *
     * `sendBloodPressureTestCommand(1)` in the SDK is `0xC7 0x11` — the same
     * "start test" sub-opcode as HR (`0xE5 0x11`) and SpO2 (`0x34 0x11`), NOT
     * `0x01`. The watch inflates the cuff-less PPG estimate and streams back
     * `0xC7` frames until it settles.
     */
    fun startBpTest(): ByteArray =
        byteArrayOf(VendorOpcodes.BLOOD_PRESSURE_TEST, 0x11)

    fun stopBpTest(): ByteArray =
        byteArrayOf(VendorOpcodes.BLOOD_PRESSURE_TEST, 0x00)

    // ───────── Historical data sync ───────────────────────────────────────

    fun syncAllSteps(): ByteArray =
        byteArrayOf(VendorOpcodes.SYNC_STEPS_LEGACY, VendorOpcodes.SUB_FULL_SYNC)

    /**
     * `syncAllSleepData()`: `[31, 01]` when the watch runs the band sleep
     * algorithm, else the legacy `[B3, FA]`.
     */
    fun syncAllSleep(bandAlgorithm: Boolean = false): ByteArray =
        if (bandAlgorithm) byteArrayOf(VendorOpcodes.SYNC_SLEEP_BAND, 0x01)
        else byteArrayOf(VendorOpcodes.SYNC_SLEEP_LEGACY, VendorOpcodes.SUB_FULL_SYNC)

    fun syncAllHr(): ByteArray =
        byteArrayOf(VendorOpcodes.SYNC_RATE_LEGACY, VendorOpcodes.SUB_FULL_SYNC)

    /**
     * `sync24HourRate()`: `[F7, FA]`, followed by a 6-byte last-sync timestamp
     * (`yr_hi, yr_lo, month, day, hour, minute`) when the watch supports
     * timestamped sync. All zeros = "never synced", i.e. send everything.
     */
    fun sync24HourRate(withTimestamp: Boolean): ByteArray =
        if (withTimestamp) {
            ByteArray(8).also {
                it[0] = VendorOpcodes.RATE_24H
                it[1] = VendorOpcodes.SUB_FULL_SYNC
            }
        } else {
            byteArrayOf(VendorOpcodes.RATE_24H, VendorOpcodes.SUB_FULL_SYNC)
        }

    // ───────── Watch alerts ───────────────────────────────────────────────

    fun findBand(vibrationPattern: Int = 1): ByteArray = byteArrayOf(
        VendorOpcodes.PUSH_SHORT,
        0x00, 0x00, 0x00,
        0x01,
        (vibrationPattern and 0xFF).toByte(),
        VendorOpcodes.FIND_BAND_SUBTYPE,
        0x01,
    )

    fun stopVibration(): ByteArray = byteArrayOf(
        VendorOpcodes.PUSH_SHORT,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    )

    fun offHook(): ByteArray = byteArrayOf(VendorOpcodes.OFF_HOOK, 0x04)

    fun openShakeMode(): ByteArray =
        byteArrayOf(VendorOpcodes.OPEN_SHAKE_MODE, 0x01)

    fun closeShakeMode(): ByteArray =
        byteArrayOf(VendorOpcodes.CLOSE_SHAKE_MODE, 0x03)

    // ───────── Notification push (short vibration) ───────────────────────

    /**
     * Short-form notification alert — vibration only, no body on the watch.
     *
     * Decompiled `WriteCommandToBLE.sendSmsCommand(type)`:
     * ```
     * writeChara(new byte[]{-85, 0, 0, 0, 1, (byte) type, 0, 0});
     * ```
     * The alert **type** goes in byte[5] and bytes[6..7] stay zero. It is a
     * small enum ([NOTIFY_TYPE_SMS] / [NOTIFY_TYPE_APP]) — *not* the
     * app bitfield, which is only used to decide whether to push at all.
     */
    fun pushNotificationShort(type: Int = NOTIFY_TYPE_APP): ByteArray = byteArrayOf(
        VendorOpcodes.PUSH_SHORT,
        0x00, 0x00, 0x00,
        0x01,
        (type and 0xFF).toByte(),
        0x00,
        0x00,
    )

    /** `sendSmsCommand(5)` — SMS-style alert. */
    const val NOTIFY_TYPE_SMS = 5

    /** `sendQQWeChatVibrationCommand(1)` — chat/app-style alert. */
    const val NOTIFY_TYPE_APP = 1

    // ───────── Notification push (chunked text body) ─────────────────────

    /**
     * Chunked text push (`0xC5` for SMS-style, `0xC6` for QQ/WeChat).
     *
     *   [0]  opcode (0xC5 or 0xC6)
     *   [1]  chunk index, 0-based
     *   [2..] up to 18 bytes of the message
     *
     * The message itself is `[appType, textLength] + UTF-16**BE** text`
     * (`GBUtils.hexStringToBytess`), so chunk 0 on the wire reads
     * `C5 00 <type> <len> <16 text bytes>`. Captured from the original app:
     * `C5 00 18 4E 0054 0065 …` = Telegram (0x18), 78 text bytes.
     *
     * Decoded from `WriteCommandToBLE.sendTextSectionKey()` +
     * `GBUtils.string2unicode()`. Four details matter — any one of them stops
     * the watch rendering the message:
     *
     *  0. **The 2-byte `[appType, length]` header** described above.
     *  1. **UTF-16 big-endian.** `string2unicode` emits each char as a
     *     4-hex-digit code unit (`'A'` → `"0041"`), i.e. BE byte order.
     *  2. **The trailing chunk is not padded.** It is sent at its real
     *     length (`remainder + 2` bytes), never zero-filled to 20.
     *  3. **A `[opcode, 0xFD]` terminator follows the last chunk**
     *     (`sendC5FD()`). Without it the watch buffers the chunks and
     *     never displays anything.
     *
     * Returns every packet to send, terminator included, in order.
     */
    /**
     * @param unicode true when the watch reports the full character set
     *   (`isSupportFunction(4)`), in which case text goes out as UTF-16BE.
     *   Otherwise the SDK encodes it as GB2312 — a different byte stream
     *   entirely, so getting this wrong renders garbage or nothing.
     * @param appType the watch-side app id from
     *   [NotificationAppId.typeForPackage]; it picks the icon.
     */
    fun pushNotificationText(
        title: String,
        body: String,
        appType: Int = NotificationAppId.TYPE_OTHER,
        opcode: Byte = VendorOpcodes.PUSH_SMS_TEXT,
        maxBytes: Int = MSG_PUSH_MAX_BYTES,
        unicode: Boolean = true,
    ): List<ByteArray> {
        val combined = when {
            title.isBlank() -> body
            body.isBlank() -> title
            // The original listener joins them as "title:text".
            else -> "$title:$body"
        }
        val sanitized = sanitizeForWatch(combined)
        var text = if (unicode) sanitized.toByteArray(UTF_16BE) else sanitized.toByteArray(GB2312)
        if (text.size > maxBytes) text = text.copyOf(maxBytes)
        // Never split a UTF-16 code unit across the cap. (GB2312 is
        // variable-width, so this only applies to the unicode path.)
        if (unicode && text.size % 2 != 0) text = text.copyOf(text.size - 1)
        if (text.isEmpty()) return emptyList()

        val bytes = ByteArray(text.size + 2)
        bytes[0] = (appType and 0xFF).toByte()
        bytes[1] = (text.size and 0xFF).toByte()
        System.arraycopy(text, 0, bytes, 2, text.size)

        val packets = mutableListOf<ByteArray>()
        val fullChunks = bytes.size / TEXT_CHUNK
        val remainder = bytes.size % TEXT_CHUNK
        for (i in 0 until fullChunks) {
            val p = ByteArray(2 + TEXT_CHUNK)
            p[0] = opcode
            p[1] = (i and 0xFF).toByte()
            System.arraycopy(bytes, i * TEXT_CHUNK, p, 2, TEXT_CHUNK)
            packets += p
        }
        if (remainder != 0) {
            val p = ByteArray(2 + remainder)
            p[0] = opcode
            p[1] = (fullChunks and 0xFF).toByte()
            System.arraycopy(bytes, fullChunks * TEXT_CHUNK, p, 2, remainder)
            packets += p
        }
        // "Message complete — render it."
        packets += byteArrayOf(opcode, VendorOpcodes.SUB_DEFAULT_RESET)
        return packets
    }

    /**
     * Mirrors the character filter in `GBUtils.string2unicode()`: drop
     * control chars, the C1 block, U+2005, and anything outside the BMP
     * (emoji), which the watch's font can't render.
     */
    private fun sanitizeForWatch(s: String): String = buildString {
        for (ch in s) {
            val c = ch.code
            if (c < 0x20) continue
            if (c in 0x7F..0x9F) continue
            if (c == 0x2005) continue
            if (ch.isHighSurrogate() || ch.isLowSurrogate()) continue
            append(ch)
        }
    }

    // ───────── Weather ─────────────────────────────────────────────────────

    /**
     * Multi-day weather push (`0xCB`), 21 bytes. The watch renders this as
     * a 7-day forecast card on the home screen.
     *
     * Layout (best-effort decode from NoiseFit Prime source):
     *   [0]    0xCB
     *   [1]    0x01  (push, not query)
     *   [2]    today condition code (WeatherConditionCode)
     *   [3]    today pollution / UV (signed)
     *   [4]    today current temp (°C, signed)
     *   [5]    today humidity %
     *   [6..7] today max temp × 10, signed big-endian (°C × 10)
     *   [8..9] today min temp × 10, signed big-endian
     *   [10]   tomorrow condition code
     *   [11]   reserved
     *   [12]   wind direction (0=N, 1=NE, …, 7=NW)
     *   [13]   wind level (Beaufort-ish, 0-12)
     *   [14..15] tomorrow max temp × 10, signed
     *   [16..17] tomorrow min temp × 10, signed
     *   [18]   day-after condition code
     *   [19]   reserved
     *   [20]   visibility (km)
     */
    /**
     * @param extended send the 21-byte packet 1 (adds humidity + UV) instead
     *   of the 19-byte one. The original only does this when the watch
     *   advertises `isSupportFunction_Sixth(2048)` in its `0xF9` capability
     *   reply; 19 bytes is the baseline every watch accepts, so that is the
     *   default here. Sending 21 to a watch that expects 19 can make it
     *   discard the frame entirely.
     */
    fun weatherPackets(
        days: List<DailyWeather>,
        pm25: Int = 0,
        aqi: Int = 0,
        extended: Boolean = false,
    ): List<ByteArray> {
        if (days.isEmpty()) return emptyList()
        // Pad short forecasts by repeating the last known day rather than
        // sending zeros (which the watch would render as 0°).
        fun day(i: Int): DailyWeather = days[i.coerceAtMost(days.size - 1)]
        val today = days[0]
        val packets = mutableListOf<ByteArray>()

        // ── Packet 1: today (0xCB 0x01) ───────────────────────────────────
        // 21 bytes when the watch supports humidity+UV, else 19.
        val p1 = ByteArray(if (extended) 21 else 19)
        p1[0] = VendorOpcodes.WEATHER_MULTI
        p1[1] = 0x01
        p1[2] = (today.conditionCode and 0xFF).toByte()
        p1[3] = 0x00
        p1[4] = absTemp(today.currentTempC)
        p1[5] = absTemp(today.maxTempC)
        p1[6] = absTemp(today.minTempC)
        p1[7] = ((pm25 shr 8) and 0xFF).toByte()
        p1[8] = (pm25 and 0xFF).toByte()
        p1[9] = ((aqi shr 8) and 0xFF).toByte()
        p1[10] = (aqi and 0xFF).toByte()
        // [11..18] city name, GB2312 — only populated for Chinese locales
        // by the original; left zeroed here.
        if (extended) {
            p1[19] = (today.humidity.coerceIn(0, 100) and 0xFF).toByte()
            p1[20] = (today.uvIndex.coerceIn(0, 255) and 0xFF).toByte()
        }
        packets += p1

        // ── Packet 2: days 2-5 (0xCB 0x02), 4 × [code, 0, max, min] ───────
        if (days.size > 1) {
            val p2 = ByteArray(18)
            p2[0] = VendorOpcodes.WEATHER_MULTI
            p2[1] = 0x02
            for (slot in 0 until 4) {
                val d = day(slot + 1)
                val o = 2 + slot * 4
                p2[o] = (d.conditionCode and 0xFF).toByte()
                p2[o + 1] = 0x00
                p2[o + 2] = absTemp(d.maxTempC)
                p2[o + 3] = absTemp(d.minTempC)
            }
            packets += p2
        }

        // ── Packet 3: days 6-7 (0xCB 0x03), 2 × [code, 0, max, min] ───────
        if (days.size > 5) {
            val p3 = ByteArray(10)
            p3[0] = VendorOpcodes.WEATHER_MULTI
            p3[1] = 0x03
            for (slot in 0 until 2) {
                val d = day(slot + 5)
                val o = 2 + slot * 4
                p3[o] = (d.conditionCode and 0xFF).toByte()
                p3[o + 1] = 0x00
                p3[o + 2] = absTemp(d.maxTempC)
                p3[o + 3] = absTemp(d.minTempC)
            }
            packets += p3
        }
        return packets
    }

    /**
     * The original sends temperatures through `getAbsolute()`: the magnitude
     * in the low 7 bits, with bit 7 set for below zero (-5 °C → 0x85).
     */
    private fun absTemp(celsius: Int): Byte {
        val magnitude = kotlin.math.abs(celsius).coerceIn(0, 127)
        return (if (celsius < 0) magnitude or 0x80 else magnitude).toByte()
    }

    /**
     * `syncWeatherEnglishCityName()`: `[CB, FF, len] + UTF-8 name` (≤100 bytes),
     * sent on the BLE 5 channel. Gives the watch's weather screen its place name.
     */
    fun weatherCityName(name: String): ByteArray? {
        val bytes = name.trim().toByteArray(Charsets.UTF_8)
        if (bytes.isEmpty()) return null
        val n = minOf(bytes.size, 100)
        return byteArrayOf(VendorOpcodes.WEATHER_MULTI, 0xFF.toByte(), n.toByte()) + bytes.copyOf(n)
    }

    /** Legacy 14-byte current-day-only weather push (`0xCA`). */
    fun weatherCurrent(condition: Int, currentTempC: Int, maxTempC: Int, minTempC: Int): ByteArray {
        val out = ByteArray(14)
        out[0] = VendorOpcodes.WEATHER_LEGACY
        out[1] = (condition and 0xFF).toByte()
        out[2] = 0x00
        out[3] = (currentTempC.coerceIn(-128, 127)).toByte()
        out[4] = 0x00
        out[5] = ((maxTempC * 10).toInt() ushr 8).toByte()
        out[6] = ((maxTempC * 10).toInt() and 0xFF).toByte()
        out[7] = ((minTempC * 10).toInt() ushr 8).toByte()
        out[8] = ((minTempC * 10).toInt() and 0xFF).toByte()
        out[9] = 0x00
        out[10] = 0x00
        out[11] = 0x00
        out[12] = 0x00
        out[13] = 0x00
        return out
    }

    // ───────── Notification capability query ─────────────────────────────

    fun queryPushDisplay(): ByteArray =
        byteArrayOf(VendorOpcodes.QUERY_PUSH_DISPLAY, VendorOpcodes.SUB_QUERY)

    fun queryHasContentPush(): ByteArray =
        byteArrayOf(VendorOpcodes.HAS_CONTENT_PUSH)

    // ───────── Helpers ───────────────────────────────────────────────────

    /** Convert to ASCII hex string for logs. Lowercase, no separator. */
    fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}

/** Daily weather snapshot used by [VendorFrame.weatherPackets]. */
data class DailyWeather(
    val conditionCode: Int,
    val currentTempC: Int,
    val maxTempC: Int,
    val minTempC: Int,
    val humidity: Int = 0,
    val uvIndex: Int = 0,
    val windDirection: Int = 0,
    val windLevel: Int = 0,
    val visibilityKm: Int = 10,
)

/**
 * Maps WMO weather codes (used by Open-Meteo) to the watch's icon codes.
 *
 * The watch's table is 1-based. Source: `WriteCommandToBLE.weatherConditionCode()`,
 * which converts HeWeather codes: 100 → 1, 101-103 → 2, 104 → 3, 300-301 → 4,
 * 302-304 → 5, 404-406 → 6, 305/309 → 7, 306-313 → 8, 400-403/407 → 9,
 * 503/504/507/508 → 10, 500-502 → 11, 200-213 → 12, anything else → 1.
 */
object WeatherConditionCode {
    const val SUNNY = 1
    const val CLOUDY = 2
    const val OVERCAST = 3
    const val SHOWER = 4
    const val THUNDERSTORM = 5
    const val SLEET = 6
    const val LIGHT_RAIN = 7
    const val HEAVY_RAIN = 8
    const val SNOW = 9
    const val SAND_DUST = 10
    const val FOG_HAZE = 11
    const val WINDY = 12

    fun fromWmo(code: Int): Int = when (code) {
        0 -> SUNNY
        1, 2 -> CLOUDY                 // mainly clear / partly cloudy
        3 -> OVERCAST
        45, 48 -> FOG_HAZE
        51, 53, 55, 61 -> LIGHT_RAIN   // drizzle, slight rain
        63, 65 -> HEAVY_RAIN
        56, 57, 66, 67 -> SLEET        // freezing drizzle / rain
        71, 73, 75, 77, 85, 86 -> SNOW
        80, 81, 82 -> SHOWER
        95, 96, 99 -> THUNDERSTORM
        else -> SUNNY                  // the original's fallback
    }
}
