package com.colorfit.companion

import android.content.Context
import android.util.Log
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Debug builds only: appends every Timber line to `files/ble-log.txt`, so a
 * BLE problem can still be read hours later — logcat on the user's phone
 * keeps only a few minutes. At [MAX_FILE_BYTES] the file is rotated to
 * `ble-log.1.txt`; two files are kept.
 *
 * Pull them with:
 * ```
 * adb shell run-as com.colorfit.companion.debug cat files/ble-log.txt > ble-log.txt
 * adb shell run-as com.colorfit.companion.debug cat files/ble-log.1.txt > ble-log.1.txt
 * ```
 * (Relative device paths are safe in Git Bash; absolute ones such as
 * `/sdcard/…` need `MSYS_NO_PATHCONV=1`.)
 *
 * All file I/O happens on one background thread and its errors are
 * swallowed: logging must never block or crash a GATT callback.
 */
class FileLoggingTree(context: Context) : Timber.Tree() {

    private val file = File(context.filesDir, FILE_NAME)
    private val previousFile = File(context.filesDir, PREVIOUS_FILE_NAME)

    private val executor = Executors.newSingleThreadExecutor { Thread(it, "FileLoggingTree") }

    // Only touched on [executor].
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private var out: FileOutputStream? = null
    private var size = 0L

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        // Timber has already appended t's stack trace to message.
        val time = System.currentTimeMillis()
        executor.execute { append(time, priority, tag, message) }
    }

    private fun append(time: Long, priority: Int, tag: String?, message: String) {
        try {
            val line = "${timeFormat.format(Date(time))} ${priorityLetter(priority)}/${tag ?: "-"}: $message\n"
            val bytes = line.toByteArray()
            val stream = out ?: FileOutputStream(file, true).also {
                out = it
                size = file.length()
            }
            // Unbuffered on purpose: each line is in the file as soon as it is
            // written, so nothing is lost when the system kills the process.
            stream.write(bytes)
            size += bytes.size
            if (size >= MAX_FILE_BYTES) {
                stream.close()
                out = null
                previousFile.delete()
                file.renameTo(previousFile)
            }
        } catch (e: Exception) {
            // Disk full, storage unavailable, …: drop the line, reopen next time.
            runCatching { out?.close() }
            out = null
        }
    }

    private fun priorityLetter(priority: Int): Char = when (priority) {
        Log.VERBOSE -> 'V'
        Log.DEBUG -> 'D'
        Log.INFO -> 'I'
        Log.WARN -> 'W'
        Log.ERROR -> 'E'
        else -> 'A'
    }

    companion object {
        private const val FILE_NAME = "ble-log.txt"
        private const val PREVIOUS_FILE_NAME = "ble-log.1.txt"

        /** Rotate at ~2 MB; with the previous file that is at most ~4 MB on disk. */
        private const val MAX_FILE_BYTES = 2L * 1024 * 1024
    }
}
