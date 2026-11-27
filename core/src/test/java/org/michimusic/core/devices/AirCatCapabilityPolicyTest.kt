package org.michimusic.core.devices

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AirCatCapabilityPolicyTest {
    @Test fun advertisedFeatureUsesExactTokens() {
        assertTrue(AirCatCapabilityPolicy.hasFeature(listOf(" AirCat-Transfer ")))
        assertTrue(AirCatCapabilityPolicy.hasFeature(listOf("devices.aircat")))
        assertFalse(AirCatCapabilityPolicy.hasFeature(listOf("not_aircat", "aircat_disabled", "aircatapult")))
        assertFalse(AirCatCapabilityPolicy.hasFeature(emptyList()))
    }

    @Test fun canonicalV1RequiresAllFivePinnedFlags() {
        val all = AirCatCapabilityPolicy.CANONICAL_V1.toList()
        assertTrue(AirCatCapabilityPolicy.hasCanonicalV1(all))
        assertFalse(AirCatCapabilityPolicy.hasCanonicalV1(all.dropLast(1)))
        assertFalse(AirCatCapabilityPolicy.hasCanonicalV1(emptyList()))
    }

    @Test fun partialCanonicalPeerCannotFallBackToLegacyAlias() {
        val partial = AirCatCapabilityPolicy.CANONICAL_V1.toList().drop(1) + "aircat_transfer"
        assertFalse(AirCatCapabilityPolicy.hasCanonicalV1(partial))
        assertTrue(AirCatCapabilityPolicy.advertisesCanonicalV1(partial))
        assertFalse(AirCatCapabilityPolicy.supportsTransfer(partial))
        assertTrue(AirCatCapabilityPolicy.supportsTransfer(listOf("AirCat-Transfer")))
    }

    @Test fun enabledFeatureKeysReadsOnlyExplicitTrueBooleans() {
        val map = mapOf(
            "aircat_original_v1" to JsonPrimitive(true),
            "aircat_manifest_v1" to JsonPrimitive(false),
            "home_tls_spki_pinned" to JsonPrimitive("true"),
            "aircat_authorize_v1" to Json.parseToJsonElement("{\"nested\":true}"),
        )
        assertEquals(setOf("aircat_original_v1"), AirCatCapabilityPolicy.enabledFeatureKeys(map))
    }

    @Test fun airCatRolesAreExplicitNotSubstringMatches() {
        assertTrue(AirCatCapabilityPolicy.hasRole("AIRCAT_RECEIVER"))
        assertTrue(AirCatCapabilityPolicy.hasRole(listOf("sync_host", "aircat-sender")))
        assertFalse(AirCatCapabilityPolicy.hasRole("NOT_AIRCAT_RECEIVER"))
        assertFalse(AirCatCapabilityPolicy.hasRole(listOf("audio_receiver", "receiver")))
    }
}
