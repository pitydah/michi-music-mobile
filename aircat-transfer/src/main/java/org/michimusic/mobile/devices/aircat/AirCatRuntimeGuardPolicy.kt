package org.michimusic.mobile.devices.aircat

import org.michimusic.mobile.devices.aircat.port.PeerPairingStatus

/** A23: fail-closed at file boundaries; no polling, resident threads or I/O. */
internal object AirCatRuntimeGuardPolicy {
    enum class Refusal { CANCELLED, PAUSED, EXPIRED, REVOKED, UNAUTHORIZED }

    fun refuse(
        pairing: PeerPairingStatus,
        sessionTokenMatches: Boolean,
        nowMillis: Long,
        expiresAtMillis: Long,
        cancelled: Boolean,
        paused: Boolean,
    ): Refusal? = when {
        cancelled -> Refusal.CANCELLED
        paused -> Refusal.PAUSED
        expiresAtMillis > 0L && nowMillis >= expiresAtMillis -> Refusal.EXPIRED
        pairing == PeerPairingStatus.REVOKED -> Refusal.REVOKED
        pairing != PeerPairingStatus.PAIRED_UNVERIFIED || !sessionTokenMatches -> Refusal.UNAUTHORIZED
        else -> null
    }
}
