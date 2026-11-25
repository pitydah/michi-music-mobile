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
            setOf(MetadataField.SYNCED_LYRICS), emptySet(), false))
        assertTrue(profile.supports(MetadataAudioContainer.ID3_V24, 4,
            setOf(MetadataField.COMPOSER, MetadataField.TRACK_TOTAL,
                MetadataField.DISC_NUMBER, MetadataField.RELEASE_DATE),
            emptySet(), false))
    }

    @Test fun id3MultivalueIsLimitedToFieldsTheColdReaderOrders() {
        assertTrue(profile.supports(MetadataAudioContainer.ID3_V24, 4,
            setOf(MetadataField.ARTIST, MetadataField.GENRE),
            setOf(MetadataField.ARTIST, MetadataField.GENRE), false))
        assertFalse(profile.supports(MetadataAudioContainer.ID3_V24, 4,
            setOf(MetadataField.REMIXER), setOf(MetadataField.REMIXER), false))
        assertFalse(profile.supports(MetadataAudioContainer.ID3_V24, 4, text,
            emptySet(), true))
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

    @Test fun flacProfileAuthorizesItsColdVerifiedTextAndMultivalueSurface() {
        val flac = MediaStoreFlacTextProfile.profile
        assertTrue(flac.supports(
            MetadataAudioContainer.FLAC, 1,
            setOf(MetadataField.TITLE, MetadataField.COMPOSER,
                MetadataField.TRACK_TOTAL, MetadataField.LYRICS),
            emptySet(), false,
        ))
        assertTrue(flac.supports(
            MetadataAudioContainer.FLAC, 1,
            setOf(MetadataField.ARTIST, MetadataField.GENRE),
            setOf(MetadataField.ARTIST, MetadataField.GENRE), false,
        ))
        assertFalse(flac.supports(
            MetadataAudioContainer.FLAC, 1,
            setOf(MetadataField.SYNCED_LYRICS), emptySet(), false,
        ))
        assertFalse(flac.permitsArtwork)
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
            setOf(MetadataField.SUBTITLE), emptySet(), false,
        ))
        assertTrue(m4a.supports(
            MetadataAudioContainer.MP4_M4A, 1,
            setOf(MetadataField.ARTIST, MetadataField.COMPOSER,
                MetadataField.TRACK_TOTAL, MetadataField.DISC_NUMBER,
                MetadataField.LYRICS),
            emptySet(), false,
        ))
        assertFalse(m4a.permitsArtwork)
        assertTrue(m4a.permitsMultiValue.isEmpty())
        assertTrue(m4a.supportedResourceOperations.isEmpty())
        assertTrue(MetadataWriteProfileRegistry.byId(m4a.id) === m4a)
        assertTrue(MetadataWriteProfileRegistry.forContainer(
            MetadataAudioContainer.MP4_M4A,
        ) === m4a)
    }

    @Test fun providerAwareLookupUsesDistinctSafEvidence() {
        assertTrue(
            MetadataWriteProfileRegistry.forProviderAndContainer(
                MetadataProviderRecoveryProfile.Kind.MEDIASTORE_AUDIO,
                MetadataAudioContainer.ID3_V24,
            ) === MediaStoreMp3TextProfile.profile,
        )
        val saf = MetadataWriteProfileRegistry.forProviderAndContainer(
            MetadataProviderRecoveryProfile.Kind.SAF_LOCAL_DOCUMENT,
            MetadataAudioContainer.ID3_V24,
        )
        assertTrue(saf === SafLocalMp3TitleProfile.profile)
        assertTrue(saf !== MediaStoreMp3TextProfile.profile)
        assertTrue(saf!!.id != MediaStoreMp3TextProfile.ID)
        assertTrue(saf.capabilityEvidenceId != MediaStoreMp3TextProfile.EVIDENCE_ID)
        assertTrue(saf.supports(
            MetadataAudioContainer.ID3_V24, 4,
            setOf(MetadataField.TITLE, MetadataField.COMPOSER,
                MetadataField.DISC_TOTAL),
            emptySet(), false,
        ))
        assertTrue(saf.supports(
            MetadataAudioContainer.ID3_V24, 4,
            setOf(MetadataField.ARTIST), setOf(MetadataField.ARTIST), false,
        ))
        assertFalse(saf.supports(
            MetadataAudioContainer.ID3_V24, 4,
            setOf(MetadataField.SYNCED_LYRICS), emptySet(), false,
        ))
        assertTrue(
            MetadataWriteProfileRegistry.forProviderAndContainer(
                MetadataProviderRecoveryProfile.Kind.UNKNOWN_OR_REMOTE,
                MetadataAudioContainer.FLAC,
            ) == null,
        )
    }
}
