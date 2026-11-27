package org.michimusic.data.artist

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtistBulkNoIoOperationGateTest {
    @Test fun allLocalActionsRemainPermitted() {
        val local = setOf(ArtistBulkNoIoOperation.INSPECT_LOCAL,
            ArtistBulkNoIoOperation.REVIEW_PERMISSION,ArtistBulkNoIoOperation.RESERVE_NO_IO,
            ArtistBulkNoIoOperation.RELEASE_NO_IO,ArtistBulkNoIoOperation.CLOSE_TERMINAL_NO_IO,
            ArtistBulkNoIoOperation.RECONCILE_STALE_NO_IO)
        for (op in local) assertTrue(ArtistBulkNoIoOperationGate.permits(op))
    }
    @Test fun allProviderAndMutationActionsAreProhibited() {
        val prohibited = setOf(ArtistBulkNoIoOperation.HTTP_DISPATCH,
            ArtistBulkNoIoOperation.SCHEDULE_PROVIDER_WORK,ArtistBulkNoIoOperation.WRITE_ARTIST_PROFILE,
            ArtistBulkNoIoOperation.REPLACE_MANUAL_PHOTO,ArtistBulkNoIoOperation.MUTATE_AUDIO_METADATA)
        for (op in prohibited) assertFalse(ArtistBulkNoIoOperationGate.permits(op))
    }
    @Test fun closedCapabilitySetCannotSilentlyPermitNewEnum() {
        assertTrue(ArtistBulkNoIoOperation.entries.count(ArtistBulkNoIoOperationGate::permits) == 6)
        assertFalse(ArtistBulkNoIoOperationGate.mayContactProvider())
    }
}
