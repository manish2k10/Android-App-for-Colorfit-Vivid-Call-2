# Opcode reference — Noise Colorfit Vivid Call 2

Decoded from the decompiled `com.yc.pedometer.sdk.WriteCommandToBLE` and
`com.yc.pedometer.sdk.BluetoothLeService` classes in NoiseFit Prime 1.1.20.

Wire format is symmetric — the same first byte travels both directions.
Most commands are 1 byte; a few take a sub-opcode in byte 2 or a payload
starting at byte 2.

Notation used below:
- `→` (phone → watch command)
- `←` (watch → phone response)
- Numbers in brackets are byte positions, e.g. `[2..9]` = bytes 2 through 9.

---

## GATT topology

| Role | Service UUID | Characteristic UUID |
|------|--------------|----------------------|
| Vendor data — legacy (BLE 4.x) | `000055ff-0000-1000-8000-00805f9b34fb` | write `000033f1-…`, notify `000033f2-…` |
| Vendor data — BLE 5 (large MTU) | `000056ff-0000-1000-8000-00805f9b34fb` | write `000034f1-…`, notify `000034f2-…` |
| Vendor "BP" secondary | `0000fff0-0000-1000-8000-00805f9b34fb` | write `0000fff6-…` |
| OTA (Dialog Semi SPOTA) | `0000fef5-…` | several |
| OTA (Realtek RK) | `0000d0ff-…` / `0000ffd3-…` | |
| Standard Battery | `0000180f-…` | `00002a19-…` |
| Standard Heart Rate | `0000180d-…` | `00002a37-…` |
| Standard Device Info | `0000180a-…` | several |

---

## System / device info

| Opcode | Name | Direction | Payload | Notes |
|--------|------|-----------|---------|-------|
| `0xA1` | `READ_VERSION` | → | none | ← `A1` + ASCII version |
| `0xA1 0x01` | `QUERY_DSP_VERSION` | → | — | ← `A1` + ASCII DSP version |
| `0xA2` | `READ_BATTERY` | → | none | ← `A2` + percent (0-100) |
| `0xA3` | `SYNC_TIME` | → | `[A3, yr_hi, yr_lo, month+1, day, h, m, s]` 8 bytes | Push current phone time to the watch |
| `0xA9` | `SET_PROFILE` | → | 19 bytes (height, weight, age, gender, units, …) | Push user profile to watch |
| `0xAA` | `GET_STEP_SLEEP_STATUS` | → | `SYNC_WORD` | ← `AA` + current step/sleep totals |
| `0xAB` | `PUSH_SHORT` (subtype 0/2/7) | → | `[AB, 0,0,0, 1, type, sub, flag]` 8 bytes | Sub-codes: `0`=SMS/QQ vibrate, `2`=incoming call, `7`=find-watch |
| `0xAB` | `STOP_VIBRATION` | → | `[AB, 0,0,0, 0, 0, 0, 0]` 8 bytes | |
| `0xAD` | `DELETE_ALL_DATA` | → | none | Wipes all stored data on watch |
| `0xAF` | `LANGUAGE_TEXT_PUSH` | → | chunked 20-byte packets | Multi-packet, language table push |
| `0xBB` | `HAS_CONTENT_PUSH` | → | none | ← `BB` + bitfield of apps with pending notifications |
| `0xC1 0x04` | `OFF_HOOK` | → | 2 bytes | Answer incoming call from watch |
| `0xC4 0x01` | `OPEN_SHAKE_MODE` | → | 2 bytes | Disable DND |
| `0xC4 0x03` | `CLOSE_SHAKE_MODE` | → | 2 bytes | Enable DND |
| `0xD3` | `SEDENTARY_REMIND` | → | 7 or 12 bytes | Set sit-reminder window |
| `0xD5 0x01` | `PASSWORD_QUERY` | → | `[D5, 0x01, "1","2","3","4"]` 9 bytes | Send pairing password (default "1234") |
| `0xD5 0x02` | `CAPTCHA_REQUEST` | → | 2 bytes | Ask watch to display its on-watch pairing code |
| `0xDB SYNC_WORD` | `QUERY_PUSH_DISPLAY` | → | 2 bytes | ← `DB` + 4-byte mask1 + 4-byte mask2 (LE) |
| `0xDF` | `HV_SCREEN` | → | `[DF, brightness]` 2 bytes | High-voltage screen brightness control |
| `0xE7 SYNC_WORD` | `QUERY_INTERFACE` | → | 2 bytes | ← `E7` + capability bitfield |
| `0xE9 0x01` | `BODY_COMP_TEST_START` | → | 2 bytes | Start body composition measurement |
| `0xE9 SYNC_WORD` | `BODY_COMP_QUERY` | → | 2 bytes | Query status |
| `0xE9 0x00` | `BODY_COMP_TEST_STOP` | → | 2 bytes | Stop measurement |
| `0xF3 SYNC_WORD` | `QUERY_SPORT_OPENED` | → | 2 bytes | Query currently-active sport |
| `0xF4 0xFA` | `SYNC_VARIETY_SPORTS` | → | 2 bytes | Pull supported sport modes |
| `0xF7 SYNC_WORD` | `QUERY_HR_24H` | → | 2 bytes | ← `F7` sub-codes for 24h HR stream |
| `0xF9 SYNC_WORD` | `QUERY_INTERFACE` | → | 2 bytes | ← `F9` + interface capability (legacy opcode) |
| `0xFB 0x01` | `HR_CALIBRATION_START` | → | 2 bytes | Begin HR sensor calibration |
| `0xFB 0x00` | `HR_CALIBRATION_STOP` | → | 2 bytes | End calibration |
| `0xFB 0xFD` | `HR_CALIBRATION_RESET` | → | 2 bytes | Reset to default |
| `0xFC 0x01` | `WRIST_CALIBRATION_START` | → | 2 bytes | Begin turn-wrist-to-wake calibration |
| `0xFC 0x00` | `WRIST_CALIBRATION_STOP` | → | 2 bytes | End calibration |
| `0xFC 0xFD` | `WRIST_CALIBRATION_RESET` | → | 2 bytes | Reset to default |
| `0xFD` | `END_OF_STREAM` | ← | none | General "all done" marker |

