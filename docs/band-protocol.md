# ColorFit band (MOYOUNG) — wire protocol

The `:band` app talks to the Noise "ColorFit" band (`F5:4D:A1:6D:22:C8`, manufacturer
`MOYOUNG`, firmware `MOY-CK4-1.6.5`), the band the NoiseFit Track app
(`com.crrepa.band.noise`) syncs with. Everything below was checked against one of
two sources; each claim says which.

| Tag | Source |
|-----|--------|
| **[sdk]** | NoiseFit Track decompiled with jadx 1.5.6 into `D:\__OWN\__noisefit track decompiled\decompiled\sources`. The BLE SDK is `com.crrepa.ble.*` (public API, readable) over obfuscated packages: `u8/a` implements `CRPBleConnection`, `o8/*` build commands, `i9/a` parses replies, `a9/*` decode payloads, `ca/d` byte helpers, `i9/f` is the send queue. |
| **[capture]** | The phone's Bluetooth HCI log of NoiseFit Track syncing this band on 2026-10-18 00:04–00:15 (from a bug report; Qualcomm `hci_snoop*.cfa`, standard btsnoop, ring buffer). Also Da Fit pushing notifications to its own MOYOUNG watch (`03:99:6B:44:04:A8`) earlier that evening. |

Not this band: "Colorfit Pro 2" (`E9:0E:90:5E:D5:BE`) exposes only a vendor service
`0AF0` (`0AF1/0AF2/0AF6/0AF7`) and is not handled by NoiseFit Track at all [sdk][capture].

## GATT [sdk][capture]

| Service | Characteristic | Props | Use |
|---------|----------------|-------|-----|
| `FEEA` | `FEE1` | read, notify | today's steps, distance, calories |
| | `FEE2` | write | every command (phone → band) |
| | `FEE3` | notify | every framed reply (band → phone) |
| | `FEE5` / `FEE6` | write-no-resp | bulk transfers (watch faces, OTA) — unused |
| `180F` | `2A19` | read, notify | battery % |
| `180D` | `2A37` | notify | standard heart-rate measurement, streamed while measuring |
| `180A` | `2A29` / `2A24` / `2A28` | read | `MOYOUNG` / `DFU=0` / `MOY-CK4-1.6.5` |
| `1812` | HID | | camera/music keys; Android's HID host keeps the link up — don't touch |

NoiseFit Track enables notifications on `FEE3`, `2A37`, `FEE1`, then `2A19` (CCCD
`0100`) and never requests a larger MTU, so everything goes in 20-byte writes.

## Framing [sdk `o8/t1`, `i9/a.S2`][capture]

```
FE EA <flag> <len> <cmd> <payload…>        len = payload + 5
flag = 0x10 when the packet size is 20 (MTU 23); otherwise 0x20 | (len >> 8)
```

* Phone → band: the frame is written to `FEE2` in chunks of 20 bytes, write-without-
  response (the first write of a session was a write request; both are accepted).
  `FE EA 10 18 43 …` (24 bytes) went out as 20 + 4 [capture].
* Band → phone: frames on `FEE3`, reassembled across notifications by the length byte.
* Pacing [sdk `i9/f.j`]: one frame at a time, then 200 ms before the next
  (500 ms after `0x11/0x12/0x3B/0x3C/0x34–0x37`, 1000 ms after `0x33`, 50 ms after
  `0x67`). Replies are not awaited — they arrive asynchronously. The capture shows the
  ~208 ms rhythm.
* Byte order [sdk `ca/d`]: replies are little-endian (`v()` 24-bit, `r()` 32-bit);
  the time-sync seconds are big-endian (`o()`).

## Commands

