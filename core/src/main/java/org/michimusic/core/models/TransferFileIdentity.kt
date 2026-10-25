package org.michimusic.core.models

import java.io.File

/**
 * Autoridad canónica única para resolver identidades, claves de transferencia
 * y rutas de archivos finales y temporales entre todos los componentes
 * (SyncWorker, LinkTransferManager, AirCatTransferClient y Checkpoints).
 */
object TransferFileIdentity {

    private val UNSAFE_CHARS_REGEX = Regex("[^A-Za-z0-9._-]")

    /**
     * Sanitiza un segmento de ruta para evitar path traversal, caracteres prohibidos
     * o colisiones por espacios en blanco.
     */
    fun safeSegment(value: String, default: String = "segment"): String {
        val sanitized = value.trim().replace(UNSAFE_CHARS_REGEX, "_")
        return sanitized.ifBlank { default }
    }

    /**
     * Construye el nombre canónico del archivo final descargado.
     */
    fun buildFinalFileName(
        remoteDeviceId: String,
        destinationLibraryId: String,
        trackId: String,
        format: String,
        representationId: String = "",
    ): String {
        val ext = safeSegment(format.lowercase().takeIf { it.isNotBlank() } ?: "mp3", default = "mp3")
        val safeTrack = safeSegment(trackId, default = "track")
        val safeRep = if (representationId.isNotBlank()) "_${safeSegment(representationId)}" else ""

        return if (remoteDeviceId.isNotBlank() && destinationLibraryId.isNotBlank()) {
            val safeDev = safeSegment(remoteDeviceId, default = "device")
            val safeLib = safeSegment(destinationLibraryId, default = "lib")
            "${safeDev}_${safeLib}_${safeTrack}${safeRep}.$ext"
        } else {
            "${safeTrack}${safeRep}.$ext"
        }
    }

    /**
     * Construye el nombre canónico del archivo temporal .part.
     * Si planId está presente, aisla físicamente el parcial vinculándolo al plan.
     */
    fun buildTempFileName(
        finalFileName: String,
        planId: String = "",
    ): String {
        return if (planId.isNotBlank()) {
            val safePlan = safeSegment(planId, default = "plan")
            "${finalFileName}_${safePlan}.part"
        } else {
            "${finalFileName}.part"
        }
    }

    /**
     * Construye la clave canónica de transferencia (transferKey).
     */
    fun buildTransferKey(
        remoteDeviceId: String,
        destinationLibraryId: String,
        trackId: String,
        planId: String = "",
        representationId: String = "",
    ): String {
        val repSuffix = if (representationId.isNotBlank()) ":$representationId" else ""
        return if (remoteDeviceId.isNotBlank() && destinationLibraryId.isNotBlank()) {
            if (planId.isNotBlank()) {
                "$remoteDeviceId:$destinationLibraryId:$trackId:$planId$repSuffix"
            } else {
                "$remoteDeviceId:$destinationLibraryId:$trackId$repSuffix"
            }
        } else {
            "$trackId$repSuffix"
        }
    }

    /**
     * Resuelve de forma segura el archivo final y temporal asegurando que permanezcan
     * dentro del directorio gestionado y sin path traversal.
     */
    fun resolveFiles(
        musicDir: File,
        remoteDeviceId: String,
        destinationLibraryId: String,
        trackId: String,
        format: String,
        planId: String = "",
        representationId: String = "",
    ): Pair<File, File> {
        val finalName = buildFinalFileName(remoteDeviceId, destinationLibraryId, trackId, format, representationId)
        val tempName = buildTempFileName(finalName, planId)

        val finalFile = File(musicDir, finalName)
        val tempFile = File(musicDir, tempName)

        // Verificación de seguridad: asegurar que las rutas permanezcan dentro de musicDir
        val canonicalBase = musicDir.canonicalPath
        check(finalFile.canonicalPath.startsWith(canonicalBase)) {
            "Path traversal detectado en archivo final: ${finalFile.absolutePath}"
        }
        check(tempFile.canonicalPath.startsWith(canonicalBase)) {
            "Path traversal detectado en archivo temporal: ${tempFile.absolutePath}"
        }

        return Pair(finalFile, tempFile)
    }
}
