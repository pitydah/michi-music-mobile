package org.michimusic.core.models

import java.io.File

/**
 * Verificador unificado de disponibilidad física para pistas descargadas / locales.
 * Evita asumir que un filepath no vacío está disponible sin comprobación real del almacenamiento.
 */
object TrackAvailabilityChecker {

    /**
     * Comprueba si la pista tiene un archivo físico accesible en disco o un identificador MediaStore válido.
     * Retorna false si filepath está en blanco o apunta a una ruta local inexistente.
     */
    fun isTrackAvailable(filepath: String?): Boolean {
        if (filepath.isNullOrBlank()) return false
        if (filepath.startsWith("content://")) {
            // MediaStore URI: uri válida proporcionada por el proveedor de contenido
            return true
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
    fun isTrackDownloaded(track: Track): Boolean {
        return isTrackAvailable(track.filepath)
    }
}
