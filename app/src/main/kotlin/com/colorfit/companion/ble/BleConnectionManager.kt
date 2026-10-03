package com.colorfit.companion.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.colorfit.companion.data.StandardParsers
import com.colorfit.companion.domain.CharProperty
import com.colorfit.companion.domain.ConnectionState
import com.colorfit.companion.domain.GattCharacteristicInfo
import com.colorfit.companion.domain.GattDescriptorInfo
import com.colorfit.companion.domain.GattServiceInfo
import com.colorfit.companion.domain.ServiceType
import com.colorfit.companion.domain.WatchState
import com.colorfit.companion.vendor.VendorGattSpec
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import timber.log.Timber
import java.util.ArrayDeque
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the single [BluetoothGatt] connection to the watch and exposes a
 * [WatchState] snapshot via [state].
 *
 * ### GATT operation serialization
 *
 * Android's GATT stack allows **exactly one** operation in flight at a time
 * (read / write / descriptor write / MTU request). Issuing a second before
 * the first's completion callback fires makes the stack silently drop it.
 * Every operation therefore goes through [enqueueOp]; the completion callbacks
 * ([onCharacteristicWrite], [onCharacteristicRead], [onDescriptorWrite],
 * [onMtuChanged]) call [completeOp] to release the gate and start the next.
 * This mirrors the `DeviceBusyLockUtils` busy-lock in the original SDK.
 *
 * Reads standard SIG services (Battery, Heart Rate, Device Information) and
 * forwards vendor-characteristic notifications to [vendorResponseListener].
 */
