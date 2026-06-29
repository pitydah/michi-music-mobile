package org.michimusic.link.errors

sealed class LinkException(message: String) : Exception(message) {
    data object Unauthorized : LinkException("Se requiere autenticación")
    data object Forbidden : LinkException("Acceso denegado")
    data object InvalidCredentials : LinkException("Credenciales incorrectas")
    data object Revoked : LinkException("Dispositivo revocado")
    data object NotImplemented : LinkException("No implementado por el servidor")
    data object PairingRequired : LinkException("Se requiere emparejamiento")
    data class ServerError(val code: String, override val message: String) : LinkException(message)
    data class NetworkError(override val message: String) : LinkException(message)
    data object TokenExpired : LinkException("Token expirado")
    data object Incompatible : LinkException("Versión incompatible del servidor")
}
