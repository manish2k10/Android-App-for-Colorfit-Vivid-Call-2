package com.colorfit.companion.data

import android.content.Context
import android.os.Environment
import com.colorfit.companion.domain.GattServiceInfo
import com.colorfit.companion.domain.WatchState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes a JSON dump of the watch's GATT table plus a CSV row index to the
 * app's external files directory so it can be shared via the system Files app.
 *
 * Phase 0 recon artefact — these dumps are exactly what we need to map
 * vendor (Noise) services before doing any protocol reverse-engineering.
 */
@Singleton
class GattDumper @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    fun export(state: WatchState): File {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir
        dir.mkdirs()

        val jsonFile = File(dir, "gatt-dump-$stamp.json")
        val payload = GattDump(
            capturedAt = Date().toInstant().toString(),
            device = state.deviceName,
            mac = (state.connection as? com.colorfit.companion.domain.ConnectionState.Connected)?.macAddress,
            model = state.modelNumber,
            firmware = state.firmwareRevision,
            services = state.gattTable.map(::serviceToDto),
        )
        jsonFile.writeText(json.encodeToString(payload))
        return jsonFile
    }

    private fun serviceToDto(s: GattServiceInfo): GattDumpService = GattDumpService(
        uuid = s.uuid,
        type = s.type.name,
        characteristics = s.characteristics.map { c ->
            GattDumpCharacteristic(
                uuid = c.uuid,
                properties = c.properties.map { it.name },
                valueHex = c.valueHex,
                descriptors = c.descriptors.map { GattDumpDescriptor(it.uuid, it.valueHex) },
            )
        },
    )

    @Serializable
    private data class GattDump(
        val capturedAt: String,
        val device: String?,
        val mac: String?,
        val model: String?,
        val firmware: String?,
        val services: List<GattDumpService>,
    )

    @Serializable
    private data class GattDumpService(
        val uuid: String,
        val type: String,
        val characteristics: List<GattDumpCharacteristic>,
    )

    @Serializable
    private data class GattDumpCharacteristic(
        val uuid: String,
        val properties: List<String>,
        val valueHex: String?,
        val descriptors: List<GattDumpDescriptor>,
    )

    @Serializable
    private data class GattDumpDescriptor(
        val uuid: String,
        val valueHex: String?,
    )
}
