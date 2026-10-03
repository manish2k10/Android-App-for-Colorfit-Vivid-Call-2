package com.colorfit.companion.vendor

/**
 * Single-byte opcodes for the Noise (yc.pedometer) vendor protocol.
 *
 * Source of truth: decompiled `com.yc.pedometer.sdk.WriteCommandToBLE` and
 * `com.yc.pedometer.sdk.BluetoothLeService` from NoiseFit Prime 1.1.20.
 *
 * Wire format is symmetric — phone→watch and watch→phone use the same first
 * byte. The watch→phone response is dispatched on `bytes[0]`; longer commands
 * add a sub-opcode in `bytes[1]` and a payload after that.
 *
 * Some commands have a "_V5" variant that uses the BLE 5 channel
 * (UUID 0x34F1) instead of the legacy channel (0x33F1). On modern firmwares
 * the watch negotiates the channel during `queryBraceletInterface` — see
 * [VendorConnection].
 */
object VendorOpcodes {

    // ─────── System / device info ──────────────────────────────────────────
    const val SYNC_TIME: Byte = 0xA3.toByte()           // +[yr_hi, yr_lo, month+1, day, h, m, s]
    const val READ_VERSION: Byte = 0xA1.toByte()        // response: A1 + ASCII version
    const val QUERY_DSP_VERSION: Byte = 0xA1.toByte()   // +0x01 sub
    const val READ_BATTERY: Byte = 0xA2.toByte()        // response: A2 + percent
    const val QUERY_INTERFACE: Byte = 0xF9.toByte()     // + SYNC_WORD → tells us which protocol variant
    const val QUERY_PUSH_DISPLAY: Byte = 0xDB.toByte()  // + SYNC_WORD → bitfield of apps watch accepts
    const val HAS_CONTENT_PUSH: Byte = 0xBB.toByte()    // response: BB + bitfield
    const val DELETE_ALL_DATA: Byte = 0xAD.toByte()

    // ─────── User profile / settings ───────────────────────────────────────
    const val SET_PROFILE: Byte = 0xA9.toByte()         // 19-byte payload (height, weight, age, gender, units, …)
    const val SET_UNIT: Byte = 0xA0.toByte()            // + [km/imperial, 12h/24h]
    const val SET_SEDENTARY_REMIND: Byte = 0xD3.toByte()// + [enable, interval, from_h, from_m, to_h, to_m]
    const val OPEN_SHAKE_MODE: Byte = 0xC4.toByte()     // + 0x01 = open (DND off)
    const val CLOSE_SHAKE_MODE: Byte = 0xC4.toByte()    // + 0x03 = close (DND on)
    const val HV_SCREEN: Byte = 0xDF.toByte()           // + brightness byte

    // ─────── Live measurement commands ─────────────────────────────────────
    const val RATE_TEST: Byte = 0xE5.toByte()           // + 0x11 = start continuous HR, 0x00 = stop
    const val RATE_MODE_OPEN: Byte = 0xD6.toByte()      // + 0x02 = open dynamic/static rate mode (alt-firmware path)
    const val RATE_TIMING_TEST: Byte = 0xD6.toByte()    // + 0x10 = set timed HR test
    const val OXYGEN_TEST: Byte = 0x34.toByte()         // + 0x11 = start SpO2
    const val OXYGEN_QUERY_STATUS: Byte = 0x34.toByte() // + SYNC_WORD
    const val BLOOD_PRESSURE_TEST: Byte = 0xC7.toByte() // + sub
    const val UV_TEST: Byte = 0xBA.toByte()             // + 0x01 start / 0x02 read last
    const val BODY_COMP_TEST: Byte = 0xE9.toByte()      // + 0x01 start / SYNC_WORD query
    const val BODY_COMP_STOP: Byte = 0xE9.toByte()      // + 0x00
    const val TEMP_QUERY: Byte = 0x24.toByte()          // + 0x01
    const val TEMP_SYNC: Byte = 0x24.toByte()           // + 0xFA

