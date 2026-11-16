package org.michimusic.mobile.devices.aircat

/**
 * A41: stable user-facing transfer failure taxonomy. Never echo exception.message,
 * which may contain a URL, local path, token or a raw HTTP response.
 * Projection only: no network, storage, timer or identity authority.
 */
internal object AirCatUserFacingFailurePolicy {
    data class Description(val code: String, val message: String)

    /**
     * A42: project EVERY legacy error string into a fixed, nonsensitive label.
     * Source-controlled text is never interpolated back into the UI, even if it
     * looks like an error code or contains private path/URL/token fragments.
     */
    /** Display metadata is untrusted even when the bytes are signed by Home. */
    fun safeTitle(title: String): String {
        // Bound code points to avoid large Compose text allocations and strip
        // bidi control characters that can visually reverse file names.
        val out = StringBuilder(128)
        var points = 0
        var i = 0
        while (i < title.length && points < 96) {
            val cp = Character.codePointAt(title, i)
            i += Character.charCount(cp)
            val type = Character.getType(cp)
            if (Character.isISOControl(cp) || cp in 0x202A..0x202E ||
                cp in 0x2066..0x2069 || cp == 0x200E || cp == 0x200F ||
                type == Character.FORMAT.toInt()) continue
            out.appendCodePoint(cp)
            points++
        }
        return out.toString().trim()
    }

    fun safeMessage(raw: String?): String? {
        if (raw == null) return null
        val code = raw.substringBefore(':').substringBefore(' ').trim()
        return when (code) {
            "AIRC_HOME_REVOKED", "AIRC_SOURCE_NOT_ENROLLED", "AIRC_HOME_NOT_ENROLLED" ->
                "Acceso al dispositivo revocado"
            "AIRC_HOME_UNAUTHORIZED", "AIRC_GRANT_SCOPES", "AIRC_GRANT_AUDIENCE" ->
                "Dispositivo no autorizado en Home"
            "AIRC_GRANT_EXPIRED", "AIRC_TRANSFER_EXPIRED" ->
                "Autorización de transferencia vencida"
            "AIRC_STORAGE_FULL" -> "Espacio insuficiente para recibir archivos"
            "AIRC_HASH_MISMATCH", "AIRC_CHECKSUM_MISMATCH" ->
                "La integridad del archivo no coincide"
            "AIRC_MANIFEST_INVALID", "AIRC_BATCH_RECEIPT_INCOMPLETE" ->
                "No se pudo confirmar la integridad de todos los archivos"
            else -> "La transferencia no pudo continuar"
        }
    }

    fun describe(error: Throwable): Description = when (error) {
        is AirCatException.RevokedPeer -> Description("AIRC_HOME_REVOKED", "Acceso al dispositivo revocado")
        is AirCatException.UnauthorizedPeer -> Description("AIRC_HOME_UNAUTHORIZED", "Dispositivo no autorizado en Home")
        is AirCatException.TransferExpired -> Description("AIRC_GRANT_EXPIRED", "Autorización de transferencia vencida")
        is AirCatException.InsufficientStorage -> Description("AIRC_STORAGE_FULL", "Espacio insuficiente para recibir archivos")
        is AirCatException.CorruptedPayload -> Description("AIRC_HASH_MISMATCH", "La integridad del archivo no coincide")
        is AirCatException.InvalidManifestStructure -> Description("AIRC_MANIFEST_INVALID", "Manifiesto de transferencia no válido")
        else -> Description("AIRC_TRANSFER_UNAVAILABLE", "La transferencia no pudo continuar")
    }
}
