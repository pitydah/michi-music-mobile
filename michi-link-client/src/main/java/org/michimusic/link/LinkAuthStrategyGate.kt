package org.michimusic.link

/**
 * A26: prevent HOME_MEMBERSHIP or unknown auth profiles from silently
 * degrading into LEGACY password / receiver-button pairing. This is a policy
 * gate until Mobile has a verified Home membership handshake; it must NEVER
 * fabricate a Home token from an Ed25519 discovery announcement.
 */
object LinkAuthStrategyGate {
    fun refusal(advertisedStrategy: String?): String? {
        val strategy = advertisedStrategy?.trim()?.uppercase().orEmpty()
        return when (strategy) {
            "", "PLAYER_PASSWORD", "PASSWORD", "SERVER_CODE", "CODE", "PIN",
            "ED25519_CHALLENGE", "RECEIVER_BUTTON", "LEGACY" -> null
            "HOME_MEMBERSHIP" -> "LINK_HOME_AUTH_NOT_IMPLEMENTED: vinculación Home requiere autorización de Micro Server"
            else -> "LINK_AUTH_STRATEGY_UNSUPPORTED: perfil de autenticación no reconocido"
        }
    }
}
