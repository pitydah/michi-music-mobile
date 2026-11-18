package org.michimusic.core.devices

/** Pure token policy shared by Devices UI and AirCat. A capability is NOT authorization. */
object AirCatCapabilityPolicy {
    private val featureKeys = setOf(
        "aircat", "aircat_transfer", "devices_aircat",
        "michi_devices_aircat", "devices.aircat",
    )
    private val roleKeys = setOf("aircat", "aircat_sender", "aircat_receiver")

    private fun normalize(raw: String): String = raw.trim().lowercase().replace('-', '_')

    fun hasFeature(features: List<String>): Boolean =
        features.any { normalize(it) in featureKeys }

    fun hasRole(role: String): Boolean = normalize(role) in roleKeys

    fun hasRole(roles: List<String>): Boolean = roles.any(::hasRole)
}
