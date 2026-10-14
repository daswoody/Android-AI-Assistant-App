package de.heimai.app.core

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Minimaler Absturz-Rekorder: schreibt unbehandelte Exceptions in eine Datei
 * im App-Speicher, damit der Nutzer den Stacktrace nach einem Crash direkt in
 * den Einstellungen sehen und kopieren kann — ohne adb/Logcat. Der bisherige
 * Default-Handler (System-Crash-Dialog) läuft danach normal weiter.
 */
object CrashLog {

    private const val FILE_NAME = "last_crash.txt"
    private const val MAX_CHARS = 12_000

    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val trace = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()
                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.GERMANY).format(Date())
                file(context).writeText(
                    "Absturz am $stamp (Thread: ${thread.name})\n\n${trace.take(MAX_CHARS)}"
                )
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** Letzter aufgezeichneter Absturz oder null. */
    fun read(context: Context): String? =
        file(context).takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() }

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)
}