| Cmd | Payload (phone → band) | Reply | Source |
|-----|------------------------|-------|--------|
| `31` | BE32 seconds of the local wall-clock time *read as GMT+8*, then `08` | — | [sdk `b1.e`][capture `6AD39C97 08` = 00:04:39 IST] |
| `BB 07 00` | LE32 UTC offset in seconds | — | [sdk `b1.f`][capture `58 4D 00 00` = +5:30] |
| `12` | `[height cm, weight kg, age, gender]` | — | [sdk `h1`][capture `AF 41 1A 00`] |
| `16` | BE32 step goal | — | [sdk `z.c`] |
| `26` | — | goal, LE (`58 1B 00 00` = 7000) | [sdk `z.a`][capture] |
| `17` | `[0]` 24-hour clock | — | [sdk `b1.d`][capture] |
| `1F` | `[n]` auto heart-rate every n×5 min, n ∈ {1,2,4,6}; `0` off | — | [sdk `o0.d`, `BandTimingHeartRateProvider`] |
| `2F` | — | `[n]` current interval (`00` = off on this band) | [sdk][capture] |
| `32` | — | sleep tonight: 3-byte records, empty if none | [sdk `a0.e`, `i9/a.J`][capture `FE EA 10 05 32`] |
| `33` | `[1]`/`[2]` steps yesterday / day before; `[3]`/`[4]` sleep same | `33 <day>` + 9-byte steps block, or `33 <day>` + sleep records | [sdk `i9/a.j2`][capture] |
| `35` | `[i]`, i = 0–3 today, 4–7 yesterday | `35 <i>` + 72 bpm samples; ask for i+1 until 3 (or 7) | [sdk `a9/j0.w`][capture] |
| `41` | `[type]` + UTF-8 text | — | [sdk `x8/a.c`][capture Da Fit `41 0E …`] |
| `42` | 7 × `[weatherId, max, min]` | — | [sdk `q1.b` + `e4/a`][capture] |
| `43` | `[0, id, temp]` + 4 spaces UTF-16BE + city UTF-16BE (≤4 chars) | — | [sdk `q1.f`][capture] |
| `B5` | `[0, id, temp, pm25 lo, pm25 hi, rise h, rise m, set h, set m]` + city UTF-8 (≤25 B) | — | [sdk `q1.c`][capture] |
| `45` | city UTF-8 (≤23 B) | — | [sdk `a1`][capture] |
| `B3 04` | LE32 lat×10⁶, LE32 lon×10⁶ | — | [sdk `f0.b`][capture 22.496506, 88.40371] |
| `61` | — | — | find band [sdk `u8/a.findDevice`] |
| `6D` | `[00]` start / `[FF]` stop single heart rate | `6D <bpm>`; live values on `2A37` meanwhile | [sdk `o0.h`, `i9/a.f`] |
| `69` | `00 00 00` start / `FF FF FF` stop blood pressure | `69 00 <sys> <dia>` | [sdk `y.e/f`, `a9/n.c`][capture `69 00 79 4A` = 121/74] |
| `6B` | `[00]` start / `[FF]` stop SpO₂ | `6B <percent>` | [sdk `v.c`, `i9/a.b3`][capture `6B 62` = 98 %] |
| `AB` | `[00]` measured heart-rate log | `AB 00 ?` + 5-byte records `[bpm, LE32 seconds (GMT+8)]` | [sdk `o0.a`, `a9/j0.t`] |

Battery: read/notify `2A19` (one byte, %). Steps: read/notify `FEE1` — three LE24
fields: steps, distance (m), calories (kcal) [sdk `i9/e.q`, `a9/s.b`][capture].

### Sleep records [sdk `a9/q.c`]

3 bytes `[state, hour, minute]`; each state lasts until the next record. States:
0 awake, 1 light, 2 deep, 3 REM. For tonight (`32`) the last segment runs until now.

### Heart-rate series [sdk `a9/j0`]

Four packets × 72 samples = 288 readings, one per 5 minutes from midnight. A byte
outside 40–200 means no reading. Only filled when auto heart-rate (`1F`) is on.

### Weather ids [sdk `CRPWeatherId`]

0 cloudy, 1 foggy, 2 overcast, 3 rainy, 4 snowy, 5 sunny, 6 sandstorm, 7 haze.
The SDK names the forecast fields `(low, high)` but NoiseFit Track passes
`(max, min)` into them, so the wire order is `[id, max, min]` (`e4/a`; the capture's
35/27 confirms it).

### Notification text [sdk `x8/a`, `x8/b`, `ca/h`, `d3/b`, `d3/e`]

* Text is `title:body` (title dropped if the body already starts with it, or is
  "WhatsApp"), `U+2005/U+2006` → space, the first `": "` → `":"`, then every `\p{C}`
  char removed (controls and bidi marks).
* Emoji in the SDK's table (`ca/h`, ~1800 code points) become `[emoji]`.
* Cut to the band's byte limit with "…" appended. The limit is `maxLength − 10` when
  the band answers `BB 0B` (this one didn't in the capture), else 92 bytes on small
  screens / 230 on large ones.
* Type byte = `CRPBleMessageType` (0 phone, 1 SMS, 4 WhatsApp, 14 Telegram,
  17 Gmail, 128 other, 130 Facebook, …), remapped for firmware code ≥ 161
  (`MOY-CK4-1.6.5` → 165): 4→8, 5→2, 8→10, 130→4, 131→5.
