package com.colorfit.band.protocol

import java.util.TimeZone

/**
 * Decoders for what the band sends back. Each cites the NoiseFit Track class
 * it mirrors (docs/band-protocol.md). Multi-byte values are little-endian
 * unless noted.
 */
object BandParsers {

    /** Today's or a past day's activity totals. */
    data class Steps(val steps: Int, val distanceMeters: Int, val calories: Int)

    /** `a9/s.b`: three little-endian 24-bit fields — steps, distance, calories. */
    fun steps(bytes: ByteArray): Steps? {
        if (bytes.size < 9 || bytes.size % 3 != 0) return null
        return Steps(le24(bytes, 0), le24(bytes, 3), le24(bytes, 6))
    }

    /** One stretch of sleep in a single state. Minutes count from the first record's midnight. */
    data class SleepSegment(val state: Int, val startMinute: Int, val endMinute: Int) {
        val minutes: Int get() = endMinute - startMinute
    }

    /** A night of sleep. States: 0 awake, 1 light, 2 deep, 3 REM. */
    data class SleepNight(val segments: List<SleepSegment>) {
        private fun total(state: Int) = segments.filter { it.state == state }.sumOf { it.minutes }
        val deepMinutes: Int get() = total(STATE_DEEP)
        val lightMinutes: Int get() = total(STATE_LIGHT)
        val remMinutes: Int get() = total(STATE_REM)
        val awakeMinutes: Int get() = total(STATE_AWAKE)
        val asleepMinutes: Int get() = deepMinutes + lightMinutes + remMinutes
        val startMinute: Int? get() = segments.firstOrNull()?.startMinute
        val endMinute: Int? get() = segments.lastOrNull()?.endMinute
    }

    const val STATE_AWAKE = 0
    const val STATE_LIGHT = 1
    const val STATE_DEEP = 2
    const val STATE_REM = 3

    /**
     * `a9/q.c`: 3-byte records `[state, hour, minute]`, each state lasting
     * until the next record. For tonight ([untilNowMinute] set) a last state
     * other than awake runs until now, as the original does.
     */
    fun sleep(records: ByteArray, untilNowMinute: Int? = null): SleepNight? {
        if (records.isEmpty() || records.size % 3 != 0) return null
        val segments = mutableListOf<SleepSegment>()
        var prevState = -1
        var prevHour = 0
        var prevMinute = 0
        var prevAbs = 0 // minutes since the first record's midnight
        for (i in records.indices step 3) {
            val state = records[i].toInt()
            val hour = records[i + 1].toInt() % 24
            val minute = records[i + 2].toInt()
            val elapsed = forwardMinutes(prevHour, prevMinute, hour, minute)
            // `q.c` skips a record that would run backwards.
            if (elapsed < 0) continue
            val abs = prevAbs + elapsed
            if (prevState >= 0) segments += SleepSegment(prevState, prevAbs, abs)
            if (i == records.size - 3 && untilNowMinute != null && state != STATE_AWAKE) {
                val toNow = forwardMinutes(hour, minute, untilNowMinute / 60, untilNowMinute % 60)
                if (toNow > 0) segments += SleepSegment(state, abs, abs + toNow)
            }
            prevState = state
            prevHour = hour
            prevMinute = minute
            prevAbs = abs
        }
        return SleepNight(segments).takeIf { it.segments.isNotEmpty() }
    }

    /** `q.a`: minutes from h1:m1 to h2:m2, wrapping once past midnight when h2 < h1. */
    private fun forwardMinutes(h1: Int, m1: Int, h2: Int, m2: Int): Int {
        val end = if (h1 > h2) h2 + 24 else h2
        return (end - h1) * 60 + m2 - m1
    }

    /** One of the four 72-sample packets of a day's heart-rate series. */
    class HeartRatePacket(val index: Int, val bpm: List<Int>)

