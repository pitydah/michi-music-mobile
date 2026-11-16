package org.michimusic.core.devices

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

    @Test fun airCatRolesAreExplicitNotSubstringMatches() {
        assertTrue(AirCatCapabilityPolicy.hasRole("AIRCAT_RECEIVER"))
        assertTrue(AirCatCapabilityPolicy.hasRole(listOf("sync_host", "aircat-sender")))
        assertFalse(AirCatCapabilityPolicy.hasRole("NOT_AIRCAT_RECEIVER"))
        assertFalse(AirCatCapabilityPolicy.hasRole(listOf("audio_receiver", "receiver")))
    }
}
