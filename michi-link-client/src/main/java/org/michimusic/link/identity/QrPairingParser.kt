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

    fun parseAndValidate(qrContent: String): Result<CanonicalQrPairing> {
        try {
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

            val expiresAt = expiresAtStr.toLongOrNull() ?: return Result.failure(IllegalArgumentException("expires_at inválido"))
            
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