@Singleton
class BleConnectionManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val manager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    private val adapter: BluetoothAdapter? get() = manager?.adapter

    private val _state = MutableStateFlow(WatchState())
    val state: StateFlow<WatchState> = _state.asStateFlow()

    private var gatt: BluetoothGatt? = null

    /**
     * Listener for incoming bytes on any *vendor* characteristic (anything
     * not handled inline as a standard SIG service). Set by
     * [com.colorfit.companion.vendor.VendorConnection]. Receives
     * `(bytes, characteristicUuid)`.
     */
    @Volatile
    var vendorResponseListener: ((ByteArray, java.util.UUID) -> Unit)? = null
        private set

    fun setVendorResponseListener(listener: ((ByteArray, java.util.UUID) -> Unit)?) {
        vendorResponseListener = listener
    }

    /**
     * Receives the raw capability blob read back from a vendor *write*
     * characteristic (`0x33F1` / `0x34F1`). These reads are how the watch
     * reports its feature bitfield — see [com.colorfit.companion.vendor.VendorCapabilities].
     */
    @Volatile
    var vendorCapabilityListener: ((ByteArray, java.util.UUID) -> Unit)? = null
        private set

    fun setVendorCapabilityListener(listener: ((ByteArray, java.util.UUID) -> Unit)?) {
        vendorCapabilityListener = listener
    }

    /**
     * Fired once per connection, after MTU + all notification-enable
     * descriptors have been written and confirmed. This is the point at
     * which it is safe to start the vendor handshake — the watch will now
     * actually deliver notifications, so auth/sync responses come back.
     */
    @Volatile
    var onNotificationsReady: (() -> Unit)? = null

    fun setNotificationsReadyListener(listener: (() -> Unit)?) {
        onNotificationsReady = listener
    }

    @Volatile
    private var readyDispatched = false

    // ───────── Serialized GATT operation queue ─────────────────────────────

    /**
     * @param service the GATT service the op touches, so a permission denial
     *   can fence off the rest of that service (see [restrictedServices]).
     */
    private class GattOp(val label: String, val service: java.util.UUID? = null, val issue: () -> Boolean)

    /**
     * Services the Android stack refused us with "Need BLUETOOTH_PRIVILEGED"
     * on this connection. On the user's phone this hits the legacy channel
     * (`55FF`: `33F1`/`33F2`) while `56FF` works.
     *
     * Once a *read or write* throws that SecurityException, `BluetoothGatt`
     * leaves its internal busy flag set (it only clears it for
     * RemoteException), so every later read/write returns false until the
     * link drops — the watch then sees nothing and disconnects after 30 s.
     * The notify-enable on `33F2` fails first and harmlessly, so recording the
     * service there lets us skip the `33F1` read that would poison the queue.
     */
    private val restrictedServices: MutableSet<java.util.UUID> =
        java.util.Collections.synchronizedSet(mutableSetOf())

    private val opLock = Any()
    private val opQueue = ArrayDeque<GattOp>()
    private var opInFlight: GattOp? = null
    /**
     * Bumped every time an operation completes, times out, or is reset. The
     * watchdog for a given op captures the generation it was scheduled under
     * and only fires if that is still current — so a completed op's stale
     * watchdog can never advance a later one.
     */
    private var opGeneration = 0
    private val opHandler = Handler(Looper.getMainLooper())

    private fun enqueueOp(label: String, service: java.util.UUID? = null, issue: () -> Boolean) {
        synchronized(opLock) { opQueue.addLast(GattOp(label, service, issue)) }
        drainOps()
    }

    /** Enqueue a synchronous side effect that expects no GATT callback. */
    private fun enqueueAction(label: String, action: () -> Unit) {
        enqueueOp(label, service = null) { action(); false }
    }

    private fun drainOps() {
        while (true) {
            val gen: Int
            val op: GattOp
            synchronized(opLock) {
                if (opInFlight != null) return
                op = opQueue.pollFirst() ?: return
                opInFlight = op
                gen = ++opGeneration
            }
            val result = if (op.service != null && op.service in restrictedServices) {
                Result.failure(SecurityException("service ${op.service} is restricted, skipped"))
            } else {
                runCatching { op.issue() }
            }
            val error = result.exceptionOrNull()
            if (error is SecurityException && op.service != null && restrictedServices.add(op.service)) {
                // Seen on the user's Moto: the stack keeps a stale "restricted
                // handles" set (meant for HID/FIDO devices) for this client
                // id, and it happens to cover 55FF. Restarting Bluetooth
                // clears it; nothing the app can do from here.
                val msg = if (op.service == VendorGattSpec.SERVICE_LEGACY) {
                    "Android is blocking the watch's main data channel (55FF), so no data can arrive. " +
                        "Turn Bluetooth off and on, then reconnect."
                } else {
                    "Android denied access to service ${op.service}"
                }
                Timber.tag(TAG).w(msg)
                _state.update { it.copy(errors = it.errors + msg) }
            }
            val accepted = result.getOrElse { false }
            Timber.tag(TAG).v("op %s accepted=%s%s", op.label, accepted,
                result.exceptionOrNull()?.let { " error=$it" } ?: "")
            if (accepted) {
                // Wait for the matching completion callback, guarded by a watchdog.
                opHandler.postDelayed({ onOpTimeout(gen) }, OP_TIMEOUT_MS)
                return
            }
            // Not accepted (or a no-callback action): drop it and continue.
            synchronized(opLock) { if (opGeneration == gen) opInFlight = null }
        }
    }

    private fun completeOp() {
        synchronized(opLock) {
            opInFlight = null
            opGeneration++ // invalidate the completed op's pending watchdog
        }
        drainOps()
    }

    private fun onOpTimeout(gen: Int) {
        synchronized(opLock) {
            if (opGeneration != gen || opInFlight == null) return
            Timber.tag(TAG).w("op %s timed out", opInFlight?.label)
            opInFlight = null
            opGeneration++
        }
        drainOps()
    }

    private fun resetOps() {
        opHandler.removeCallbacksAndMessages(null)
        restrictedServices.clear()
        synchronized(opLock) {
            opQueue.clear()
            opInFlight = null
            opGeneration++
        }
    }

    // ───────── Connection lifecycle ────────────────────────────────────────

    /**
     * @param autoConnect false for a direct, fast connect (user picked from a
     *   scan); true for background reconnects to a known watch that
     *   power-saves — the stack waits passively for it to reappear instead of
     *   timing out with status 133.
     */
    fun connect(device: BluetoothDevice, autoConnect: Boolean = false) {
        if (!hasConnectPermission()) {
            _state.update {
                it.copy(
                    connection = ConnectionState.Error("Missing BLUETOOTH_CONNECT"),
                    errors = it.errors + "Missing BLUETOOTH_CONNECT permission",
                )
            }
            return
        }
        // Tear down any prior connection and its client interface first.
        closeGatt()

        @SuppressLint("MissingPermission")
        val g = device.connectGatt(context, autoConnect, callback, BluetoothDevice.TRANSPORT_LE)
        gatt = g
        _state.update {
            it.copy(
                connection = ConnectionState.Connecting(device.name ?: device.address),
                deviceName = device.name ?: it.deviceName,
                errors = emptyList(),
            )
        }
    }

    fun disconnect() {
        gatt?.let { runCatching { it.disconnect() } }
    }

    fun close() {
        closeGatt()
        _state.update { WatchState() }
    }

    /** Close the GATT client and free its interface. Safe to call repeatedly. */
    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        resetOps()
        readyDispatched = false
        gatt?.let { runCatching { it.disconnect(); it.close() } }
        gatt = null
    }

    /**
     * Enqueue a payload to the watch's vendor write characteristic. Returns
     * false only when there is no connection or no vendor write characteristic
     * was discovered; true means the write was queued (it drains in order).
     *
     * @param ble5 send on the BLE 5 channel (`34F1`). The original uses it only
     *   for a handful of long commands (`writeCharaBle5`: city name, music,
     *   quick replies, sport list); everything else goes to `33F1`, and the
     *   watch ignores some commands on the wrong channel — `B2 FA` and `31 01`
     *   sent to `34F1` get no reply at all.
     */
    fun writeVendorCommand(payload: ByteArray, ble5: Boolean = false): Boolean {
        if (resolveVendorWriteChar(ble5) == null) return false
        // Resolve again when the op runs: by then the bring-up may have found
        // the legacy service blocked, and the write must fall back to 34F1.
        enqueueOp("vendorWrite[${payload.size}]") {
            val c = resolveVendorWriteChar(ble5) ?: return@enqueueOp false
            issueWrite(c, payload)
        }
        return true
    }

    // ───────── Individual GATT operations (issued one at a time) ────────────

    @SuppressLint("MissingPermission")
    private fun issueWrite(c: BluetoothGattCharacteristic, payload: ByteArray): Boolean {
        val g = gatt ?: return false
        Timber.tag(TAG).d("TX %s %s", c.uuid.short(), payload.toHexString())
        val writeType = if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(c, payload, writeType) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                c.writeType = writeType
                c.value = payload
                g.writeCharacteristic(c)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun issueRead(c: BluetoothGattCharacteristic): Boolean {
        val g = gatt ?: return false
        return g.readCharacteristic(c)
    }

    @SuppressLint("MissingPermission")
    private fun issueEnableNotify(c: BluetoothGattCharacteristic): Boolean {
        val g = gatt ?: return false
        if (!g.setCharacteristicNotification(c, true)) return false
        val cccd = c.getDescriptor(GattSpec.CLIENT_CHARACTERISTIC_CONFIGURATION) ?: return false
        val value = if (c.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        }
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

    @SuppressLint("MissingPermission")
    private fun issueMtu(mtu: Int): Boolean {
        val g = gatt ?: return false
        return g.requestMtu(mtu)
    }

    private fun resolveVendorWriteChar(ble5: Boolean): BluetoothGattCharacteristic? {
        val g = gatt ?: return null
        val services = g.services ?: return null
        // Preferred channel first, then the other one, then BP; skipping any
        // service Android has refused us.
        val order = if (ble5) {
            listOf(VendorGattSpec.WRITE_BLE5, VendorGattSpec.WRITE_LEGACY, VendorGattSpec.WRITE_BP)
        } else {
            listOf(VendorGattSpec.WRITE_LEGACY, VendorGattSpec.WRITE_BLE5, VendorGattSpec.WRITE_BP)
        }
        for (uuid in order) {
            for (svc in services) {
                if (svc.uuid in restrictedServices) continue
                svc.getCharacteristic(uuid)?.let { return it }
            }
        }
        return null
    }

    // ───────── GATT callback ────────────────────────────────────────────────

    private val callback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            Timber.tag(TAG).i("connection state=%d status=%d", newState, status)
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                resetOps()
                readyDispatched = false
                _state.update {
                    it.copy(
                        connection = ConnectionState.Connected(
                            deviceName = g.device.name ?: it.deviceName ?: "Unknown",
                            macAddress = g.device.address,
                        ),
                        deviceName = g.device.name ?: it.deviceName,
                        macAddress = g.device.address,
                    )
                }
                // Higher connection priority improves throughput/stability during sync.
                runCatching { g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) }
                runCatching { g.discoverServices() }
                    .onFailure { e -> _state.update { it.copy(errors = it.errors + "discoverServices failed: ${e.message}") } }
            } else {
                // Any disconnect, or an error while connecting: close the client
                // so its interface is freed (otherwise reconnects fail with 133).
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    _state.update { it.copy(errors = it.errors + "GATT status $status (newState=$newState)") }
                }
                resetOps()
                        readyDispatched = false
                runCatching { g.close() }
                if (gatt === g) gatt = null
                _state.update { it.copy(connection = ConnectionState.Disconnected) }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _state.update { it.copy(errors = it.errors + "Service discovery failed: $status") }
                return
            }
            val services = g.services
            Timber.tag(TAG).i("services: %s", services.joinToString { s ->
                s.uuid.short() + "[" + s.characteristics.joinToString(",") {
                    it.uuid.short() + ":" + Integer.toHexString(it.properties)
                } + "]"
            })
            _state.update { it.copy(gattTable = services.map(::describeService)) }

            // Enqueue the whole bring-up as serialized operations. Each runs
            // only after the previous one's callback returns.

            // 1. Negotiate a larger MTU (original SDK requests 247). Needed for
            //    the 21-byte weather frame and BLE-5 payloads.
            enqueueOp("mtu") { issueMtu(PREFERRED_MTU) }

            // 2. Device Information (small, static).
            services.firstOrNull { it.uuid == GattSpec.DEVICE_INFORMATION }?.let { devInfo ->
                listOf(
                    GattSpec.MANUFACTURER_NAME,
                    GattSpec.MODEL_NUMBER,
                    GattSpec.SERIAL_NUMBER,
                    GattSpec.FIRMWARE_REVISION,
                    GattSpec.SOFTWARE_REVISION,
                    GattSpec.HARDWARE_REVISION,
                ).forEach { uuid ->
                    devInfo.getCharacteristic(uuid)?.let { c -> enqueueOp("read $uuid") { issueRead(c) } }
                }
            }

            // 3. Battery level (one-shot read).
            services.firstOrNull { it.uuid == GattSpec.BATTERY }
                ?.getCharacteristic(GattSpec.BATTERY_LEVEL)
                ?.let { c -> enqueueOp("read battery") { issueRead(c) } }

            // 4. Standard Heart Rate notifications.
            services.firstOrNull { it.uuid == GattSpec.HEART_RATE }
                ?.getCharacteristic(GattSpec.HEART_RATE_MEASUREMENT)
                ?.let { c -> enqueueOp("notify hr") { issueEnableNotify(c) } }

            // 5. Vendor notification characteristics — these carry ALL live
            //    data (HR, steps, SpO2) and every command response. Without
            //    these enabled the watch never sends anything back.
            listOf(VendorGattSpec.READ_LEGACY, VendorGattSpec.READ_BLE5).forEach { charUuid ->
                services.forEach { svc ->
                    svc.getCharacteristic(charUuid)?.let { c ->
                        val notifiable = c.properties and
                            (BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                                BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                        if (notifiable) {
                            enqueueOp("notify vendor $charUuid", svc.uuid) { issueEnableNotify(c) }
                        }
                    }
                }
            }

            // 6. Read the vendor WRITE characteristics. Counter-intuitive, but
            //    this is how the watch reports its capability bitfield and max
            //    payload length (SDK: writeNotifyCommandIndex cases 11/12 →
            //    onCharacteristicRead). Must land before the handshake so the
            //    encoding choices it drives are known.
            listOf(VendorGattSpec.WRITE_LEGACY, VendorGattSpec.WRITE_BLE5).forEach { charUuid ->
                services.forEach { svc ->
                    svc.getCharacteristic(charUuid)?.let { c ->
                        if (c.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) {
                            enqueueOp("read caps $charUuid", svc.uuid) { issueRead(c) }
                        }
                    }
                }
            }

            // 7. Everything above is enabled — signal the vendor layer to start
            //    its handshake now (auth + time/profile + live/history sync).
            enqueueAction("ready") {
                if (!readyDispatched) {
                    readyDispatched = true
                    onNotificationsReady?.invoke()
                }
            }
        }

        // Reads — new (API 33+) and deprecated (<33) variants both route here.
        override fun onCharacteristicRead(
            g: BluetoothGatt,
            c: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            handleCharacteristicRead(c, value, status)
            completeOp()
        }

        @Deprecated("Deprecated in API 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            handleCharacteristicRead(c, c.value ?: ByteArray(0), status)
            completeOp()
        }

        // Notifications — new (API 33+) and deprecated (<33) variants.
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            c: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            handleCharacteristicChanged(c, value)
        }

        @Deprecated("Deprecated in API 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            handleCharacteristicChanged(c, c.value ?: ByteArray(0))
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            Timber.tag(TAG).v("descriptor write %s status=%d", d.characteristic.uuid.short(), status)
            completeOp()
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            c: BluetoothGattCharacteristic,
            status: Int,
        ) {
            Timber.tag(TAG).v("write done %s status=%d", c.uuid.short(), status)
            completeOp()
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                _state.update { it.copy(mtu = mtu) }
            }
            completeOp()
        }

        override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) { /* unused */ }
    }

    private fun handleCharacteristicRead(c: BluetoothGattCharacteristic, v: ByteArray, status: Int) {
        Timber.tag(TAG).d("READ %s status=%d %s", c.uuid.short(), status, v.toHexString())
        if (status != BluetoothGatt.GATT_SUCCESS) return
        // Reading a vendor *write* characteristic yields the capability blob.
        if (c.uuid == VendorGattSpec.WRITE_LEGACY || c.uuid == VendorGattSpec.WRITE_BLE5) {
            vendorCapabilityListener?.invoke(v, c.uuid)
            return
        }
        when (c.uuid) {
            GattSpec.BATTERY_LEVEL ->
                StandardParsers.parseBatteryLevel(v)?.let { pct ->
                    _state.update { it.copy(batteryPercent = pct) }
                }
            // NB: the manufacturer is its OWN field — writing it into
            // deviceName used to clobber the advertised watch name.
            GattSpec.MANUFACTURER_NAME ->
                _state.update { it.copy(manufacturer = v.asDeviceInfoString()) }
            GattSpec.MODEL_NUMBER ->
                _state.update { it.copy(modelNumber = v.asDeviceInfoString()) }
            GattSpec.SERIAL_NUMBER ->
                _state.update { it.copy(serialNumber = v.asDeviceInfoString()) }
            GattSpec.FIRMWARE_REVISION ->
                _state.update { it.copy(firmwareRevision = v.asDeviceInfoString()) }
            GattSpec.SOFTWARE_REVISION ->
                _state.update { it.copy(softwareRevision = v.asDeviceInfoString()) }
            GattSpec.HARDWARE_REVISION ->
                _state.update { it.copy(hardwareRevision = v.asDeviceInfoString()) }
        }
    }

    /**
     * Device Information strings are UTF-8 and frequently NUL-padded or
     * whitespace-padded by cheap firmware. Returns null when nothing useful
     * is left, so the UI can fall back instead of showing a blank row.
     */
    private fun ByteArray.asDeviceInfoString(): String? =
        String(this, Charsets.UTF_8)
            .trim { it <= ' ' || it == '\u0000' }
            .takeIf { it.isNotBlank() }

    private fun handleCharacteristicChanged(c: BluetoothGattCharacteristic, v: ByteArray) {
        Timber.tag(TAG).d("RX %s %s", c.uuid.short(), v.toHexString())
        when (c.uuid) {
            GattSpec.HEART_RATE_MEASUREMENT ->
                StandardParsers.parseHeartRateMeasurement(v)?.let { hr ->
                    _state.update { it.copy(lastHeartRateBpm = hr.bpm) }
                }
            GattSpec.BATTERY_LEVEL ->
                StandardParsers.parseBatteryLevel(v)?.let { pct ->
                    _state.update { it.copy(batteryPercent = pct) }
                }
            else ->
                // Vendor-characteristic notification — hand off to the vendor layer.
                vendorResponseListener?.invoke(v, c.uuid)
        }
    }

    private fun hasConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_CONNECT,
            ) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    private fun describeService(s: BluetoothGattService): GattServiceInfo {
        val type = when {
            GattSpec.standardServices.contains(s.uuid) -> ServiceType.STANDARD
            VendorGatt.knownVendorServices.contains(s.uuid) -> ServiceType.VENDOR
            else -> ServiceType.UNKNOWN
        }
        return GattServiceInfo(
            uuid = s.uuid.toString(),
            type = type,
            characteristics = s.characteristics.map(::describeCharacteristic),
        )
    }

    private fun describeCharacteristic(c: BluetoothGattCharacteristic): GattCharacteristicInfo {
        val props = mutableListOf<CharProperty>()
        if (c.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) props += CharProperty.READ
        if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) props += CharProperty.WRITE
        if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) props += CharProperty.WRITE_NO_RESPONSE
        if (c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) props += CharProperty.NOTIFY
        if (c.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) props += CharProperty.INDICATE
        if (c.properties and BluetoothGattCharacteristic.PROPERTY_SIGNED_WRITE != 0) props += CharProperty.SIGNED_WRITE
        @Suppress("DEPRECATION")
        return GattCharacteristicInfo(
            uuid = c.uuid.toString(),
            properties = props,
            descriptors = c.descriptors.map { d ->
                GattDescriptorInfo(
                    uuid = d.uuid.toString(),
                    valueHex = d.value?.toHexString(),
                )
            },
            valueHex = c.value?.toHexString(),
        )
    }

    companion object {
        /** MTU the original NoiseFit SDK requests (BpElPressureListener.STOP_APP_EXIT = 247). */
        private const val PREFERRED_MTU = 247

        /**
         * Watchdog: if a GATT completion callback never arrives, advance the
         * queue after this long so one lost operation can't wedge everything.
         */
        private const val OP_TIMEOUT_MS = 2_000L
    }
}

/** Helper used by the in-app GATT dumper. */
private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }

/** `000033f1-0000-…` → `33f1`, for compact logs. */
private fun java.util.UUID.short(): String = toString().substring(4, 8)

private const val TAG = "BleGatt"
