package com.mssdvd.platestracker

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Mirrors [Log] calls to a bounded file under app-private storage.
 *
 * logcat's ring buffer is not a reliable field-test record: its size isn't
 * shell-persistable across reboots and has been observed reset by a bare logd
 * restart (2026-07-10, ~2.5 min retention with no reboot in between) — a pull
 * done any later than that has zero app lines. This file survives until the
 * app overwrites it on its own rotation, so a dump pulled hours after a drive
 * still has the full session.
 */
object AppLog {
    private const val FILE_NAME = "app_log.txt"
    private const val MAX_BYTES = 5L * 1024 * 1024
    private const val TRIM_TO_BYTES = 2L * 1024 * 1024

    private val dateFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val lock = Any()
    private var logFile: File? = null

    fun init(context: Context) {
        val file = File(context.filesDir, FILE_NAME)
        logFile = file
        trimIfNeeded(file)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            e("AppLog", "uncaught exception on ${thread.name}", throwable)
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun d(tag: String, msg: String) {
        Log.d(tag, msg)
        write('D', tag, msg, null)
    }

    fun i(tag: String, msg: String) {
        Log.i(tag, msg)
        write('I', tag, msg, null)
    }

    fun w(tag: String, msg: String, tr: Throwable? = null) {
        Log.w(tag, msg, tr)
        write('W', tag, msg, tr)
    }

    fun e(tag: String, msg: String, tr: Throwable? = null) {
        Log.e(tag, msg, tr)
        write('E', tag, msg, tr)
    }

    private fun write(level: Char, tag: String, msg: String, tr: Throwable?) {
        val file = logFile ?: return
        synchronized(lock) {
            try {
                if (file.length() > MAX_BYTES) trimIfNeeded(file)
                FileOutputStream(file, true).use { fos ->
                    PrintWriter(fos).use { pw ->
                        pw.println("${dateFormat.format(java.util.Date())} $level/$tag: $msg")
                        if (tr != null) pw.print(Log.getStackTraceString(tr))
                    }
                }
            } catch (_: Exception) {
                // Logging must never crash the app.
            }
        }
    }

    /** Keeps the most recent [TRIM_TO_BYTES] worth of lines; called with [lock] held or at init. */
    private fun trimIfNeeded(file: File) {
        if (!file.exists() || file.length() <= MAX_BYTES) return
        try {
            val kept = ArrayDeque<String>()
            var bytes = 0L
            for (line in file.readLines().asReversed()) {
                bytes += line.length + 1
                kept.addFirst(line)
                if (bytes >= TRIM_TO_BYTES) break
            }
            file.writeText(kept.joinToString("\n", postfix = "\n"))
        } catch (_: Exception) {
            // Best effort — an oversized log file is better than a crash loop.
        }
    }
}
