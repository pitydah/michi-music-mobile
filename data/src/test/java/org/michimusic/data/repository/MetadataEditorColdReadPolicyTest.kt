package org.michimusic.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.core.metadata.MetadataField
import org.michimusic.core.metadata.MetadataFieldObservation
import org.michimusic.core.metadata.MetadataFieldReadReport
import org.michimusic.core.metadata.MetadataObservationIssue
import org.michimusic.core.metadata.MetadataPhysicalContainer
import org.michimusic.core.metadata.MetadataReadFieldCatalog

/**
 * ME-11S P0. Pure cold-read classification: physical evidence first, Room sidecar
 * only as a labelled reference, and no state may report success without a stable
 * stamp plus a real observation window.
 */
class MetadataEditorColdReadPolicyTest {
    private val before = RecoveryDocumentStamp(size = 1_024, modifiedAt = 5_000)
    private val indexed = IndexedMetadataReference(
        sourceId = "source-1", trackId = "track-1", sizeBytes = 1_024,
        modifiedAt = 5_000, parserVersion = 3,
        rows = listOf(
            IndexedMetadataReference.Row("Título", "Título del índice"),
            IndexedMetadataReference.Row("Álbum", "Álbum del índice"),
        ),
    )

    private fun classify(
        legacy: EmbeddedTagReadResult,
        report: MetadataFieldReadReport?,
        after: RecoveryDocumentStamp = before,
        expectedSize: Long = 1_024,
    ): MetadataEditorColdRead = MetadataEditorColdReadPolicy.classify(
        expectedSize = expectedSize,
        before = before,
        after = after,
        legacy = legacy,
        report = report,
        indexed = indexed,
    ) { fields, stamp, proof ->
        MetadataEditorEvidenceMapper.fromColdVerifiedRead(
            "source-1", "track-1", "content://doc/1", stamp, fields, proof)
    }

    private fun report(
        vararg overrides: Pair<MetadataField, MetadataFieldObservation>,
    ): MetadataFieldReadReport = MetadataFieldReadReport(
        container = MetadataPhysicalContainer.FLAC,
        parserVersion = 3,
        observations = MetadataReadFieldCatalog.primary.associateWith { field ->
            overrides.firstOrNull { it.first == field }?.second
                ?: MetadataFieldObservation.VerifiedAbsent
        },
    )

    private val physical = EmbeddedTagFields(
        title = "Canción real",
        artist = "Artista real",
        genres = listOf("Jazz; Fusion", "Rock"),
    )

    @Test
    fun `a readable file is verified even when the Room sidecar has no row`() {
        val result = classify(
            legacy = EmbeddedTagReadResult.Parsed(physical),
            report = report(
                MetadataField.TITLE to MetadataFieldObservation.Present(listOf("Canción real")),
                MetadataField.ARTIST to MetadataFieldObservation.Present(listOf("Artista real")),
                MetadataField.GENRE to MetadataFieldObservation.Present(
                    listOf("Jazz; Fusion", "Rock")),
            ),
        )
        assertTrue(result is MetadataEditorColdRead.Verified)
        val verified = result as MetadataEditorColdRead.Verified
        // The physical read is the only source of tag values; the index reference
        // is carried separately and never merged into the snapshot.
        assertEquals(listOf("Canción real"), verified.snapshot.known[MetadataField.TITLE])
        assertEquals(listOf("Jazz; Fusion", "Rock"),
            verified.snapshot.known[MetadataField.GENRE])
        assertEquals(indexed, verified.indexed)
        assertFalse(verified.snapshot.known.values.flatten().contains("Título del índice"))
    }

    @Test
    fun `unobserved fields stay partial and cannot masquerade as empty`() {
        val result = classify(
            legacy = EmbeddedTagReadResult.Parsed(physical),
            report = report(
                MetadataField.TITLE to MetadataFieldObservation.Present(listOf("Canción real")),
                MetadataField.ARTIST to MetadataFieldObservation.Unverifiable(
                    MetadataObservationIssue.FIELD_NOT_SUPPORTED),
            ),
        )
        assertTrue(result is MetadataEditorColdRead.Partial)
        val partial = result as MetadataEditorColdRead.Partial
        assertTrue(MetadataField.ARTIST in partial.missingFields)
        assertFalse(MetadataField.ARTIST in partial.snapshot.verifiedFields)
        assertFalse(MetadataField.ARTIST in partial.snapshot.known)
    }

