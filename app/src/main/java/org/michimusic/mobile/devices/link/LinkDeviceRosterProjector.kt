package org.michimusic.mobile.devices.link

import java.net.URI
import org.michimusic.core.models.DeviceAccessState
import org.michimusic.core.models.DeviceAuthorization
import org.michimusic.core.models.DeviceAvailability
import org.michimusic.core.models.DiscoveredPeer
import org.michimusic.core.models.SyncConnectionState
import org.michimusic.core.models.UnifiedDevice
import org.michimusic.core.devices.AirCatCapabilityPolicy
import org.michimusic.link.PairedDeviceRegistry

/** Pure roster projection with no sockets, pairing or credential exposure. */
internal class LinkDeviceRosterProjector(
    private val registry: PairedDeviceRegistry,
    private val homeAuthorized: (sourceMichiId: String) -> Boolean = { false },
    private val homeAuthenticated: (sourceMichiId: String) -> Boolean = { false },
) {
    fun project(
        peers: List<DiscoveredPeer>,
        connectionStates: Map<String, SyncConnectionState>,
    ): List<UnifiedDevice> {
        val registered = registry.getAllDevices()
        val unified = mutableListOf<UnifiedDevice>()

        registered.forEach { reg ->
            val previousEndpoint = runCatching { URI(reg.lastUrl) }.getOrNull()
            val hostFromUrl = previousEndpoint?.host.orEmpty()
            val portFromUrl = previousEndpoint?.port?.takeIf { it in 1..65535 } ?: 8400
            // A32: even a signed announce is a PRESENCE hint, not a proof that
            // its datagram origin controls the currently pinned HTTPS endpoint.
            // Never route an already enrolled device to a new IP/port solely
            // because UDP was relayed or replayed within the announce window.
            // A31: even a matching legacy device_id or IP is only a discovery
            // hint. Never let an unsigned UDP announcement rewrite a paired
            // device's cached name or endpoint. Ed25519 identity MUST match
            // the immutable Michi ID pinned during enrolment.
            val pinnedId = reg.effectiveMichiId
            val dPeer = peers.firstOrNull { candidate ->
                pinnedId.isNotBlank() && candidate.identityTrust == "VERIFIED" &&
                    candidate.michiId == pinnedId
            }
            unified.add(
                UnifiedDevice(
                    id = reg.deviceId,
                    canonicalMichiId = pinnedId.takeIf { it.isNotBlank() },
                    name = dPeer?.alias ?: reg.deviceName,
                    ip = hostFromUrl,
                    port = portFromUrl,
                    connectionState = connectionStates[reg.deviceId]
                        ?: if (dPeer != null) SyncConnectionState.DISCONNECTED else SyncConnectionState.OFFLINE,
                    isPaired = true,
                    deviceType = reg.serviceType.ifBlank { dPeer?.deviceType?.takeIf { it.isNotBlank() } ?: "unknown" },
                    roles = reg.roles,
                    features = (reg.features + AirCatCapabilityPolicy.enabledFeatureKeys(reg.featuresMap)).distinct(),
                    isRevoked = reg.isRevoked,
                    accessState = registeredAccess(reg.effectiveMichiId, reg.isRevoked,
                        connectionStates[reg.deviceId], reg.featuresMap.isNotEmpty()),
                )
            )
        }

        peers.filter { candidate ->
            // A matching verified Michi ID is authoritative for the roster
            // even when a firmware migration changes its legacy device_id.
            // A mismatching Michi ID is never silently associated by IP.
            // UI deduplication below still avoids two equal display IDs.
            registered.none { reg ->
                val pinnedId = reg.effectiveMichiId
                pinnedId.isNotBlank() && candidate.identityTrust == "VERIFIED" &&
                    candidate.michiId == pinnedId
            }
        }.forEach { peer ->
            val deviceId = peer.deviceId.ifBlank { "${peer.ip}:${peer.port}" }
            unified.add(
                UnifiedDevice(
                    id = deviceId,
                    name = peer.alias,
                    ip = peer.ip,
                    port = peer.port,
                    connectionState = SyncConnectionState.DISCONNECTED,
                    isPaired = false,
                    deviceType = peer.deviceType,
                    roles = emptyList(), // Discovery never assigns functional capabilities.
                    features = emptyList(),
                    accessState = DeviceAccessState(
                        availability = DeviceAvailability.DISCOVERED,
                        identityVerified = peer.identityTrust == "VERIFIED" &&
                            peer.michiId.isNotBlank(),
                        authorization = if (peer.identityTrust == "VERIFIED" &&
                            peer.michiId.isNotBlank()) {
                            DeviceAuthorization.DISCOVERED_IDENTITY
                        } else {
                            DeviceAuthorization.NONE
                        },
                    ),
                )
            )
        }
        return unified.distinctBy { it.id }
    }

    private fun registeredAccess(
        sourceMichiId: String,
        revoked: Boolean,
        connection: SyncConnectionState?,
        capabilitiesVerified: Boolean,
    ): DeviceAccessState {
        val availability = when (connection) {
            SyncConnectionState.CONNECTED,
            SyncConnectionState.CONNECTING,
            SyncConnectionState.PAIRING,
            SyncConnectionState.PAIRING_REQUIRED,
            SyncConnectionState.PAIRED,
            -> DeviceAvailability.REACHABLE
            SyncConnectionState.OFFLINE,
            SyncConnectionState.DISCONNECTED,
            SyncConnectionState.DISCOVERING,
            -> DeviceAvailability.OFFLINE
            SyncConnectionState.ERROR,
            SyncConnectionState.AUTH_ERROR,
            SyncConnectionState.UNAUTHORIZED,
            SyncConnectionState.REVOKED,
            null,
            -> DeviceAvailability.UNKNOWN
        }
        val home = sourceMichiId.isNotBlank() && homeAuthorized(sourceMichiId)
        val authenticated = !revoked && sourceMichiId.isNotBlank() && home &&
            homeAuthenticated(sourceMichiId)
        val legacyAuthenticated = !revoked && !home &&
            connection == SyncConnectionState.CONNECTED
        val authorization = when {
            revoked -> DeviceAuthorization.REVOKED
            home && authenticated -> DeviceAuthorization.HOME_AUTHENTICATED
            home -> DeviceAuthorization.HOME_ENROLLED
            legacyAuthenticated || connection == SyncConnectionState.PAIRED ->
                DeviceAuthorization.LEGACY_PAIRED
            else -> DeviceAuthorization.NONE
        }
        return DeviceAccessState(
            availability = availability,
            identityVerified = sourceMichiId.isNotBlank(),
            homeAuthorized = home,
            authenticated = authenticated || legacyAuthenticated,
            capabilitiesVerified = capabilitiesVerified,
            authorization = authorization,
        )
    }
}
