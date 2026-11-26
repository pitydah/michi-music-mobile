package org.michimusic.core.metadata

import org.junit.Assert.assertEquals
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

    @Test fun id3v23MultiValueIsRefusedBeforeAnyWrite() {
        assertFalse(profile.supports(MetadataAudioContainer.ID3_V23, 3,
            setOf(MetadataField.ARTIST), setOf(MetadataField.ARTIST), false))
        assertFalse(profile.supports(MetadataAudioContainer.ID3_V23, 3,
            setOf(MetadataField.GENRE), setOf(MetadataField.GENRE), false))
        // The same scalar surface stays certified for v2.3.
        assertTrue(profile.supports(MetadataAudioContainer.ID3_V23, 3,
            setOf(MetadataField.ARTIST, MetadataField.GENRE), emptySet(), false))
        // ID3v2.4 keeps its certified repeated-value surface.
        assertTrue(profile.supports(MetadataAudioContainer.ID3_V24, 4,
            setOf(MetadataField.ARTIST, MetadataField.GENRE),
            setOf(MetadataField.ARTIST, MetadataField.GENRE), false))
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
        assertTrue(
            MetadataResourceOperation.ID3_DESCRIBED_TEXT_ADD in
                profile.supportedResourceOperations,
        )
    }

    @Test fun describedTextAdditionMapsToItsOwnCertifiedOperation() {
        val add = MetadataCommitIntentManifest.ResourceChange.DescribedText(
            kind = MetadataId3DescribedTagCodec.Kind.LYRICS,
            language = "spa",
            description = "letra",
            beforeCount = 0,
            afterCount = 1,
            beforeSha256 = MetadataCommitIntentManifest.valuesDigest(emptyList()),
            afterSha256 = MetadataCommitIntentManifest.valuesDigest(listOf("Hola")),
        )
        assertEquals(
            MetadataResourceOperation.ID3_DESCRIBED_TEXT_ADD,
            MetadataResourceOperation.of(add),
        )
        val replace = add.copy(
            beforeCount = 1,
            beforeSha256 = MetadataCommitIntentManifest.valuesDigest(listOf("Antes")),
        )
        assertEquals(
            MetadataResourceOperation.ID3_DESCRIBED_TEXT_REPLACE,
            MetadataResourceOperation.of(replace),
        )
        val clear = replace.copy(afterCount = 0, afterSha256 = add.beforeSha256)
        assertEquals(
            MetadataResourceOperation.ID3_DESCRIBED_TEXT_CLEAR,
            MetadataResourceOperation.of(clear),
        )
        assertTrue(MediaStoreMp3TextProfile.profile.supports(listOf(add)))
        assertTrue(MediaStoreMp3TextProfile.profile.supports(listOf(replace, clear)))
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
        assertTrue(m4a.supportedResourceOperations.containsAll(setOf(
            MetadataResourceOperation.MP4_COVR_REPLACE,
            MetadataResourceOperation.MP4_COVR_ADD,
            MetadataResourceOperation.MP4_COVR_REMOVE,
        )))
        assertFalse(MetadataResourceOperation.ID3_APIC_REPLACE in
            m4a.supportedResourceOperations)
        assertFalse(MetadataResourceOperation.FLAC_PICTURE_REPLACE in
            m4a.supportedResourceOperations)
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

    @Test fun safFlacAndM4aUseDistinctProviderEvidence() {
        val flac = MetadataWriteProfileRegistry.forProviderAndContainer(
            MetadataProviderRecoveryProfile.Kind.SAF_LOCAL_DOCUMENT,
            MetadataAudioContainer.FLAC,
        )
        assertTrue(flac === SafLocalFlacTextProfile.profile)
        assertTrue(flac !== MediaStoreFlacTextProfile.profile)
        assertTrue(flac!!.supports(
            MetadataAudioContainer.FLAC, 1,
            setOf(MetadataField.COMPOSER, MetadataField.TRACK_TOTAL,
                MetadataField.LYRICS),
            emptySet(), false,
        ))
        assertTrue(flac.supports(
            MetadataAudioContainer.FLAC, 1,
            setOf(MetadataField.ARTIST), setOf(MetadataField.ARTIST), false,
        ))
        assertFalse(flac.supports(
            MetadataAudioContainer.FLAC, 1,
            setOf(MetadataField.SYNCED_LYRICS), emptySet(), false,
        ))
        assertTrue(flac.supportedResourceOperations.isEmpty())

        val m4a = MetadataWriteProfileRegistry.forProviderAndContainer(
            MetadataProviderRecoveryProfile.Kind.SAF_LOCAL_DOCUMENT,
            MetadataAudioContainer.MP4_M4A,
        )
        assertTrue(m4a === SafLocalM4aTextProfile.profile)
        assertTrue(m4a !== MediaStoreM4aTextProfile.profile)
        assertTrue(m4a!!.supports(
            MetadataAudioContainer.MP4_M4A, 1,
            setOf(MetadataField.TITLE, MetadataField.COMPOSER,
                MetadataField.TRACK_TOTAL, MetadataField.DISC_NUMBER),
            emptySet(), false,
        ))
        assertFalse(m4a.supports(
            MetadataAudioContainer.MP4_M4A, 1,
            setOf(MetadataField.MEDIA_FORMAT), emptySet(), false,
        ))
        assertTrue(m4a.permitsMultiValue.isEmpty())
        assertTrue(m4a.supportedResourceOperations.isEmpty())
    }
}