    @Test
    fun `revoked permission is reported without touching the index values`() {
        val result = classify(
            legacy = EmbeddedTagReadResult.PermissionDenied,
            report = null,
        )
        assertEquals(MetadataEditorColdRead.Unavailable(
            ExtendedTagUnavailableReason.PERMISSION_DENIED, indexed), result)
    }

    @Test
    fun `provider failure is distinguished from an unsupported container`() {
        assertEquals(
            MetadataEditorColdRead.Unavailable(
                ExtendedTagUnavailableReason.PROVIDER_UNAVAILABLE, indexed),
            classify(EmbeddedTagReadResult.ReadFailed, report = null),
        )
        assertEquals(
            MetadataEditorColdRead.IndexedOnly(
                indexed, ExtendedTagUnavailableReason.TAG_READER_UNSUPPORTED),
            classify(EmbeddedTagReadResult.Unverifiable, report = null),
        )
    }

    @Test
    fun `a document that changes during the read is refused`() {
        val result = classify(
            legacy = EmbeddedTagReadResult.Parsed(physical),
            report = report(
                MetadataField.TITLE to MetadataFieldObservation.Present(listOf("Canción real")),
            ),
            after = before.copy(modifiedAt = before.modifiedAt + 1),
        )
        assertEquals(
            MetadataEditorColdRead.Unavailable(
                ExtendedTagUnavailableReason.DOCUMENT_CHANGED_DURING_READ, indexed),
            result,
        )
    }

    @Test
    fun `size mismatch and missing modification time are refused before parsing`() {
        assertEquals(
            MetadataEditorColdRead.Unavailable(
                ExtendedTagUnavailableReason.SIZE_MISMATCH, indexed),
            classify(EmbeddedTagReadResult.Parsed(physical), report(), expectedSize = 2_048),
        )
        assertEquals(
            MetadataEditorColdRead.Unavailable(
                ExtendedTagUnavailableReason.MODIFIED_AT_ZERO, indexed),
            MetadataEditorColdReadPolicy.classify(
                expectedSize = 1_024,
                before = before.copy(modifiedAt = 0),
                after = before.copy(modifiedAt = 0),
                legacy = EmbeddedTagReadResult.Parsed(physical),
                report = report(),
                indexed = indexed,
            ) { fields, stamp, proof ->
                MetadataEditorEvidenceMapper.fromColdVerifiedRead(
                    "source-1", "track-1", "content://doc/1", stamp, fields, proof)
            },
        )
    }

    @Test
    fun `a proven-absent genre is verified without inventing an empty tag`() {
        val legacy = EmbeddedTagReadResult.NoGenreTag(EmbeddedTagFields(title = "Solo título"))
        val result = classify(
            legacy = legacy,
            report = report(
                MetadataField.TITLE to MetadataFieldObservation.Present(listOf("Solo título")),
                MetadataField.GENRE to MetadataFieldObservation.VerifiedAbsent,
            ),
        )
        assertTrue(result is MetadataEditorColdRead.Verified)
        val snapshot = (result as MetadataEditorColdRead.Verified).snapshot
        assertTrue(MetadataField.GENRE in snapshot.verifiedFields)
        assertFalse(MetadataField.GENRE in snapshot.known)
    }

    @Test
    fun `multivalue order from the physical tag is preserved`() {
        val result = classify(
            legacy = EmbeddedTagReadResult.Parsed(physical),
            report = report(
                MetadataField.GENRE to MetadataFieldObservation.Present(
                    listOf("Jazz; Fusion", "Rock")),
            ),
        )
        val snapshot = (result as MetadataEditorColdRead.Partial).snapshot
        assertEquals(listOf("Jazz; Fusion", "Rock"), snapshot.known[MetadataField.GENRE])
    }

    @Test
    fun `a legacy-only read is partial because absence is never proven`() {
        val result = classify(
            legacy = EmbeddedTagReadResult.Parsed(physical),
            report = null,
        )
        assertTrue(result is MetadataEditorColdRead.Partial)
        assertTrue((result as MetadataEditorColdRead.Partial)
            .missingFields.contains(MetadataField.COMPOSER))
    }
}
