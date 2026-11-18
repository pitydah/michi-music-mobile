package org.michimusic.mobile.devices.aircat.port

import org.michimusic.link.PairedDeviceRegistry
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Reads the existing encrypted registry on demand; no new cache, worker or poller.
 * This is a legacy pairing adapter, not an Ed25519/Home authorization oracle.
 */
class RegistryPeerTrustPort(private val registry: PairedDeviceRegistry) : PeerTrustPort {
    override fun status(deviceId: String): PeerPairingStatus {
        if (deviceId.isBlank()) return PeerPairingStatus.MISSING
        val peer = registry.getDevice(deviceId) ?: return PeerPairingStatus.MISSING
        return if (peer.isRevoked) PeerPairingStatus.REVOKED else PeerPairingStatus.PAIRED_UNVERIFIED
    }

    override fun matchesSessionToken(deviceId: String, presentedToken: String): Boolean {
        if (deviceId.isBlank() || presentedToken.isEmpty()) return false
        val peer = registry.getDevice(deviceId) ?: return false
        if (peer.isRevoked || peer.deviceToken.isEmpty()) return false
        return MessageDigest.isEqual(
            peer.deviceToken.toByteArray(StandardCharsets.UTF_8),
            presentedToken.toByteArray(StandardCharsets.UTF_8),
        )
    }
}
