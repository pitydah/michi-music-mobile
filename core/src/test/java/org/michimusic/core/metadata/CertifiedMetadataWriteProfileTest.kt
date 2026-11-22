package org.michimusic.core.metadata

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CertifiedMetadataWriteProfileTest {
    private val profile = MediaStoreMp3TextProfile.profile
    private val text = setOf(
        MetadataField.TITLE, MetadataField.ARTIST,
        MetadataField.ALBUM, MetadataField.GENRE,
    )

    @Test fun acceptsCertifiedId3RevisionsAndTextFields() {
        assertTrue(profile.supports(MetadataAudioContainer.ID3_V23, 3, text, emptySet(), false))
        assertTrue(profile.supports(MetadataAudioContainer.ID3_V24, 4, text, emptySet(), false))
    }

    @Test fun rejectsUncertifiedContainersRevisionsAndFields() {
        assertFalse(profile.supports(MetadataAudioContainer.FLAC, 4, text, emptySet(), false))
        assertFalse(profile.supports(MetadataAudioContainer.ID3_V24, 3, text, emptySet(), false))
        assertFalse(profile.supports(MetadataAudioContainer.ID3_V24, 4,
            text + MetadataField.COMPOSER, emptySet(), false))
    }

    @Test fun rejectsArtworkAndMultivalueUntilSeparatelyCertified() {
        assertFalse(profile.supports(MetadataAudioContainer.ID3_V24, 4, text, emptySet(), true))
        assertFalse(profile.supports(MetadataAudioContainer.ID3_V24, 4, text,
            setOf(MetadataField.ARTIST), false))
    }

    @Test fun profileIdentityIsExplicit() {
        assertTrue(profile.id == MediaStoreMp3TextProfile.ID)
        assertTrue(profile.capabilityEvidenceId == MediaStoreMp3TextProfile.EVIDENCE_ID)
        assertTrue(profile.provider == MetadataProviderRecoveryProfile.Kind.MEDIASTORE_AUDIO)
    }
}