---

## Live measurements

| Opcode | Name | Direction | Payload | Notes |
|--------|------|-----------|---------|-------|
| `0xBA 0x01` | `UV_TEST` | → | 2 bytes | Start UV sensor test |
| `0xBA 0x02` | `UV_READ_LAST` | → | 2 bytes | Read last UV result |
| `0xC7 0x01` | `BP_TEST_START` | → | 2 bytes | Start blood-pressure measurement |
| `0xC7 0x00` | `BP_TEST_STOP` | → | 2 bytes | Stop BP measurement |
| `0xC7 0x02` | `BP_AUTO_INTERVAL` | → | `[C7, 0x02, hours]` 3 bytes | Automatic BP test interval (hours) |
| `0xC7 0x03` | `BP_MORNING_AUTO` | → | `[C7, 0x03, hh*60+mm]` 3 bytes | Morning BP auto-test time |
| `0xC7 0x04` | `BP_NIGHT_PERIOD` | → | `[C7, 0x04, en, start_h, start_m, end_h, end_m]` 7 bytes | Night BP time window |
| `0xC7 0x05` | `BP_DAY_PERIOD` | → | `[C7, 0x05, period, en, hh, mm]` 6 bytes | Day BP time window |
| `0xE5 0x11` | `HR_LIVE_START` | → | 2 bytes | **Start continuous HR streaming** — ← `E5` + bpm each second |
| `0xE5 0x00` | `HR_LIVE_STOP` | → | 2 bytes | Stop continuous HR |
| `0xE5 SYNC_WORD` | `HR_LIVE_QUERY` | → | 2 bytes | Query current HR once |
| `0x34 0x11` | `SPO2_TEST_START` | → | 2 bytes | **Start SpO₂ measurement** |
| `0x34 0x00` | `SPO2_TEST_STOP` | → | 2 bytes | Stop SpO₂ |
| `0x34 SYNC_WORD` | `SPO2_QUERY_STATUS` | → | 2 bytes | ← `34` + percent when complete |
| `0x24 0x01` | `TEMP_QUERY` | → | 2 bytes | Query current temperature |
| `0x24 0xFA` | `TEMP_SYNC_ALL` | → | 2 bytes | Pull historical temperature |
| `0x24 0x05` | `TEMP_HIST_DELETE` | → | 2 bytes | Delete stored temperature history |
| `0x24 0x07` | `TEMP_CALIBRATE` | → | 2 bytes | Calibrate temperature sensor |
| `0x24 0x09` | `TEMP_RAW_STATUS` | → | 2 bytes | Query raw sensor status |

