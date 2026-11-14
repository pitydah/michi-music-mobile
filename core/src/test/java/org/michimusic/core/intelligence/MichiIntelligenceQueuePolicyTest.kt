package org.michimusic.core.intelligence

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.core.models.Track
import org.michimusic.core.models.TrackSource

class MichiIntelligenceQueuePolicyTest {
    private fun track(i: Int) = Track(id = "id-$i", sourceId = "", title = "Song", filepath = "/local/$i.flac")

    private fun accepted(
        tracks: List<Track> = listOf(track(1), track(2)),
        start: Int = 0,
        expectedEpoch: Long = 10,
        actualEpoch: Long = 10,
        intent: Long = MichiPlaybackIntentFence.snapshot().generation,
        expectedRevision: Long = 4,
        actualRevision: Long = 4,
        sessionEndpointId: String = "local_phone",
        sessionEndpointLocal: Boolean = true,
        sessionRemoteActive: Boolean = false,
        handoffStable: Boolean = true,
        remoteSelected: Boolean = false,
        outputEndpointId: String = "local_phone",
        outputLocal: Boolean = true,
    ) = MichiIntelligenceQueuePolicy.permits(tracks, start, expectedEpoch, actualEpoch,
        intent, expectedRevision, actualRevision, sessionEndpointId, sessionEndpointLocal,
        sessionRemoteActive, handoffStable, remoteSelected, outputEndpointId, outputLocal)

    @Test fun exactlyTheRecommendedLocalSubsetIsAccepted() {
        MichiPlaybackIntentFence.confirmedLocalEndpoint()
        assertTrue(accepted())
        assertTrue(accepted(tracks = (0..5).map(::track), start = 5))
    }
    @Test fun changedSessionEpochOrOutputRevisionRejectsOldTicket() {
        assertFalse(accepted(actualEpoch = 11))
        assertFalse(accepted(actualRevision = 5))
    }
    @Test fun transitionAwayAndBackDuringAvailabilityIoStillRejectsOldTicket() {
        MichiPlaybackIntentFence.confirmedLocalEndpoint()
        val captured = MichiPlaybackIntentFence.snapshot().generation
        val transfer = MichiPlaybackIntentFence.beginTransfer()
        MichiPlaybackIntentFence.completeTransfer(transfer, true)
        assertFalse(accepted(intent = captured))
    }
    @Test fun anyRemoteHostReceiverOrPendingHandoffDeniesCommand() {
        assertFalse(accepted(remoteSelected = true))
        assertFalse(accepted(sessionRemoteActive = true))
        assertFalse(accepted(sessionEndpointId = "stream-1",sessionEndpointLocal = false))
        assertFalse(accepted(outputEndpointId = "server"))
        assertFalse(accepted(outputLocal = false))
        assertFalse(accepted(handoffStable = false))
    }
    @Test fun malformedIndexEmptyQueueAndFullLibrarySubstitutionAreRejected() {
        assertFalse(accepted(start = 2))
        assertFalse(accepted(tracks = emptyList()))
        assertFalse(accepted(tracks = (0..6).map(::track)))
    }
    @Test fun rejectsRemoteTracksDuplicateMedia3IdsAndMissingLocalPaths() {
        assertFalse(accepted(tracks = listOf(track(1),track(1).copy(sourceId="other"))))
        assertFalse(accepted(tracks = listOf(track(1).copy(source=TrackSource.STREAMING))))
        assertFalse(accepted(tracks = listOf(track(1).copy(filepath=""))))
        assertFalse(accepted(tracks = listOf(track(1).copy(id=""))))
    }
}
