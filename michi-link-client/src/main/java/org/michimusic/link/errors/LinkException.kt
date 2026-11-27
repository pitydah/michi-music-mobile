package org.michimusic.link.errors

sealed class LinkException(message: String) : Exception(message) {
    data object Unauthorized : LinkException("Se requiere autenticación")
    data object Forbidden : LinkException("Acceso denegado")
    data object InvalidCredentials : LinkException("Credenciales incorrectas")
    data object Revoked : LinkException("Dispositivo revocado")
    data object NotImplemented : LinkException("No implementado por el servidor")
    data object PairingRequired : LinkException("Se requiere emparejamiento")
    data object SessionConflict : LinkException("Conflicto de sesión o secuencia repetida")
    data class ServerError(val code: String, override val message: String) : LinkException(message)
    data class NetworkError(override val message: String) : LinkException(message)
    data object TokenExpired : LinkException("Token expirado")
    data object Incompatible : LinkException("Versión incompatible del servidor")
    data object RangeNotSatisfiable : LinkException("Rango HTTP solicitado no satisfacible (416)")

    // MIM-07/MIM-08: typed Home/Michi Control refusals. They are deliberately
    // NOT collapsed into Forbidden/Revoked: the UI and reauth policy differ.
    data object HomeSessionRequired : LinkException("Se requiere una sesión Home verificada")
    data object TlsRequired : LinkException("El servidor Home exige HTTPS")
    data object MemberRevoked : LinkException("La membresía de este dispositivo fue revocada")
    data object RevocationStoreUnavailable : LinkException("Estado de revocación no disponible; reintento seguro")
    data class QueueDegraded(val reason: String) : LinkException("Cola degradada: $reason")
    data class MembershipRejected(val code: String, override val message: String) : LinkException(message)
}
