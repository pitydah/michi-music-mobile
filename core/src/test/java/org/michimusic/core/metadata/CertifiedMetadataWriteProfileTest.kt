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

    @Test fun mp3ProfileAuthorizesOnlyTheExactE2eCertifiedResourceOperations() {
        assertTrue(
            MetadataResourceOperation.ID3_APIC_REPLACE in
                profile.supportedResourceOperations,
        )
        assertFalse(
            MetadataResourceOperation.FLAC_PICTURE_REPLACE in
                profile.supportedResourceOperations,
        )
        assertTrue(
            MetadataResourceOperation.ID3_DESCRIBED_TEXT_REPLACE in
                profile.supportedResourceOperations,
        )
        assertTrue(
            MetadataResourceOperation.ID3_DESCRIBED_TEXT_CLEAR in
                profile.supportedResourceOperations,
        )
    }

    @Test fun flacProfileAuthorizesOnlyProductionE2eCertifiedTitleAndPictureReplacement() {
        val flac = MediaStoreFlacTextProfile.profile
        assertTrue(flac.supports(
            MetadataAudioContainer.FLAC, 1,
            setOf(MetadataField.TITLE), emptySet(), false,
        ))
        assertFalse(flac.supports(
            MetadataAudioContainer.FLAC, 1,
            setOf(MetadataField.COMPOSER), emptySet(), false,
        ))
        assertFalse(flac.permitsArtwork)
        assertTrue(flac.permitsMultiValue.isEmpty())
        assertTrue(
            MetadataResourceOperation.FLAC_PICTURE_REPLACE in
                flac.supportedResourceOperations,
        )
        assertFalse(
            MetadataResourceOperation.ID3_APIC_REPLACE in
                flac.supportedResourceOperations,
        )
    }

    @Test fun m4aProfileAuthorizesOnlyAudioOnlyProductionE2eCertifiedTitle() {
        val m4a = MediaStoreM4aTextProfile.profile
        assertTrue(m4a.supports(
            MetadataAudioContainer.MP4_M4A, 1,
            setOf(MetadataField.TITLE), emptySet(), false,
        ))
        assertFalse(m4a.supports(
            MetadataAudioContainer.MP4_M4A, 1,
            setOf(MetadataField.ARTIST), emptySet(), false,
        ))
        assertFalse(m4a.permitsArtwork)
        assertTrue(m4a.permitsMultiValue.isEmpty())
        assertTrue(m4a.supportedResourceOperations.isEmpty())
        assertTrue(MetadataWriteProfileRegistry.byId(m4a.id) === m4a)
        assertTrue(MetadataWriteProfileRegistry.forContainer(
            MetadataAudioContainer.MP4_M4A,
        ) === m4a)
    }
}
