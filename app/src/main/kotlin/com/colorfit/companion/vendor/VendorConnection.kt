package com.colorfit.companion.vendor

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Build
import com.colorfit.companion.ble.BleConnectionManager
import com.colorfit.companion.domain.ConnectionState
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ActivityRetainedScoped
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.util.Calendar
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the Noise vendor-protocol state machine: auth handshake after
 * service discovery, time sync + profile push on first connect, and
 * dispatch of incoming vendor notifications into [VendorResponse] events.
 *
 * This sits on top of [BleConnectionManager] (which handles standard SIG
 * services and the raw GATT connection) — the two share the same
 * `BluetoothGatt` instance via the manager's `writeVendorCommand` helper
 * and `vendorResponseListener` callback.
 */
@Singleton
class VendorConnection @Inject constructor(
    private val ble: BleConnectionManager,
    @ApplicationContext private val context: Context,
) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _live = MutableStateFlow(LiveVendorData())
    val live: StateFlow<LiveVendorData> = _live.asStateFlow()

    private val _phase = MutableStateFlow(Phase.IDLE)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    private val _historical = MutableStateFlow<List<HistoricalRecord>>(emptyList())
    val historical: StateFlow<List<HistoricalRecord>> = _historical.asStateFlow()

    private val _pushCapability = MutableStateFlow(PushCapability.UNKNOWN)
    val pushCapability: StateFlow<PushCapability> = _pushCapability.asStateFlow()

    /**
     * Feature bitfield read from the vendor write characteristics. Decides
     * the weather packet size and the pushed-text encoding, so it must be
     * populated before the handshake sends either.
     */
    private val _capabilities = MutableStateFlow(VendorCapabilities.UNKNOWN)
    val capabilities: StateFlow<VendorCapabilities> = _capabilities.asStateFlow()

    private val _errors = MutableStateFlow<List<String>>(emptyList())
    val errors: StateFlow<List<String>> = _errors.asStateFlow()

    /** Combined state for UI consumers. */
    val state: StateFlow<VendorState> = combine(
        _phase, _live, _historical, _pushCapability, _errors,
    ) { phase, live, hist, cap, errs ->
        VendorState(phase, live, hist, cap, errs)
    }.stateIn(scope, SharingStarted.Eagerly, VendorState())

    /** Fires once each time the vendor handshake completes successfully. */
    private val _readyEvents = MutableStateFlow<Long>(0L)
    val readyEvents: StateFlow<Long> = _readyEvents.asStateFlow()

    /** MAC of the last device we successfully connected to. */
    @Volatile var lastConnectedMac: String? = null
        private set

    /** Timestamp (ms) of the last live HR reading received from the watch. */
    @Volatile var lastHeartRateAt: Long = 0L
        private set

    /** True if the watch is currently streaming live HR. */
    val heartRateStreaming: Boolean
        get() = System.currentTimeMillis() - lastHeartRateAt < HR_STALE_THRESHOLD_MS

    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null

    /**
     * Guards [finishAuth] so time-sync + profile + live/history sync run
     * exactly once per connection, even though it can be reached from both
     * the auth-success response and the optimistic fallback timer. Reset on
     * disconnect so the next connection re-runs the handshake.
     */
    private val syncStarted = AtomicBoolean(false)

    private val incoming = Channel<Pair<ByteArray, java.util.UUID>>(Channel.UNLIMITED)

    /** Every frame from the watch, for [request] to wait on. */
    private val rxFrames = MutableSharedFlow<ByteArray>(extraBufferCapacity = 256)

    /**
     * One command in flight at a time. The watch handles commands strictly one
     * by one: fired back-to-back, it answered the first few and silently
     * dropped `E5 11`, `B2 FA` and `31 01`. The original likewise waits for
     * each reply (or a 3 s timeout) before sending the next command.
     */
    private val commandMutex = Mutex()

    /** Day currently being received in a band-algorithm sleep sync. */
    private var sleepDateKey: String? = null
    private val sleepBuffer = ByteArrayOutputStream()

    init {
        // Wire vendor-protocol bytes from the BLE manager into our parser.
        // Frames go through a channel drained by one coroutine so they are
        // handled in arrival order — multi-frame streams (sleep chunks, text
        // acks) break if two frames race each other on the dispatcher.
        ble.setVendorResponseListener { bytes, charUuid ->
            incoming.trySend(bytes to charUuid)
        }
        scope.launch {
            for ((bytes, charUuid) in incoming) handleIncoming(bytes, charUuid)
        }
        // Capability blobs read back from the vendor write characteristics.
        ble.setVendorCapabilityListener { bytes, charUuid ->
            applyCapabilityBlob(bytes, charUuid)
        }
        // Start the vendor handshake only once notifications are actually
        // enabled — otherwise auth/sync responses never come back.
        ble.setNotificationsReadyListener {
            scope.launch { startHandshake() }
        }
        // Restore the last bonded MAC and kick off an immediate reconnect
        // attempt on launch. Cheap watches don't survive process death
        // (or task switches to other apps) without this — the user would
        // otherwise have to re-pair every cold start.
        val savedMac = prefs.getString(PREF_LAST_MAC, null)
        if (savedMac != null) {
            lastConnectedMac = savedMac
            // Schedule the first reconnect attempt. UI consumers can
            // observe `lastConnectedMac` and immediately flip their
            // "reconnecting" indicator on, so the user never sees the
            // scan card on relaunch.
            reconnectJob = scope.launch {
                delay(AUTO_RECONNECT_DELAY_MS)
                reconnect(savedMac)
            }
        }
        // Watch the standard state so we can kick off the handshake after
        // service discovery completes (we know services are up because
        // BleConnectionManager has populated gattTable).
        scope.launch {
            ble.state.collect { s ->
                when (val c = s.connection) {
                    is ConnectionState.Connected -> {
                        // Just remember the device. The handshake is kicked off
                        // by the BLE manager's notifications-ready callback once
                        // service discovery + notification enablement complete.
                        lastConnectedMac = c.macAddress
                        persistLastMac(c.macAddress)
                    }
                    ConnectionState.Disconnected, ConnectionState.Idle -> {
                        if (_phase.value != Phase.IDLE) {
                            stopHeartbeat()
                            _phase.value = Phase.IDLE
                            _live.value = LiveVendorData()
                            _historical.value = emptyList()
                            // Drop the hourly step buckets — the watch
                            // re-sends them on the next sync, and keeping
                            // them risks mixing in a stale day.
                            clearStepBuckets()
                            // Capabilities are per-device and re-read on every
                            // connect; clearing avoids applying one watch's
                            // feature flags to another.
                            _capabilities.value = VendorCapabilities.UNKNOWN
                        }
                        // Allow the next connection to run the handshake again.
                        syncStarted.set(false)
                        // Auto-reconnect: cheap Chinese watches drop idle
                        // connections aggressively. If we have a MAC we
                        // bonded with, retry after a short delay.
                        val mac = lastConnectedMac
                        if (mac != null && reconnectJob?.isActive != true) {
                            reconnectJob = scope.launch {
                                delay(AUTO_RECONNECT_DELAY_MS)
                                reconnect(mac)
                            }
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    /** Persist the MAC we successfully bonded to, so we can find it next launch. */
    private fun persistLastMac(mac: String) {
        prefs.edit().putString(PREF_LAST_MAC, mac).apply()
    }

    /** Forget the bonded device (used when the user taps "Forget this watch"). */
    fun clearLastMac() {
        prefs.edit().remove(PREF_LAST_MAC).apply()
        lastConnectedMac = null
        reconnectJob?.cancel()
        reconnectJob = null
        stopHeartbeat()
        _phase.value = Phase.IDLE
        _live.value = LiveVendorData()
        _historical.value = emptyList()
        _pushCapability.value = PushCapability.UNKNOWN
    }

    /** Force a reconnect to the last bonded watch. Returns true if scheduled. */
    fun reconnectNow(): Boolean {
        val mac = lastConnectedMac ?: return false
        scope.launch { reconnect(mac) }
        return true
    }

    private suspend fun reconnect(mac: String) {
        val adapter = context.getSystemService(Context.BLUETOOTH_SERVICE)
            ?.let { it as? BluetoothManager }?.adapter
            ?: run {
                _errors.update { it + "Bluetooth adapter unavailable for reconnect" }
                return
            }
        if (!adapter.isEnabled) {
            _errors.update { it + "Bluetooth is off — can't reconnect to $mac" }
            return
        }
        try {
            @Suppress("MissingPermission")
            val device = adapter.getRemoteDevice(mac)
            // autoConnect = true: let the stack wait passively for a
            // power-saving watch to reappear instead of timing out (133).
            ble.connect(device, autoConnect = true)
        } catch (e: SecurityException) {
            _errors.update { it + "Missing BLUETOOTH_CONNECT permission for reconnect" }
        } catch (e: IllegalArgumentException) {
            _errors.update { it + "Bad MAC for reconnect: $mac" }
        }
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                if (_phase.value == Phase.READY) {
                    // Cheap Chinese watches aggressively power-save after
                    // ~60s of silence; a status query every 30s keeps the
                    // link warm and confirms the watch is still responsive.
                    // Battery (`A2`) is a query this watch is known to answer.
                    request(VendorFrame.batteryQuery())
                    // If HR streaming went quiet (sensor power-saved),
                    // re-poke the start commands so it resumes.
                    val since = System.currentTimeMillis() - lastHeartRateAt
                    if (since > HR_RESTART_THRESHOLD_MS && _live.value.heartRateBpm != null) {
                        // We've seen HR before — the link just went quiet.
                        request(VendorFrame.startHrTest(), timeoutMs = SHORT_TIMEOUT_MS)
                    }
                }
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    /**
     * Begin the vendor handshake. Called from the BLE manager's
     * notifications-ready callback, i.e. only after the watch will actually
     * deliver notifications.
     *
     * Mirrors `BluetoothLeService.mSyncTimeRunnable()`: a watch that reports
     * password support (`isSupportFunction(1)`) gets the `0xD5` password;
     * every other watch gets the account-id query `33 01`, which an
     * already-bound watch answers with `33 04 01`. The reply is handled in
     * [handleIncoming]; if none arrives an optimistic fallback proceeds to
     * [finishAuth].
     */
    private suspend fun startHandshake() {
        if (_phase.value != Phase.IDLE) return
        syncStarted.set(false)
        _phase.value = Phase.AUTHENTICATING
        // Waits for the reply (or AUTH_FALLBACK_MS); handleIncoming acts on it.
        if (_capabilities.value.supportsPassword) {
            request(VendorFrame.passwordAuth(VendorOpcodes.DEFAULT_PASSWORD), timeoutMs = AUTH_FALLBACK_MS)
        } else {
            request(VendorFrame.accountIdQuery(), timeoutMs = AUTH_FALLBACK_MS)
        }
        if (_phase.value == Phase.AUTHENTICATING) {
            // No auth reply — the watch needs neither. Proceed.
            finishAuth()
        }
    }

    private suspend fun finishAuth() {
        // Run exactly once per connection (reachable from both the auth-success
        // response and the optimistic fallback above).
        if (!syncStarted.compareAndSet(false, true)) return
        _phase.value = Phase.SYNCING

        // Push time + profile so the watch's clock and step target are right.
        // Each request waits for the watch's reply before the next goes out.
        request(VendorFrame.timeSync(currentYear(), currentMonth(), currentDay(), currentHour(), currentMinute(), currentSecond()))
        request(VendorFrame.userProfile(
            heightCm = PROFILE_HEIGHT_CM,
            weightKg = PROFILE_WEIGHT_KG,
            age = PROFILE_AGE,
            isMale = PROFILE_IS_MALE,
        ))
        // Which apps the watch will display, then the current battery/version
        // over the vendor channel. (Capabilities come from the characteristic
        // read during bring-up, not from a command.)
        request(VendorFrame.queryPushDisplay())
        request(VendorFrame.batteryQuery())
        request(VendorFrame.versionQuery())
        request(VendorFrame.dspVersionQuery())

        // Basic info is in: let the UI (and the weather push) go ahead. Their
        // commands queue behind the history sync below.
        _phase.value = Phase.READY
        _readyEvents.value = System.currentTimeMillis()
        startHeartbeat()

        // Pull whatever historical data the watch has buffered.
        syncHistorySuspend()

        // Auto-start live HR last: the watch only answers once it has a first
        // reading (~10 s), so nothing should be queued behind it.
        request(VendorFrame.startHrTest(), timeoutMs = SHORT_TIMEOUT_MS)
    }

    /**
     * Ask the watch for its stored steps, sleep and heart-rate history, using
     * the command variants its capability bits call for.
     */
    fun syncHistory() {
        scope.launch { syncHistorySuspend() }
    }

    /**
     * Each sync streams frames and ends with its own marker; the next request
     * waits for that marker (as the original does) so the streams don't
     * overlap on the watch.
     */
    private suspend fun syncHistorySuspend() {
        val caps = _capabilities.value
        request(VendorFrame.syncAllSteps(), timeoutMs = STREAM_TIMEOUT_MS) {
            it.opcode(0xB2) && it.sub() == 0xFD
        }
        val sleep = VendorFrame.syncAllSleep(bandAlgorithm = caps.supportsBandSleep)
        request(sleep, timeoutMs = STREAM_TIMEOUT_MS) {
            if (caps.supportsBandSleep) it.opcode(0x31) && it.sub() == 0x02
            else it.opcode(0xB3) && it.sub() == 0xFD
        }
        // The original pulls the 24-hour HR log (`F7 FA`), not `E6 FA`.
        request(
            VendorFrame.sync24HourRate(withTimestamp = caps.supportsSyncTimestamp),
            timeoutMs = STREAM_TIMEOUT_MS,
        ) { it.opcode(0xF7) && it.sub() == 0xFD }
    }

    /**
     * Send a single packet without waiting for anything. Returns true if the
     * BLE write was queued. [ble5] routes it to `34F1`; see
     * [BleConnectionManager.writeVendorCommand]. Prefer [request] for commands
     * the watch answers.
     */
    fun send(payload: ByteArray, ble5: Boolean = false): Boolean = ble.writeVendorCommand(payload, ble5)

    /**
     * Send [payload] and wait until a frame matching [done] arrives (default:
     * same opcode) or [timeoutMs] passes, holding [commandMutex] throughout so
     * no other command reaches the watch meanwhile. Returns the matching
     * frame, or null on timeout / when nothing could be written.
     */
    private suspend fun request(
        payload: ByteArray,
        ble5: Boolean = false,
        timeoutMs: Long = COMMAND_TIMEOUT_MS,
        done: (ByteArray) -> Boolean = { it.isNotEmpty() && it[0] == payload[0] },
    ): ByteArray? = commandMutex.withLock {
        coroutineScope {
            // Subscribe before writing so a fast reply can't be missed.
            val reply = async(start = CoroutineStart.UNDISPATCHED) { rxFrames.first(done) }
            if (!send(payload, ble5)) {
                reply.cancel()
                return@coroutineScope null
            }
            withTimeoutOrNull(timeoutMs) { reply.await() }.also { reply.cancel() }
        }
    }

    /** Fire-and-forget [request] for UI actions. */
    private fun requestAsync(payload: ByteArray, timeoutMs: Long = COMMAND_TIMEOUT_MS): Boolean {
        if (_phase.value == Phase.IDLE) return false
        scope.launch { request(payload, timeoutMs = timeoutMs) }
        return true
    }

    private fun ByteArray.opcode(op: Int): Boolean = isNotEmpty() && (this[0].toInt() and 0xFF) == op
    private fun ByteArray.sub(): Int = if (size >= 2) this[1].toInt() and 0xFF else -1

    // ───────── High-level helpers used by UI / services ───────────────────

    /**
     * Push a notification to the watch: the chunked text body (which ends
     * with the `0xC5 0xFD` "render it" marker) followed by a short
     * vibration so there's a haptic even if the watch is slow to draw.
     *
     * Like the original, each chunk waits for the watch's `C5 <index>`
     * acknowledgement before the next one goes out. Returns immediately; the
     * push runs in the background, one message at a time.
     *
     * @param appType watch-side app id ([NotificationAppId.typeForPackage]).
     */
    fun pushNotification(
        title: String,
        body: String,
        alertType: Int = VendorFrame.NOTIFY_TYPE_APP,
        appType: Int = NotificationAppId.TYPE_OTHER,
    ) {
        val packets = VendorFrame.pushNotificationText(
            title = title,
            body = body,
            appType = appType,
            // Watches without the full character set expect GB2312, which is
            // a completely different byte stream from UTF-16.
            unicode = _capabilities.value.supportsFullCharset,
        )
        if (packets.isEmpty()) return
        scope.launch {
            for (packet in packets) {
                val index = packet[1].toInt() and 0xFF
                // Wait for `C5 <index>` (`C5 FD …` for the terminator). A
                // missed ack shouldn't strand the rest of the message: after
                // the timeout carry on regardless.
                request(packet, timeoutMs = COMMAND_TIMEOUT_MS) {
                    it.opcode(packet[0].toInt() and 0xFF) && it.sub() == index
                }
            }
            request(VendorFrame.pushNotificationShort(alertType))
        }
    }

    /**
     * Push the forecast. Returns false if nothing could be written — no
     * watch connected, or no vendor write characteristic — so the caller
     * can tell the user rather than silently reporting success.
     */
    fun pushWeather(days: List<DailyWeather>, cityName: String = ""): Boolean {
        if (days.isEmpty()) return false
        // 21-byte packet 1 only when the watch advertises support; otherwise
        // 19 bytes, which every watch accepts. The original sends the 21-byte
        // variant (and the forecast packets after it) on the BLE 5 channel.
        val extended = _capabilities.value.supportsExtendedWeather
        val packets = VendorFrame.weatherPackets(days = days, extended = extended)
        if (packets.isEmpty() || _phase.value == Phase.IDLE) return false
        scope.launch {
            // Each packet is acked with `CB <sub>`.
            for (p in packets) request(p, ble5 = extended) { it.opcode(0xCB) && it.sub() == (p[1].toInt() and 0xFF) }
            // Place name for the watch's weather screen (`CB FF`, always BLE 5).
            VendorFrame.weatherCityName(cityName)?.let { p ->
                request(p, ble5 = true) { it.opcode(0xCB) && it.sub() == 0xFF }
            }
        }
        return true
    }

    /**
     * Start the continuous HR test (`0xE5 0x11`).
     *
     * Sends *only* that command — `0xD6 0x02` is a separate dynamic/static
     * mode switch that cancels the test, so firing both makes the watch do
     * nothing at all.
     */
    fun startHrStream() = requestAsync(VendorFrame.startHrTest(), timeoutMs = SHORT_TIMEOUT_MS)

    fun stopHrStream() = requestAsync(VendorFrame.stopHrTest())
    fun startSpo2Measurement() = requestAsync(VendorFrame.startSpo2Test(), timeoutMs = SHORT_TIMEOUT_MS)
    fun startBpMeasurement() = requestAsync(VendorFrame.startBpTest())
    fun stopBpMeasurement() = requestAsync(VendorFrame.stopBpTest())
    fun findWatch() = requestAsync(VendorFrame.findBand())
    fun openShakeMode() = requestAsync(VendorFrame.openShakeMode())
    fun closeShakeMode() = requestAsync(VendorFrame.closeShakeMode())

    // ───────── Response dispatch ───────────────────────────────────────────

    private fun handleIncoming(bytes: ByteArray, charUuid: java.util.UUID) {
        rxFrames.tryEmit(bytes)
        val response = VendorResponseParser.parse(bytes)
        when (response) {
            is VendorResponse.Battery ->
                _live.update { it.copy(batteryPercent = response.percent) }

            is VendorResponse.Version ->
                _live.update { it.copy(firmwareVersion = response.raw) }

            is VendorResponse.DspVersion ->
                _live.update { it.copy(dspVersion = response.raw) }

            is VendorResponse.StepRealtime ->
                applyHourSteps(response.dateKey, response.hour, response.hourSteps)

            is VendorResponse.StepHistorical -> {
                if (response.isLastChunk) {
                    _historical.update { it + HistoricalRecord.EndMarker }
                } else {
                    // Historical chunks are the earlier hours of the same
                    // day(s), so they feed the same accumulator — that's
                    // what makes today's total complete rather than just
                    // the current hour.
                    val total = applyHourSteps(response.dateKey, response.hour, response.hourSteps)
                    _historical.update {
                        it + HistoricalRecord.Step(response.dateKey, response.hour, response.hourSteps, total)
                    }
                }
            }

            is VendorResponse.SleepHistorical -> {
                if (response.isLastChunk) {
                    _historical.update { it + HistoricalRecord.EndMarker }
                } else {
                    _historical.update {
                        it + HistoricalRecord.Sleep(
                            response.timestampUtcSeconds,
                            response.durationMinutes,
                            response.deepMinutes,
                            response.lightMinutes,
                        )
                    }
                }
            }

            is VendorResponse.HeartRateLive -> {
                lastHeartRateAt = System.currentTimeMillis()
                _live.update { it.copy(heartRateBpm = response.bpm) }
            }

            is VendorResponse.HeartRateHistorical ->
                _historical.update {
                    it + HistoricalRecord.HeartRate(
                        response.timestampUtcSeconds,
                        response.avgBpm, response.minBpm, response.maxBpm,
                    )
                }

            is VendorResponse.Oxygen ->
                // 0 means "test opened/closed" or an invalid reading — keep
                // the previous value rather than flashing a bogus 0%.
                if (response.percent > 0) {
                    _live.update { it.copy(spO2Percent = response.percent) }
                }

            is VendorResponse.BloodPressure ->
                // Start/stop/ack frames and invalid samples arrive as 0/0 —
                // only surface a real reading.
                if (response.systolic > 0 && response.diastolic > 0) {
                    _live.update {
                        it.copy(
                            systolicMmHg = response.systolic,
                            diastolicMmHg = response.diastolic,
                        )
                    }
                }

            is VendorResponse.PushDisplay ->
                _pushCapability.value = PushCapability(
                    mask1 = response.mask1,
                    mask2 = response.mask2,
                )

            is VendorResponse.Interface ->
                _live.update { it.copy(interfaceRaw = response.raw.toHex()) }

            is VendorResponse.Auth -> when (response) {
                VendorResponse.Auth.Success ->
                    if (_phase.value == Phase.AUTHENTICATING) scope.launch { finishAuth() }
                is VendorResponse.Auth.CaptchaRequired -> {
                    _phase.value = Phase.CAPTCHA_REQUIRED
                    _errors.update { it + "Watch requires pairing code: ${response.displayCode}" }
                }
                VendorResponse.Auth.ConfirmOnWatch ->
                    _errors.update { it + "Confirm the pairing request on the watch" }
                VendorResponse.Auth.Failed -> {
                    _phase.value = Phase.AUTH_FAILED
                    _errors.update { it + "Auth failed — check password" }
                }
            }

            is VendorResponse.SleepDayStart -> {
                flushSleepDay()
                sleepDateKey = response.dateKey
            }

            is VendorResponse.SleepDayData -> sleepBuffer.write(response.data)

            VendorResponse.SleepSyncEnd -> {
                flushSleepDay()
                _historical.update { it + HistoricalRecord.EndMarker }
            }

            is VendorResponse.HeartRate24h ->
                // One record per 2-hour frame keeps the list readable; the
                // window starts 110 minutes before the hour it ends at.
                if (response.bpms.isNotEmpty()) {
                    _historical.update {
                        it + HistoricalRecord.HeartRate(
                            response.windowEndUtcSeconds - 110 * 60,
                            response.bpms.average().toInt(),
                            response.bpms.min(),
                            response.bpms.max(),
                        )
                    }
                }

            VendorResponse.HeartRate24hEnd ->
                _historical.update { it + HistoricalRecord.EndMarker }

            is VendorResponse.TextPushAck -> Unit // awaited by pushNotification via rxFrames

            is VendorResponse.Ack -> Unit

            VendorResponse.EndOfStream -> Unit
            is VendorResponse.Unknown -> {
                // Surface the first few unknowns for debug but don't spam.
                if (_errors.value.size < 5) {
                    _errors.update { it + "Unknown vendor frame: ${bytes.toHex()}" }
                }
            }
        }
    }

    /**
     * Turn the buffered `32` chunks of one day into a sleep record.
     *
     * `BluetoothLeService.saveSleepBleData()`: 6-byte sections
     * `[start_hour, start_minute, state+1, ?, duration_hi, duration_lo]`
     * (duration in minutes); sections with an impossible start time are
     * skipped. The state numbering (0 deep, 1 light, 2 awake) is the usual
     * UTE convention and is not spelled out in the decompiled source.
     */
    private fun flushSleepDay() {
        val dateKey = sleepDateKey
        val data = sleepBuffer.toByteArray()
        sleepDateKey = null
        sleepBuffer.reset()
        if (dateKey == null || dateKey.length != 8) return

        var total = 0
        var deep = 0
        var light = 0
        var firstStartMinute = -1
        for (i in 0 until data.size / 6) {
            val o = i * 6
            val hour = data[o].toInt() and 0xFF
            val minute = data[o + 1].toInt() and 0xFF
            if (hour > 23 || minute > 59) continue
            val state = ((data[o + 2].toInt() and 0xFF) - 1).coerceAtLeast(0)
            val duration = ((data[o + 4].toInt() and 0xFF) shl 8) or (data[o + 5].toInt() and 0xFF)
            if (firstStartMinute < 0) firstStartMinute = hour * 60 + minute
            when (state) {
                0 -> { deep += duration; total += duration }
                1 -> { light += duration; total += duration }
                else -> Unit // awake — not counted as sleep
            }
        }
        if (total == 0) return
        val cal = Calendar.getInstance().apply {
            clear()
            set(
                dateKey.substring(0, 4).toInt(),
                dateKey.substring(4, 6).toInt() - 1,
                dateKey.substring(6, 8).toInt(),
                firstStartMinute / 60, firstStartMinute % 60, 0,
            )
        }
        _historical.update {
            it + HistoricalRecord.Sleep(cal.timeInMillis / 1000L, total, deep, light)
        }
    }

    fun submitCaptcha(code: String) {
        _phase.value = Phase.AUTHENTICATING
        scope.launch { request(VendorFrame.passwordAuth(code), timeoutMs = AUTH_FALLBACK_MS) }
    }

    // ───────── Capabilities ───────────────────────────────────────────────

    /**
     * Merge a capability blob into [capabilities]. The two characteristics
     * carry different word ranges (1-7 vs 8-13) plus, on the BLE 5 one, the
     * watch's max payload length — so they accumulate rather than replace.
     */
    private fun applyCapabilityBlob(bytes: ByteArray, charUuid: java.util.UUID) {
        val isBle5 = charUuid == VendorGattSpec.WRITE_BLE5
        val newWords = if (isBle5) {
            VendorCapabilities.parseBle5Blob(bytes)
        } else {
            VendorCapabilities.parseLegacyBlob(bytes)
        }
        // The max length is valid even when the rest of the blob isn't the
        // expected 20 bytes.
        val maxLen = if (isBle5) VendorCapabilities.parseMaxCommunicationLength(bytes) else null
        if (newWords == null && maxLen == null) return
        _capabilities.update { current ->
            current.copy(
                words = current.words + (newWords ?: emptyMap()),
                maxCommunicationLength = maxLen ?: current.maxCommunicationLength,
            )
        }
    }

    // ───────── Step accumulation ──────────────────────────────────────────

    /**
     * `"YYYYMMDD"` → (hour → steps in that hour).
     *
     * The watch never sends a daily total: each `0xB1`/`0xB2` packet is one
     * hour's bucket, and the day's figure is their sum
     * (`mStepCount = tempSteps + currentHourStep` in the original SDK).
     * Buckets are *replaced*, not added, because the watch re-sends the
     * current hour as it grows.
     */
    private val stepLock = Any()
    private val stepBuckets = HashMap<String, HashMap<Int, Int>>()

    /** Record one hour's steps and return the new day total for [dateKey]. */
    private fun applyHourSteps(dateKey: String, hour: Int, steps: Int): Int {
        val total = synchronized(stepLock) {
            val day = stepBuckets.getOrPut(dateKey) { HashMap() }
            day[hour] = steps
            day.values.sum()
        }
        if (dateKey == todayKey()) {
            val meters = estimateDistanceMeters(total)
            _live.update {
                it.copy(
                    steps = total,
                    distanceMeters = meters,
                    caloriesKcal = estimateCalories(meters),
                )
            }
        }
        return total
    }

    private fun clearStepBuckets() {
        synchronized(stepLock) { stepBuckets.clear() }
    }

    private fun todayKey(): String {
        val c = Calendar.getInstance()
        return "%04d%02d%02d".format(
            c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH),
        )
    }

    /**
     * Distance is computed on the phone — the watch only sends step counts.
     * `PedometerUtils.calculateDistance()`: `steps × (height × 0.418) / 100000`
     * kilometres, i.e. a 0.418 × height stride in cm.
     */
    private fun estimateDistanceMeters(steps: Int): Int =
        (steps * (PROFILE_HEIGHT_CM * 0.418f) / 100f).toInt()

    /**
     * `PedometerUtils.calculateCaloriesForDistance(meters, 1)`:
     * `weight × 0.708 × meters / 1000` kcal.
     */
    private fun estimateCalories(meters: Int): Int =
        (PROFILE_WEIGHT_KG * 0.708f * meters / 1000f).toInt()

    // ───────── Time helpers ───────────────────────────────────────────────

    private fun currentYear() = Calendar.getInstance().get(Calendar.YEAR)
    private fun currentMonth() = Calendar.getInstance().get(Calendar.MONTH) + 1
    private fun currentDay() = Calendar.getInstance().get(Calendar.DAY_OF_MONTH)
    private fun currentHour() = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    private fun currentMinute() = Calendar.getInstance().get(Calendar.MINUTE)
    private fun currentSecond() = Calendar.getInstance().get(Calendar.SECOND)

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    companion object {
        private const val PREFS_NAME = "colorfit_vendor_prefs"
        private const val PREF_LAST_MAC = "last_connected_mac"

        /**
         * Profile pushed to the watch, and used for the phone-side
         * distance/calorie estimates so both agree. TODO: make these
         * user-editable in Settings (Phase 3).
         */
        const val PROFILE_HEIGHT_CM = 170
        const val PROFILE_WEIGHT_KG = 70f
        const val PROFILE_AGE = 30
        const val PROFILE_IS_MALE = true

        /** Cheap watches power-save aggressively; ping every 30s. */
        private const val HEARTBEAT_INTERVAL_MS = 30_000L

        /**
         * How long to wait for the watch's auth reply (`33 04 01` or `0xD5`)
         * before assuming it needs none and proceeding with the sync.
         */
        private const val AUTH_FALLBACK_MS = 1_500L

        /**
         * How long [request] waits for a reply. The watch answered within
         * ~0.1 s in captured traffic (~0.6 s per notification chunk); the
         * original app's command timeout is 3 s.
         */
        private const val COMMAND_TIMEOUT_MS = 3_000L

        /**
         * For commands whose first reply only comes with a measurement
         * (`E5 11` live HR took ~10 s): don't block the queue that long.
         */
        private const val SHORT_TIMEOUT_MS = 800L

        /** For history syncs, which stream frames until their end marker. */
        private const val STREAM_TIMEOUT_MS = 15_000L

        /**
         * Delay between an unexpected disconnect and a reconnect attempt.
         * 10s gives the watch enough time to settle after a power-save
         * event without hammering it on every transient drop. The first
         * attempt on app launch also uses this delay so the UI has
         * time to flip into the "Reconnecting" state cleanly.
         */
        private const val AUTO_RECONNECT_DELAY_MS = 10_000L

        /**
         * If we haven't seen an HR packet in this long, restart the HR
         * stream on the next heartbeat tick.
         */
        private const val HR_RESTART_THRESHOLD_MS = 60_000L

        /**
         * Threshold for considering the HR stream "live" (used by the
         * UI to decide whether to show "streaming" or "stale").
         */
        private const val HR_STALE_THRESHOLD_MS = 15_000L
    }
}

// ───────── State types ─────────────────────────────────────────────────────

enum class Phase {
    IDLE,
    AUTHENTICATING,
    CAPTCHA_REQUIRED,
    AUTH_FAILED,
    SYNCING,
    READY,
}

data class LiveVendorData(
    val batteryPercent: Int? = null,
    /** BLE firmware version reported over the vendor channel (`0xA1`). */
    val firmwareVersion: String? = null,
    /** DSP firmware version (`0xA1 0x01`), when the watch supports it. */
    val dspVersion: String? = null,
    val steps: Int? = null,
    val distanceMeters: Int? = null,
    val caloriesKcal: Int? = null,
    val heartRateBpm: Int? = null,
    val spO2Percent: Int? = null,
    val systolicMmHg: Int? = null,
    val diastolicMmHg: Int? = null,
    val interfaceRaw: String? = null,
)

/** Bitfield of which apps the watch is currently configured to mirror. */
data class PushCapability(
    val mask1: Int,
    val mask2: Int,
) {
    fun accepts(appBit: Int): Boolean = (mask1 and appBit) != 0 || (mask2 and appBit) != 0

    companion object {
        val UNKNOWN = PushCapability(0, 0)
    }
}

sealed interface HistoricalRecord {
    /**
     * One hour's step bucket. [dayTotal] is the running sum for
     * [dateKey] ("YYYYMMDD") across every hour received so far.
     */
    data class Step(
        val dateKey: String,
        val hour: Int,
        val hourSteps: Int,
        val dayTotal: Int,
    ) : HistoricalRecord
    data class Sleep(
        val timestampUtcSeconds: Long,
        val durationMinutes: Int,
        val deepMinutes: Int,
        val lightMinutes: Int,
    ) : HistoricalRecord
    data class HeartRate(
        val timestampUtcSeconds: Long,
        val avgBpm: Int,
        val minBpm: Int,
        val maxBpm: Int,
    ) : HistoricalRecord
    data object EndMarker : HistoricalRecord
}

data class VendorState(
    val phase: Phase = Phase.IDLE,
    val live: LiveVendorData = LiveVendorData(),
    val historical: List<HistoricalRecord> = emptyList(),
    val pushCapability: PushCapability = PushCapability.UNKNOWN,
    val errors: List<String> = emptyList(),
)
