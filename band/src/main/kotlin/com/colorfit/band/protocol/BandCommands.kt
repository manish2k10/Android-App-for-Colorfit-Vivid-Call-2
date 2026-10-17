package com.colorfit.band.protocol

import java.nio.charset.Charset
import java.util.TimeZone

/**
 * Command builders (phone → band). Each mirrors a builder in NoiseFit Track's
 * `o8` package; the byte layouts were checked against the captured session
 * (docs/band-protocol.md).
 */
object BandCommands {

    const val SET_PROFILE = 0x12
    const val SET_AUTO_HEART_RATE = 0x1F
    const val QUERY_GOAL = 0x26
    const val QUERY_AUTO_HEART_RATE = 0x2F
    const val SET_TIME = 0x31
    const val SLEEP_TONIGHT = 0x32
    const val HISTORY = 0x33
    const val HEART_RATE_SERIES = 0x35
    const val MESSAGE = 0x41
    const val FORECAST = 0x42
    const val WEATHER_TODAY_LEGACY = 0x43
    const val CITY = 0x45
    const val FIND_BAND = 0x61
    const val BLOOD_PRESSURE = 0x69
    const val BLOOD_OXYGEN = 0x6B
    const val HEART_RATE = 0x6D
    const val HEART_RATE_LOG = 0xAB
    const val LOCATION = 0xB3
    const val WEATHER_TODAY = 0xB5
    const val SETTINGS = 0xBB

    /** `0x33` day argument: steps of yesterday / the day before. */
    const val HISTORY_STEPS_YESTERDAY = 1
    const val HISTORY_STEPS_DAY_BEFORE = 2

    /** `0x33` day argument: sleep of last night / the night before. */
    const val HISTORY_SLEEP_YESTERDAY = 3
    const val HISTORY_SLEEP_DAY_BEFORE = 4

    /** Auto heart-rate interval values (× 5 minutes) the original offers. */
    val AUTO_HEART_RATE_STEPS = listOf(0, 1, 2, 4, 6)

    private const val GMT8_MS = 8 * 3_600_000L
    private val UTF_16BE: Charset = Charsets.UTF_16BE

    /**
     * `b1.e`: the local wall-clock time re-read as if it were GMT+8, as
     * big-endian epoch seconds, then `08`. 00:04:39 IST went out as
     * `6A D3 9C 97 08`.
     */
    fun setTime(nowMillis: Long = System.currentTimeMillis(), zone: TimeZone = TimeZone.getDefault()): ByteArray {
        val seconds = (nowMillis + zone.getOffset(nowMillis) - GMT8_MS) / 1000
        return BandFrame.build(SET_TIME, be32(seconds) + byteArrayOf(8))
    }

    /** `b1.f`: `BB 07 00` + the raw UTC offset in seconds, little-endian. */
    fun timeZone(zone: TimeZone = TimeZone.getDefault()): ByteArray =
        BandFrame.build(SETTINGS, byteArrayOf(7, 0) + le32(zone.rawOffset / 1000L))

    fun queryGoal(): ByteArray = BandFrame.build(QUERY_GOAL)

    /** `o0.d`: auto heart-rate every `steps × 5` minutes; 0 turns it off. */
    fun setAutoHeartRate(steps: Int): ByteArray =
        BandFrame.build(SET_AUTO_HEART_RATE, byteArrayOf(steps.coerceAtLeast(0).toByte()))

    fun queryAutoHeartRate(): ByteArray = BandFrame.build(QUERY_AUTO_HEART_RATE)

    fun sleepTonight(): ByteArray = BandFrame.build(SLEEP_TONIGHT)

    /** `0x33 [day]` — see the HISTORY_* constants. */
    fun history(day: Int): ByteArray = BandFrame.build(HISTORY, byteArrayOf(day.toByte()))

    /** `o0.k`: one of the four 72-sample packets of a day (0–3 today, 4–7 yesterday). */
    fun heartRateSeries(index: Int): ByteArray = BandFrame.build(HEART_RATE_SERIES, byteArrayOf(index.toByte()))

    fun heartRateLog(): ByteArray = BandFrame.build(HEART_RATE_LOG, byteArrayOf(0))

    fun findBand(): ByteArray = BandFrame.build(FIND_BAND)

    /** `o0.h`: one heart-rate measurement. */
    fun measureHeartRate(start: Boolean): ByteArray =
        BandFrame.build(HEART_RATE, byteArrayOf(if (start) 0 else -1))