---

## Historical data sync

| Opcode | Name | Direction | Payload | Notes |
|--------|------|-----------|---------|-------|
| `0xB2 0xFA` | `SYNC_STEPS` | → | 2 bytes | **Pull today's step history** — ← `B2` chunks, `B2 FD` = end |
| `0xB3 0xFA` | `SYNC_SLEEP` | → | 2 bytes | **Pull sleep records** — ← `B3` chunks, `B3 FD` = end |
| `0xE6 0xFA` | `SYNC_HR` | → | 2 bytes | **Pull HR history** — ← `E6` per-day aggregates |
| `0xC8 0xFA` | `SYNC_BP` | → | 2 bytes | Pull BP history |
| `0xB1` | `STEPS_REALTIME` | ← | — | Watch pushes live step counts |
| `0xE6` | `HR_HISTORICAL` | ← | — | Watch responds with HR aggregates |

Capability-gated variants (what the Vivid Call 2 actually uses, per captured traffic):
- `0x31 0x01` on `0x33F1` — sync sleep when `isSupportFunction_Fourth(262144)`; ← `31 01` + date per day, `32` data chunks, `31 02` = end
- `0xF7 0xFA` + 6-byte last-sync timestamp on `0x33F1` — 24-hour HR log; ← `F7` + date + hour + 12 samples, `F7 FD` = end
- `0xEB 0x02 0xFA` on `0x34F1` — sync all steps when `isSupportFunction_8(32)`

---

## Phone → watch push (notifications, weather, dials)

| Opcode | Name | Direction | Payload | Notes |
|--------|------|-----------|---------|-------|
| `0xC5` | `SMS_TEXT_PUSH` | → | `[C5, idx]` + 18 bytes per chunk; message = `[appType, textLen]` + UTF-16**BE** `title:body` (≤160 bytes) | **Push any app notification.** Watch acks each chunk with `C5 idx`; finish with `C5 FD` (← `C5 FD type len`), then `AB 00 00 00 01 01 00 00` to vibrate. `appType` = `getAppType()` (WhatsApp 7, Instagram 13, Gmail 19, Telegram 24, other 4 …) |
| `0xC6` | `QQ_WECHAT_TEXT_PUSH` | → | same shape as `0xC5` | Push QQ/WeChat-style notification body |
| `0xCA` | `WEATHER_CURRENT` | → | 14 bytes | Legacy single-day weather push |
| `0xCB 0x01` | `WEATHER_MULTI_DAY` | → | 19 or 21 bytes | **Multi-day weather push** (today + tomorrow + day-after) |
| `0x26 0x01` | `DIAL_READ_CONFIG` | → | 2 bytes | Read current watch-face config |
| `0x26 0x02` | `DIAL_PREPARE` | → | 2 bytes | Prepare to receive dial ZIP |
| `0x26 0x03 0x00` | `DIAL_FINISH` | → | 3 bytes | End of dial push |
| `0x47 0x73 0x01` | `GPS_OPEN` | → | 3 bytes | Enable watch GPS |
| `0x47 0x73 0x00` | `GPS_CLOSE` | → | 3 bytes | Disable watch GPS |

