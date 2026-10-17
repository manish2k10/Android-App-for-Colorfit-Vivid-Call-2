package com.colorfit.band

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.colorfit.band.ble.BandGatt
import com.colorfit.band.ble.BandPrefs
import com.colorfit.band.protocol.BandCommands
import com.colorfit.band.protocol.BandFrame
import com.colorfit.band.protocol.BandMessageType
import com.colorfit.band.protocol.BandParsers
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The band's protocol session: the paced command queue, the handshake on
 * connect, reply parsing, measurements, history and reconnects.
 *
 * Commands go out one frame at a time with the pause NoiseFit Track's send
 * queue uses (`i9/f.j`); replies are handled whenever they arrive — the band
 * also pushes results (heart rate, SpO₂, blood pressure) for measurements
 * started on the band itself.
 */
@Singleton
class BandSession @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gatt: BandGatt,
) : BandGatt.Listener {

    enum class Measurement { HEART_RATE, BLOOD_PRESSURE, BLOOD_OXYGEN }

    data class State(
        val link: BandGatt.Link = BandGatt.Link.Idle,
        /** Notifications are on and the handshake has been sent. */
        val ready: Boolean = false,
        val bandName: String? = null,
        val bandMac: String? = null,
        val userDisconnected: Boolean = false,
        val battery: Int? = null,
        val stepsToday: BandParsers.Steps? = null,
        val stepsYesterday: BandParsers.Steps? = null,
        val stepsDayBefore: BandParsers.Steps? = null,
        val goal: Int? = null,
        val firmware: String? = null,
        val manufacturer: String? = null,
        val model: String? = null,
        val measuring: Measurement? = null,
        /** Readings streamed on `2A37` during a measurement. */
        val liveHeartRate: Int? = null,
        val lastHeartRate: Int? = null,
        val bloodPressure: Pair<Int, Int>? = null,
        val bloodOxygen: Int? = null,
        val sleepLastNight: BandParsers.SleepNight? = null,
        val sleepNightBefore: BandParsers.SleepNight? = null,
        val sleepTwoNightsAgo: BandParsers.SleepNight? = null,
        /** Today's 288 five-minute readings (0 = none), once all four packets are in. */
        val heartRateToday: List<Int> = emptyList(),
        /** Auto heart-rate interval in 5-minute steps (0 = off), as the band reports it. */
        val autoHeartRate: Int? = null,
        val lastSyncAt: Long? = null,
        val errors: List<String> = emptyList(),
    ) {
        val connected: Boolean get() = link is BandGatt.Link.Connected
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(
        State(
            bandMac = BandPrefs.mac(context),
            bandName = BandPrefs.name(context),
            userDisconnected = BandPrefs.userDisconnected(context),
        ),
    )
    val state: StateFlow<State> = _state.asStateFlow()

    /** Fires each time a connection finishes its handshake (weather push hooks on). */
    private val _readyEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val readyEvents: SharedFlow<Unit> = _readyEvents.asSharedFlow()

    private val outbox = Channel<ByteArray>(Channel.UNLIMITED)
    private var reconnectJob: Job? = null
    private var measureTimeout: Job? = null

    private val heartRateLock = Any()
    private val heartRatePackets = arrayOfNulls<List<Int>>(4)

    init {
        gatt.listener = this
        scope.launch {
            for (frame in outbox) {
                gatt.writeFrame(frame)
                delay(pauseAfter(frame))
            }
        }
        scope.launch { gatt.link.collect(::onLink) }
        registerBluetoothStateReceiver()
        if (BandPrefs.shouldStayConnected(context)) scheduleReconnect(LAUNCH_RECONNECT_DELAY_MS)
        scope.launch { refreshLoop() }
    }

    // ───────── User actions ────────────────────────────────────────────────

    /** The user picked a band from the scan list. */
    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        val name = runCatching { device.name }.getOrNull()
        BandPrefs.setUserDisconnected(context, false)
        BandPrefs.remember(context, device.address, name)
        reconnectJob?.cancel()
        _state.update {
            it.copy(userDisconnected = false, bandMac = device.address, bandName = name ?: it.bandName, errors = emptyList())
        }
        gatt.connect(device, autoConnect = false)
    }

    /** Disconnect and stay disconnected — across restarts — until the user connects again. */
    fun disconnect() {
        BandPrefs.setUserDisconnected(context, true)
        reconnectJob?.cancel()
        _state.update { it.copy(userDisconnected = true) }
        if (gatt.link.value is BandGatt.Link.Connected) gatt.disconnect() else gatt.close()
    }

    /** Try the remembered band straight away (direct connect, not the background wait). */
    fun reconnectNow() {
        BandPrefs.setUserDisconnected(context, false)
        _state.update { it.copy(userDisconnected = false) }
        reconnectJob?.cancel()
        reconnect(direct = true)
    }

    fun forget() {
        BandPrefs.forget(context)
        reconnectJob?.cancel()
        gatt.close()
        _state.value = State()
    }

    fun syncNow() {
        if (!_state.value.ready) return
        gatt.readSteps()
        gatt.readBattery()
        syncHistory()
    }

    fun findBand() = sendIfReady(BandCommands.findBand())

    fun setAutoHeartRate(steps: Int) {
        if (!_state.value.ready) return
        send(BandCommands.setAutoHeartRate(steps))
        send(BandCommands.queryAutoHeartRate())
    }

    fun measureHeartRate() = startMeasurement(Measurement.HEART_RATE, BandCommands.measureHeartRate(true))
    fun measureBloodPressure() = startMeasurement(Measurement.BLOOD_PRESSURE, BandCommands.measureBloodPressure(true))
    fun measureBloodOxygen() = startMeasurement(Measurement.BLOOD_OXYGEN, BandCommands.measureBloodOxygen(true))

    fun stopMeasurement() {
        val kind = _state.value.measuring ?: return
        measureTimeout?.cancel()
        send(stopCommand(kind))
        _state.update { it.copy(measuring = null) }
    }

    /**
     * Send a notification. [text] is already prepared by
     * [com.colorfit.band.protocol.BandText]; [type] is a [BandMessageType].
     */
    fun pushMessage(type: Int, text: ByteArray): Boolean {
        val s = _state.value
        if (!s.ready || text.isEmpty()) return false
        send(BandCommands.message(BandMessageType.wireType(type, BandMessageType.firmwareCode(s.firmware)), text))
        return true
    }

    /** Weather in the order NoiseFit Track sends it: today twice, city, forecast, location. */
    fun pushWeather(
        weatherId: Int,
        tempC: Int,
        sunriseMinutes: Int?,
        sunsetMinutes: Int?,
        days: List<BandCommands.Day>,
        city: String,
        latitude: Double,
        longitude: Double,
    ): Boolean {
        if (!_state.value.ready) return false
        send(BandCommands.weatherTodayLegacy(weatherId, tempC, city))
        send(BandCommands.weatherToday(weatherId, tempC, city, sunriseMinutes, sunsetMinutes))
        if (city.isNotBlank()) send(BandCommands.city(city))
        if (days.isNotEmpty()) send(BandCommands.forecast(days))
        send(BandCommands.location(latitude, longitude))
        return true
    }

    // ───────── Connection lifecycle ────────────────────────────────────────

    private fun onLink(link: BandGatt.Link) {
        when (link) {
            is BandGatt.Link.Connected -> {
                BandPrefs.remember(context, link.address, link.name ?: _state.value.bandName)
                _state.update { it.copy(link = link, bandMac = link.address, bandName = link.name ?: it.bandName) }
            }
            is BandGatt.Link.Connecting -> _state.update { it.copy(link = link) }
            BandGatt.Link.Disconnected, BandGatt.Link.Idle -> {
                // Anything still queued was meant for the old link.
                while (outbox.tryReceive().isSuccess) Unit
                measureTimeout?.cancel()
                _state.update { it.copy(link = link, ready = false, measuring = null, liveHeartRate = null) }
                if (BandPrefs.shouldStayConnected(context)) scheduleReconnect(RECONNECT_DELAY_MS)
            }
        }
    }

    private fun scheduleReconnect(delayMs: Long) {
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            delay(delayMs)
            reconnect(direct = false)
        }
    }

    @SuppressLint("MissingPermission")
    private fun reconnect(direct: Boolean) {
        val mac = BandPrefs.mac(context) ?: return
        if (BandPrefs.userDisconnected(context)) return
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter ?: return
        // With Bluetooth off there's nothing to do; the state receiver below
        // tries again as soon as it comes back on.
        if (!adapter.isEnabled) return
        when (gatt.link.value) {
            is BandGatt.Link.Connected -> return
            // A background connect is already waiting for the band.
            is BandGatt.Link.Connecting -> if (!direct) return
            else -> Unit
        }
        val device = runCatching { adapter.getRemoteDevice(mac) }.getOrNull() ?: return
        gatt.connect(device, autoConnect = !direct)
    }

    /**
     * Android doesn't reliably report a disconnect when Bluetooth is switched
     * off, so drop the client ourselves, and reconnect once it is back on.
     */
    private fun registerBluetoothStateReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                    BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> gatt.close()
                    BluetoothAdapter.STATE_ON -> if (BandPrefs.shouldStayConnected(context)) {
                        reconnectJob?.cancel()
                        scheduleReconnect(BLUETOOTH_ON_DELAY_MS)
                    }
                }
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    // ───────── Band → phone ────────────────────────────────────────────────

    override fun onReady() {
        _state.update { it.copy(ready = true, lastSyncAt = System.currentTimeMillis()) }
        send(BandCommands.setTime())
        send(BandCommands.timeZone())
        send(BandCommands.queryGoal())
        send(BandCommands.queryAutoHeartRate())
        syncHistory()
        _readyEvents.tryEmit(Unit)
    }

    override fun onFrame(frame: BandFrame.Frame) {
        val p = frame.payload
        when (frame.cmd) {
            BandCommands.QUERY_GOAL -> BandParsers.goal(p)?.let { g -> _state.update { it.copy(goal = g) } }
            BandCommands.QUERY_AUTO_HEART_RATE -> p.firstOrNull()?.let { v ->
                _state.update { it.copy(autoHeartRate = v.toInt()) }
            }
            BandCommands.SLEEP_TONIGHT -> _state.update {
                it.copy(sleepLastNight = BandParsers.sleep(p, untilNowMinute = nowMinute()))
            }
            BandCommands.HISTORY -> onHistory(p)
            BandCommands.HEART_RATE_SERIES -> onHeartRatePacket(p)
            BandCommands.HEART_RATE -> BandParsers.singleValue(p)?.let { bpm ->
                finish(Measurement.HEART_RATE) { it.copy(lastHeartRate = bpm) }
            }
            BandCommands.BLOOD_PRESSURE -> BandParsers.bloodPressure(p)?.let { bp ->
                finish(Measurement.BLOOD_PRESSURE) { it.copy(bloodPressure = bp) }
            }
            BandCommands.BLOOD_OXYGEN -> BandParsers.singleValue(p)?.let { pct ->
                finish(Measurement.BLOOD_OXYGEN) { it.copy(bloodOxygen = pct) }
            }
            else -> Timber.tag(TAG).v("frame %02x (%d bytes) not used", frame.cmd, p.size)
        }
    }

    override fun onSteps(value: ByteArray) {
        BandParsers.steps(value)?.let { s -> _state.update { it.copy(stepsToday = s) } }
    }

    override fun onHeartRate(bpm: Int) {
        _state.update { it.copy(liveHeartRate = bpm) }
    }

    override fun onBattery(percent: Int) {
        if (percent in 0..100) _state.update { it.copy(battery = percent) }
    }

    override fun onDeviceInfo(kind: BandGatt.DeviceInfo, value: String) {
        _state.update {
            when (kind) {
                BandGatt.DeviceInfo.MANUFACTURER -> it.copy(manufacturer = value)
                BandGatt.DeviceInfo.MODEL -> it.copy(model = value)
                BandGatt.DeviceInfo.FIRMWARE -> it.copy(firmware = value)
            }
        }
    }

    override fun onUnsupportedDevice() {
        val message = "That device isn't a ColorFit (MOYOUNG) band — it has no FEEA service."
        BandPrefs.forget(context)
        reconnectJob?.cancel()
        gatt.close()
        _state.value = State(errors = listOf(message))
    }

    override fun onError(message: String) {
        _state.update { it.copy(errors = (it.errors + message).takeLast(MAX_ERRORS)) }
    }

    /** `0x33 [day]`: steps for days 0–2, sleep for 3–4 (`i9/a.j2`). */
    private fun onHistory(p: ByteArray) {
        if (p.isEmpty()) return
        val rest = p.copyOfRange(1, p.size)
        when (p[0].toInt()) {
            0 -> BandParsers.steps(rest)?.let { s -> _state.update { it.copy(stepsToday = s) } }
            BandCommands.HISTORY_STEPS_YESTERDAY -> _state.update { it.copy(stepsYesterday = BandParsers.steps(rest)) }
            BandCommands.HISTORY_STEPS_DAY_BEFORE -> _state.update { it.copy(stepsDayBefore = BandParsers.steps(rest)) }
            BandCommands.HISTORY_SLEEP_YESTERDAY -> _state.update { it.copy(sleepNightBefore = BandParsers.sleep(rest)) }
            BandCommands.HISTORY_SLEEP_DAY_BEFORE -> _state.update { it.copy(sleepTwoNightsAgo = BandParsers.sleep(rest)) }
        }
    }

    /** Today's series comes in four packets; ask for the next as each arrives (`a9/j0.w`). */
    private fun onHeartRatePacket(p: ByteArray) {
        val packet = BandParsers.heartRateSeries(p) ?: return
        if (packet.index !in 0..3) return
        val complete: List<Int>? = synchronized(heartRateLock) {
            heartRatePackets[packet.index] = packet.bpm
            if (packet.index == 3) heartRatePackets.filterNotNull().flatten() else null
        }
        if (complete != null) {
            _state.update { it.copy(heartRateToday = complete) }
        } else {
            send(BandCommands.heartRateSeries(packet.index + 1))
        }
    }

    private fun syncHistory() {
        synchronized(heartRateLock) { heartRatePackets.fill(null) }
        send(BandCommands.sleepTonight())
        send(BandCommands.history(BandCommands.HISTORY_SLEEP_YESTERDAY))
        send(BandCommands.history(BandCommands.HISTORY_SLEEP_DAY_BEFORE))
        send(BandCommands.history(BandCommands.HISTORY_STEPS_YESTERDAY))
        send(BandCommands.history(BandCommands.HISTORY_STEPS_DAY_BEFORE))
        send(BandCommands.heartRateSeries(0))
        _state.update { it.copy(lastSyncAt = System.currentTimeMillis()) }
    }

    // ───────── Measurements ────────────────────────────────────────────────

    private fun startMeasurement(kind: Measurement, start: ByteArray) {
        val current = _state.value
        if (!current.ready) return
        current.measuring?.let { send(stopCommand(it)) }
        _state.update { it.copy(measuring = kind, liveHeartRate = null) }
        send(start)
        measureTimeout?.cancel()
        measureTimeout = scope.launch {
            delay(MEASURE_TIMEOUT_MS)
            if (_state.value.measuring == kind) stopMeasurement()
        }
    }

    /** A result arrived; clear the spinner if it was for the running measurement. */
    private fun finish(kind: Measurement, apply: (State) -> State) {
        if (_state.value.measuring == kind) measureTimeout?.cancel()
        _state.update { apply(it).copy(measuring = it.measuring.takeUnless { m -> m == kind }) }
    }

    private fun stopCommand(kind: Measurement): ByteArray = when (kind) {
        Measurement.HEART_RATE -> BandCommands.measureHeartRate(false)
        Measurement.BLOOD_PRESSURE -> BandCommands.measureBloodPressure(false)
        Measurement.BLOOD_OXYGEN -> BandCommands.measureBloodOxygen(false)
    }

    // ───────── Plumbing ────────────────────────────────────────────────────

    private fun send(frame: ByteArray) {
        outbox.trySend(frame)
    }

    private fun sendIfReady(frame: ByteArray) {
        if (_state.value.ready) send(frame)
    }

    /** Steps and battery every 10 minutes; the full history sync hourly. */
    private suspend fun refreshLoop() {
        var tick = 0
        while (true) {
            delay(REFRESH_INTERVAL_MS)
            if (!_state.value.ready) continue
            tick++
            gatt.readSteps()
            gatt.readBattery()
            if (tick % HISTORY_EVERY_TICKS == 0) syncHistory()
        }
    }

    /** `i9/f.j`: how long the original waits after each command before the next. */
    private fun pauseAfter(frame: ByteArray): Long = when (frame.getOrNull(4)?.toInt()?.and(0xFF)) {
        0x11, 0x12, 0x3B, 0x3C, 0x32, 0x34, 0x35, 0x36, 0x37 -> 500L
        0x33 -> 1_000L
        0x67 -> 50L
        else -> 200L
    }

    private fun nowMinute(): Int = Calendar.getInstance().let {
        it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE)
    }

    companion object {
        private const val TAG = "BandSession"
        private const val RECONNECT_DELAY_MS = 10_000L
        private const val LAUNCH_RECONNECT_DELAY_MS = 2_000L
        private const val BLUETOOTH_ON_DELAY_MS = 2_000L
        private const val MEASURE_TIMEOUT_MS = 75_000L
        private const val REFRESH_INTERVAL_MS = 10 * 60_000L
        private const val HISTORY_EVERY_TICKS = 6
        private const val MAX_ERRORS = 5
    }
}
