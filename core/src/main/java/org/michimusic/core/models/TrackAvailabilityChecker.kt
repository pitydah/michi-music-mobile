package org.michimusic.core.models

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * Verificador unificado de disponibilidad física para pistas descargadas / locales.
 * Evita asumir que un filepath no vacío está disponible sin comprobación real del almacenamiento.
 */
object TrackAvailabilityChecker {

    /**
     * Comprueba si la pista tiene un archivo físico accesible en disco o un identificador MediaStore válido y resoluble.
     * Retorna false si filepath está en blanco o apunta a una ruta local/URI inexistente o vacía.
     */
    fun isTrackAvailable(filepath: String?, context: Context? = null): Boolean {
        if (filepath.isNullOrBlank()) return false
        if (filepath.startsWith("content://")) {
            if (context != null) {
                val uri = runCatching { Uri.parse(filepath) }.getOrNull() ?: return false
                return try {
                    context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                        pfd.statSize > 0L
                    } ?: false
                } catch (_: Exception) {
                    false
                }
            } else {
                // Sin context disponible, valida que el URI tenga esquema content y path no vacío
                val pathPart = filepath.removePrefix("content://")
                return pathPart.isNotBlank() && pathPart.contains('/')
            }
        }
        val cleanPath = if (filepath.startsWith("file://")) filepath.removePrefix("file://") else filepath
        return try {
            val file = File(cleanPath)
            file.exists() && file.isFile && file.length() > 0L
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Sobrecarga conveniente para [Track].
     */
    fun isTrackDownloaded(track: Track, context: Context? = null): Boolean {
        return isTrackAvailable(track.filepath, context)
    }
}
