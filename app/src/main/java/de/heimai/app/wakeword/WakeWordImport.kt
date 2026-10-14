package de.heimai.app.wakeword

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * Kopiert ein per Dokumenten-Picker gewähltes openWakeWord-Modell (.onnx)
 * in den App-internen Speicher. Die Engine benötigt einen echten Dateipfad —
 * ein content://-Uri funktioniert nicht direkt.
 */
object WakeWordImport {

    /** @return absoluter Pfad der Kopie oder null bei Fehler. */
    fun copyToStorage(context: Context, uri: Uri, fileName: String): String? = runCatching {
        val dir = File(context.filesDir, "wakeword").apply { mkdirs() }
        val dest = File(dir, fileName)
        context.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
        dest.absolutePath
    }.getOrNull()

    /** Versucht, den Anzeigenamen des Dokuments zu ermitteln (nur informativ). */
    fun displayName(context: Context, uri: Uri, fallback: String): String = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) cursor.getString(nameIndex) else fallback
        } ?: fallback
    }.getOrDefault(fallback)
}
