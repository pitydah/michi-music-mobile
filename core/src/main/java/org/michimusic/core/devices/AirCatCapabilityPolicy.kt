package org.michimusic.core.devices

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** Pure token policy shared by Devices UI and AirCat. A capability is NOT authorization. */
object AirCatCapabilityPolicy {
    /**
     * MIM-03/MIM-10: the five R1.4 AirCat flags from the pinned handoff
     * (`interop/mobile-readiness/ANDROID_IMPLEMENTATION_HANDOFF.md`). A peer that
     * participates in the canonical capability map must advertise ALL of them;
     * a partially flagged producer is not eligible.
     */
    val CANONICAL_V1 = setOf(
        "aircat_original_v1",
        "home_tls_spki_pinned",
        "aircat_manifest_v1",
        "aircat_authorize_v1",
        "aircat_original_download_v1",
    )

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

    fun hasCanonicalV1(features: List<String>): Boolean {
        val normalized = features.map(::normalize).toSet()
        return CANONICAL_V1.all { it in normalized }
    }

    /** True when the peer exposes any canonical R1.4 flag at all. */
    fun advertisesCanonicalV1(features: List<String>): Boolean =
        features.any { normalize(it) in CANONICAL_V1 }

    /**
     * Transitional eligibility: a peer advertising the canonical map must have
     * every required flag; a clearly legacy peer may still use the old alias.
     * This never upgrades a capability into authorization.
     */
    fun supportsTransfer(features: List<String>): Boolean =
        hasCanonicalV1(features) ||
            (!advertisesCanonicalV1(features) && hasFeature(features))

    /** Reads a `server/info.features` map without inventing missing keys. */
    fun enabledFeatureKeys(map: Map<String, JsonElement>): Set<String> =
        map.filterValues { element ->
            val primitive = element as? JsonPrimitive ?: return@filterValues false
            !primitive.isString && primitive.booleanOrNull == true
        }.keys
}
