package com.colorfit.band.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.colorfit.band.protocol.BandFrame
import com.colorfit.band.protocol.BandParsers
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the single GATT connection to the band.
 *
 * Android allows one GATT operation in flight per client, so every read,
 * write and descriptor write goes through one queue and starts only when the
 * previous one's callback has fired (or its watchdog gave up).
 *
 * Bring-up mirrors NoiseFit Track's session: enable notifications on `FEE3`
 * (framed replies), `2A37` (heart rate) and `FEE1` (steps), read the device
 * strings and battery, enable battery notifications, read today's steps, then
 * report [Listener.onReady].
 */
@Singleton
class BandGatt @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    sealed interface Link {
        data object Idle : Link
        data class Connecting(val address: String) : Link
        data class Connected(val address: String, val name: String?) : Link
        data object Disconnected : Link
    }

    enum class DeviceInfo { MANUFACTURER, MODEL, FIRMWARE }

    interface Listener {
        fun onReady()
        fun onFrame(frame: BandFrame.Frame)
        fun onSteps(value: ByteArray)
        fun onHeartRate(bpm: Int)
        fun onBattery(percent: Int)
        fun onDeviceInfo(kind: DeviceInfo, value: String)
        fun onUnsupportedDevice()
        fun onError(message: String)
    }

    private val _link = MutableStateFlow<Link>(Link.Idle)
    val link: StateFlow<Link> = _link.asStateFlow()

    @Volatile
    var listener: Listener? = null

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var writeChar: BluetoothGattCharacteristic? = null
    @Volatile private var stepsChar: BluetoothGattCharacteristic? = null
    @Volatile private var batteryChar: BluetoothGattCharacteristic? = null
    private val reassembler = BandFrame.Reassembler()

    /** Discovery delay and watchdog; kept apart from the op watchdogs. */
    private val mainHandler = Handler(Looper.getMainLooper())

    // ───────── Connection ──────────────────────────────────────────────────

    /**
     * @param autoConnect false for a direct connect the user asked for; true
     *   for background reconnects, where the stack waits for the band to come
     *   back instead of timing out.
     */
    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice, autoConnect: Boolean) {
        if (!hasConnectPermission()) {
            listener?.onError("Missing the Bluetooth \"Nearby devices\" permission")
            return
        }
        closeGatt()
        _link.value = Link.Connecting(device.address)
        Timber.tag(TAG).i("connect %s autoConnect=%s", device.address, autoConnect)
        gatt = device.connectGatt(context, autoConnect, callback, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        gatt?.let { runCatching { it.disconnect() } }
    }

    /** Drop the client outright (no callback follows) and go idle. */
    fun close() {
        closeGatt()
        _link.value = Link.Idle
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        resetOps()
        mainHandler.removeCallbacksAndMessages(null)
        writeChar = null
        stepsChar = null
        batteryChar = null
        gatt?.let { runCatching { it.disconnect(); it.close() } }
        gatt = null
    }

    // ───────── Public operations ───────────────────────────────────────────

    /**
     * Write one frame to `FEE2` in 20-byte pieces and wait until the last one
     * has gone out. Returns false if there is no link or a piece failed.
     */
    suspend fun writeFrame(frame: ByteArray): Boolean {
        val c = writeChar ?: return false
        val packets = BandFrame.packets(frame)
        val done = CompletableDeferred<Boolean>()
        val ok = AtomicBoolean(true)
        Timber.tag(TAG).d("TX %s", frame.toHex())
        packets.forEachIndexed { i, packet ->
            enqueue("write ${packet.size}", onDone = { success ->
                if (!success) ok.set(false)
                if (i == packets.lastIndex) done.complete(ok.get())
            }) { issueWrite(c, packet) }
        }
        return withTimeoutOrNull(FRAME_TIMEOUT_MS) { done.await() } ?: false
    }

    fun readSteps() {
        stepsChar?.let { c -> enqueue("read steps") { issueRead(c) } }
    }

    fun readBattery() {
        batteryChar?.let { c -> enqueue("read battery") { issueRead(c) } }
    }

    // ───────── Serialized GATT operation queue ─────────────────────────────

    private class GattOp(val label: String, val onDone: ((Boolean) -> Unit)?, val issue: () -> Boolean)

    private val opLock = Any()
    private val opQueue = ArrayDeque<GattOp>()
    private var opInFlight: GattOp? = null

    /** Bumped on every completion/timeout/reset so a stale watchdog can't fire. */
    private var opGeneration = 0
    private val opHandler = Handler(Looper.getMainLooper())

    private fun enqueue(label: String, onDone: ((Boolean) -> Unit)? = null, issue: () -> Boolean) {
        synchronized(opLock) { opQueue.addLast(GattOp(label, onDone, issue)) }
        drain()
    }

    /** A step that needs no GATT callback, run in queue order. */
    private fun enqueueAction(label: String, action: () -> Unit) =
        enqueue(label) { action(); false }

    private fun drain() {
        while (true) {
            val gen: Int
            val op: GattOp
            synchronized(opLock) {
                if (opInFlight != null) return
                op = opQueue.pollFirst() ?: return
                opInFlight = op
                gen = ++opGeneration
            }
            val accepted = runCatching { op.issue() }
                .onFailure { Timber.tag(TAG).w(it, "op %s threw", op.label) }
                .getOrDefault(false)
            if (accepted) {
                opHandler.postDelayed({ onOpTimeout(gen) }, OP_TIMEOUT_MS)
                return
            }
            synchronized(opLock) { if (opGeneration == gen) opInFlight = null }
            op.onDone?.invoke(false)
        }
    }

    private fun completeOp(success: Boolean) {
        val op: GattOp?
        synchronized(opLock) {
            op = opInFlight
            opInFlight = null
            opGeneration++
        }
        op?.onDone?.invoke(success)
        drain()
    }

    private fun onOpTimeout(gen: Int) {
        val op: GattOp?
        synchronized(opLock) {
            if (opGeneration != gen || opInFlight == null) return
            op = opInFlight
            opInFlight = null
            opGeneration++
        }
        Timber.tag(TAG).w("op %s timed out", op?.label)
        op?.onDone?.invoke(false)
        drain()
    }

    private fun resetOps() {
        opHandler.removeCallbacksAndMessages(null)
        val dropped: List<GattOp>
        synchronized(opLock) {
            dropped = opQueue.toList() + listOfNotNull(opInFlight)
            opQueue.clear()
            opInFlight = null
            opGeneration++
        }
        dropped.forEach { it.onDone?.invoke(false) }
    }

    // ───────── Individual GATT operations ──────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun issueWrite(c: BluetoothGattCharacteristic, payload: ByteArray): Boolean {
        val g = gatt ?: return false
        // FEE2 on this band only advertises "write"; NoiseFit Track also used
        // write-without-response and the band took both.
        val type = if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(c, payload, type) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                c.writeType = type
                c.value = payload
                g.writeCharacteristic(c)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun issueRead(c: BluetoothGattCharacteristic): Boolean = gatt?.readCharacteristic(c) ?: false

    @SuppressLint("MissingPermission")
    private fun issueEnableNotify(c: BluetoothGattCharacteristic): Boolean {
        val g = gatt ?: return false
        if (!g.setCharacteristicNotification(c, true)) return false
        val cccd = c.getDescriptor(CCCD) ?: return false
        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(cccd, value) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                cccd.value = value
                g.writeDescriptor(cccd)
            }
        }
    }

    // ───────── Bring-up ────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun startDiscovery(g: BluetoothGatt) {
        if (gatt !== g) return
        val started = runCatching { g.discoverServices() }.getOrDefault(false)
        if (!started) {
            failLink("Service discovery could not start")
            return
        }
        mainHandler.postDelayed(discoveryWatchdog, DISCOVERY_TIMEOUT_MS)
    }

    private val discoveryWatchdog = Runnable { failLink("Service discovery never finished") }

    /**
     * Without services there is nothing to talk to, so drop the link and let
     * the session's reconnect try again rather than sit "connected" with no data.
     */
    private fun failLink(message: String) {
        Timber.tag(TAG).w(message)
        listener?.onError(message)
        disconnect()
    }

    private fun bringUp(g: BluetoothGatt) {
        val band = g.getService(SERVICE_BAND)
        val write = band?.getCharacteristic(CHAR_COMMAND)
        val replies = band?.getCharacteristic(CHAR_REPLY)
        if (write == null || replies == null) {
            Timber.tag(TAG).w("no FEEA service: %s", g.services.joinToString { it.uuid.toString() })
            listener?.onUnsupportedDevice()
            return
        }
        writeChar = write
        stepsChar = band.getCharacteristic(CHAR_STEPS)
        batteryChar = g.getService(SERVICE_BATTERY)?.getCharacteristic(CHAR_BATTERY)
        val heartRate = g.getService(SERVICE_HEART_RATE)?.getCharacteristic(CHAR_HEART_RATE)
        val info = g.getService(SERVICE_DEVICE_INFO)

        enqueue("notify FEE3") { issueEnableNotify(replies) }
        heartRate?.let { c -> enqueue("notify 2A37") { issueEnableNotify(c) } }
        stepsChar?.let { c -> enqueue("notify FEE1") { issueEnableNotify(c) } }
        listOf(CHAR_MANUFACTURER, CHAR_FIRMWARE, CHAR_MODEL).forEach { uuid ->
            info?.getCharacteristic(uuid)?.let { c -> enqueue("read $uuid") { issueRead(c) } }
        }
        batteryChar?.let { c ->
            enqueue("read battery") { issueRead(c) }
            enqueue("notify battery") { issueEnableNotify(c) }
        }
        readSteps()
        enqueueAction("ready") { listener?.onReady() }
    }

    // ───────── GATT callback ───────────────────────────────────────────────

    private val callback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            Timber.tag(TAG).i("connection state=%d status=%d", newState, status)
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                resetOps()
                reassembler.reset()
                _link.value = Link.Connected(g.device.address, g.device.name)
                // A short pause before discovery, as the vendor apps do: the
                // bonded band re-encrypts the link right after connecting.
                mainHandler.postDelayed({ startDiscovery(g) }, DISCOVERY_DELAY_MS)
            } else {
                resetOps()
                mainHandler.removeCallbacksAndMessages(null)
                writeChar = null
                stepsChar = null
                batteryChar = null
                runCatching { g.close() }
                if (gatt === g) gatt = null
                _link.value = Link.Disconnected
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            mainHandler.removeCallbacks(discoveryWatchdog)
            if (status != BluetoothGatt.GATT_SUCCESS) {
                failLink("Service discovery failed (status $status)")
                return
            }
            bringUp(g)
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) handleRead(c.uuid, value)
            completeOp(status == BluetoothGatt.GATT_SUCCESS)
        }

        @Deprecated("Deprecated in API 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) handleRead(c.uuid, c.value ?: ByteArray(0))
            completeOp(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            handleNotify(c.uuid, value)
        }

        @Deprecated("Deprecated in API 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            handleNotify(c.uuid, c.value ?: ByteArray(0))
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            completeOp(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                listener?.onError("Couldn't enable notifications on ${d.characteristic.uuid} (status $status)")
            }
            completeOp(status == BluetoothGatt.GATT_SUCCESS)
        }
    }

    private fun handleRead(uuid: UUID, value: ByteArray) {
        Timber.tag(TAG).d("READ %s %s", uuid.short(), value.toHex())
        when (uuid) {
            CHAR_BATTERY -> value.firstOrNull()?.let { listener?.onBattery(it.toInt() and 0xFF) }
            CHAR_STEPS -> listener?.onSteps(value)
            CHAR_MANUFACTURER -> listener?.onDeviceInfo(DeviceInfo.MANUFACTURER, value.asText())
            CHAR_MODEL -> listener?.onDeviceInfo(DeviceInfo.MODEL, value.asText())
            CHAR_FIRMWARE -> listener?.onDeviceInfo(DeviceInfo.FIRMWARE, value.asText())
        }
    }

    private fun handleNotify(uuid: UUID, value: ByteArray) {
        Timber.tag(TAG).d("RX %s %s", uuid.short(), value.toHex())
        when (uuid) {
            CHAR_REPLY -> reassembler.feed(value)?.let { listener?.onFrame(it) }
            CHAR_STEPS -> listener?.onSteps(value)
            CHAR_HEART_RATE -> BandParsers.standardHeartRate(value)?.let { listener?.onHeartRate(it) }
            CHAR_BATTERY -> value.firstOrNull()?.let { listener?.onBattery(it.toInt() and 0xFF) }
        }
    }

    private fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    private fun ByteArray.asText(): String = String(this, Charsets.UTF_8).trim { it <= ' ' }

    companion object {
        private const val TAG = "BandGatt"

        private fun uuid16(short: Int): UUID =
            UUID.fromString("0000%04x-0000-1000-8000-00805f9b34fb".format(short))

        val SERVICE_BAND: UUID = uuid16(0xFEEA)
        val CHAR_STEPS: UUID = uuid16(0xFEE1)
        val CHAR_COMMAND: UUID = uuid16(0xFEE2)
        val CHAR_REPLY: UUID = uuid16(0xFEE3)
        val SERVICE_BATTERY: UUID = uuid16(0x180F)
        val CHAR_BATTERY: UUID = uuid16(0x2A19)
        val SERVICE_HEART_RATE: UUID = uuid16(0x180D)
        val CHAR_HEART_RATE: UUID = uuid16(0x2A37)
        val SERVICE_DEVICE_INFO: UUID = uuid16(0x180A)
        val CHAR_MANUFACTURER: UUID = uuid16(0x2A29)
        val CHAR_MODEL: UUID = uuid16(0x2A24)
        val CHAR_FIRMWARE: UUID = uuid16(0x2A28)
        val CCCD: UUID = uuid16(0x2902)

        /** Watchdog for one GATT operation whose callback never comes. */
        private const val OP_TIMEOUT_MS = 2_000L
        private const val DISCOVERY_DELAY_MS = 600L
        private const val DISCOVERY_TIMEOUT_MS = 15_000L
        private const val FRAME_TIMEOUT_MS = 5_000L
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

private fun UUID.short(): String = toString().substring(4, 8)
