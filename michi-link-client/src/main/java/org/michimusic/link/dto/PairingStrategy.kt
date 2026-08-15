package org.michimusic.link.dto

enum class PairingStrategy {
    PLAYER_PASSWORD,
    SERVER_CODE,
    ED25519_CHALLENGE,
    RECEIVER_BUTTON,
    LEGACY
}