    // ─────── Historical data sync ──────────────────────────────────────────
    const val SYNC_STEPS_LEGACY: Byte = 0xB2.toByte()   // + 0xFA
    const val SYNC_SLEEP_LEGACY: Byte = 0xB3.toByte()   // + 0xFA
    const val SYNC_RATE_LEGACY: Byte = 0xE6.toByte()    // + 0xFA
    const val SYNC_BP_LEGACY: Byte = 0xC8.toByte()      // + 0xFA

    /** BLE 5 channel variant: [EB, 02, FA] (sync all steps with timestamp). */
    const val SYNC_STEPS_V5: Byte = 0xEB.toByte()
    /**
     * Band-algorithm sleep sync: [31, 01] on the legacy channel, used when the
     * watch reports `isSupportFunction_Fourth(262144)`. Reply is `31 01` + date
     * per day, `32` raw-data chunks, then `31 02`.
     */
    const val SYNC_SLEEP_BAND: Byte = 0x31.toByte()
    const val SLEEP_BAND_DATA: Byte = 0x32.toByte()
    /** 24-hour HR: [F7, FA] (+ 6-byte last-sync timestamp when supported). */
    const val RATE_24H: Byte = 0xF7.toByte()

    // ─────── Phone→watch push ──────────────────────────────────────────────
    /** Short alert only, no body. Format: [AB, 0,0,0, 1, type, 0, 0]. */
    const val PUSH_SHORT: Byte = 0xAB.toByte()
    const val FIND_BAND_SUBTYPE: Byte = 0x07
    const val INCALL_SUBTYPE: Byte = 0x02
    const val SMS_SHORT_SUBTYPE: Byte = 0x00
    const val OFF_HOOK: Byte = 0xC1.toByte()            // + 0x04
    const val STOP_VIBRATION: Byte = 0xAB.toByte()      // + [0,0,0,0,0,0,0,0]
    const val PUSH_SMS_TEXT: Byte = 0xC5.toByte()       // chunked, 20-byte packets, 18 bytes payload each
    const val PUSH_QQ_WECHAT_TEXT: Byte = 0xC6.toByte() // chunked
    const val PUSH_LANGUAGE_TEXT: Byte = 0xAF.toByte()  // chunked

    // ─────── Weather ───────────────────────────────────────────────────────
    /** Legacy 14-byte current-day weather push. */
    const val WEATHER_LEGACY: Byte = 0xCA.toByte()
    /** Multi-day weather push, 19 or 21 bytes depending on capability. */
    const val WEATHER_MULTI: Byte = 0xCB.toByte()

    // ─────── Watch face / dial push ────────────────────────────────────────
    const val DIAL_READ_CONFIG: Byte = 0x26.toByte()    // + 0x01
    const val DIAL_PREPARE: Byte = 0x26.toByte()        // + 0x02
    const val DIAL_FINISH: Byte = 0x26.toByte()         // + 0x03, 0x00

    // ─────── Auth ──────────────────────────────────────────────────────────
    const val PASSWORD_QUERY: Byte = 0xD5.toByte()      // + 0x01
    const val CAPTCHA_REQUEST: Byte = 0xD5.toByte()     // + 0x02
    const val ACCOUNT_ID_QUERY: Byte = 0x33.toByte()    // + 0x01
    const val ACCOUNT_ID_SEND: Byte = 0x33.toByte()     // + 0x02 + 4-byte BE id

    /** Default pairing password. Most of these watches ship with this. */
    const val DEFAULT_PASSWORD: String = "1234"

    /**
     * `TransportLayerPacket.SYNC_WORD` from the Realtek BBpro transport layer
     * (`public static final byte SYNC_WORD = -86`), i.e. 0xAA. Confirmed on
     * the wire: the original app sends `DB AA`, `FD AA`, `51 AA`.
     */
    private const val TransportLayerSyncWord: Byte = 0xAA.toByte()

    // ─────── Sub-opcode markers ────────────────────────────────────────────
    const val SUB_FULL_SYNC: Byte = 0xFA.toByte()       // "give me everything"
    const val SUB_QUERY: Byte = TransportLayerSyncWord  // "current status?"
    const val SUB_START: Byte = 0x01.toByte()
    const val SUB_STOP: Byte = 0x00.toByte()
    const val SUB_DEFAULT_RESET: Byte = 0xFD.toByte()   // -3 signed
}
