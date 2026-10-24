package org.michimusic.core.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.core.models.DeliveryProfile

class SyncProfileNegotiatorTest {

    @Test
    fun withoutServerTranscoding_onlyOriginalProfileIsAvailable() {
        val serverCaps = setOf("SYNC_V1", "MEDIA_STREAM")
        val representations = emptyList<String>()

        val profiles = SyncProfileNegotiator.evaluateAvailableProfiles(serverCaps, representations)

        val original = profiles.first { it.profile == DeliveryProfile.ORIGINAL }
        val balanced = profiles.first { it.profile == DeliveryProfile.BALANCED }
        val lowStorage = profiles.first { it.profile == DeliveryProfile.LOW_STORAGE }

        assertTrue(original.isAvailable)
        assertFalse(balanced.isAvailable)
        assertFalse(lowStorage.isAvailable)

        assertNotNull(balanced.reasonIfNot)
        assertTrue(balanced.reasonIfNot!!.contains("El servidor no tiene transcodificación habilitada"))
        assertNotNull(lowStorage.reasonIfNot)
        assertTrue(lowStorage.reasonIfNot!!.contains("El servidor no tiene transcodificación habilitada"))
    }

    @Test
    fun withoutServerTranscoding_requestingBalancedOrLowStorage_fallsBackToOriginal() {
        val serverCaps = setOf("SYNC_V1")
        val choice = SyncProfileNegotiator.resolveDeliveryChoice(DeliveryProfile.BALANCED, serverCaps)

        assertEquals(DeliveryProfile.ORIGINAL, choice.profile)
        assertEquals("original", choice.codec)
        assertFalse(choice.isServerTranscoded)
    }

    @Test
    fun withServerTranscoding_allProfilesAvailableAndConfigured() {
        val serverCaps = setOf("TRANSCODING_AAC", "TRANSCODING_OPUS")
        val profiles = SyncProfileNegotiator.evaluateAvailableProfiles(serverCaps)

        assertTrue(profiles.all { it.isAvailable })

        val balancedChoice = SyncProfileNegotiator.resolveDeliveryChoice(DeliveryProfile.BALANCED, serverCaps)
        assertEquals(DeliveryProfile.BALANCED, balancedChoice.profile)
        assertEquals("aac", balancedChoice.codec)
        assertEquals(256, balancedChoice.maxBitrateKbps)
        assertTrue(balancedChoice.isServerTranscoded)

        val lowChoice = SyncProfileNegotiator.resolveDeliveryChoice(DeliveryProfile.LOW_STORAGE, serverCaps)
        assertEquals(DeliveryProfile.LOW_STORAGE, lowChoice.profile)
        assertEquals("opus", lowChoice.codec)
        assertEquals(128, lowChoice.maxBitrateKbps)
        assertTrue(lowChoice.isServerTranscoded)
    }

    @Test
    fun withServerRepresentations_enablesTranscodingProfiles() {
        val representations = listOf("audio/aac", "audio/opus")
        val profiles = SyncProfileNegotiator.evaluateAvailableProfiles(emptySet(), representations)

        assertTrue(profiles.all { it.isAvailable })
    }

    @Test
    fun withAacOnlyRepresentation_enablesBalancedAndDisablesLowStorage() {
        val representations = listOf("audio/aac")
        val profiles = SyncProfileNegotiator.evaluateAvailableProfiles(emptySet(), representations)

        val balanced = profiles.first { it.profile == DeliveryProfile.BALANCED }
        val lowStorage = profiles.first { it.profile == DeliveryProfile.LOW_STORAGE }

        assertTrue(balanced.isAvailable)
        assertFalse(lowStorage.isAvailable)
        assertTrue(lowStorage.reasonIfNot!!.contains("Opus"))
    }

    @Test
    fun withOpusOnlyRepresentation_enablesLowStorageAndDisablesBalanced() {
        val representations = listOf("audio/opus")
        val profiles = SyncProfileNegotiator.evaluateAvailableProfiles(emptySet(), representations)

        val balanced = profiles.first { it.profile == DeliveryProfile.BALANCED }
        val lowStorage = profiles.first { it.profile == DeliveryProfile.LOW_STORAGE }

        assertFalse(balanced.isAvailable)
        assertTrue(lowStorage.isAvailable)
        assertTrue(balanced.reasonIfNot!!.contains("AAC"))
    }
}
