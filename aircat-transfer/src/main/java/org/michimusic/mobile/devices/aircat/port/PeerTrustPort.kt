package org.michimusic.mobile.devices.aircat.port

/** A paired registry entry is not cryptographic identity verification. */
enum class PeerPairingStatus { MISSING, REVOKED, PAIRED_UNVERIFIED }

/** No pairing tokens, HTTP clients, Android dependencies, or link concrete classes. */
interface PeerTrustPort {
    fun status(deviceId: String): PeerPairingStatus
    fun matchesSessionToken(deviceId: String, presentedToken: String): Boolean
}