---

## Sub-opcode markers

Used as byte[1] in many of the commands above:

| Hex | Dec (signed) | Meaning |
|-----|--------------|---------|
| `0xFA` | `-6` | `FULL_SYNC` — "give me everything" (sync-all marker) |
| `0xFD` | `-3` | `RESET` / "end of stream" |
| `0xFF` | `-1` | reserved (rarely used) |
| `0x00` | `0` | `STOP` / disable |
| `0x01` | `1` | `START` / enable |
| `0xFE` | `-2` | reserved |
| `SYNC_WORD` (`0xAA`) | `-86` | `QUERY_STATUS` — "what's the current value?" (`TransportLayerPacket.SYNC_WORD`; on the wire as `DB AA`, `FD AA`, `51 AA`) |

---

## Notification app bitfield

The watch's response to `QUERY_PUSH_DISPLAY` carries two 32-bit LE masks.
A bit set in either mask means the user enabled that app family for
mirroring on the watch side. The push command (`0xAB` short or `0xC5`/`0xC6`
chunked) carries the same bit in byte[5] (short) or as a class id (chunked).

| Bit | Hex | App family |
|-----|-----|-----------|
| 1 | `0x01` | SMS / Telegram |
| 2 | `0x02` | QQ / Truecaller |
| 4 | `0x04` | WeChat / Paytm |
| 8 | `0x08` | Phone / Zalo |
| 16 | `0x10` | Other / WhatsApp Business |
| 32 | `0x20` | Facebook / Microsoft Teams |
| 64 | `0x40` | Twitter / Outlook |
| 128 | `0x80` | WhatsApp / Swiggy |
| 256 | `0x100` | Facebook Messenger / Zomato |
| 512 | `0x200` | Line / GPay |
| 1024 | `0x400` | Skype / PhonePe / Prime Video |
| 2048 | `0x800` | Hangouts / Hotstar |
| 4096 | `0x1000` | LinkedIn / Amazon |
| 8192 | `0x2000` | Instagram / Flipkart |
| 16384 | `0x4000` | Viber / Amazon |
| 32768 | `0x8000` | KakaoTalk / Myntra |
| 65536 | `0x10000` | NoiseApp / VKontakte |
| 131072 | `0x20000` | Snapchat / Dailyhunt |
| 262144 | `0x40000` | Google+ / Inshorts |
| 524288 | `0x80000` | Gmail / BookMyShow |
| 1048576 | `0x100000` | Flickr |
| 2097152 | `0x200000` | Tumblr |
| 4194304 | `0x400000` | Pinterest |
| 8388608 | `0x800000` | YouTube |

---

## Auth flow

Which path runs depends on the capability blob read from `0x33F1`
(`BluetoothLeService.mSyncTimeRunnable`):

- **Account-id watches** (`isSupportFunction_Fifth(4)`, e.g. Vivid Call 2):
  app sends `33 01`; a bound watch replies `33 04 01` and the sync starts.
  `33 04 03/04` = confirm on the watch, `33 04 05` = user cancelled.
- **Password watches** (`isSupportFunction(1)`): the `0xD5` flow below.
- Neither: straight to the sync.

1. App sends `0xD5 0x01` + ASCII password (default `"1234"`).
2. Watch replies:
   - `0xD5 0x00` — accepted, handshake done.
   - `0xD5 0x01` + bytes — rejected; bytes after `0x01` are the
     on-watch 4-digit pairing code the user must type into the app.
3. On accept, app immediately pushes:
   - `0xA3` — time sync
   - `0xA9` — user profile
   - `0xF9 SYNC_WORD` — capability query
   - `0xDB SYNC_WORD` — push capability bitfield
   - `0xE5 0x11` — start continuous HR streaming
   - `0xB2 0xFA` / `0xB3 0xFA` / `0xE6 0xFA` — sync steps / sleep / HR history

The whole flow finishes within ~2 seconds of `STATE_CONNECTED`.
