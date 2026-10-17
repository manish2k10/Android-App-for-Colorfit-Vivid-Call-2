package com.colorfit.band.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * Frames and replies taken byte for byte from NoiseFit Track's session with
 * the band (HCI log, 2026-10-18 00:04–00:15 IST). Builders must reproduce what
 * the original sent; parsers must read what the band answered.
 */
class CaptureFramesTest {

    private val ist: TimeZone = TimeZone.getTimeZone("Asia/Kolkata")

    @Test fun setTime_matchesCapture() {
        val now = Calendar.getInstance(ist).apply {
            clear()
            set(2026, Calendar.OCTOBER, 18, 0, 4, 39)
        }.timeInMillis
        assertArrayEquals(hex("feea100a316ad39c9708"), BandCommands.setTime(now, ist))
    }

    @Test fun timeZone_matchesCapture() {
        assertArrayEquals(hex("feea100bbb0700584d0000"), BandCommands.timeZone(ist))
    }

    @Test fun weather_matchesCapture() {
        assertArrayEquals(
            hex("feea10184300001c0020002000200020006b006f006c006b"),
            BandCommands.weatherTodayLegacy(0, 28, "kolkata"),
        )
        assertArrayEquals(
            hex("feea1015b500001c0000052111096b6f6c6b617461"),
            BandCommands.weatherToday(0, 28, "kolkata", sunriseMinutes = 5 * 60 + 33, sunsetMinutes = 17 * 60 + 9),
        )
        assertArrayEquals(hex("feea100c456b6f6c6b617461"), BandCommands.city("kolkata"))
        val days = listOf(
            BandCommands.Day(0, 35, 27), BandCommands.Day(0, 35, 25), BandCommands.Day(5, 33, 23),
            BandCommands.Day(0, 32, 21), BandCommands.Day(5, 33, 22), BandCommands.Day(5, 33, 22),
            BandCommands.Day(5, 32, 22),
        )
        assertArrayEquals(
            hex("feea101a4200231b002319052117002015052116052116052016"),
            BandCommands.forecast(days),
        )
        assertArrayEquals(hex("feea100eb304fa445701feee4405"), BandCommands.location(22.496506, 88.40371))
    }

    @Test fun simpleCommands_matchCapture() {
        assertArrayEquals(hex("feea100526"), BandCommands.queryGoal())
        assertArrayEquals(hex("feea10052f"), BandCommands.queryAutoHeartRate())
        assertArrayEquals(hex("feea100532"), BandCommands.sleepTonight())
        assertArrayEquals(hex("feea10063303"), BandCommands.history(BandCommands.HISTORY_SLEEP_YESTERDAY))
        assertArrayEquals(hex("feea10063500"), BandCommands.heartRateSeries(0))
        assertArrayEquals(hex("feea1006ab00"), BandCommands.heartRateLog())
        assertArrayEquals(hex("feea100869000000"), BandCommands.measureBloodPressure(true))
        assertArrayEquals(hex("feea100869ffffff"), BandCommands.measureBloodPressure(false))
        // Continuous heart rate (`o0.e`); the band ignores the single-shot 6D.
        assertArrayEquals(hex("feea10066800"), BandCommands.measureHeartRate(true))
        assertArrayEquals(hex("feea100668ff"), BandCommands.measureHeartRate(false))
    }

    @Test fun packets_splitAt20Bytes() {
        val parts = BandFrame.packets(BandCommands.weatherTodayLegacy(0, 28, "kolkata"))
        assertEquals(listOf(20, 4), parts.map { it.size })
        assertArrayEquals(hex("006c006b"), parts[1])
    }

    @Test fun reassembler_joinsHeartRatePacket() {
        val r = BandFrame.Reassembler()
        assertNull(r.feed(hex("feea104e35000000000000000000000000000000")))
        assertNull(r.feed(ByteArray(20)))
        assertNull(r.feed(ByteArray(20)))
        val frame = r.feed(ByteArray(18))!!
        assertEquals(0x35, frame.cmd)
        assertEquals(73, frame.payload.size)
        val packet = BandParsers.heartRateSeries(frame.payload)!!
        assertEquals(0, packet.index)
        assertEquals(72, packet.bpm.size)
    }

    @Test fun replies_parse() {
        assertEquals(7000, BandParsers.goal(hex("581b0000")))
        assertEquals(121 to 74, BandParsers.bloodPressure(hex("00794a")))
        assertEquals(98, BandParsers.singleValue(hex("62")))
        assertEquals(BandParsers.Steps(0, 0, 0), BandParsers.steps(hex("000000000000000000")))
        assertEquals(72, BandParsers.standardHeartRate(hex("16480004")))
    }

    @Test fun bandTime_isLocalTimeWrittenAsGmt8() {
        // The band's record pushed at 00:06:17 carried 0x6AD39CE8 = 00:06:00 IST.
        val expected = Calendar.getInstance(ist).apply {
            clear()
            set(2026, Calendar.OCTOBER, 18, 0, 6, 0)
        }.timeInMillis
        assertEquals(expected, BandParsers.fromBandTime(0x6AD39CE8L, ist))
    }

    @Test fun sleep_segmentsAcrossMidnight() {
        // light 23:10, deep 00:40, light 02:00, awake 06:30
        val night = BandParsers.sleep(hex("01170a" + "020028" + "010200" + "00061e"))!!
        assertEquals(90 + 270, night.lightMinutes)
        assertEquals(80, night.deepMinutes)
        assertEquals(440, night.asleepMinutes)
        assertEquals(23 * 60 + 10, night.startMinute)
        assertEquals(6 * 60 + 30, night.endMinute!! % (24 * 60))
    }

    @Test fun text_matchesOriginalPipeline() {
        // ❤ is in the SDK's emoji table; the variation selector after it isn't.
        assertEquals(
            "Mom:Good night [emoji]️",
            String(BandText.forBand("Mom", "Good night ❤️", "WhatsApp"), Charsets.UTF_8),
        )
        assertEquals("Hi there", BandText.compose("WhatsApp", "Hi there", "WhatsApp"))
        assertEquals("Alex:Lunch?", BandText.compose("Alex", "Lunch?", "Telegram"))
        assertEquals("a:b", BandText.clean("a: b"))
        assertEquals("x".repeat(92) + "…", String(BandText.encode("x".repeat(200), 92), Charsets.UTF_8))
    }

    @Test fun messageType_remapsForNewFirmware() {
        assertEquals(165, BandMessageType.firmwareCode("MOY-CK4-1.6.5"))
        assertEquals(172, BandMessageType.firmwareCode(null))
        assertEquals(8, BandMessageType.wireType(BandMessageType.WHATSAPP, 165))
        assertEquals(4, BandMessageType.wireType(BandMessageType.FACEBOOK, 165))
        assertEquals(128, BandMessageType.wireType(BandMessageType.OTHER, 165))
        assertEquals(2, BandMessageType.wireType(BandMessageType.FACEBOOK, 150))
    }

    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
