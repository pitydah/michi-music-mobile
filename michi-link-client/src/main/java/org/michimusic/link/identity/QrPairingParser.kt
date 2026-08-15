package org.michimusic.link.identity

import android.net.Uri

data class CanonicalQrPairing(
    val serverMichiId: String,
    val serverPublicKey: String,
    val sessionId: String,
    val endpoint: String,
    val expiresAt: Long
)

class QrPairingParser(private val identity: MichiIdentity) {

    companion object {
        const val MAX_QR_URI_LENGTH = 1024
        private val UUID_REGEX = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    }

    fun parseAndValidate(qrContent: String): Result<CanonicalQrPairing> {
        try {
            if (qrContent.length > MAX_QR_URI_LENGTH) {
                return Result.failure(IllegalArgumentException("El código QR excede la longitud máxima permitida ($MAX_QR_URI_LENGTH bytes)."))
            }

            val uri = Uri.parse(qrContent)
            
            if (uri.scheme != "michi" || uri.host != "pair") {
                return Result.failure(IllegalArgumentException("El QR no es un QR de emparejamiento de Michi."))
            }

            if (uri.getQueryParameter("format") != "michi-link-pairing") {
                return Result.failure(IllegalArgumentException("Formato de QR no soportado."))
            }

            if (uri.getQueryParameter("version") != "1") {
                return Result.failure(IllegalArgumentException("Versión del QR no soportada."))
            }

            val serverMichiId = uri.getQueryParameter("server_michi_id") ?: return Result.failure(IllegalArgumentException("Falta server_michi_id"))
            val serverPublicKey = uri.getQueryParameter("server_public_key") ?: return Result.failure(IllegalArgumentException("Falta server_public_key"))
            val sessionId = uri.getQueryParameter("session_id") ?: return Result.failure(IllegalArgumentException("Falta session_id"))
            val expiresAtStr = uri.getQueryParameter("expires_at") ?: return Result.failure(IllegalArgumentException("Falta expires_at"))
            val endpoint = uri.getQueryParameter("endpoint") ?: return Result.failure(IllegalArgumentException("Falta endpoint"))

            if (!UUID_REGEX.matches(sessionId)) {
                return Result.failure(IllegalArgumentException("session_id inválido en QR: debe ser un UUID estándar."))
            }

            if (!endpoint.startsWith("http://") && !endpoint.startsWith("https://")) {
                return Result.failure(IllegalArgumentException("endpoint inválido en QR: debe ser una URI HTTP o HTTPS válida."))
            }

            val endpointUri = Uri.parse(endpoint)
            if (endpointUri.host.isNullOrBlank()) {
                return Result.failure(IllegalArgumentException("endpoint inválido en QR: host ausente."))
            }

            val expiresAt = try {
                java.time.Instant.parse(expiresAtStr).toEpochMilli() / 1000
            } catch (e: Exception) {
                return Result.failure(IllegalArgumentException("expires_at inválido (debe ser RFC 3339)"))
            }
            
            if (System.currentTimeMillis() / 1000 > expiresAt) {
                return Result.failure(IllegalArgumentException("El QR ha expirado."))
            }

            if (!identity.verifyServerIdentity(serverMichiId, serverPublicKey)) {
                return Result.failure(IllegalArgumentException("Identity mismatch: La clave pública del QR no coincide con su Michi ID."))
            }

            return Result.success(
                CanonicalQrPairing(
                    serverMichiId = serverMichiId,
                    serverPublicKey = serverPublicKey,
                    sessionId = sessionId,
                    endpoint = endpoint,
                    expiresAt = expiresAt
                )
            )
        } catch (e: Exception) {
            return Result.failure(IllegalArgumentException("Error al leer el QR", e))
        }
    }
}
