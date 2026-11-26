package org.michimusic.data.metadata

import org.junit.Assert.assertEquals
import org.michimusic.core.metadata.MetadataCommittedLibraryChange
import org.michimusic.core.metadata.MetadataField
import org.michimusic.core.metadata.MetadataSnapshot
import org.michimusic.core.metadata.MetadataTarget
import org.michimusic.data.cache.MetadataAppliedTransaction
import org.michimusic.data.cache.MichiDatabase
import org.michimusic.data.repository.MetadataLibraryPublicationGate
import org.junit.Test

class MetadataLibraryPublicationGateTest {
    private val source = MichiDatabase.DEFAULT_DEVICE_LIBRARY_ID
    private val track = "local_7"
    private val uri = "content://media/external_primary/audio/media/7"
    private val candidate = "b".repeat(64)

    private fun change(
        previous: Long = 1L,
        committed: Long = 2L,
        transaction: String = "12341234-1234-4abc-8abc-123412341234",
    ) = MetadataCommittedLibraryChange(
        transactionId = transaction,
        sourceId = source,
        trackId = track,
        uri = uri,
        changedFields = setOf(MetadataField.TITLE),
        resourceOperations = emptySet(),
        snapshot = MetadataSnapshot(
            target = MetadataTarget(source, track, uri, "post-readback"),
            tagsVerified = true,
            known = mapOf(MetadataField.TITLE to listOf("After")),
        ),
        previousRevision = previous,
        committedRevision = committed,
        committedBytes = 120L,
        committedSha256 = candidate,
    )

    private fun marker(change: MetadataCommittedLibraryChange) = MetadataAppliedTransaction(
        transactionId = change.transactionId,
        sourceId = change.sourceId,
        trackId = change.trackId,
        uri = change.uri,
        originalRevision = change.previousRevision,
        committedRevision = change.committedRevision,
        candidateSha256 = change.committedSha256,
        candidateBytes = change.committedBytes,
        appliedAt = 1L,
    )

    @Test fun matchingMarkerAndEqualDurableRevisionStillApplies() {
        val change = change()
        assertEquals(
            MetadataLibraryPublicationGate.Decision.Apply,
            MetadataLibraryPublicationGate.evaluate(change, marker(change), 2L),
        )
    }

    @Test fun matchingMarkerWithOlderCommittedRevisionIsStale() {
        val change = change(committed = 2L)
        assertEquals(
            MetadataLibraryPublicationGate.Decision.Ignore("STALE_REVISION"),
            MetadataLibraryPublicationGate.evaluate(change, marker(change), 3L),
        )
    }

    @Test fun mismatchedMarkerIsRefused() {
        val change = change()
        val forged = marker(change).copy(candidateSha256 = "c".repeat(64))
        assertEquals(
            MetadataLibraryPublicationGate.Decision.Refuse("APPLIED_MARKER_MISMATCH"),
            MetadataLibraryPublicationGate.evaluate(change, forged, 2L),
        )
        val anotherTransaction = marker(change).copy(
            transactionId = "99999999-1234-4abc-8abc-123412341234",
        )
        assertEquals(
            MetadataLibraryPublicationGate.Decision.Refuse("APPLIED_MARKER_MISMATCH"),
            MetadataLibraryPublicationGate.evaluate(change, anotherTransaction, 2L),
        )
    }

    @Test fun withoutMarkerAnOlderRevisionIsStale() {
        assertEquals(
            MetadataLibraryPublicationGate.Decision.Ignore("STALE_REVISION"),
            MetadataLibraryPublicationGate.evaluate(change(), null, 3L),
        )
    }

    @Test fun withoutMarkerNoRevisionCanManufactureACommit() {
        assertEquals(
            MetadataLibraryPublicationGate.Decision.Refuse("COMMITTED_EVIDENCE_REQUIRED"),
            MetadataLibraryPublicationGate.evaluate(change(), null, 2L),
        )
        assertEquals(
            MetadataLibraryPublicationGate.Decision.Refuse("COMMITTED_EVIDENCE_REQUIRED"),
            MetadataLibraryPublicationGate.evaluate(change(), null, 1L),
        )
        assertEquals(
            MetadataLibraryPublicationGate.Decision.Refuse("COMMITTED_EVIDENCE_REQUIRED"),
            MetadataLibraryPublicationGate.evaluate(change(), null, null),
        )
    }

    @Test fun exactMarkerWithoutADurableRowCannotResurrectADeletedSong() {
        val change = change()
        assertEquals(
            MetadataLibraryPublicationGate.Decision.Refuse("DURABLE_REVISION_UNAVAILABLE"),
            MetadataLibraryPublicationGate.evaluate(change, marker(change), null),
        )
    }

    @Test fun markerAheadOfTheDurableRowIsNotACommittedRevision() {
        val change = change()
        assertEquals(
            MetadataLibraryPublicationGate.Decision.Refuse("DURABLE_REVISION_NOT_COMMITTED"),
            MetadataLibraryPublicationGate.evaluate(change, marker(change), 1L),
        )
    }

    @Test fun secondEditAppliesAndTheOlderReplayIsIgnored() {
        val first = change(previous = 1L, committed = 2L, transaction = "11111111-1234-4abc-8abc-123412341234")
        val second = change(previous = 2L, committed = 3L, transaction = "22222222-1234-4abc-8abc-123412341234")
        assertEquals(
            MetadataLibraryPublicationGate.Decision.Apply,
            MetadataLibraryPublicationGate.evaluate(first, marker(first), 2L),
        )
        assertEquals(
            MetadataLibraryPublicationGate.Decision.Apply,
            MetadataLibraryPublicationGate.evaluate(second, marker(second), 3L),
        )
        assertEquals(
            MetadataLibraryPublicationGate.Decision.Ignore("STALE_REVISION"),
            MetadataLibraryPublicationGate.evaluate(first, marker(first), 3L),
        )
    }
}
