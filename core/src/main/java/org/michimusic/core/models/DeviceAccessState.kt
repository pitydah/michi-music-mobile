package org.michimusic.core.models

/**
 * MIM-03: availability, identity, Home authorization, authentication and
 * capabilities are orthogonal facts. A peer being reachable, or announcing a
 * signed identity, never implies that it is authorized for a Home operation.
 */
enum class DeviceAvailability {
    UNKNOWN,
    DISCOVERED,
    REACHABLE,
    OFFLINE,
}

enum class DeviceAuthorization {
    NONE,
    DISCOVERED_IDENTITY,
    LEGACY_PAIRED,
    HOME_ENROLLED,
    HOME_AUTHENTICATED,
    REVOKED,
}

data class DeviceAccessState(
    val availability: DeviceAvailability = DeviceAvailability.UNKNOWN,
    val identityVerified: Boolean = false,
    val homeAuthorized: Boolean = false,
    val authenticated: Boolean = false,
    val capabilitiesVerified: Boolean = false,
    val authorization: DeviceAuthorization = DeviceAuthorization.NONE,
)