    /** `y.e` / `y.f`. */
    fun measureBloodPressure(start: Boolean): ByteArray =
        BandFrame.build(BLOOD_PRESSURE, if (start) byteArrayOf(0, 0, 0) else byteArrayOf(-1, -1, -1))

    /** `v.c`. */
    fun measureBloodOxygen(start: Boolean): ByteArray =
        BandFrame.build(BLOOD_OXYGEN, byteArrayOf(if (start) 0 else -1))

    /** `x8/a.c`: the type byte, then the already-prepared UTF-8 text. */
    fun message(typeByte: Int, text: ByteArray): ByteArray =
        BandFrame.build(MESSAGE, byteArrayOf(typeByte.toByte()) + text)

    /**
     * `q1.f`: `[0, id, temp]`, four spaces of UTF-16BE (the festival/lunar
     * slot, unused outside China), then up to four characters of the city in
     * UTF-16BE. Sent before the newer [weatherToday], as the original does.
     */
    fun weatherTodayLegacy(weatherId: Int, tempC: Int, city: String): ByteArray {
        val padding = "    ".toByteArray(UTF_16BE)
        val place = encodeWithin(city.removeSuffix("市"), 8, UTF_16BE)
        return BandFrame.build(
            WEATHER_TODAY_LEGACY,
            byteArrayOf(0, weatherId.toByte(), tempC.toByte()) + padding + place,
        )
    }

    /**
     * `q1.c`: `[0, id, temp, pm2.5 lo, pm2.5 hi, sunrise h, m, sunset h, m]`
     * + up to 25 bytes of the city in UTF-8.
     */
    fun weatherToday(
        weatherId: Int,
        tempC: Int,
        city: String,
        sunriseMinutes: Int?,
        sunsetMinutes: Int?,
        pm25: Int = 0,
    ): ByteArray {
        val head = byteArrayOf(
            0,
            weatherId.toByte(),
            tempC.toByte(),
            (pm25 and 0xFF).toByte(),
            ((pm25 shr 8) and 0xFF).toByte(),
            ((sunriseMinutes ?: 0) / 60).toByte(),
            ((sunriseMinutes ?: 0) % 60).toByte(),
            ((sunsetMinutes ?: 0) / 60).toByte(),
            ((sunsetMinutes ?: 0) % 60).toByte(),
        )
        return BandFrame.build(WEATHER_TODAY, head + encodeWithin(city.removeSuffix("市"), 25, Charsets.UTF_8))
    }

    /** `a1`: the city in UTF-8, at most 23 bytes. */
    fun city(name: String): ByteArray = BandFrame.build(CITY, encodeWithin(name, 23, Charsets.UTF_8))

    /** A forecast day for [forecast]. */
    data class Day(val weatherId: Int, val maxC: Int, val minC: Int)

    /**
     * `q1.b` as NoiseFit Track calls it: seven `[id, max, min]` triples (the
     * SDK names them low/high, but the app passes max first — `e4/a`).
     */
    fun forecast(days: List<Day>): ByteArray {
        val out = ByteArray(21)
        days.take(7).forEachIndexed { i, d ->
            out[i * 3] = d.weatherId.toByte()
            out[i * 3 + 1] = d.maxC.toByte()
            out[i * 3 + 2] = d.minC.toByte()
        }
        return BandFrame.build(FORECAST, out)
    }

    /** `f0.b`: `B3 04` + latitude and longitude × 10⁶, little-endian. */
    fun location(latitude: Double, longitude: Double): ByteArray =
        BandFrame.build(
            LOCATION,
            byteArrayOf(4) + le32((latitude * 1_000_000.0).toLong()) + le32((longitude * 1_000_000.0).toLong()),
        )

    /**
     * `ca.d.h`: at most [maxBytes] characters, then trimmed until the encoding
     * fits in [maxBytes].
     */
    private fun encodeWithin(text: String, maxBytes: Int, charset: Charset): ByteArray {
        var s = text.take(maxBytes)
        while (s.isNotEmpty() && s.toByteArray(charset).size > maxBytes) s = s.dropLast(1)
        return s.toByteArray(charset)
    }

    private fun be32(v: Long): ByteArray = byteArrayOf(
        (v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte(),
    )

    private fun le32(v: Long): ByteArray = byteArrayOf(
        v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte(),
    )
}
