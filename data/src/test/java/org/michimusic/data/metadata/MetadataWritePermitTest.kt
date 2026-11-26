package org.michimusic.data.metadata

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.core.metadata.MediaStoreMp3TextProfile
import org.michimusic.core.metadata.MetadataField
import org.michimusic.core.metadata.MetadataRecoveryPhase

/**
 * ME-13: a permit reconstructed after a process restart must carry the multi-value scope of the
 * durable intent it was rebuilt from. `matches` may not assume that scope is empty: a profile that
 * cannot represent repeated values for the container and revision has to refuse the permit, even
 * when the field itself is supported.
 */
class MetadataWritePermitTest {
    private val transactionId = "6f1b5f5e-8f0d-4a2a-9a51-1f1b2c3d4e5f"

    // MediaStore local audio: the only source id the provider classifier accepts besides "".
    private val sourceId = "lib_device_local"
    private val trackId = "track-1"
    private val uri = "content://media/external/audio/media/1"
    private val originalSha256 = "a".repeat(64)
    private val candidateSha256 = "b".repeat(64)
    private val stageBytes = 4096L

    private fun entry(format: String) = MetadataRecoveryJournalV1.Entry(
        id = transactionId,
        phase = MetadataRecoveryPhase.POSTCHECK_VERIFIED,
        sourceId = sourceId,
        trackId = trackId,
        uri = uri,
        expectedBytes = 2048L,
        expectedModifiedAt = 1_700_000_000_000L,
        originalSha256 = originalSha256,
        stageSha256 = candidateSha256,
        stageBytes = stageBytes,
        format = format,
    )

    private fun permit(
        container: String,
        revision: Int,
        multiValueFields: Set<MetadataField>,
    ) = MetadataWritePermit(
        transactionId = transactionId,
        sourceId = sourceId,
        trackId = trackId,
        uri = uri,
        originalSha256 = originalSha256,
        candidateSha256 = candidateSha256,
        stageBytes = stageBytes,
        profileId = MediaStoreMp3TextProfile.ID,
        evidenceId = MediaStoreMp3TextProfile.EVIDENCE_ID,
        container = container,
        revision = revision,
        fields = setOf(MetadataField.GENRE),
        explicitUserAction = true,
        multiValueFields = multiValueFields,
    )

    @Test fun reconstructedPermitRejectsUnsupportedMultiValue() {
        // ID3v2.3 repeats no text value inside one frame, so a two-value genre candidate is not
        // representable there. Rebuilding the permit without the intent scope would authorize it.
        val unsupported = permit("ID3_V23", 3, setOf(MetadataField.GENRE))
        assertFalse(
            "an ID3v2.3 permit must not authorize a durable multi-value genre scope",
            unsupported.matches(entry(format = "ID3_V23")),
        )

        // The same container keeps authorizing historical single-value recovery, whose durable
        // intent recorded one value only.
        val historicalSingleValue = permit("ID3_V23", 3, emptySet())
        assertTrue(
            "a permit whose intent recorded one value keeps recovering on ID3v2.3",
            historicalSingleValue.matches(entry(format = "ID3_V23")),
        )

        // ID3v2.4 does carry an ordered value list, so the identical scope stays authorized.
        val representable = permit("ID3_V24", 4, setOf(MetadataField.GENRE))
        assertTrue(
            "ID3v2.4 represents an ordered genre list",
            representable.matches(entry(format = "ID3_V24")),
        )
    }
}