    /**
     * `a9/j0.w` + `o`: `[index]` then one byte per 5 minutes. Anything outside
     * 40–200 means no reading and becomes 0.
     */
    fun heartRateSeries(payload: ByteArray): HeartRatePacket? {
        if (payload.isEmpty()) return null
        val samples = (1 until payload.size).map { i ->
            (payload[i].toInt() and 0xFF).takeIf { it in 40..200 } ?: 0
        }
        return HeartRatePacket(payload[0].toInt() and 0xFF, samples)
    }

    /** A heart-rate reading from the band's log, with its real time. */
    data class LoggedHeartRate(val epochMillis: Long, val bpm: Int)

    /**
     * `a9/j0.t`: after a 2-byte header, 5-byte records `[bpm, LE32 seconds]`.
     * The seconds are local time written as if it were GMT+8 (`b1.b`).
     */
    fun heartRateLog(payload: ByteArray, zone: TimeZone = TimeZone.getDefault()): List<LoggedHeartRate> {
        val out = mutableListOf<LoggedHeartRate>()
        var i = 2
        while (i + 4 < payload.size) {
            val bpm = payload[i].toInt() and 0xFF
            val seconds = le32(payload, i + 1)
            if (bpm in 40..200 && seconds > 0) out += LoggedHeartRate(fromBandTime(seconds, zone), bpm)
            i += 5
        }
        return out
    }

    /** `a9/n.c`: `[0, systolic, diastolic]`. */
    fun bloodPressure(payload: ByteArray): Pair<Int, Int>? {
        if (payload.size != 3) return null
        val sys = payload[1].toInt() and 0xFF
        val dia = payload[2].toInt() and 0xFF
        return (sys to dia).takeIf { sys > 0 && dia > 0 }
    }

    /** `i9/a.b3` / `a9/j0.v`: a single unsigned byte (SpO₂ % or bpm). */
    fun singleValue(payload: ByteArray): Int? =
        payload.firstOrNull()?.let { it.toInt() and 0xFF }?.takeIf { it in 1..254 }

    /** `o8/z.a`: the step goal (`58 1B 00 00` = 7000). */
    fun goal(payload: ByteArray): Int? = when {
        payload.size == 2 -> (payload[0].toInt() and 0xFF) or ((payload[1].toInt() and 0xFF) shl 8)
        payload.size == 4 && (payload[2].toInt() == 0 || payload[3].toInt() == 0) -> le24(payload, 0)
        payload.size >= 4 -> ((payload[1].toInt() and 0xFF) shl 16) or
            ((payload[2].toInt() and 0xFF) shl 8) or (payload[3].toInt() and 0xFF)
        else -> null
    }

    /** Standard Heart Rate Measurement (`2A37`): flags, then uint8 or uint16 bpm. */
    fun standardHeartRate(bytes: ByteArray): Int? {
        if (bytes.size < 2) return null
        val wide = bytes[0].toInt() and 0x01 != 0
        val bpm = if (wide && bytes.size >= 3) {
            (bytes[1].toInt() and 0xFF) or ((bytes[2].toInt() and 0xFF) shl 8)
        } else {
            bytes[1].toInt() and 0xFF
        }
        return bpm.takeIf { it in 30..240 }
    }

    /** `b1.b`: band seconds (local time as GMT+8) back to a real instant. */
    fun fromBandTime(seconds: Long, zone: TimeZone = TimeZone.getDefault()): Long {
        val asIfGmt8 = seconds * 1000
        val shifted = asIfGmt8 - (zone.rawOffset - 8 * 3_600_000L)
        return if (zone.inDaylightTime(java.util.Date(shifted))) shifted - zone.dstSavings else shifted
    }

    private fun le24(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or ((b[at + 2].toInt() and 0xFF) shl 16)

    private fun le32(b: ByteArray, at: Int): Long =
        (b[at].toLong() and 0xFF) or ((b[at + 1].toLong() and 0xFF) shl 8) or
            ((b[at + 2].toLong() and 0xFF) shl 16) or ((b[at + 3].toLong() and 0xFF) shl 24)
}
